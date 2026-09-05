package app.morphe.extension.music.patches.lyrics.requests;

import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

import app.morphe.extension.music.patches.lyrics.Lyrics;
import app.morphe.extension.music.patches.lyrics.LyricsLine;
import app.morphe.extension.music.patches.lyrics.TrackInfo;
import app.morphe.extension.music.settings.Settings;
import app.morphe.extension.music.shared.VideoInformation;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.innertube.utils.AuthUtils;
import app.morphe.extension.shared.requests.Requester;
import app.morphe.extension.shared.spoof.ClientType;
import app.morphe.extension.shared.spoof.potoken.PoTokenManager;

public final class SubtitlesFetcher {

    private static final int CONNECT_TIMEOUT_MS = 5_000;
    private static final int READ_TIMEOUT_MS = 8_000;
    private static final String INNERTUBE_PLAYER_URL =
            "https://www.youtube.com/youtubei/v1/player?prettyPrint=false";
    private static final String TIMEDTEXT_URL =
            "https://www.youtube.com/api/timedtext";
    private static final String USER_AGENT =
            "com.google.android.youtube/20.10.38 (Linux; U; Android 11) gzip";

    private static final String CAPTION_USER_AGENT =
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/140.0.0.0 Safari/537.36";

    private static final Pattern BRACKETS_PATTERN = Pattern.compile("\\[[^]]*]");
    private static final Pattern PARENTHESES_PATTERN = Pattern.compile("\\([^)]*\\)");

    private SubtitlesFetcher() {
    }

    private static final class CaptionTrack {
        final String url;
        final String langCode;
        final boolean isAsr;
        final String displayName;

        CaptionTrack(String url, String langCode, boolean isAsr, String displayName) {
            this.url = url;
            this.langCode = langCode;
            this.isAsr = isAsr;
            this.displayName = displayName;
        }

        @Override
        public String toString() {
            return langCode + (isAsr ? " (ASR)" : " (manual)") + " '" + displayName + "'";
        }
    }

    public static final class SubtitlesOutcome {
        static final SubtitlesOutcome ALLOW_PROVIDERS = new SubtitlesOutcome(null, null, false, null);
        static final SubtitlesOutcome SUPPRESS_PROVIDERS = new SubtitlesOutcome(null, null, true, null);

        public final @Nullable Lyrics lyrics;
        public final @Nullable Lyrics translationLyrics;
        public final boolean suppressProviders;
        public final @Nullable TrackInfo innertubeTrack;

        private SubtitlesOutcome(@Nullable Lyrics lyrics, @Nullable Lyrics translationLyrics,
                                 boolean suppressProviders, @Nullable TrackInfo innertubeTrack) {
            this.lyrics = lyrics;
            this.translationLyrics = translationLyrics;
            this.suppressProviders = suppressProviders;
            this.innertubeTrack = innertubeTrack;
        }

        static SubtitlesOutcome subtitles(Lyrics lyrics, @Nullable TrackInfo innertubeTrack) {
            return new SubtitlesOutcome(lyrics, null, false, innertubeTrack);
        }

        static SubtitlesOutcome subtitles(Lyrics lyrics, @Nullable Lyrics translationLyrics,
                                          @Nullable TrackInfo innertubeTrack) {
            return new SubtitlesOutcome(lyrics, translationLyrics, false, innertubeTrack);
        }
    }

    public static SubtitlesOutcome fetch() {
        final String videoId = readVideoIdWithRetry();
        if (videoId == null || videoId.isEmpty()) {
            Logger.printDebug(() -> "Subtitles: no video id available, skipping");
            return SubtitlesOutcome.ALLOW_PROVIDERS;
        }
        Logger.printDebug(() -> "Subtitles: video id=" + videoId);

        try {
            final CaptionListResult captions = findCaptionList(videoId);
            if (captions == null || !captions.structurePresent) {
                return tryTimedtext(videoId, captions != null ? captions.poToken : null,
                        captions != null ? captions.innertubeTrack : null);
            }

            if (!captions.tracks.isEmpty()) {
                final CaptionTrack primaryTrack = selectPrimaryTrack(captions.tracks);
                if (primaryTrack != null) {
                    final Lyrics primaryLyrics = fetchTrackLyrics(primaryTrack, captions.poToken);
                    if (primaryLyrics != null && !primaryLyrics.isEmpty()) {
                        final CaptionTrack translationTrack =
                                selectTranslationTrack(captions.tracks, primaryTrack.langCode);
                        Lyrics translationLyrics = null;
                        if (translationTrack != null) {
                            final Lyrics tl = fetchTrackLyrics(translationTrack, captions.poToken);
                            if (tl != null && !tl.isEmpty()) {
                                translationLyrics = tl;
                                Logger.printDebug(() -> "Subtitles: translation track "
                                        + translationTrack + " → " + tl.lines().size()
                                        + " lines");
                            }
                        }
                        Logger.printDebug(() -> "Subtitles: primary track " + primaryTrack
                                + " → " + primaryLyrics.lines().size() + " lines");
                        return SubtitlesOutcome.subtitles(primaryLyrics, translationLyrics,
                                captions.innertubeTrack);
                    }
                }
                Logger.printDebug(() -> "Subtitles: caption tracks present but primary fetch failed");
            } else {
                Logger.printDebug(() -> "Subtitles: captions structure present but no tracks");
            }

            final Lyrics timed = fetchViaTimedtext(videoId, captions.poToken, null);
            if (timed != null && !timed.isEmpty()) {
                return SubtitlesOutcome.subtitles(timed, captions.innertubeTrack);
            }
            Logger.printDebug(() -> "Subtitles: no usable captions, falling back to providers");
            return allowProviders(captions.innertubeTrack);
        } catch (Exception ex) {
            Logger.printDebug(() -> "Subtitles fetch failed", ex);
            return SubtitlesOutcome.ALLOW_PROVIDERS;
        }
    }

    @Nullable
    private static Lyrics fetchTrackLyrics(CaptionTrack track, @Nullable String poToken) {
        try {
            final String json = fetchCaptionUrl(buildCaptionUrl(track.url, poToken));
            final List<LyricsLine> lines = parseJson3(json);
            if (!lines.isEmpty()) {
                return new Lyrics(lines, Lyrics.CAPTIONS_PROVIDER, true, null, null, null, null, json, "json3", null);
            }
        } catch (Exception ex) {
            Logger.printDebug(() -> "Subtitles: caption url fetch failed for " + track, ex);
        }
        return null;
    }

    /** Builds a json3 caption URL, appending the poToken modern endpoints require. */
    private static String buildCaptionUrl(String baseUrl, @Nullable String poToken) {
        String url = baseUrl.replaceAll("&fmt=[^&]*", "") + "&fmt=json3";
        if (poToken != null && !poToken.isEmpty()) {
            url += "&pot=" + poToken;
        }
        return url;
    }

    private static SubtitlesOutcome tryTimedtext(String videoId, @Nullable String poToken,
                                                 @Nullable TrackInfo innertubeTrack) {
        final Lyrics timed = fetchViaTimedtext(videoId, poToken, null);
        if (timed != null && !timed.isEmpty()) {
            return SubtitlesOutcome.subtitles(timed, innertubeTrack);
        }
        Logger.printDebug(() -> "Subtitles: timedtext fallback found nothing");
        return allowProviders(innertubeTrack);
    }

    private static SubtitlesOutcome allowProviders(@Nullable TrackInfo innertubeTrack) {
        return new SubtitlesOutcome(null, null, false, innertubeTrack);
    }

    @Nullable
    private static String readVideoIdWithRetry() {
        String videoId = VideoInformation.getVideoId();
        if (videoId != null && !videoId.isEmpty()) {
            return videoId;
        }
        for (int i = 0; i < 5; i++) {
            try {
                Thread.sleep(200);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                break;
            }
            videoId = VideoInformation.getVideoId();
            if (videoId != null && !videoId.isEmpty()) {
                final int attempt = i;
                Logger.printDebug(() -> "Subtitles: video id available after retry " + attempt);
                return videoId;
            }
        }
        return null;
    }

    private static final class CaptionListResult {
        final boolean structurePresent;
        final List<CaptionTrack> tracks;
        final @Nullable String poToken;
        final @Nullable TrackInfo innertubeTrack;

        CaptionListResult(boolean structurePresent, List<CaptionTrack> tracks,
                          @Nullable String poToken, @Nullable TrackInfo innertubeTrack) {
            this.structurePresent = structurePresent;
            this.tracks = tracks;
            this.poToken = poToken;
            this.innertubeTrack = innertubeTrack;
        }
    }

    @Nullable
    private static CaptionListResult findCaptionList(String videoId) {
        final String json = fetchInnertubePlayer(videoId);
        if (json == null) {
            return null;
        }
        final TrackInfo innertubeTrack = extractVideoDetails(json);
        if (innertubeTrack != null) {
            Logger.printDebug(() -> "Subtitles: innertube videoDetails title='"
                    + innertubeTrack.title() + "' artist='" + innertubeTrack.artist() + "'");
        }
        String resolvedPoToken = extractPoToken(json);
        if (resolvedPoToken == null || resolvedPoToken.isEmpty()) {
            Logger.printDebug(() -> "Subtitles: innertube response has no poToken, generating via BotGuard");
            try {
                resolvedPoToken = PoTokenManager.getPlayerPoToken(ClientType.ANDROID, videoId);
            } catch (Exception ex) {
                Logger.printDebug(() -> "Subtitles: PoTokenManager failed", ex);
            }
        }
        final String poToken = resolvedPoToken;
        final int tracksIdx = json.indexOf("\"captionTracks\":[");
        if (tracksIdx < 0) {
            Logger.printDebug(() -> "Subtitles: innertube response has no captionTracks field");
            return new CaptionListResult(false, new ArrayList<>(), poToken, innertubeTrack);
        }
        final List<CaptionTrack> tracks = extractCaptionTracks(json, tracksIdx);
        Logger.printDebug(() -> "Subtitles: innertube captionTracks count=" + tracks.size()
                + " poToken=" + (poToken != null));
        for (CaptionTrack track : tracks) {
            Logger.printDebug(() -> "  track: " + track);
        }
        return new CaptionListResult(true, tracks, poToken, innertubeTrack);
    }

    /** Extracts the proof-of-origin token required by modern caption endpoints. */
    @Nullable
    private static String extractPoToken(String json) {
        int idx = json.indexOf("\"poToken\":\"");
        if (idx < 0) {
            return null;
        }
        idx += "\"poToken\":\"".length();
        final int end = json.indexOf('"', idx);
        return end < 0 ? null : json.substring(idx, end);
    }

    @Nullable
    private static TrackInfo extractVideoDetails(String json) {
        final int vdIdx = json.indexOf("\"videoDetails\":{");
        if (vdIdx < 0) {
            return null;
        }
        final int vdStart = vdIdx + "\"videoDetails\":".length();
        final String title = extractJsonString(json, vdStart, "title");
        final String author = extractJsonString(json, vdStart, "author");
        if (title == null || title.isEmpty() || author == null || author.isEmpty()) {
            return null;
        }
        return new TrackInfo(title, author, "", 0);
    }

    @Nullable
    private static String extractJsonString(String json, int from, String key) {
        String needle = "\"" + key + "\":\"";
        int idx = json.indexOf(needle, from);
        if (idx < 0) {
            return null;
        }
        idx += needle.length();
        final int end = json.indexOf('"', idx);
        if (end < 0) {
            return null;
        }
        return json.substring(idx, end);
    }

    /** Reads the {@code lang} subtag out of a caption track baseUrl, or empty string. */
    private static String extractLangFromUrl(String url) {
        for (String prefix : new String[]{"&lang=", "?lang="}) {
            int idx = url.indexOf(prefix);
            if (idx >= 0) {
                idx += prefix.length();
                final int end = url.indexOf('&', idx);
                return end < 0 ? url.substring(idx) : url.substring(idx, end);
            }
        }
        return "";
    }

    /** Parses caption tracks from the innertube JSON response. */
    private static List<CaptionTrack> extractCaptionTracks(String json, int tracksIdx) {
        List<CaptionTrack> tracks = new ArrayList<>();
        try {
            final int arrStart = json.indexOf('[', tracksIdx);
            if (arrStart < 0) return tracks;
            int depth = 0;
            int arrEnd = -1;
            for (int i = arrStart; i < json.length(); i++) {
                char c = json.charAt(i);
                if (c == '[') depth++;
                else if (c == ']') {
                    depth--;
                    if (depth == 0) { arrEnd = i; break; }
                }
            }
            if (arrEnd < 0) return tracks;

            final JSONArray arr = new JSONArray(json.substring(arrStart, arrEnd + 1));
            for (int i = 0; i < arr.length(); i++) {
                final JSONObject obj = arr.getJSONObject(i);

                String baseUrl = obj.optString("baseUrl", "");
                if (baseUrl.isEmpty()) continue;
                baseUrl = baseUrl.replace("\\u0026", "&")
                        .replace("\\u003d", "=")
                        .replace("\\u003e", ">")
                        .replace("\\u003c", "<");

                String langCode = obj.optString("languageCode", "");
                if (langCode.isEmpty()) {
                    langCode = extractLangFromUrl(baseUrl);
                }
                if (langCode.isEmpty()) continue;

                String kind = obj.optString("kind", "");
                boolean isAsr = "asr".equals(kind);

                String name = obj.optString("name", "");
                if (name.isEmpty()) {
                    name = langCode + (isAsr ? " (auto-generated)" : "");
                }

                tracks.add(new CaptionTrack(baseUrl, langCode, isAsr, name));
            }
        } catch (Exception ex) {
            Logger.printDebug(() -> "Subtitles: failed to parse captionTracks JSON", ex);
        }
        return tracks;
    }

    @Nullable
    private static CaptionTrack selectPrimaryTrack(List<CaptionTrack> tracks) {
        CaptionTrack firstManual = null;
        CaptionTrack firstAsr = null;
        for (CaptionTrack track : tracks) {
            if (track.url.contains("variant=gemini")) continue;
            if (!track.isAsr && firstManual == null) {
                firstManual = track;
            } else if (track.isAsr && firstAsr == null) {
                firstAsr = track;
            }
            if (firstManual != null) break;
        }
        return firstManual != null ? firstManual : firstAsr;
    }

    @Nullable
    private static CaptionTrack selectTranslationTrack(List<CaptionTrack> tracks,
                                                       String primaryLang) {
        final String sysLang = Locale.getDefault().getLanguage();
        if (sysLang.isEmpty()) return null;

        CaptionTrack manualMatch = null;
        CaptionTrack asrMatch = null;
        for (CaptionTrack track : tracks) {
            if (track.url.contains("variant=gemini")) continue;
            if (track.langCode.equals(primaryLang)) continue;
            if (!track.langCode.startsWith(sysLang) && !sysLang.startsWith(track.langCode)) continue;
            if (!track.isAsr && manualMatch == null) {
                manualMatch = track;
            } else if (track.isAsr && asrMatch == null) {
                asrMatch = track;
            }
            if (manualMatch != null) break;
        }
        return manualMatch != null ? manualMatch : asrMatch;
    }

    @Nullable
    private static String fetchInnertubePlayer(String videoId) {
        final String sysLang = Locale.getDefault().getLanguage();
        String body = "{\"context\":{\"client\":{\"clientName\":\"ANDROID\","
                + "\"clientVersion\":\"20.10.38\","
                + "\"hl\":\"en\",\"gl\":\"US\"}},"
                + "\"videoId\":\"" + videoId + "\","
                + "\"captionParams\":{\"captionsEnabled\":true,"
                + "\"languageCode\":\"" + sysLang + "\"},"
                + "\"contentCheckOk\":true,\"racyCheckOk\":true}";

        HttpURLConnection conn = null;
        try {
            conn = Requester.openConnection(INNERTUBE_PLAYER_URL);
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("User-Agent", USER_AGENT);
            conn.setRequestProperty("X-YouTube-Client-Name", "3");
            conn.setRequestProperty("X-YouTube-Client-Version", "20.10.38");
            conn.setDoOutput(true);

            for (Map.Entry<String, String> entry : AuthUtils.getRequestHeader().entrySet()) {
                if (!entry.getValue().isEmpty()) {
                    conn.setRequestProperty(entry.getKey(), entry.getValue());
                }
            }

            try (OutputStream os = conn.getOutputStream()) {
                os.write(body.getBytes(StandardCharsets.UTF_8));
            }

            final int responseCode = conn.getResponseCode();
            if (responseCode != 200) {
                Logger.printDebug(() -> "Subtitles: innertube player HTTP " + responseCode);
                return null;
            }
            return Requester.parseString(conn);
        } catch (Exception ex) {
            Logger.printDebug(() -> "Subtitles: innertube player request failed", ex);
            return null;
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    @Nullable
    private static Lyrics fetchViaTimedtext(String videoId, @Nullable String poToken,
                                           @Nullable List<String> preferredLangs) {
        List<String> langs = new ArrayList<>();
        if (preferredLangs != null) {
            for (String lang : preferredLangs) {
                if (!langs.contains(lang)) {
                    langs.add(lang);
                }
            }
        }
        final java.util.Locale sys = java.util.Locale.getDefault();
        final String sysLang = sys.getLanguage();
        if (!sysLang.isEmpty() && !langs.contains(sysLang)) {
            langs.add(sysLang);
        }
        final String sysRegion = sys.getLanguage() + "-" + sys.getCountry();
        if (!sysRegion.equals(sysLang) && !langs.contains(sysRegion)) {
            langs.add(sysRegion);
        }

        final String pot = (poToken != null && !poToken.isEmpty()) ? "&pot=" + poToken : "";
        for (String lang : langs) {
            try {
                final String url = TIMEDTEXT_URL + "?lang=" + lang + "&v=" + videoId
                        + "&kind=asr&fmt=json3" + pot;
                final String json = fetchCaptionUrl(url);
                final List<LyricsLine> lines = parseJson3(json);
                if (!lines.isEmpty()) {
                    Logger.printDebug(() -> "Subtitles: timedtext fallback got " + lines.size()
                            + " lines (lang=" + lang + ")");
                    return new Lyrics(lines, Lyrics.CAPTIONS_PROVIDER, true, null, null, null, null, json, "json3", null);
                }
            } catch (Exception ex) {
                // Wrong language or unavailable; try the next candidate.
            }
        }
        return null;
    }

    private static List<LyricsLine> parseJson3(String json) throws Exception {
        final JSONObject root = new JSONObject(json);
        if (!root.has("events")) {
            return new ArrayList<>();
        }

        final JSONArray events = root.getJSONArray("events");
        final List<LyricsLine> lines = new ArrayList<>();

        for (int i = 0; i < events.length(); i++) {
            final JSONObject event = events.getJSONObject(i);
            // ASR streams emit append events that only scroll the 2-line caption window; they
            // duplicate timing of real lines and carry no useful text, so skip them.
            if (event.optInt("aAppend", 0) == 1) {
                continue;
            }
            if (!event.has("segs")) {
                continue;
            }

            String text = "";
            long startTimeMs = event.optLong("tStartMs", 0);

            final JSONArray segments = event.getJSONArray("segs");
            for (int j = 0; j < segments.length(); j++) {
                final JSONObject seg = segments.getJSONObject(j);
                if (seg.has("utf8")) {
                    text += seg.getString("utf8");
                }
            }

            final String trimmed = text.trim();
            if (trimmed.isEmpty()
                    || BRACKETS_PATTERN.matcher(trimmed).matches()
                    || PARENTHESES_PATTERN.matcher(trimmed).matches()) {
                continue;
            }

            lines.add(new LyricsLine(startTimeMs, trimmed));
        }

        return lines;
    }

    private static String fetchCaptionUrl(String urlStr) throws Exception {
        HttpURLConnection conn = null;
        try {
            conn = Requester.openConnection(urlStr);
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);
            // A desktop user-agent is required: YouTube serves a consent wall / 403 to embedded
            // clients for the caption endpoint. See the YouTube TranscriptFetcher for reference.
            conn.setRequestProperty("User-Agent", CAPTION_USER_AGENT);

            final String cookies = Settings.LYRICS_CAPTION_COOKIES.get();
            if (cookies != null && !cookies.isEmpty()) {
                conn.setRequestProperty("Cookie", cookies);
            }

            for (Map.Entry<String, String> entry : AuthUtils.getRequestHeader().entrySet()) {
                if (!entry.getValue().isEmpty()) {
                    conn.setRequestProperty(entry.getKey(), entry.getValue());
                }
            }

            if (conn.getResponseCode() != 200) {
                throw new Exception("HTTP response code: " + conn.getResponseCode());
            }
            return Requester.parseString(conn);
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }
}
