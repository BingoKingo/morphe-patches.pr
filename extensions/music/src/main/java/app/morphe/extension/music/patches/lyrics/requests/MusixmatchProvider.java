/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/2625
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.lyrics.requests;

import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import app.morphe.extension.music.patches.lyrics.model.Lyrics;
import app.morphe.extension.music.patches.lyrics.model.LyricsLine;
import app.morphe.extension.music.patches.lyrics.model.TrackInfo;
import app.morphe.extension.music.patches.lyrics.model.Word;
import app.morphe.extension.music.settings.Settings;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.requests.Requester;

public final class MusixmatchProvider extends ScoredSongProvider {

    private static final String API_BASE = "https://apic.musixmatch.com/ws/1.1/";
    private static final String TOKEN_URL = API_BASE + "token.get";
    private static final String SEARCH_URL = API_BASE + "track.search";
    private static final String MACRO_URL = API_BASE + "macro.subtitles.get";
    private static final String APP_ID = "android-player-v1.0";
    private static final String USER_AGENT = "Dalvik/2.1.0 (Linux; U; Android 17)";
    private static final String COOKIE = "AWSELB=0; AWSELBCORS=0";

    private static final long MIN_WORD_MS = 100;
    private static final long BRIDGING_THRESHOLD_MS = 2500;
    private static final long REQUEST_THROTTLE_MS = 500;

    private static final AtomicLong lastRequestTime = new AtomicLong(0);
    private static final Object TOKEN_LOCK = new Object();
    private static final Pattern LRC_LINE_PATTERN =
            Pattern.compile("\\[(\\d{2}):(\\d{2})\\.\\d{2}]\\s?(.*)");
    private static String cachedToken = null;
    private static String rejectedUserToken = null;

    @Nullable
    private static String extractCopyright(JSONObject macro) {
        JSONObject lyricsMsg = obj(obj(macro, "track.lyrics.get"), "message");
        JSONObject lyricsBody = obj(obj(lyricsMsg, "body"), "lyrics");
        if (lyricsBody != null) {
            final String c = LyricsRequests.optString(lyricsBody, "lyrics_copyright");
            if (c != null && !c.trim().isEmpty()) {
                return c.trim();
            }
        }
        JSONObject subMsg = obj(obj(macro, "track.subtitles.get"), "message");
        JSONObject subBody = obj(subMsg, "body");
        final JSONArray subList = subBody != null ? subBody.optJSONArray("subtitle_list") : null;
        if (subList != null && subList.length() > 0) {
            JSONObject subItem = subList.optJSONObject(0);
            JSONObject subtitle = subItem != null ? subItem.optJSONObject("subtitle") : null;
            if (subtitle != null) {
                final String c = LyricsRequests.optString(subtitle, "lyrics_copyright");
                if (c != null && !c.trim().isEmpty()) {
                    return c.trim();
                }
            }
        }
        JSONObject richMsg = obj(obj(macro, "track.richsync.get"), "message");
        JSONObject richBody = obj(obj(richMsg, "body"), "richsync");
        if (richBody != null) {
            final String c = LyricsRequests.optString(richBody, "lyrics_copyright");
            if (c != null && !c.trim().isEmpty()) {
                return c.trim();
            }
        }
        return null;
    }

    @Nullable
    private static String extractBacklinkUrl(JSONObject macro) {
        JSONObject lyricsMsg = obj(obj(macro, "track.lyrics.get"), "message");
        JSONObject lyricsBody = obj(obj(lyricsMsg, "body"), "lyrics");
        if (lyricsBody != null) {
            final String url = LyricsRequests.optString(lyricsBody, "backlink_url");
            if (url != null && !url.isEmpty()) {
                return stripTrackingParams(url);
            }
        }
        return null;
    }

    @Nullable
    private static Lyrics appendCopyright(@Nullable Lyrics lyrics, @Nullable String copyright) {
        if (lyrics == null || copyright == null || copyright.isEmpty()) {
            return lyrics;
        }
        final List<String> songwriters = new ArrayList<>();
        if (lyrics.songwriters() != null) {
            songwriters.addAll(lyrics.songwriters());
        }
        songwriters.add(copyright);
        return new Lyrics(lyrics.lines(), lyrics.providerName(), lyrics.synced(), null,
                lyrics.translations(), null, songwriters, lyrics.rawFormat(),
                lyrics.formatType(), lyrics.sourceUrl());
    }

    private static String stripTrackingParams(String url) {
        final int queryStart = url.indexOf('?');
        if (queryStart < 0) return url;
        final String base = url.substring(0, queryStart);
        final String query = url.substring(queryStart + 1);
        final StringBuilder filtered = new StringBuilder();
        for (String param : query.split("&")) {
            if (!param.toLowerCase(Locale.ROOT).startsWith("utm")) {
                //noinspection SizeReplaceableByIsEmpty
                if (filtered.length() > 0) filtered.append('&');
                filtered.append(param);
            }
        }
        //noinspection SizeReplaceableByIsEmpty
        return filtered.length() > 0 ? base + "?" + filtered : base;
    }

    private static String requestId() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    private static void rejectToken(String hint) {
        synchronized (TOKEN_LOCK) {
            if ("renew".equalsIgnoreCase(hint)
                    && cachedToken != null
                    && cachedToken.equals(Settings.MUSIXMATCH_TOKEN.get())) {
                rejectedUserToken = cachedToken;
            }
            cachedToken = null;
        }
    }

    public static void invalidateToken() {
        synchronized (TOKEN_LOCK) {
            cachedToken = null;
            rejectedUserToken = null;
        }
    }

    private static boolean isUsableToken(String token) {
        if (token == null || token.isEmpty() || "null".equals(token)) {
            return false;
        }
        for (int i = 0; i < token.length(); i++) {
            if (token.charAt(i) != '0') {
                return true;
            }
        }
        return false;
    }

    private static int headerStatus(JSONObject root) {
        JSONObject header = obj(obj(root, "message"), "header");
        return header == null ? 200 : header.optInt("status_code", 200);
    }

    @Nullable
    private static String headerHint(JSONObject root) {
        JSONObject header = obj(obj(root, "message"), "header");
        return header != null ? LyricsRequests.optString(header, "hint") : null;
    }

    private static JSONObject obj(JSONObject o, String key) {
        return o == null ? null : o.optJSONObject(key);
    }

    public static boolean validateToken(String token) {
        if (!isUsableToken(token)) return false;
        try {
            final String url = SEARCH_URL
                    + "?page_size=1&page=1&q_track=a&q_artist=a"
                    + "&usertoken=" + LyricsRequests.encode(token)
                    + "&format=json"
                    + "&app_id=" + APP_ID
                    + "&t=" + requestId();
            final HttpURLConnection connection = LyricsRequests.openConnection(url);
            connection.setRequestProperty("User-Agent", USER_AGENT);
            connection.setRequestProperty("Cookie", COOKIE);
            final int httpCode = connection.getResponseCode();
            if (httpCode != 200) {
                connection.disconnect();
                return false;
            }
            JSONObject root = Requester.parseJSONObject(connection);
            final int status = headerStatus(root);
            final String hint = headerHint(root);
            connection.disconnect();
            return status != 401 || !"renew".equalsIgnoreCase(hint);
        } catch (Exception ex) {
            Logger.printDebug(() -> "Could not validate Musixmatch token", ex);
            return true;
        }
    }

    private static String joinWords(List<Word> words) {
        final StringBuilder sb = new StringBuilder();
        for (int i = 0; i < words.size(); i++) {
            final Word w = words.get(i);
            sb.append(w.text());
            if (w.endsWithSpace() && i < words.size() - 1) {
                sb.append(' ');
            }
        }
        return sb.toString();
    }

    @Override
    public String name() {
        return "Musixmatch";
    }

    @Override
    protected List<ScoredSong> fetchScoredSongs(TrackInfo track) throws Exception {
        final String token = ensureToken();
        if (token == null) {
            return Collections.emptyList();
        }

        final List<JSONObject> trackObjs;
        try {
            trackObjs = searchTracks(track, token);
        } catch (Exception ex) {
            Logger.printDebug(() -> "Could not search Musixmatch tracks", ex);
            return Collections.emptyList();
        }
        if (trackObjs.isEmpty()) {
            return Collections.emptyList();
        }

        final List<ScoredSong> scored = new ArrayList<>();
        ScoredSong firstAccepted = null;
        for (JSONObject trackObj : trackObjs) {
            final ScoredSong candidate = scoredSongOf(trackObj, token, track);
            if (candidate == null) continue;

            if (firstAccepted == null) {
                firstAccepted = candidate;
            }
            if (candidate.score() >= LyricsRequests.SOFT_MIN
                    + LyricsRequests.syncRank(candidate.lyrics())) {
                scored.add(candidate);
                break;
            }
        }
        if (scored.isEmpty() && firstAccepted != null) {
            scored.add(firstAccepted);
        }

        scored.sort((a, b) -> Integer.compare(b.score(), a.score()));
        return scored;
    }

    /** The scored answer of one search result, or null when it has no usable lyrics. */
    @Nullable
    private ScoredSong scoredSongOf(JSONObject trackObj, String token, TrackInfo track) {
        final int trackId = trackObj.optInt("track_id", -1);
        if (trackId <= 0) return null;

        final Lyrics lyrics;
        try {
            lyrics = fetchLyricsByTrackId(trackId, token);
        } catch (Exception ex) {
            Logger.printDebug(() -> "Could not fetch Musixmatch lyrics by track ID", ex);
            return null;
        }
        if (lyrics == null) return null;

        final String title = trackObj.optString("track_name", "");
        final String artist = trackObj.optString("artist_name", "");
        final long durationSec = trackObj.optInt("track_length", 0);
        final int score = LyricsRequests.scoreLyricsCandidate(
                title, artist, durationSec, lyrics, track);
        return new ScoredSong(score, lyrics, title, artist, durationSec);
    }

    @Nullable
    private String ensureToken() throws IOException, JSONException {
        synchronized (TOKEN_LOCK) {
            final String userToken = Settings.MUSIXMATCH_TOKEN.get();
            if (!userToken.trim().isEmpty() && isUsableToken(userToken)
                    && !userToken.equals(rejectedUserToken)) {
                cachedToken = userToken;
                return cachedToken;
            }

            if (cachedToken != null && !cachedToken.isEmpty()) {
                return cachedToken;
            }

            final String url = TOKEN_URL
                    + "?user_language=en"
                    + "&app_id=" + APP_ID
                    + "&t=" + requestId();

            final HttpURLConnection connection = openApi(url);
            try {
                final int httpCode = connection.getResponseCode();
                if (httpCode != 200) {
                    return null;
                }

                JSONObject root = Requester.parseJSONObject(connection);
                final int status = headerStatus(root);
                if (status != 200) {
                    return null;
                }

                JSONObject body = obj(obj(root, "message"), "body");
                if (body != null) {
                    final String t = LyricsRequests.optString(body, "user_token");
                    if (isUsableToken(t)) {
                        cachedToken = t;
                    }
                }
                return cachedToken;
            } finally {
                connection.disconnect();
            }
        }
    }

    private List<JSONObject> searchTracks(TrackInfo track, String token)
            throws IOException, JSONException {
        final double durationSec = track.durationSeconds() > 0
                ? (double) track.durationSeconds() : 0;

        final StringBuilder url = new StringBuilder(SEARCH_URL)
                .append("?page_size=10&page=1&s_track_rating=desc")
                .append("&q_track=").append(LyricsRequests.encode(track.title()))
                .append("&q_artist=").append(LyricsRequests.encode(track.artist()))
                .append("&usertoken=").append(LyricsRequests.encode(token))
                .append("&format=json")
                .append("&app_id=" + APP_ID).append("&t=").append(requestId());

        if (durationSec > 0) {
            url.append("&q_duration=").append((int) durationSec);
        }

        LyricsRequests.throttle(lastRequestTime, REQUEST_THROTTLE_MS);
        final HttpURLConnection connection = openApi(url.toString());
        try {
            final int httpCode = connection.getResponseCode();
            if (httpCode != 200) {
                return Collections.emptyList();
            }

            JSONObject root = Requester.parseJSONObject(connection);
            final int status = headerStatus(root);
            final String hint = headerHint(root);
            if (status == 401) {
                rejectToken(hint);
                return Collections.emptyList();
            }
            if (status != 200) {
                return Collections.emptyList();
            }

            JSONObject body = obj(obj(root, "message"), "body");
            final JSONArray trackList = body == null
                    ? null : body.optJSONArray("track_list");
            if (trackList == null || trackList.length() == 0) {
                return Collections.emptyList();
            }

            final List<JSONObject> result = new ArrayList<>();
            for (int i = 0; i < trackList.length(); i++) {
                JSONObject trackObj = trackObjectAt(trackList, i);
                if (trackObj != null) {
                    result.add(trackObj);
                }
            }
            return result;
        } finally {
            connection.disconnect();
        }
    }

    /** The track of a search entry, or null when the entry has none with an id. */
    @Nullable
    private static JSONObject trackObjectAt(JSONArray trackList, int index) {
        final JSONObject item = trackList.optJSONObject(index);
        final JSONObject trackObj = item == null ? null : item.optJSONObject("track");
        if (trackObj == null || trackObj.optInt("track_id", -1) <= 0) {
            return null;
        }
        return trackObj;
    }

    @Nullable
    private Lyrics fetchLyricsByTrackId(int trackId, String token)
            throws IOException, JSONException {

        final String url = MACRO_URL
                + "?namespace=lyrics_richsynched"
                + "&optional_calls=track.richsync"
                + "&subtitle_format=lrc"
                + "&track_id=" + trackId
                + "&f_subtitle_length_max_deviation=40"
                + "&usertoken=" + LyricsRequests.encode(token)
                + "&format=json"
                + "&app_id=" + APP_ID
                + "&t=" + requestId();

        LyricsRequests.throttle(lastRequestTime, REQUEST_THROTTLE_MS);
        final HttpURLConnection connection = openApi(url);
        try {
            final int httpCode = connection.getResponseCode();
            if (httpCode != 200) {
                return null;
            }

            JSONObject root = Requester.parseJSONObject(connection);
            final int status = headerStatus(root);
            final String hint = headerHint(root);
            if (status == 401) {
                rejectToken(hint);
                return null;
            }
            if (status != 200) {
                return null;
            }

            JSONObject message = obj(root, "message");
            JSONObject messageBody = obj(message, "body");
            JSONObject macroCalls = messageBody != null
                    ? messageBody.optJSONObject("macro_calls") : null;
            if (macroCalls == null) {
                return null;
            }

            final String copyright = extractCopyright(macroCalls);
            final String sourceUrl = extractBacklinkUrl(macroCalls);

            Lyrics lyrics = richsyncLyrics(macroCalls, sourceUrl);
            if (lyrics != null) return appendCopyright(lyrics, copyright);

            lyrics = subtitleLyrics(macroCalls, sourceUrl);
            if (lyrics != null) return appendCopyright(lyrics, copyright);

            lyrics = plainLyrics(macroCalls, sourceUrl);
            return appendCopyright(lyrics, copyright);
        } finally {
            connection.disconnect();
        }
    }

    /** The word-synced lyrics of the macro call, or null when it holds none. */
    @Nullable
    private Lyrics richsyncLyrics(JSONObject macroCalls, @Nullable String sourceUrl)
            throws JSONException {
        final JSONObject richMsg = callMessage(macroCalls, "track.richsync.get");
        if (!callSucceeded(richMsg)) return null;

        final JSONObject richBody = obj(obj(richMsg, "body"), "richsync");
        final String body = richBody == null
                ? null : LyricsRequests.optString(richBody, "richsync_body");
        return isBlank(body) ? null : parseRichsync(body, sourceUrl);
    }

    /** The line-synced lyrics of the macro call, or null when it holds none. */
    @Nullable
    private Lyrics subtitleLyrics(JSONObject macroCalls, @Nullable String sourceUrl)
            throws JSONException {
        final JSONObject subMsg = callMessage(macroCalls, "track.subtitles.get");
        if (!callSucceeded(subMsg)) return null;

        final JSONObject subBody = obj(subMsg, "body");
        final JSONArray subList = subBody == null
                ? null : subBody.optJSONArray("subtitle_list");
        if (subList == null || subList.length() == 0) return null;

        final JSONObject subtitle = subtitleAt(subList, 0);
        final String body = subtitle == null
                ? null : LyricsRequests.optString(subtitle, "subtitle_body");
        return isBlank(body) ? null : parseSubtitles(body, sourceUrl);
    }

    /** The plain lyrics of the macro call, or null when it holds none. */
    @Nullable
    private Lyrics plainLyrics(JSONObject macroCalls, @Nullable String sourceUrl)
            throws JSONException {
        final JSONObject lyricsMsg = callMessage(macroCalls, "track.lyrics.get");
        if (!callSucceeded(lyricsMsg)) return null;

        final JSONObject lyricsBody = obj(obj(lyricsMsg, "body"), "lyrics");
        final String text = lyricsBody == null
                ? null : LyricsRequests.optString(lyricsBody, "lyrics_body");
        return isBlank(text) ? null : parseLyrics(text, sourceUrl);
    }

    /** The message of one of the macro call's sub-requests. */
    @Nullable
    private static JSONObject callMessage(JSONObject macroCalls, String call) {
        return obj(obj(macroCalls, call), "message");
    }

    /** Whether a sub-request answered 200. */
    private static boolean callSucceeded(@Nullable JSONObject message) {
        final JSONObject header = obj(message, "header");
        return header != null && header.optInt("status_code", 0) == 200;
    }

    @Nullable
    private static JSONObject subtitleAt(JSONArray subList, int index) {
        final JSONObject item = subList.optJSONObject(index);
        return item == null ? null : item.optJSONObject("subtitle");
    }

    private static boolean isBlank(@Nullable String text) {
        return text == null || text.isEmpty();
    }

    @Nullable
    private Lyrics parseRichsync(String body, @Nullable String sourceUrl)
            throws JSONException {
        final JSONArray lines = new JSONArray(body);
        if (lines.length() == 0) {
            return null;
        }
        final List<LyricsLine> result = new ArrayList<>();
        for (int i = 0; i < lines.length(); i++) {
            JSONObject line = lines.optJSONObject(i);
            if (line == null) {
                continue;
            }
            final double lineTs = line.optDouble("ts", 0);
            final double lineTe = line.optDouble("te", lineTs);
            final long lineStartMs = (long) (lineTs * 1000);
            final long lineEndMs = (long) (lineTe * 1000);
            final String x = LyricsRequests.optString(line, "x");

            List<Word> words = null;
            final JSONArray lArr = line.optJSONArray("l");
            if (lArr != null && lArr.length() > 0) {
                words = richWords(lArr, lineTs, lineEndMs, x);
            }

            final String text;
            if (words != null && !words.isEmpty()) {
                text = joinWords(words);
            } else {
                text = x == null ? "" : x;
            }
            if (text.isEmpty()) {
                continue;
            }
            result.add(new LyricsLine(lineStartMs, text, words == null ? new ArrayList<>() : words));
        }
        if (result.isEmpty()) {
            return null;
        }
        return new Lyrics(result, name(), true, null, null, null, null, body, "mxm.json",
                sourceUrl);
    }

    /**
     * The word timings of one richsync line. Each word lasts until the next one starts, clamped to
     * the bridging window, and the published text corrects the spelling of each chunk.
     */
    private static List<Word> richWords(JSONArray lArr, double lineTs, long lineEndMs,
                                        @Nullable String x) {
        List<Word> words = new ArrayList<>();
        int xCursor = 0;
        for (int j = 0; j < lArr.length(); j++) {
            JSONObject w = lArr.optJSONObject(j);
            if (w == null) continue;
            final String rawChunk = LyricsRequests.optString(w, "c");
            if (rawChunk == null || rawChunk.isEmpty()) continue;

            final double offset = w.optDouble("o", 0);
            final long ws = (long) ((lineTs + offset) * 1000);
            final JSONObject nextW = nextWord(lArr, j);
            long we = nextW == null
                    ? lineEndMs
                    : wordEndMs(ws, (long) ((lineTs + nextW.optDouble("o", offset)) * 1000));
            if (nextW != null) {
                final long nextWs = (long) ((lineTs + nextW.optDouble("o", offset)) * 1000);
                if (nextWs > ws && we > nextWs) {
                    we = nextWs;
                }
            }

            boolean endsWithSpace = rawChunk.endsWith(" ") || rawChunk.endsWith("\t");
            String chunk = endsWithSpace ? rawChunk.stripTrailing() : rawChunk;

            if (x != null && !x.isEmpty()) {
                xCursor = skipWhitespace(x, xCursor);
                ChunkMatch match = matchInPublishedText(x, xCursor, chunk);
                if (match != null) {
                    chunk = match.chunk();
                    endsWithSpace = match.endsWithSpace();
                    xCursor = match.end();
                }
            }

            words.add(new Word(ws, we, chunk, null, endsWithSpace));
        }
        return words;
    }

    /** The word after this one in the line, or null at the end or when the entry is not an object. */
    @Nullable
    private static JSONObject nextWord(JSONArray lArr, int index) {
        final int nextIdx = index + 1;
        return nextIdx < lArr.length() ? lArr.optJSONObject(nextIdx) : null;
    }

    /** Where a word ends when the next one starts: at most one bridging window after it starts. */
    private static long wordEndMs(long startMs, long nextStartMs) {
        if (nextStartMs <= startMs) {
            return startMs + MIN_WORD_MS;
        }
        return nextStartMs - startMs <= BRIDGING_THRESHOLD_MS
                ? nextStartMs : startMs + BRIDGING_THRESHOLD_MS;
    }

    private static int skipWhitespace(String text, int from) {
        int at = from;
        while (at < text.length() && Character.isWhitespace(text.charAt(at))) {
            at++;
        }
        return at;
    }

    /**
     * The chunk as the published text spells it at {@code from}, or null when it does not appear
     * there. The published reply uses the reader facing spelling of names and punctuation.
     */
    @Nullable
    private static ChunkMatch matchInPublishedText(String text, int from, String chunk) {
        final int matchLen = chunk.length();
        if (from + matchLen > text.length() || !text.regionMatches(true, from, chunk, 0, matchLen)) {
            return null;
        }
        final int end = from + matchLen;
        final boolean endsWithSpace = end < text.length() && Character.isWhitespace(text.charAt(end));
        return new ChunkMatch(text.substring(from, end), endsWithSpace, end);
    }

    private record ChunkMatch(String chunk, boolean endsWithSpace, int end) {
    }

    @Nullable
    private Lyrics parseSubtitles(String body, @Nullable String sourceUrl)
            throws JSONException {
        String trimmed = body.trim();
        if (trimmed.startsWith("[") && trimmed.length() > 1
                && Character.isDigit(trimmed.charAt(1))) {
            return parseSubtitlesLrc(body, sourceUrl);
        }
        return parseSubtitlesJson(body, sourceUrl);
    }

    @Nullable
    private Lyrics parseSubtitlesLrc(String body, @Nullable String sourceUrl) {
        final List<LyricsLine> result = new ArrayList<>();
        for (String raw : body.split("\\n")) {
            final String line = raw.trim();
            if (line.isEmpty()) continue;
            final Matcher m = LRC_LINE_PATTERN.matcher(line);
            if (!m.matches()) continue;
            final int min = Integer.parseInt(Objects.requireNonNull(m.group(1)));
            final int sec = Integer.parseInt(Objects.requireNonNull(m.group(2)));
            final String text = m.group(3);
            if (text == null || text.isEmpty()) continue;
            final long startMs = min * 60_000L + sec * 1_000L;
            result.add(new LyricsLine(startMs, text));
        }
        if (result.isEmpty()) return null;
        return new Lyrics(result, name(), true, null, null, null, null, body, "lrc", sourceUrl);
    }

    @Nullable
    private Lyrics parseSubtitlesJson(String body, @Nullable String sourceUrl)
            throws JSONException {
        final JSONArray lines = new JSONArray(body);
        if (lines.length() == 0) {
            return null;
        }
        final List<LyricsLine> result = new ArrayList<>();
        for (int i = 0; i < lines.length(); i++) {
            JSONObject line = lines.optJSONObject(i);
            if (line == null) {
                continue;
            }
            JSONObject time = line.optJSONObject("time");
            final double total = time != null ? time.optDouble("total", 0) : 0;
            final long startMs = (long) (total * 1000);
            String text = line.optString("text", "♪");
            if (text.isEmpty()) {
                text = "♪";
            }
            result.add(new LyricsLine(startMs, text));
        }
        if (result.isEmpty()) {
            return null;
        }
        return new Lyrics(result, name(), true, null, null, null, null, body, "mxm.json",
                sourceUrl);
    }

    @Nullable
    private Lyrics parseLyrics(String body, @Nullable String sourceUrl) {
        final List<LyricsLine> result = LyricsRequests.parsePlainTextLines(body);
        if (result.isEmpty()) {
            return null;
        }
        return new Lyrics(result, name(), false, null, null, null, null, body, "txt", sourceUrl);
    }

    private HttpURLConnection openApi(String url) throws IOException {
        final HttpURLConnection connection = LyricsRequests.openConnection(url);
        connection.setRequestProperty("User-Agent", USER_AGENT);
        connection.setRequestProperty("Cookie", COOKIE);
        return connection;
    }
}
