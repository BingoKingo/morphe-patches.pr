/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/2269
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.lyrics;

import android.media.MediaMetadata;
import android.media.session.PlaybackState;
import android.net.Uri;
import android.os.SystemClock;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import app.morphe.extension.music.patches.lyrics.requests.CharactersConverter;
import app.morphe.extension.music.patches.lyrics.requests.SubtitlesFetcher;
import app.morphe.extension.music.patches.lyrics.requests.KuGouProvider;
import app.morphe.extension.music.patches.lyrics.requests.LocalLyricsFetcher;
import app.morphe.extension.music.patches.lyrics.requests.LrcLibProvider;
import app.morphe.extension.music.patches.lyrics.requests.LyricsProvider;
import app.morphe.extension.music.patches.lyrics.requests.NetEaseProvider;
import app.morphe.extension.music.patches.lyrics.requests.QQProvider;
import app.morphe.extension.music.patches.lyrics.requests.BinimumProvider;
import app.morphe.extension.music.patches.lyrics.requests.BlyricsProvider;
import app.morphe.extension.music.patches.lyrics.requests.MusixmatchProvider;
import app.morphe.extension.music.patches.lyrics.requests.UnisonProvider;
import app.morphe.extension.music.patches.lyrics.requests.AmllProvider;
import app.morphe.extension.music.patches.lyrics.requests.AppleMusicProvider;
import app.morphe.extension.music.patches.lyrics.requests.SpotifyProvider;
import app.morphe.extension.music.settings.Settings;
import app.morphe.extension.music.shared.VideoInformation;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;

/**
 * Fetches lyrics for the currently playing track and tracks playback position.
 *
 * <p>The position is extrapolated from the last {@link PlaybackState} update so synced
 * lyrics stay accurate to a few tens of milliseconds between updates, but the
 * extrapolation is re-anchored to the player time hook ({@link VideoInformation#getVideoTime()},
 * which ticks roughly once per second) so any drift between the two clocks cannot
 * accumulate. A seek or play/pause also re-anchors via {@link #onSetPlaybackState}.
 */
public final class LyricsManager {


    public enum State {
        IDLE,
        LOADING,
        LOADED,
        NOT_FOUND,
        ERROR
    }

    public interface Listener {

        /** Called on the main thread whenever the state or the lyrics change. */
        void onLyricsChanged(State state, @Nullable Lyrics lyrics);
    }

    private static final LyricsManager INSTANCE = new LyricsManager();

    private final ExecutorService executor = Executors.newFixedThreadPool(4);

    private final List<Listener> listeners = new ArrayList<>(2);

    private static final Lyrics EMPTY_SUBTITLES =
            new Lyrics(Collections.emptyList(), Lyrics.CAPTIONS_PROVIDER, true);

    /**
     * Strings some providers or captions return for instrumental tracks, indicating the
     * track has no actual lyrics. A result made up entirely of these is discarded.
     */
    private static final Set<String> INSTRUMENTAL_PLACEHOLDERS = new HashSet<>(Arrays.asList(
            "此歌曲为没有填词的纯音乐，请您欣赏",
            "纯音乐，请欣赏",
            "《纯音乐，请欣赏》"
    ));

    @Nullable
    private TrackInfo currentTrack;

    /** Content/file URI of the currently displayed track, when played from local storage. */
    @Nullable
    private volatile Uri currentMediaUri;

    /** Raw (un-cleaned) title/artist of the current track, used for the MediaStore lookup. */
    @Nullable
    private String currentRawTitle;
    @Nullable
    private String currentRawArtist;

    @Nullable
    private Lyrics currentLyrics;

    private State state = State.IDLE;

    /**
     * Incremented for every track change so that a late response for a previous
     * track is discarded instead of being shown for the current one.
     */
    private int requestId;

    private long positionMs;
    private long positionUpdatedAtUptimeMs;
    private long lastVideoTimeSample = -1;
    private float playbackSpeed = 1f;
    private boolean playing;

    private long smoothedPosition = -1;

    private LyricsManager() {
    }

    private int currentProviderIndex;
    private int currentCandidateIndex;
    private List<LyricsProvider> currentProviders;
    private TrackInfo currentCandidateTrack;
    private int currentCandidateRequestId;
    private java.util.Map<Integer, List<Lyrics>> candidateCache;

    public static LyricsManager getInstance() {
        return INSTANCE;
    }

    public void addListener(Listener listener) {
        Utils.verifyOnMainThread();
        if (!listeners.contains(listener)) {
            listeners.add(listener);
        }
        listener.onLyricsChanged(state, currentLyrics);
    }

    public void removeListener(Listener listener) {
        Utils.verifyOnMainThread();
        listeners.remove(listener);
    }

    @Nullable
    public TrackInfo getCurrentTrack() {
        return currentTrack;
    }

    /** Whether lyrics for the current track are loaded and ready to show. */
    public boolean hasLyrics() {
        return state == State.LOADED && currentLyrics != null && !currentLyrics.isEmpty();
    }

    static boolean isInstrumental(@Nullable Lyrics lyrics) {
        if (lyrics == null || lyrics == Lyrics.NOT_FOUND || lyrics.isEmpty()) {
            return false;
        }
        for (LyricsLine line : lyrics.lines()) {
            final String text = line.text();
            if (text != null && !text.isBlank() && isPlaceholderLine(text)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isPlaceholderLine(@Nullable String text) {
        if (text == null) {
            return false;
        }
        final String trimmed = text.trim();
        if (trimmed.isEmpty()) {
            return false;
        }
        for (String placeholder : INSTRUMENTAL_PLACEHOLDERS) {
            if (trimmed.contains(placeholder)) {
                return true;
            }
        }
        return false;
    }

    /** Whether the lyrics are usable (non-null, non-empty and not instrumental). */
    static boolean isValidLyrics(@Nullable Lyrics lyrics) {
        return lyrics != null
                && lyrics != Lyrics.NOT_FOUND
                && !lyrics.isEmpty()
                && !isInstrumental(lyrics);
    }

    /**
     * Current playback position including the user configured offset.
     */
    public long getPositionMs() {
        final long videoTime = VideoInformation.getVideoTime();
        if (videoTime > 0 && videoTime != lastVideoTimeSample) {
            positionMs = videoTime;
            positionUpdatedAtUptimeMs = SystemClock.uptimeMillis();
            lastVideoTimeSample = videoTime;
        }

        long position = positionMs;
        if (playing && positionUpdatedAtUptimeMs != 0) {
            final long elapsed = SystemClock.uptimeMillis() - positionUpdatedAtUptimeMs;
            position += (long) (elapsed * playbackSpeed);
        }
        long result = position - Settings.LYRICS_OFFSET_MS.get();

        // Position smoothing: reject implausible forward jumps.
        // Normal 120ms tick advances ~120ms at 1x speed.
        // Backward jumps are real seeks — accept immediately.
        // Reject forward jumps > 60s (likely garbage, real seeks ≤ 60s).
        if (result > 0 && smoothedPosition >= 0) {
            final long delta = result - smoothedPosition;
            if (delta < 0) {
                smoothedPosition = result;
            } else if (delta > 60_000) {
                return smoothedPosition;
            }
        }
        if (result > 0) {
            smoothedPosition = result;
        }
        return smoothedPosition >= 0 ? smoothedPosition : result;
    }

    /**
     * Injection point relay. Called on the main thread.
     */
    public void onSetMetadata(@Nullable MediaMetadata metadata) {
        Utils.verifyOnMainThread();
        if (metadata == null || !Settings.LYRICS_ENABLED.get()) {
            return;
        }

        String rawTitle = metadata.getString(MediaMetadata.METADATA_KEY_TITLE);
        String rawArtist = metadata.getString(MediaMetadata.METADATA_KEY_ARTIST);
        if (rawTitle == null || rawTitle.isBlank() || rawArtist == null || rawArtist.isBlank()) {
            return;
        }

        String[] parsed = MetadataCleaner.parseTitleAndArtist(rawTitle);
        String effectiveTitle = parsed != null ? parsed[1] : MetadataCleaner.cleanTitle(rawTitle);
        String effectiveArtist = parsed != null ? parsed[0] : MetadataCleaner.cleanArtist(rawArtist);

        TrackInfo track = new TrackInfo(
                effectiveTitle,
                effectiveArtist,
                MetadataCleaner.cleanAlbum(metadata.getString(MediaMetadata.METADATA_KEY_ALBUM)),
                (int) (metadata.getLong(MediaMetadata.METADATA_KEY_DURATION) / 1000)
        );

        if (track.title().isEmpty() || track.artist().isEmpty()) {
            return;
        }

        currentRawTitle = rawTitle;
        currentRawArtist = rawArtist;
        currentMediaUri = parseMediaUri(metadata);

        if (track.equals(currentTrack)) {
            positionMs = 0;
            positionUpdatedAtUptimeMs = SystemClock.uptimeMillis();
            lastVideoTimeSample = -1;
            smoothedPosition = -1;
            return;
        }

        currentTrack = track;
        // A new track starts at zero, and the first playback state update corrects it.
        positionMs = 0;
        positionUpdatedAtUptimeMs = SystemClock.uptimeMillis();
        lastVideoTimeSample = -1;
        smoothedPosition = -1;

        load(track);
    }

    /**
     * Injection point relay. Called on the main thread.
     */
    public void onSetPlaybackState(@Nullable PlaybackState playbackState) {
        Utils.verifyOnMainThread();
        if (playbackState == null) {
            return;
        }

        playing = playbackState.getState() == PlaybackState.STATE_PLAYING;
        final long newPosition = playbackState.getPosition();

        if (smoothedPosition >= 0 && positionMs != newPosition) {
            final long expected = positionMs
                    + (long) ((SystemClock.uptimeMillis() - positionUpdatedAtUptimeMs) * playbackSpeed);
            if (Math.abs(newPosition - expected) > 2000) {
                smoothedPosition = -1;
            }
        }

        positionMs = newPosition;
        positionUpdatedAtUptimeMs = SystemClock.uptimeMillis();

        final float speed = playbackState.getPlaybackSpeed();
        // A paused state reports a speed of zero, which would freeze extrapolation
        // even after playback resumes, so only positive speeds are kept.
        if (speed > 0) {
            playbackSpeed = speed;
        }
    }

    public void onDisplayedTrackChanged(@Nullable String title, @Nullable String artist) {
        onDisplayedTrackChanged(title, artist, null);
    }

    public void onDisplayedTrackChanged(@Nullable String title, @Nullable String artist, @Nullable Uri mediaUri) {
        Utils.verifyOnMainThread();
        currentRawTitle = title;
        currentRawArtist = artist;
        if (title == null || title.isBlank() || artist == null || artist.isBlank()) {
            return;
        }

        final String[] parsed = MetadataCleaner.parseTitleAndArtist(title);
        final String cleanedTitle = parsed != null ? parsed[1] : MetadataCleaner.cleanTitle(title);
        final String cleanedArtist = parsed != null ? parsed[0] : MetadataCleaner.cleanArtist(artist);
        if (cleanedTitle.isEmpty() || cleanedArtist.isEmpty()) {
            return;
        }

        if (currentTrack != null
                && currentTrack.title().equals(cleanedTitle)
                && currentTrack.artist().equals(cleanedArtist)) {
            if (mediaUri != null) {
                currentMediaUri = mediaUri;
            }
            return;
        }

        currentTrack = new TrackInfo(cleanedTitle, cleanedArtist, "", 0);
        currentMediaUri = mediaUri;
        positionMs = 0;
        positionUpdatedAtUptimeMs = SystemClock.uptimeMillis();
        lastVideoTimeSample = -1;
        smoothedPosition = -1;
        load(currentTrack);
    }

    public void clearLyrics() {
        Utils.verifyOnMainThread();
        currentMediaUri = null;
        setState(State.IDLE, null);
    }

    /**
     * Returns true for tracks backed by a local file ({@code file://} or {@code content://}) as
     * opposed to a streamed YouTube video ({@code http(s)://}). Only local files can carry
     * embedded lyrics in their tags.
     */
    private static boolean isLocalUri(@Nullable Uri uri) {
        if (uri == null) {
            return false;
        }
        final String scheme = uri.getScheme();
        return "file".equals(scheme) || "content".equals(scheme);
    }

    @Nullable
    private static Uri parseMediaUri(@NonNull MediaMetadata metadata) {
        final String uri = metadata.getString(MediaMetadata.METADATA_KEY_MEDIA_URI);
        return (uri != null) ? Uri.parse(uri) : null;
    }

    private void load(TrackInfo track) {
        final int id = ++requestId;
        setState(State.LOADING, null);

        // Reset candidate cycling state for the new track.
        currentProviderIndex = 0;
        currentCandidateIndex = 0;
        currentProviders = null;
        currentCandidateTrack = track;
        currentCandidateRequestId = id;
        candidateCache = null;

        final Lyrics cachedSubtitles = LyricsCache.get(track, LyricsSource.SUBTITLES.name());
        if (cachedSubtitles != null) {
            if (cachedSubtitles == Lyrics.NOT_FOUND) {
                executor.execute(() -> runProviderLookup(id, track, null));
                return;
            }
            final Lyrics subtitles = cachedSubtitles;
            Utils.runOnMainThread(() -> {
                if (id != requestId) {
                    return;
                }
                publish(id, subtitles);
                if (!subtitles.isEmpty()) {
                    translateSubtitles(id, track, subtitles);
                }
            });
            return;
        }

        executor.execute(() -> {
            final SubtitlesFetcher.SubtitlesOutcome outcome = SubtitlesFetcher.fetch();
            if (outcome.lyrics != null) {
                if (isInstrumental(outcome.lyrics)) {
                    LyricsCache.put(track, LyricsSource.SUBTITLES.name(), Lyrics.NOT_FOUND);
                    runProviderLookup(id, track, outcome.innertubeTrack);
                    return;
                }
                Lyrics result = outcome.lyrics;
                if (outcome.translationLyrics != null && !outcome.translationLyrics.isEmpty()) {
                    final String langTag = Locale.getDefault().toLanguageTag();
                    final Map<String, List<LyricsLine>> translations = new HashMap<>();
                    translations.put(langTag, outcome.translationLyrics.lines());
                    result = new Lyrics(result.lines(), result.providerName(), result.synced(),
                            result.romanization(), translations,
                            result.romanizations(), result.songwriters());
                    Logger.printDebug(() -> "Lyrics: embedded YouTube translation lang=" + langTag
                            + " lines=" + outcome.translationLyrics.lines().size());
                }
                final Lyrics toCache = result;
                LyricsCache.put(track, LyricsSource.SUBTITLES.name(), toCache);
                Utils.runOnMainThread(() -> {
                    if (id != requestId) {
                        return;
                    }
                    publish(id, toCache);
                    translateSubtitles(id, track, toCache);
                });
                return;
            }

            if (outcome.suppressProviders) {
                LyricsCache.put(track, LyricsSource.SUBTITLES.name(), EMPTY_SUBTITLES);
                Utils.runOnMainThread(() -> {
                    if (id != requestId) {
                        return;
                    }
                    publish(id, EMPTY_SUBTITLES);
                });
                return;
            }

            LyricsCache.put(track, LyricsSource.SUBTITLES.name(), Lyrics.NOT_FOUND);
            runProviderLookup(id, track, outcome.innertubeTrack);
        });
    }

    /**
     * Fetches the next candidate lyrics. Called from the refresh button.
     * Cycles through candidates within the current source first, then falls back
     * to the next source. Circular cycling: all sources exhausted → cycle back to first.
     */
    public void fetchNextCandidate() {
        Utils.verifyOnMainThread();
        final TrackInfo track = currentTrack;
        if (track == null) {
            return;
        }

        final int id = currentCandidateRequestId;
        if (id != requestId) {
            return;
        }

        if (currentProviders == null) {
            final String order = Settings.LYRICS_SOURCE.get();
            currentProviders = new ArrayList<>(providersInOrder(order));
            candidateCache = new java.util.HashMap<>();
        }

        setState(State.LOADING, null);

        executor.execute(() -> {
            final int totalProviders = currentProviders.size();

            for (int attempt = 0; attempt < totalProviders; attempt++) {
                final LyricsProvider provider = currentProviders.get(currentProviderIndex);

                List<Lyrics> candidates = candidateCache.get(currentProviderIndex);
                if (candidates == null && provider.hasCandidates()) {
                    try {
                        candidates = provider.fetchCandidates(track);
                        if (candidates != null && candidates.size() > 5) {
                            candidates = new ArrayList<>(candidates.subList(0, 5));
                        }
                    } catch (Exception ex) {
                        Logger.printInfo(() -> "Candidate fetch failed: " + provider.name(), ex);
                    }
                    if (candidates == null) {
                        candidates = new ArrayList<>();
                    }
                    candidateCache.put(currentProviderIndex, candidates);
                }

                if (candidates != null && !candidates.isEmpty()
                        && currentCandidateIndex < candidates.size()) {
                    final Lyrics candidate = candidates.get(currentCandidateIndex);
                    if (isValidLyrics(candidate)) {
                        final Lyrics toPublish = candidate;
                        final int providerIdx = currentProviderIndex;
                        final int candidateIdx = currentCandidateIndex;
                        final int candidateCount = candidates.size();
                        Utils.runOnMainThread(() -> {
                            if (id != requestId) {
                                return;
                            }
                            Logger.printDebug(() -> "Refresh: provider=" + currentProviders.get(providerIdx).name()
                                    + " candidate=" + (candidateIdx + 1) + "/" + candidateCount);
                            publish(id, toPublish);
                        });
                        currentCandidateIndex++;
                        return;
                    }
                }

                currentProviderIndex = (currentProviderIndex + 1) % totalProviders;
                currentCandidateIndex = 0;
            }

            // All providers exhausted — go back to regular load.
            Utils.runOnMainThread(() -> {
                if (id != requestId) {
                    return;
                }
                load(track);
            });
        });
    }

    private void runProviderLookup(int id, TrackInfo track, @Nullable TrackInfo innertubeTrack) {
        Logger.printInfo(() -> "Lyrics lookup start: title='" + track.title() + "' artist='"
                + track.artist() + "' innertubeTitle='" + (innertubeTrack != null ? innertubeTrack.title() : "n/a")
                + "' useEmbedded=" + Settings.LYRICS_USE_EMBEDDED.get()
                + " isLocalTrack=" + isLocalTrack() + " mediaUri=" + currentMediaUri
                + " videoId='" + VideoInformation.getVideoId() + "'");
        // Local files take priority: read embedded LYRICS/LYRIC tags before hitting providers.
        if (Settings.LYRICS_USE_EMBEDDED.get()) {
            final Uri embeddedUri = localUriFor(track);
            Logger.printInfo(() -> "Local embedded lyrics: videoId='"
                    + VideoInformation.getVideoId() + "' currentUri=" + currentMediaUri
                    + " resolvedUri=" + embeddedUri + " isLocalTrack=" + isLocalTrack());
            if (embeddedUri != null) {
                Lyrics embedded = LyricsCache.get(track, LyricsSource.LOCAL.name());
                if (embedded == null) {
                    embedded = LocalLyricsFetcher.fetch(embeddedUri);
                    LyricsCache.put(track, LyricsSource.LOCAL.name(),
                            embedded != null ? embedded : Lyrics.NOT_FOUND);
                }
                if (embedded != null && embedded != Lyrics.NOT_FOUND) {
                    final Lyrics toPublish = embedded;
                    Utils.runOnMainThread(() -> publish(id, toPublish));
                    return;
                }
            }
        }

        final String order = Settings.LYRICS_SOURCE.get();
        final List<LyricsProvider> providers = providersInOrder(order);

        // A cached hit for any provider short-circuits the lookup. A cached miss is
        // ignored so a lower-priority provider with cached lyrics still gets a chance.
        // Check both localized and InnerTube metadata caches.
        for (LyricsProvider provider : providers) {
            final Lyrics cached = LyricsCache.get(track, provider.name());
            if (cached != null && cached != Lyrics.NOT_FOUND) {
                final Lyrics toPublish = cached;
                Utils.runOnMainThread(() -> publish(id, toPublish));
                return;
            }
            if (innertubeTrack != null && !innertubeTrack.equals(track)) {
                final Lyrics cachedIT = LyricsCache.get(innertubeTrack, provider.name());
                if (cachedIT != null && cachedIT != Lyrics.NOT_FOUND) {
                    final Lyrics toPublish = cachedIT;
                    LyricsCache.put(track, provider.name(), toPublish);
                    Utils.runOnMainThread(() -> publish(id, toPublish));
                    return;
                }
            }
        }

        if (!Utils.isNetworkConnected()) {
            Utils.runOnMainThread(() -> {
                if (id == requestId) {
                    setState(State.ERROR, null);
                }
            });
            return;
        }

        final boolean[] failed = {false};
        Lyrics result = null;

        // 1. Try InnerTube canonical metadata first (different title/artist from localized).
        if (innertubeTrack != null && !innertubeTrack.equals(track)) {
            // Check cache for InnerTube metadata too.
            for (LyricsProvider provider : providers) {
                final Lyrics cached = LyricsCache.get(innertubeTrack, provider.name());
                if (cached != null && cached != Lyrics.NOT_FOUND) {
                    final Lyrics toPublish = cached;
                    LyricsCache.put(track, provider.name(), toPublish);
                    Utils.runOnMainThread(() -> publish(id, toPublish));
                    return;
                }
            }
            result = fetchFromProviders(innertubeTrack, failed, providers);
            if (!isValidLyrics(result)) {
                for (TrackInfo variant : CharactersConverter.variants(innertubeTrack)) {
                    final Lyrics fetched = fetchFromProviders(variant, failed, providers);
                    if (isValidLyrics(fetched)) {
                        result = fetched;
                        break;
                    }
                }
            }
            if (isValidLyrics(result)) {
                final Lyrics toPublish = result;
                LyricsCache.put(innertubeTrack, result.providerName(), toPublish);
                LyricsCache.put(track, result.providerName(), toPublish);
                Utils.runOnMainThread(() -> publish(id, toPublish));
                return;
            }
        }

        // 2. Fall back to localized MediaSession metadata.
        if (!isValidLyrics(result)) {
            result = fetchFromProviders(track, failed, providers);
        }

        if (!isValidLyrics(result)) {
            for (TrackInfo variant : CharactersConverter.variants(track)) {
                final Lyrics fetched = fetchFromProviders(variant, failed, providers);
                if (isValidLyrics(fetched)) {
                    result = fetched;
                    break;
                }
            }
        }

        if (!isValidLyrics(result)) {
            final TrackInfo swapped = MetadataCleaner.swapTitleAndArtist(track, currentRawTitle);
            if (swapped != null) {
                result = fetchFromProviders(swapped, failed, providers);
            }
        }

        if (isValidLyrics(result)) {
            final Lyrics toPublish = result;
            LyricsCache.put(track, result.providerName(), toPublish);
            Utils.runOnMainThread(() -> publish(id, toPublish));
            return;
        }

        // No provider returned lyrics: remember the miss for every enabled provider.
        for (LyricsProvider provider : providers) {
            LyricsCache.put(track, provider.name(), Lyrics.NOT_FOUND);
            if (innertubeTrack != null && !innertubeTrack.equals(track)) {
                LyricsCache.put(innertubeTrack, provider.name(), Lyrics.NOT_FOUND);
            }
        }
        if (failed[0]) {
            Utils.runOnMainThread(() -> {
                if (id == requestId) {
                    setState(State.ERROR, null);
                }
            });
        } else {
            Utils.runOnMainThread(() -> publish(id, Lyrics.NOT_FOUND));
        }
    }

    /**
     * A track is local (not a streamed YouTube video) when the media session already exposes a
     * local file/content URI, or when no video id is known. Streamed videos always carry a video
     * id, so an empty id is the reliable "local song" signal used elsewhere (subtitles, Unison).
     */
    private boolean isLocalTrack() {
        if (isLocalUri(currentMediaUri)) {
            return true;
        }
        // The video id is set on the main thread and may not have settled yet when this runs on the
        // executor. Wait briefly so a streamed video's id can appear (proving it is not local) and
        // so a local song (id stays empty) is not mistaken for a video. Mirrors SubtitlesFetcher.
        for (int i = 0; i < 3; i++) {
            if (VideoInformation.getVideoId().isEmpty()) {
                return true;
            }
            if (i < 2) {
                try {
                    Thread.sleep(150);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        return false;
    }

    /**
     * Resolves the file URI used to read embedded lyrics: prefer a known local URI, otherwise look
     * the on-device file up in the MediaStore by title/artist/duration.
     */
    @Nullable
    private Uri localUriFor(TrackInfo track) {
        if (isLocalUri(currentMediaUri)) {
            return currentMediaUri;
        }
        return LocalLyricsFetcher.resolveMediaStoreUri(
                track.title(), track.artist(), track.durationSeconds(), currentRawTitle, currentRawArtist);
    }

    private void translateSubtitles(int id, TrackInfo track, Lyrics subtitles) {
        LyricsTranslator.translate(track, subtitles, LyricsSource.SUBTITLES.name(), (translated, fromGoogle) -> {
            if (id != requestId || translated == null) {
                return;
            }

            List<LyricsLine> original = subtitles.lines();
            List<LyricsLine> lines = new ArrayList<>(original.size());
            for (int i = 0; i < original.size(); i++) {
                String text = original.get(i).text();
                if (i < translated.size()) {
                    String t = translated.get(i);
                    if (t != null && !t.isEmpty()) {
                        text = t;
                    }
                }
                lines.add(new LyricsLine(original.get(i).startTimeMs(), text));
            }
            publish(id, new Lyrics(lines, subtitles.providerName(), true));
        });
    }

    @Nullable
    private Lyrics fetchFromProviders(TrackInfo track, boolean[] failed, List<LyricsProvider> providers) {
        final CompletionService<Lyrics> cs = new ExecutorCompletionService<>(executor);
        final List<Future<Lyrics>> futures = new ArrayList<>(providers.size());
        final AtomicBoolean threadFailed = new AtomicBoolean(false);

        for (LyricsProvider provider : providers) {
            futures.add(cs.submit(() -> {
                try {
                    return provider.fetch(track);
                } catch (Exception ex) {
                    threadFailed.set(true);
                    Logger.printInfo(() -> "Lyrics request failed: " + provider.name(), ex);
                    return null;
                }
            }));
        }

        final boolean wordSync = Settings.LYRICS_WORD_SYNC.get();
        Lyrics bestResult = null;
        int bestRank = wordSync ? -1 : -2;
        int completed = 0;
        long deadline = System.currentTimeMillis() + 30_000;

        while (completed < futures.size()) {
            long remaining = deadline - System.currentTimeMillis();
            if (remaining <= 0) {
                Logger.printDebug(() -> "fetchFromProviders: overall timeout reached");
                break;
            }

            Future<Lyrics> f;
            try {
                f = cs.poll(remaining, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
            if (f == null) {
                Logger.printDebug(() -> "fetchFromProviders: poll timeout");
                break;
            }

            completed++;
            try {
                final Lyrics fetched = f.get();
                if (!isValidLyrics(fetched)) {
                    continue;
                }
                final int rank = rankOf(fetched);
                if (wordSync) {
                    if (rank > bestRank) {
                        bestRank = rank;
                        bestResult = fetched;
                    }
                    if (rank == 2) {
                        break; // word-synced is the best possible tier
                    }
                } else {
                    final int effectiveRank = rank == 2 ? -1 : rank;
                    if (effectiveRank > bestRank) {
                        bestRank = effectiveRank;
                        bestResult = fetched;
                    }
                    if (effectiveRank == 1) {
                        break; // line-synced is the best preferred tier
                    }
                }
            } catch (Exception e) {
            }
        }

        for (Future<Lyrics> f : futures) {
            if (!f.isDone()) {
                f.cancel(true);
            }
        }

        failed[0] = threadFailed.get();
        return bestResult;
    }

    private static int rankOf(Lyrics lyrics) {
        for (LyricsLine line : lyrics.lines()) {
            if (line.hasWords()) {
                return 2;
            }
        }
        return lyrics.synced() ? 1 : 0;
    }

    private void publish(int id, Lyrics lyrics) {
        if (id != requestId) {
            Logger.printDebug(() -> "Discarding lyrics of a previous track");
            return;
        }

        lyrics = filterLyricsText(lyrics);

        if (lyrics == Lyrics.NOT_FOUND || lyrics.isEmpty()) {
            setState(State.NOT_FOUND, null);
        } else {
            setState(State.LOADED, lyrics);
            LyricsPanelInstaller.enableLyricsButton();
            Utils.runOnMainThreadDelayed(() -> LyricsPanelInstaller.onLyricsPanelDetected(), 300);
            final Lyrics loaded = lyrics;
            Logger.printInfo(() -> "Lyrics loaded: source=" + loaded.providerName()
                    + " subtitles=" + loaded.isSubtitles()
                    + " lines=" + loaded.lines().size());
        }
    }

    /**
     * Applies {@link Settings#LYRICS_TEXT_FILTER} to the original lyrics text only.
     * Translations and romanizations are left untouched.
     */
    private static Lyrics filterLyricsText(Lyrics lyrics) {
        if (lyrics == Lyrics.NOT_FOUND) {
            return lyrics;
        }
        String filter = Settings.LYRICS_TEXT_FILTER.get();
        if (filter.isBlank()) {
            return lyrics;
        }

        List<LyricsLine> original = lyrics.lines();
        List<LyricsLine> filtered = new ArrayList<>(original.size());
        boolean anyDropped = false;
        for (LyricsLine line : original) {
            String text = MetadataCleaner.applyRegex(line.text(), filter);
            if (text.isEmpty()) {
                anyDropped = true;
                continue;
            }
            if (line.hasWords()) {
                List<Word> words = new ArrayList<>(line.words().size());
                for (Word w : line.words()) {
                    String wt = MetadataCleaner.applyRegex(w.text(), filter);
                    if (!wt.isEmpty()) {
                        words.add(new Word(w.startMs(), w.endMs(), wt,
                                w.romaji(), w.endsWithSpace()));
                    }
                }
                filtered.add(new LyricsLine(line.startTimeMs(), text, words));
            } else {
                filtered.add(new LyricsLine(line.startTimeMs(), text));
            }
        }

        if (!anyDropped) {
            return lyrics;
        }

        return new Lyrics(filtered, lyrics.providerName(), lyrics.synced());
    }

    private void setState(State newState, @Nullable Lyrics lyrics) {
        state = newState;
        currentLyrics = lyrics;

        // A listener may remove itself while being notified.
        for (Listener listener : new ArrayList<>(listeners)) {
            try {
                listener.onLyricsChanged(newState, lyrics);
            } catch (Exception ex) {
                Logger.printException(() -> "Lyrics listener failure", ex);
            }
        }
    }

    @NonNull
    public String getCurrentLineText() {
        if (currentLyrics == null || !currentLyrics.synced() || currentLyrics.isEmpty()) {
            return "";
        }
        final int index = currentLyrics.indexForPosition(getPositionMs(), -1);
        if (index < 0) {
            return "";
        }
        final String text = currentLyrics.lines().get(index).text();
        return text == null ? "" : text;
    }

    public boolean areLyricsAvailable() {
        return currentLyrics != null
                && currentLyrics != Lyrics.NOT_FOUND
                && !currentLyrics.isEmpty();
    }

    @NonNull
    private static List<LyricsProvider> providersInOrder(String order) {
        List<LyricsProvider> providers = new ArrayList<>(PROVIDER_ORDER.size());
        for (String id : enabledProviderIds(order)) {
            LyricsProvider provider = providerFor(id);
            if (provider != null) {
                providers.add(provider);
            }
        }
        return providers;
    }

    /** Canonical provider ids, in the default priority order. */
    private static final List<String> PROVIDER_ORDER = Arrays.asList(
            "LRCLIB", "QQ", "NetEase", "KuGou", "bLyrics", "BiniLyrics", "Unison", "AMLL",
            "Apple", "Musixmatch", "Spotify");

    @NonNull
    private static List<String> enabledProviderIds(String order) {
        List<String> result = new ArrayList<>();
        if (order == null || order.isEmpty() || !order.contains(",")) {
            order = Settings.DEFAULT_LYRICS_ORDER;
        }
        for (String raw : order.split(",")) {
            String token = raw.trim();
            if (token.isEmpty()) {
                continue;
            }
            boolean enabled = true;
            if (token.startsWith("-")) {
                enabled = false;
                token = token.substring(1).trim();
            }
            if (!PROVIDER_ORDER.contains(token)) {
                continue;
            }
            boolean seen = false;
            for (String existing : result) {
                if (existing.equals(token)) {
                    seen = true;
                    break;
                }
            }
            if (seen) {
                continue;
            }
            if (enabled) {
                result.add(token);
            }
        }
        if (result.isEmpty()) {
            result.addAll(PROVIDER_ORDER);
        }
        return result;
    }

    @Nullable
    private static LyricsProvider providerFor(String id) {
        switch (id) {
            case "LRCLIB": return new LrcLibProvider();
            case "Spotify": return new SpotifyProvider();
            case "QQ": return new QQProvider();
            case "KuGou": return new KuGouProvider();
            case "NetEase": return new NetEaseProvider();
            case "BiniLyrics": return new BinimumProvider();
            case "bLyrics": return new BlyricsProvider();
            case "Musixmatch": return new MusixmatchProvider();
            case "Unison": return new UnisonProvider();
            case "AMLL": return new AmllProvider();
            case "Apple": return new AppleMusicProvider();
            default: return null;
        }
    }

    /**
     * Maps a lyrics (content) timeline position to the player video time.
     * SponsorBlock auto-skip remapping was removed, so the two timelines are identical.
     */
    public long toVideoTime(long contentMs) {
        return contentMs;
    }
}
