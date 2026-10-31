/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/2625
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.lyrics.requests;

import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.HttpURLConnection;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import app.morphe.extension.music.patches.lyrics.model.Lyrics;
import app.morphe.extension.music.patches.lyrics.model.LyricsLine;
import app.morphe.extension.music.patches.lyrics.model.TrackInfo;
import app.morphe.extension.music.patches.lyrics.parsers.LRCParser;
import app.morphe.extension.music.patches.lyrics.parsers.TTMLParser;
import app.morphe.extension.shared.Logger;

public final class RMMProvider implements LyricsProvider {

    private static final String RMM_LYRICS = "https://lyrics.rmmreviv.al/lyrics?id=";

    private static final String DISPLAY_NAME = "RMM Revival";
    private static final String DISPLAY_NAME_VIA = "RMM Revival (via SpicyLyrics)";

    private static final int READ_TIMEOUT_MS = 20_000;

    private static final int HIGH_MATCH_SCORE = 10;

    private static String lrcFormat(List<LyricsLine> lines) {
        StringBuilder sb = new StringBuilder();
        for (LyricsLine line : lines) {
            sb.append(LRCParser.formatLine(line)).append('\n');
        }
        return sb.toString();
    }

    /**
     * Public iTunes search, scored with the shared match scale. Unlike the Apple catalog
     * search this needs no token, so the provider works for anyone who enables it.
     */
    private static List<JSONObject> searchItunesCandidates(TrackInfo track) {
        HttpURLConnection connection = null;
        try {
            String term = LyricsRequests.encode(track.title() + " " + track.artist());
            String url = LyricsRequests.ITUNES_SEARCH_URL + "?term=" + term + "&entity=song&limit=5";
            connection = LyricsRequests.openConnection(url);
            final int code = connection.getResponseCode();
            if (code != 200) {
                return new ArrayList<>();
            }
            JSONObject root = LyricsRequests.parseGzipJsonObject(connection);
            JSONArray results = root.optJSONArray("results");
            if (results == null || results.length() == 0) {
                return new ArrayList<>();
            }

            final String title = track.title().toLowerCase(Locale.ROOT).trim();
            final String artist = track.artist().toLowerCase(Locale.ROOT).trim();

            List<ScoredItem> scored = new ArrayList<>();
            for (int i = 0; i < results.length(); i++) {
                ScoredItem entry = scoredItem(results.optJSONObject(i), title, artist, track);
                if (entry != null) {
                    scored.add(entry);
                }
            }

            scored.sort(RMMProvider::compareScored);

            List<JSONObject> ordered = new ArrayList<>(scored.size());
            for (ScoredItem entry : scored) {
                ordered.add(entry.item);
            }
            return ordered;
        } catch (Exception ex) {
            Logger.printDebug(() -> "Could not search iTunes for RMM Revival candidates", ex);
            return new ArrayList<>();
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    /**
     * One search result, or null when it has no id or falls below the shared match floor.
     *
     * @param title  The track title, already lower-cased and trimmed.
     * @param artist The track artist, already lower-cased and trimmed.
     */
    @Nullable
    private static ScoredItem scoredItem(@Nullable JSONObject item, String title, String artist,
                                         TrackInfo track) {
        if (item == null || item.optLong("trackId", 0) == 0) {
            return null;
        }
        String itemTitle = item.optString("trackName", "");
        String itemArtist = item.optString("artistName", "");
        int score = LyricsRequests.scoreTrackCandidate(itemTitle, itemArtist,
                item.optLong("trackTimeMillis", 0) / 1000, track);
        if (score < LyricsRequests.SOFT_MIN) {
            return null;
        }
        boolean exact = !title.isEmpty() && !artist.isEmpty()
                && itemTitle.toLowerCase(Locale.ROOT).contains(title)
                && itemArtist.toLowerCase(Locale.ROOT).contains(artist);
        return new ScoredItem(item, score, exact);
    }

    /** Exact title and artist matches first, then the higher score. */
    private static int compareScored(ScoredItem a, ScoredItem b) {
        if (a.exact != b.exact) {
            return a.exact ? -1 : 1;
        }
        return Integer.compare(b.score, a.score);
    }

    @Nullable
    private static JSONObject fetchRmmJson(long trackId) {
        HttpURLConnection connection = null;
        try {
            connection = LyricsRequests.openConnection(RMM_LYRICS + trackId);
            connection.setRequestProperty("Accept", "application/json");
            connection.setReadTimeout(READ_TIMEOUT_MS);
            final int code = connection.getResponseCode();
            if (code != 200) {
                return null;
            }
            return LyricsRequests.parseGzipJsonObject(connection);
        } catch (Exception ex) {
            Logger.printDebug(() -> "Could not fetch RMM Revival lyrics", ex);
            return null;
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    /** SpicyLyrics hands the url over without a scheme, which nothing can open. */
    @Nullable
    private static String usableUrl(@Nullable String url) {
        if (url == null) {
            return null;
        }
        String trimmed = url.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        if (trimmed.startsWith("//")) {
            return "https:" + trimmed;
        }
        int schemeEnd = trimmed.indexOf("://");
        if (schemeEnd <= 0) {
            return "https://" + trimmed;
        }
        return trimmed;
    }

    @Nullable
    private static Uploader parseUploader(@Nullable JSONObject root) {
        if (root == null) {
            return null;
        }
        JSONObject attribution = root.optJSONObject("uploadAttribution");
        if (attribution == null) {
            attribution = root.optJSONObject("UploadAttribution");
        }
        if (attribution == null) {
            return null;
        }
        JSONObject uploader = attribution.optJSONObject("Uploader");
        if (uploader == null) {
            return null;
        }
        String username = uploader.optString("username", "").trim();
        if (username.isEmpty()) {
            return null;
        }
        return new Uploader(username, LyricsRequests.optString(uploader, "url"));
    }

    @Nullable
    private static List<String> parseJsonSongwriters(JSONObject root) {
        JSONArray array = root.optJSONArray("songWriters");
        if (array == null || array.length() == 0) {
            return null;
        }
        StringBuilder sb = new StringBuilder("Written by ");
        int count = 0;
        for (int i = 0; i < array.length(); i++) {
            String name = array.optString(i, "").trim();
            if (name.isEmpty()) {
                continue;
            }
            if (count > 0) {
                sb.append(" · ");
            }
            sb.append(name);
            count++;
        }
        return count == 0 ? null : List.of(sb.toString());
    }

    /**
     * The writers the lyrics themselves name, then whoever uploaded the sync. The TTML head carries
     * the writers whenever it has any, and the JSON array is the fallback for the rest.
     */
    @Nullable
    private static List<String> buildCredits(@Nullable Uploader uploader,
                                             @Nullable List<String> ttmlSongwriters,
                                             @Nullable List<String> jsonSongwriters) {
        List<String> credits = new ArrayList<>();
        List<String> songwriters = hasAny(ttmlSongwriters) ? ttmlSongwriters
                : hasAny(jsonSongwriters) ? jsonSongwriters : null;
        if (songwriters != null) {
            credits.addAll(songwriters);
        }
        if (uploader != null) {
            credits.add("Uploaded by " + uploader.username());
        }
        return credits.isEmpty() ? null : credits;
    }

    private static boolean hasAny(@Nullable List<String> values) {
        return values != null && !values.isEmpty();
    }

    /** The credits of a JSON-derived payload, which never names its own writers. */
    @Nullable
    private static List<String> buildCredits(@Nullable Uploader uploader,
                                             @Nullable List<String> jsonSongwriters) {
        return buildCredits(uploader, null, jsonSongwriters);
    }

    @Nullable
    private static List<LyricsLine> parseLineLyrics(JSONObject root) {
        JSONArray array = root.optJSONArray("lineLyrics");
        if (array == null || array.length() == 0) {
            return null;
        }
        List<LyricsLine> lines = new ArrayList<>(array.length());
        for (int i = 0; i < array.length(); i++) {
            LyricsLine line = lineEntry(array.optJSONObject(i));
            if (line != null) {
                lines.add(line);
            }
        }
        return lines.isEmpty() ? null : lines;
    }

    /** One timed line of a JSON {@code lineLyrics} array, or null when it is unusable. */
    @Nullable
    private static LyricsLine lineEntry(@Nullable JSONObject entry) {
        if (entry == null) {
            return null;
        }
        String text = entry.optString("text", "").trim();
        if (text.isEmpty()) {
            return null;
        }
        double seconds = entry.optDouble("time", -1);
        if (seconds < 0) {
            return null;
        }
        String agent = LyricsRequests.optString(entry, "speakerLabel");
        return new LyricsLine(Math.round(seconds * 1000), text, List.of(),
                agent, false, false);
    }

    @Nullable
    private static Lyrics buildLyrics(JSONObject root, long trackId) {
        if (!root.optBoolean("hasLyrics", false)) {
            return null;
        }

        Uploader uploader = parseUploader(root);
        String providerName = uploader != null ? DISPLAY_NAME_VIA : DISPLAY_NAME;
        String sourceUrl = uploader != null ? usableUrl(uploader.url()) : null;

        List<String> jsonSongwriters = parseJsonSongwriters(root);

        String ttml = root.optString("ttml", "");
        if (!ttml.isEmpty()) {
            Lyrics parsed = TTMLParser.ttmlToLyrics(ttml, providerName, sourceUrl);
            if (parsed != null && !parsed.isEmpty()) {
                return withCredits(parsed, providerName,
                        buildCredits(uploader, parsed.songwriters(), jsonSongwriters), sourceUrl);
            }
        }

        List<LyricsLine> lineLevel = parseLineLyrics(root);
        if (lineLevel != null) {
            String raw = lrcFormat(lineLevel);
            return new Lyrics(lineLevel, providerName, true, null, null, null,
                    buildCredits(uploader, jsonSongwriters),
                    raw, "lrc", sourceUrl);
        }

        Lyrics synced = parsedLines(root, "syncedLyrics", "lrc", providerName, true);
        if (synced != null) {
            String raw = root.optString("syncedLyrics", "");
            return new Lyrics(synced.lines(), providerName, true, null, null, null,
                    buildCredits(uploader, jsonSongwriters),
                    raw, "lrc", sourceUrl);
        }

        Lyrics plain = parsedLines(root, "lyrics", "txt", providerName, false);
        if (plain != null) {
            String raw = root.optString("lyrics", "");
            return new Lyrics(plain.lines(), providerName, false, null, null, null,
                    buildCredits(uploader, jsonSongwriters),
                    raw, "txt", sourceUrl);
        }

        return null;
    }

    /**
     * Returns {@code parsed} unchanged when it has no extra credits, and otherwise a copy of it
     * that carries them.
     */
    private static Lyrics withCredits(Lyrics parsed, String providerName,
                                      @Nullable List<String> credits, @Nullable String sourceUrl) {
        if (credits == null) {
            return parsed;
        }
        return new Lyrics(parsed.lines(), providerName, parsed.synced(),
                parsed.romanization(), parsed.translations(), parsed.romanizations(),
                credits, parsed.rawFormat(), parsed.formatType(), sourceUrl);
    }

    /** The lines of one raw-text field parsed for its own format, or null when it has none. */
    @Nullable
    private static Lyrics parsedLines(JSONObject root, String key, String formatType,
                                      String providerName, boolean synced) {
        String raw = root.optString(key, "");
        if (raw.isEmpty()) {
            return null;
        }
        List<LyricsLine> lines = synced ? LRCParser.parseSynced(raw) : LRCParser.parsePlain(raw);
        if (lines.isEmpty()) {
            return null;
        }
        return new Lyrics(lines, providerName, synced, null, null, null, null,
                raw, formatType, null);
    }

    @Override
    public String name() {
        return "RMM";
    }

    @Override
    public boolean hasCandidates() {
        return true;
    }

    @Nullable
    @Override
    public FetchResult fetch(TrackInfo track) throws Exception {
        List<JSONObject> candidates = searchItunesCandidates(track);
        if (candidates.isEmpty()) {
            return null;
        }
        JSONObject best = candidates.get(0);
        final long trackId = best.optLong("trackId", 0);
        if (trackId == 0) {
            return null;
        }
        JSONObject root = fetchRmmJson(trackId);
        if (root == null) {
            return null;
        }
        Lyrics lyrics = buildLyrics(root, trackId);
        if (lyrics == null) {
            return null;
        }
        return FetchResult.of(lyrics,
                best.optString("trackName", ""),
                best.optString("artistName", ""),
                best.optLong("trackTimeMillis", 0) / 1000,
                track);
    }

    @Override
    public List<Lyrics.ScoredLyrics> fetchCandidates(TrackInfo track) throws Exception {
        List<JSONObject> candidates = searchItunesCandidates(track);
        List<Lyrics.ScoredLyrics> scored = new ArrayList<>();
        for (JSONObject item : candidates) {
            if (scored.size() >= LyricsRequests.MAX_CANDIDATES) {
                break;
            }
            Lyrics.ScoredLyrics entry = scoredCandidate(item, track);
            if (entry == null) {
                continue;
            }
            scored.add(entry);
            if (entry.score() >= HIGH_MATCH_SCORE) {
                break;
            }
        }
        return Lyrics.sortScoredByScore(scored);
    }

    /** The scored lyrics one search result yields, or null when it has none. */
    @Nullable
    private static Lyrics.ScoredLyrics scoredCandidate(JSONObject item, TrackInfo track) {
        final long trackId = item.optLong("trackId", 0);
        if (trackId == 0) {
            return null;
        }
        try {
            JSONObject root = fetchRmmJson(trackId);
            if (root == null) {
                return null;
            }
            Lyrics lyrics = buildLyrics(root, trackId);
            if (lyrics == null) {
                return null;
            }
            int score = LyricsRequests.scoreLyricsCandidate(
                    item.optString("trackName", ""),
                    item.optString("artistName", ""),
                    item.optLong("trackTimeMillis", 0) / 1000,
                    lyrics, track);
            return new Lyrics.ScoredLyrics(score, lyrics);
        } catch (Exception ex) {
            Logger.printDebug(() -> "Could not fetch RMM Revival lyrics for a candidate", ex);
            return null;
        }
    }

    private record ScoredItem(JSONObject item, int score, boolean exact) {
    }

    /**
     * The community uploader, when the entry is a SpicyLyrics community sync rather than
     * Apple lyrics relayed through the same API.
     */
    private record Uploader(String username, @Nullable String url) {
    }
}
