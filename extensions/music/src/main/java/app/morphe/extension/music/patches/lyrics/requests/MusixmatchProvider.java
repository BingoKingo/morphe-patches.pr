/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/2269
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.lyrics.requests;

import android.util.Log;

import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.util.ArrayList;
import java.util.List;

import app.morphe.extension.music.patches.lyrics.Lyrics;
import app.morphe.extension.music.patches.lyrics.LyricsLine;
import app.morphe.extension.music.patches.lyrics.TrackInfo;
import app.morphe.extension.music.patches.lyrics.Word;
import app.morphe.extension.music.settings.Settings;
import app.morphe.extension.shared.requests.Requester;

public final class MusixmatchProvider implements LyricsProvider {

    private static final String TAG = "Musixmatch";

    private static final String MACRO_URL =
            "https://apic-desktop.musixmatch.com/ws/1.1/macro.subtitles.get";
    private static final String MATCHER_URL =
            "https://apic-desktop.musixmatch.com/ws/1.1/matcher.track.get";
    private static final String TOKEN_URL =
            "https://apic-desktop.musixmatch.com/ws/1.1/token.get";
    private static final String APP_ID = "web-desktop-app-v1.0";
    private static final String COMPACT_TYPE = "words";
    private static final String USER_AGENT =
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) "
                    + "Chrome/58.0.3029.110 Electron/1.7.6 Safari/537.36";

    private static final long MIN_WORD_MS = 40;
    private static final long BRIDGING_THRESHOLD_MS = 400;

    @Override
    public String name() {
        return "Musixmatch";
    }

    @Nullable
    @Override
    public Lyrics fetch(TrackInfo track) throws Exception {
        Log.d(TAG, "Musixmatch: fetch '" + track.title() + "' by '" + track.artist() + "'");

        String userToken = Settings.MUSIXMATCH_TOKEN.get();
        final boolean hadStaticToken = !userToken.isEmpty();

        if (userToken.isEmpty()) {
            Log.d(TAG, "Musixmatch: no user token configured, fetching anonymous token");
            userToken = fetchAnonymousToken();
            if (userToken == null || userToken.isEmpty()) {
                Log.d(TAG, "Musixmatch: failed to get anonymous token");
                return null;
            }
        }

        Lyrics result = fetchLyrics(track, userToken);

        if (result == null && hadStaticToken) {
            Log.d(TAG, "Musixmatch: static token failed, trying anonymous token");
            final String freshToken = fetchAnonymousToken();
            if (freshToken != null && !freshToken.isEmpty()) {
                result = fetchLyrics(track, freshToken);
            }
        }

        return result;
    }

    @Nullable
    private Lyrics fetchLyrics(TrackInfo track, String token) throws IOException, JSONException {
        // Step 1: matcher.track.get — match track and get commontrack_id
        final JSONObject matchedTrack = matchTrack(track, token);

        if (matchedTrack == null) {
            Log.d(TAG, "Musixmatch: matcher returned no track, falling back to macro.subtitles.get");
            return fetchLyricsFallback(track, token);
        }

        final String trackName = matchedTrack.optString("track_name", "");
        final long commontrackId = matchedTrack.optLong("commontrack_id", 0);
        final boolean instrumental = matchedTrack.optBoolean("instrumental", false);
        final boolean hasLyrics = matchedTrack.optBoolean("has_lyrics", false);
        final boolean hasRichsync = matchedTrack.optBoolean("has_richsync", false);
        Log.d(TAG, "Musixmatch: matched '" + trackName + "' (commontrack_id=" + commontrackId
                + ", instrumental=" + instrumental
                + ", has_lyrics=" + hasLyrics
                + ", has_richsync=" + hasRichsync + ")");

        if (instrumental) {
            Log.d(TAG, "Musixmatch: track is instrumental");
            final List<LyricsLine> line = new ArrayList<>();
            line.add(new LyricsLine(0, "♪ Instrumental ♪", new ArrayList<>()));
            return new Lyrics(line, name(), false);
        }

        if (!hasLyrics) {
            Log.d(TAG, "Musixmatch: matched track has no lyrics");
            return null;
        }
        if (!hasRichsync) {
            Log.d(TAG, "Musixmatch: matched track has no richsync (word-level)");
            return null;
        }

        // Step 2: macro.subtitles.get — fetch lyrics by commontrack_id
        final double durationSec = track.durationSeconds() > 0
                ? (double) track.durationSeconds() : 0;

        final StringBuilder url = new StringBuilder(MACRO_URL)
                .append("?format=json")
                .append("&namespace=lyrics_richsynched")
                .append("&subtitle_format=mxm")
                .append("&optional_calls=track.richsync")
                .append("&richsync_compact_type=").append(COMPACT_TYPE)
                .append("&app_id=").append(APP_ID)
                .append("&commontrack_id=").append(commontrackId)
                .append("&q_duration=").append((int) durationSec)
                .append("&f_subtitle_length=").append((int) durationSec)
                .append("&usertoken=").append(encode(token));

        Log.d(TAG, "Musixmatch: fetching richsync for commontrack_id=" + commontrackId);

        return executeMacro(url.toString());
    }

    @Nullable
    private Lyrics fetchLyricsFallback(TrackInfo track, String token) throws IOException, JSONException {
        final double durationSec = track.durationSeconds() > 0
                ? (double) track.durationSeconds() : 0;

        final StringBuilder url = new StringBuilder(MACRO_URL)
                .append("?format=json")
                .append("&namespace=lyrics_richsynched")
                .append("&subtitle_format=mxm")
                .append("&optional_calls=track.richsync")
                .append("&richsync_compact_type=").append(COMPACT_TYPE)
                .append("&app_id=").append(APP_ID)
                .append("&q_track=").append(encode(track.title()))
                .append("&q_artist=").append(encode(track.artist()))
                .append("&q_artists=").append(encode(track.artist()))
                .append("&q_duration=").append((int) durationSec)
                .append("&f_subtitle_length=").append((int) durationSec)
                .append("&usertoken=").append(encode(token));

        if (track.album() != null && !track.album().isEmpty()) {
            url.append("&q_album=").append(encode(track.album()));
        }

        return executeMacro(url.toString());
    }

    @Nullable
    private Lyrics executeMacro(String url) throws IOException, JSONException {
        HttpURLConnection connection = null;
        try {
            connection = openDesktopApi(url);
            final int httpCode = connection.getResponseCode();
            Log.d(TAG, "Musixmatch: HTTP " + httpCode);
            if (httpCode != 200) {
                LyricsRequests.logFailure(name(), connection);
                return null;
            }

            final JSONObject root = Requester.parseJSONObject(connection);

            if (statusIsTokenError(root)) {
                Log.d(TAG, "Musixmatch: token rejected (status=" + statusCode(root) + ")");
                return null;
            }

            final JSONObject macro = obj(obj(obj(root, "message"), "body"), "macro_calls");
            if (macro == null) {
                Log.d(TAG, "Musixmatch: no macro_calls in response");
                return null;
            }

            final JSONObject richMsg = obj(obj(macro, "track.richsync.get"), "message");
            final JSONObject richHeader = obj(richMsg, "header");
            if (richHeader != null && richHeader.optInt("status_code", 200) != 200) {
                Log.d(TAG, "Musixmatch: track.richsync.get failed (status="
                        + richHeader.optInt("status_code") + ")");
                return null;
            }

            final JSONObject richCall = obj(richMsg, "body");
            final JSONObject rich = obj(richCall, "richsync");
            if (rich == null) {
                Log.d(TAG, "Musixmatch: no richsync object in response body");
                return null;
            }

            final String richBody = LyricsRequests.optString(rich, "richsync_body");
            if (richBody == null || richBody.isEmpty()) {
                Log.d(TAG, "Musixmatch: richsync_body is empty");
                return null;
            }

            return parseRichsync(richBody);
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    @Nullable
    private JSONObject matchTrack(TrackInfo track, String token) throws IOException, JSONException {
        final double durationSec = track.durationSeconds() > 0
                ? (double) track.durationSeconds() : 0;

        final StringBuilder url = new StringBuilder(MATCHER_URL)
                .append("?format=json")
                .append("&app_id=").append(APP_ID)
                .append("&q_track=").append(encode(track.title()))
                .append("&q_artist=").append(encode(track.artist()))
                .append("&q_artists=").append(encode(track.artist()))
                .append("&q_duration=").append((int) durationSec)
                .append("&usertoken=").append(encode(token));

        if (track.album() != null && !track.album().isEmpty()) {
            url.append("&q_album=").append(encode(track.album()));
        }

        HttpURLConnection connection = null;
        try {
            connection = openDesktopApi(url.toString());
            final int httpCode = connection.getResponseCode();
            Log.d(TAG, "Musixmatch: matcher HTTP " + httpCode);
            if (httpCode != 200) {
                LyricsRequests.logFailure(name(), connection);
                return null;
            }

            final JSONObject root = Requester.parseJSONObject(connection);

            if (statusIsTokenError(root)) {
                Log.d(TAG, "Musixmatch: matcher token rejected (status=" + statusCode(root) + ")");
                return null;
            }

            final JSONObject header = obj(obj(root, "message"), "header");
            if (header != null && header.optInt("status_code", 200) != 200) {
                Log.d(TAG, "Musixmatch: matcher API status=" + header.optInt("status_code"));
                return null;
            }

            final JSONObject body = obj(obj(root, "message"), "body");
            if (body == null) {
                return null;
            }
            return body.optJSONObject("track");
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    @Nullable
    private String fetchAnonymousToken() {
        final String url = TOKEN_URL + "?app_id=" + APP_ID;
        HttpURLConnection connection = null;
        try {
            connection = openDesktopApi(url);
            final int code = connection.getResponseCode();
            Log.d(TAG, "Musixmatch: token.get HTTP " + code);
            if (code != 200) {
                LyricsRequests.logFailure(name(), connection);
                return null;
            }
            final JSONObject root = Requester.parseJSONObject(connection);
            if (statusCode(root) != 200) {
                Log.d(TAG, "Musixmatch: token.get API status=" + statusCode(root));
                return null;
            }
            final JSONObject body = obj(obj(root, "message"), "body");
            if (body == null) {
                Log.d(TAG, "Musixmatch: token.get response body is null");
                return null;
            }
            final String userToken = body.optString("user_token", "");
            if (userToken.isEmpty()) {
                Log.d(TAG, "Musixmatch: token.get returned empty user_token");
                return null;
            }
            Log.d(TAG, "Musixmatch: got anonymous token (len=" + userToken.length() + ")");
            return userToken;
        } catch (Exception e) {
            Log.e(TAG, "Musixmatch: failed to fetch anonymous token", e);
            return null;
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    /**
     * Parses Musixmatch richsync_body JSON with word-level timing.
     * <p>
     * Format: [{"ts":lineStartSec, "te":lineEndSec, "x":"lineText",
     *           "l":[{"c":"word", "o":offsetSecFromLineStart}]}]
     * <p>
     * Word absolute start = ts + o.
     * Bridging: if gap to next word ≤ 400ms, word end bridges to next word start.
     * Minimum word duration: 40ms.
     */
    @Nullable
    private Lyrics parseRichsync(String body) throws JSONException {
        final JSONArray lines = new JSONArray(body);
        if (lines.length() == 0) {
            return null;
        }
        final List<LyricsLine> result = new ArrayList<>();
        for (int i = 0; i < lines.length(); i++) {
            final JSONObject line = lines.optJSONObject(i);
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
                words = new ArrayList<>();
                for (int j = 0; j < lArr.length(); j++) {
                    final JSONObject w = lArr.optJSONObject(j);
                    if (w == null) {
                        continue;
                    }
                    final String chunk = LyricsRequests.optString(w, "c");
                    if (chunk == null || chunk.isEmpty()) {
                        continue;
                    }
                    final double offset = w.optDouble("o", 0);
                    final long ws = (long) ((lineTs + offset) * 1000);

                    long we;
                    final int nextIdx = j + 1;
                    if (nextIdx < lArr.length()) {
                        final JSONObject nextW = lArr.optJSONObject(nextIdx);
                        if (nextW != null) {
                            final double nextOffset = nextW.optDouble("o", offset);
                            final long nextWs = (long) ((lineTs + nextOffset) * 1000);
                            if (nextWs - ws <= BRIDGING_THRESHOLD_MS) {
                                we = nextWs;
                            } else {
                                we = ws;
                            }
                        } else {
                            we = lineEndMs;
                        }
                    } else {
                        we = lineEndMs;
                    }
                    if (we <= ws) {
                        we = ws + MIN_WORD_MS;
                    } else if (we - ws < MIN_WORD_MS) {
                        we = ws + MIN_WORD_MS;
                    }
                    words.add(new Word(ws, we, chunk));
                }
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
        Log.d(TAG, "Musixmatch: parsed " + result.size() + " richsync lines");
        return new Lyrics(result, name(), true);
    }

    private HttpURLConnection openDesktopApi(String url) throws IOException {
        final HttpURLConnection connection = LyricsRequests.openConnection(url);
        connection.setRequestProperty("User-Agent", USER_AGENT);
        connection.setRequestProperty("Cookie", "x-mxm-token-guid=");
        return connection;
    }

    private static boolean statusIsTokenError(JSONObject root) {
        final int code = statusCode(root);
        return code == 401 || code == 402 || code == 403 || code == 429;
    }

    private static int statusCode(JSONObject root) {
        final JSONObject header = obj(obj(root, "message"), "header");
        return header == null ? 200 : header.optInt("status_code", 200);
    }

    private static JSONObject obj(JSONObject o, String key) {
        return o == null ? null : o.optJSONObject(key);
    }

    private static String encode(String s) {
        if (s == null) return "";
        final StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            final char c = s.charAt(i);
            if (c >= 'A' && c <= 'Z' || c >= 'a' && c <= 'z' || c >= '0' && c <= '9'
                    || c == '-' || c == '_' || c == '.' || c == '~') {
                sb.append(c);
            } else {
                sb.append(String.format("%%%02X", (int) c));
            }
        }
        return sb.toString();
    }

    private static String joinWords(List<Word> words) {
        final StringBuilder sb = new StringBuilder();
        for (Word w : words) {
            sb.append(w.text());
        }
        return sb.toString();
    }
}
