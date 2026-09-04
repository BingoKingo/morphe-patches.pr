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

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import app.morphe.extension.music.patches.lyrics.Lyrics;
import app.morphe.extension.music.patches.lyrics.LyricsLine;
import app.morphe.extension.music.patches.lyrics.TrackInfo;
import app.morphe.extension.music.patches.lyrics.Word;
import app.morphe.extension.music.settings.Settings;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.requests.Requester;

public final class SpotifyProvider implements LyricsProvider {

    private static final String SECRETS_URL =
            "https://raw.githubusercontent.com/xyloflake/spot-secrets-go/refs/heads/main/secrets/secretDict.json";
    private static final String TOKEN_URL = "https://open.spotify.com/api/token";
    private static final String SERVER_TIME_URL = "https://open.spotify.com/api/server-time";
    private static final String SEARCH_URL = "https://api.spotify.com/v1/search";
    private static final String LYRICS_URL = "https://spclient.wg.spotify.com/color-lyrics/v2/track/";

    private static final String USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/127.0.0.0 Safari/537.36";
    private static final String SPOTIFY_UA =
            "Spotify/9.0.34.593 iOS/18.4 (iPhone15,3)";

    @Nullable
    private static volatile String cachedAccessToken;
    @Nullable
    private static String[] cachedTotpSecrets;  // XOR-encoded values for newest version
    @Nullable
    private static String cachedTotpVersion;

    @Override
    public String name() {
        return "Spotify";
    }

    @Nullable
    @Override
    public Lyrics fetch(TrackInfo track) throws Exception {
        final String spDc = Settings.SPOTIFY_TOKEN.get();
        if (spDc == null || spDc.isBlank()) {
            Logger.printDebug(() -> "Spotify: no sp_dc token configured");
            return null;
        }

        final String trackId = searchTrack(spDc, track.title(), track.artist());
        if (trackId == null) {
            Logger.printDebug(() -> "Spotify: no track found for '" + track.title() + "' by '" + track.artist() + "'");
            return null;
        }

        final JSONObject lyricsResponse = fetchLyrics(spDc, trackId);
        if (lyricsResponse == null) {
            return null;
        }

        return parseLyrics(lyricsResponse);
    }


    @Nullable
    private String searchTrack(String spDc, String title, String artist) throws Exception {
        final String accessToken = getAccessToken(spDc);
        if (accessToken == null) {
            return null;
        }

        final String query = title + " " + artist;
        final String url = SEARCH_URL
                + "?q=" + LyricsRequests.encode(query)
                + "&type=track&limit=5&market=from_token";

        HttpURLConnection connection = LyricsRequests.openConnection(url);
        connection.setRequestProperty("Authorization", "Bearer " + accessToken);
        connection.setRequestProperty("Accept", "application/json");
        connection.setRequestProperty("User-Agent", USER_AGENT);

        final int code = connection.getResponseCode();
        if (code != 200) {
            LyricsRequests.logFailure(name(), connection);
            return null;
        }

        final String json = Requester.parseString(connection);
        final JSONObject root = new JSONObject(json);
        final JSONObject tracks = root.optJSONObject("tracks");
        if (tracks == null) {
            return null;
        }

        final JSONArray items = tracks.optJSONArray("items");
        if (items == null || items.length() == 0) {
            return null;
        }

        // Pick the first result — Spotify's search is already relevance-ranked.
        final JSONObject best = items.getJSONObject(0);
        final String id = best.optString("id", null);
        Logger.printDebug(() -> "Spotify: matched track '" + best.optString("name") + "' (id=" + id + ")");
        return id;
    }


    @Nullable
    private JSONObject fetchLyrics(String spDc, String trackId) throws Exception {
        final String accessToken = getAccessToken(spDc);
        if (accessToken == null) {
            return null;
        }

        final String url = LYRICS_URL + trackId + "?format=json&market=from_token";
        HttpURLConnection connection = LyricsRequests.openConnection(url);
        connection.setRequestProperty("Authorization", "Bearer " + accessToken);
        connection.setRequestProperty("Accept", "application/json");
        connection.setRequestProperty("Accept-Language", "en");
        connection.setRequestProperty("Accept-Encoding", "gzip, deflate, br");
        connection.setRequestProperty("User-Agent", SPOTIFY_UA);

        final int code = connection.getResponseCode();
        if (code != 200) {
            LyricsRequests.logFailure(name(), connection);
            return null;
        }

        final String json = LyricsRequests.parseGzipString(connection);
        return new JSONObject(json);
    }


    @Nullable
    private Lyrics parseLyrics(JSONObject response) {
        final JSONObject lyricsObj = response.optJSONObject("lyrics");
        if (lyricsObj == null) {
            return null;
        }

        final String syncType = lyricsObj.optString("syncType", "UNSYNCED");
        final JSONArray linesArr = lyricsObj.optJSONArray("lines");
        if (linesArr == null || linesArr.length() == 0) {
            return null;
        }

        final boolean isSynced = !"UNSYNCED".equals(syncType);

        if (isSynced) {
            return parseSyncedLines(linesArr);
        } else {
            return parsePlainLines(linesArr);
        }
    }

    @Nullable
    private Lyrics parseSyncedLines(JSONArray linesArr) {
        final List<LyricsLine> lines = new ArrayList<>(linesArr.length());

        for (int i = 0; i < linesArr.length(); i++) {
            final JSONObject lineObj = linesArr.optJSONObject(i);
            if (lineObj == null) {
                continue;
            }

            final long startTimeMs = lineObj.optLong("startTimeMs", LyricsLine.NO_TIME);
            final String text = lineObj.optString("words", "").trim();
            if (text.isEmpty()) {
                continue;
            }

            // Build word-level timing. Spotify gives per-line startTimeMs but not
            // per-word timing. Distribute words evenly across the line duration.
            final long nextStartMs = (i + 1 < linesArr.length())
                    ? linesArr.optJSONObject(i + 1).optLong("startTimeMs", startTimeMs + 2000)
                    : startTimeMs + 3000;
            final long lineDuration = Math.max(nextStartMs - startTimeMs, 100);
            final String[] tokens = text.split("\\s+");
            final List<Word> words = distributeWords(tokens, startTimeMs, lineDuration);

            lines.add(new LyricsLine(startTimeMs, text, words));
        }

        if (lines.isEmpty()) {
            return null;
        }
        return new Lyrics(lines, name(), true);
    }

    private static List<Word> distributeWords(String[] tokens, long startMs, long durationMs) {
        final List<Word> words = new ArrayList<>(tokens.length);
        if (tokens.length == 0) {
            return words;
        }

        final long perWord = durationMs / tokens.length;
        long cursor = startMs;

        for (int j = 0; j < tokens.length; j++) {
            final long wordEnd = cursor + perWord;
            final boolean spaceAfter = j < tokens.length - 1;
            words.add(new Word(cursor, wordEnd, tokens[j], null, spaceAfter));
            cursor = wordEnd;
        }
        return words;
    }

    @Nullable
    private Lyrics parsePlainLines(JSONArray linesArr) {
        final List<LyricsLine> lines = new ArrayList<>(linesArr.length());

        for (int i = 0; i < linesArr.length(); i++) {
            final JSONObject lineObj = linesArr.optJSONObject(i);
            if (lineObj == null) {
                continue;
            }

            final String text = lineObj.optString("words", "").trim();
            if (text.isEmpty()) {
                continue;
            }
            lines.add(new LyricsLine(LyricsLine.NO_TIME, text));
        }

        if (lines.isEmpty()) {
            return null;
        }
        return new Lyrics(lines, name(), false);
    }


    @Nullable
    private synchronized String getAccessToken(String spDc) {
        // Return cached token if still valid.
        if (cachedAccessToken != null) {
            return cachedAccessToken;
        }

        try {
            ensureTotpSecrets();

            final long serverTimeMs = getServerTime(spDc);
            final long localTimeMs = System.currentTimeMillis();

            final String totpLocal = generateTotp(localTimeMs);
            final String totpServer = generateTotp(serverTimeMs);

            final String url = TOKEN_URL
                    + "?reason=init&productType=mobile-web-player"
                    + "&totp=" + totpLocal
                    + "&totpVer=" + cachedTotpVersion
                    + "&totpServer=" + totpServer;

            final HttpURLConnection connection = (HttpURLConnection)
                    new java.net.URL(url).openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(5000);
            connection.setReadTimeout(8000);
            connection.setRequestProperty("User-Agent", USER_AGENT);
            connection.setRequestProperty("Origin", "https://open.spotify.com/");
            connection.setRequestProperty("Referer", "https://open.spotify.com/");
            connection.setRequestProperty("Cookie", "sp_dc=" + spDc);

            final int code = connection.getResponseCode();
            if (code != 200) {
                Logger.printDebug(() -> "Spotify: token request failed: " + code);
                return null;
            }

            final String body = parseBody(connection);
            final JSONObject json = new JSONObject(body);
            final String token = json.optString("accessToken", "");
            if (token.isBlank()) {
                Logger.printDebug(() -> "Spotify: no accessToken in response");
                return null;
            }
            cachedAccessToken = token;

            Logger.printDebug(() -> "Spotify: access token obtained successfully");
            return cachedAccessToken;
        } catch (Exception ex) {
            Logger.printException(() -> "Spotify: auth failed", ex);
            return null;
        }
    }

    private long getServerTime(String spDc) {
        try {
            final HttpURLConnection connection = (HttpURLConnection)
                    new java.net.URL(SERVER_TIME_URL).openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(5000);
            connection.setReadTimeout(5000);
            connection.setRequestProperty("User-Agent", USER_AGENT);
            connection.setRequestProperty("Origin", "https://open.spotify.com/");
            connection.setRequestProperty("Referer", "https://open.spotify.com/");
            connection.setRequestProperty("Cookie", "sp_dc=" + spDc);

            if (connection.getResponseCode() == 200) {
                final String body = parseBody(connection);
                final JSONObject json = new JSONObject(body);
                final long serverTime = json.optLong("serverTime", 0);
                if (serverTime > 0) {
                    return serverTime * 1000;
                }
            }
        } catch (Exception ex) {
            Logger.printDebug(() -> "Spotify: server time fetch failed, using local time");
        }
        return System.currentTimeMillis();
    }


    private void ensureTotpSecrets() throws Exception {
        if (cachedTotpSecrets != null && cachedTotpVersion != null) {
            return;
        }

        final HttpURLConnection connection = (HttpURLConnection)
                new java.net.URL(SECRETS_URL).openConnection();
        connection.setRequestMethod("GET");
        connection.setConnectTimeout(5000);
        connection.setReadTimeout(8000);
        connection.setRequestProperty("User-Agent", USER_AGENT);

        if (connection.getResponseCode() != 200) {
            throw new IOException("Failed to fetch TOTP secrets: " + connection.getResponseCode());
        }

        final String body = parseBody(connection);
        final JSONObject secrets = new JSONObject(body);

        int newestVersion = -1;
        Iterator<String> keys = secrets.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            try {
                int v = Integer.parseInt(key);
                if (v > newestVersion) {
                    newestVersion = v;
                }
            } catch (NumberFormatException ignored) {
            }
        }

        if (newestVersion < 0) {
            throw new JSONException("No valid TOTP version found");
        }

        cachedTotpVersion = String.valueOf(newestVersion);
        final JSONArray arr = secrets.getJSONArray(cachedTotpVersion);
        final int[] secretData = new int[arr.length()];
        for (int i = 0; i < arr.length(); i++) {
            secretData[i] = arr.getInt(i);
        }

        // XOR with the index-based key: value ^ ((index % 33) + 9)
        final int[] xored = new int[secretData.length];
        for (int i = 0; i < secretData.length; i++) {
            xored[i] = secretData[i] ^ ((i % 33) + 9);
        }

        // Match reference JS: Buffer.from(mappedData.join(""), "utf8").toString("hex")
        // → OTPAuth.Secret.fromHex(hexData)
        // The HMAC key is the UTF-8 bytes of the decimal digit string.
        final StringBuilder decimalString = new StringBuilder();
        for (int v : xored) {
            decimalString.append(v);
        }
        cachedTotpSecrets = new String[]{ decimalString.toString() };

        Logger.printDebug(() -> "Spotify: TOTP secrets loaded, version=" + cachedTotpVersion);
    }

    private String generateTotp(long timestampMs) throws NoSuchAlgorithmException, InvalidKeyException {
        final long epochSeconds = timestampMs / 1000;
        final long counter = epochSeconds / 30;

        final byte[] counterBytes = new byte[8];
        long tmp = counter;
        for (int i = 7; i >= 0; i--) {
            counterBytes[i] = (byte) (tmp & 0xFF);
            tmp >>= 8;
        }

        final Mac mac = Mac.getInstance("HmacSHA1");
        final byte[] secretBytes = cachedTotpSecrets[0].getBytes(StandardCharsets.UTF_8);
        mac.init(new SecretKeySpec(secretBytes, "HmacSHA1"));
        final byte[] hash = mac.doFinal(counterBytes);

        final int offset = hash[hash.length - 1] & 0x0F;
        final int binary = ((hash[offset] & 0x7F) << 24)
                | ((hash[offset + 1] & 0xFF) << 16)
                | ((hash[offset + 2] & 0xFF) << 8)
                | (hash[offset + 3] & 0xFF);

        final int otp = binary % 1_000_000;
        return String.format(Locale.US, "%06d", otp);
    }


    private static String parseBody(HttpURLConnection connection) throws IOException {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8))) {
            final StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append('\n');
            }
            return sb.toString();
        }
    }

    public static void invalidateToken() {
        cachedAccessToken = null;
    }
}
