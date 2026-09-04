/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/2269
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
import java.util.List;

import app.morphe.extension.music.patches.lyrics.Lyrics;
import app.morphe.extension.music.patches.lyrics.LyricsLine;
import app.morphe.extension.music.patches.lyrics.TrackInfo;
import app.morphe.extension.music.patches.lyrics.Word;
import app.morphe.extension.music.settings.Settings;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.requests.Requester;

public final class MusixmatchProvider implements LyricsProvider {

    private static final String BASE_URL =
            "https://apic-desktop.musixmatch.com/ws/1.1/macro.subtitles.get";
    private static final String APP_ID = "web-desktop-app-v1.0";
    private static final String COMPACT_TYPE = "words";
    private static final String USER_AGENT =
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) "
                    + "Chrome/58.0.3029.110 Electron/1.7.6 Safari/537.36";

    @Override
    public String name() {
        return "Musixmatch";
    }

    @Nullable
    @Override
    public Lyrics fetch(TrackInfo track) throws Exception {
        final String userToken = Settings.MUSIXMATCH_TOKEN.get();
        if (userToken.isEmpty()) {
            Logger.printDebug(() -> "Musixmatch: no user token configured");
            return null;
        }
        return fetchRichsync(track, userToken);
    }

    @Nullable
    private Lyrics fetchRichsync(TrackInfo track, String token) throws IOException, JSONException {
        final double durationSec = track.durationSeconds() > 0
                ? (double) track.durationSeconds() : 0;

        final StringBuilder url = new StringBuilder(BASE_URL)
                .append("?format=json")
                .append("&namespace=lyrics_richsynched")
                .append("&subtitle_format=mxm")
                .append("&optional_calls=track.richsync")
                .append("&richsync_compact_type=").append(COMPACT_TYPE)
                .append("&app_id=").append(APP_ID)
                .append("&q_track=").append(LyricsRequests.encode(track.title()))
                .append("&q_artist=").append(LyricsRequests.encode(track.artist()))
                .append("&q_duration=").append((int) durationSec)
                .append("&f_subtitle_length=").append((int) durationSec)
                .append("&usertoken=").append(LyricsRequests.encode(token));

        HttpURLConnection connection = null;
        try {
            connection = openDesktopApi(url.toString());
            if (connection.getResponseCode() != 200) {
                LyricsRequests.logFailure(name(), connection);
                return null;
            }
            final JSONObject root = Requester.parseJSONObject(connection);
            if (statusIsTokenError(root)) {
                Logger.printDebug(() -> "Musixmatch: token rejected");
                return null;
            }

            final JSONObject macro = obj(obj(obj(root, "message"), "body"), "macro_calls");
            if (macro == null) {
                return null;
            }

            // Check track matching.
            final JSONObject matcherHeader = obj(obj(obj(macro, "matcher.track.get"),
                    "message"), "header");
            if (matcherHeader != null && matcherHeader.optInt("status_code", 200) != 200) {
                Logger.printDebug(() -> "Musixmatch: track matching failed");
                return null;
            }

            // Only word-level richsync — no fallback to line-level subtitles.
            final JSONObject richCall = obj(obj(obj(macro, "track.richsync.get"),
                    "message"), "body");
            final JSONObject rich = obj(richCall, "richsync");
            if (rich == null) {
                Logger.printDebug(() -> "Musixmatch: no richsync data available");
                return null;
            }
            final String richBody = LyricsRequests.optString(rich, "richsync_body");
            if (richBody == null) {
                Logger.printDebug(() -> "Musixmatch: richsync_body is empty");
                return null;
            }
            return parseRichsync(richBody);
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    /**
     * Parses Musixmatch richsync_body JSON.
     * <p>
     * Format: [{"ts":lineStartSec, "te":lineEndSec, "x":"lineText",
     *           "l":[{"c":"word", "o":offsetSecFromLineStart}]}]
     * <p>
     * Word absolute start = ts + o.
     * Word duration = next word offset - current offset (last word: te - (ts + o)).
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
                            we = (long) ((lineTs + nextOffset) * 1000);
                        } else {
                            we = (long) (lineTe * 1000);
                        }
                    } else {
                        we = (long) (lineTe * 1000);
                    }
                    if (we <= ws) {
                        we = ws + 1;
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

    private static String joinWords(List<Word> words) {
        final StringBuilder sb = new StringBuilder();
        for (Word w : words) {
            sb.append(w.text());
        }
        return sb.toString();
    }
}
