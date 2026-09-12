/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/2269
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.lyrics.requests;

import android.util.Log;

import androidx.annotation.Nullable;

import org.json.JSONObject;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.zip.GZIPInputStream;

import app.morphe.extension.music.patches.lyrics.Lyrics;
import app.morphe.extension.music.patches.lyrics.LyricsLine;
import app.morphe.extension.music.patches.lyrics.TrackInfo;
import app.morphe.extension.music.settings.Settings;

public final class LyricifyProvider implements LyricsProvider {

    private static final String TAG = "LyricifyProvider";
    private static final String API_BASE =
            "https://api.lyricify.app/lyrics/get/mobile/new";

    private static final String USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/127.0.0.0 Safari/537.36";

    private static final Random RNG = new Random();

    @Override
    public String name() {
        return "Lyricify";
    }

    @Nullable
    @Override
    public Lyrics fetch(TrackInfo track) throws Exception {
        final String spDc = Settings.SPOTIFY_TOKEN.get();
        if (spDc == null || spDc.isBlank()) {
            return null;
        }

        final SpotifyProvider.TrackSearchResult search =
                SpotifyProvider.searchTrackWithISRC(spDc, track.title(), track.artist());
        if (search == null) {
            Log.w(TAG, "Spotify search returned null for: " + track);
            return null;
        }

        final String username = generateUsername();
        final String idParam = base64NoWrap(search.trackId);
        final String isrcParam = (search.isrc != null && !search.isrc.isEmpty())
                ? base64NoWrap(search.isrc) : "";

        Log.d(TAG, "search.trackId=" + search.trackId);
        Log.d(TAG, "search.isrc=" + search.isrc);
        Log.d(TAG, "idParam (raw base64)=" + idParam);
        Log.d(TAG, "isrcParam (raw base64)=" + isrcParam);

        final String idEncoded = URLEncoder.encode(idParam, "UTF-8");
        final String isrcEncoded = URLEncoder.encode(isrcParam, "UTF-8");
        Log.d(TAG, "idParam (url encoded)=" + idEncoded);
        Log.d(TAG, "isrcParam (url encoded)=" + isrcEncoded);

        final String url = API_BASE
                + "?username=" + username
                + "&id=" + idEncoded
                + "&isrc=" + isrcEncoded;

        Log.d(TAG, "Full URL: " + url);

        final HttpURLConnection conn = LyricsRequests.openConnection(url);
        conn.setRequestProperty("User-Agent", USER_AGENT);
        conn.setRequestProperty("Accept", "application/json");
        conn.setConnectTimeout(5000);
        conn.setReadTimeout(8000);

        Log.d(TAG, "Request: GET " + url);
        Log.d(TAG, "Request UA: " + USER_AGENT);

        final int code = conn.getResponseCode();
        if (code != 200) {
            Log.w(TAG, "Lyricify API returned HTTP " + code);
            Log.w(TAG, "Response message: " + conn.getResponseMessage());
            java.util.Map<String, java.util.List<String>> headers = conn.getHeaderFields();
            for (java.util.Map.Entry<String, java.util.List<String>> entry : headers.entrySet()) {
                Log.w(TAG, "Header: " + entry.getKey() + " = " + entry.getValue());
            }
            LyricsRequests.logFailure(name(), conn);
            conn.disconnect();
            return null;
        }

        final String json = readGzipBody(conn);
        conn.disconnect();

        if (json == null || json.isEmpty()) {
            Log.w(TAG, "Lyricify API returned empty body");
            return null;
        }

        final JSONObject response = new JSONObject(json);

        if (response.optBoolean("isInstrumental", false)) {
            Log.d(TAG, "Track is instrumental, skipping");
            return null;
        }

        final String text = response.optString("text", null);
        if (text == null || text.isEmpty()) {
            Log.w(TAG, "Lyricify API returned no text field");
            return null;
        }

        final int offset = response.optInt("offset", 0);
        final String writer = response.optString("writer", null);
        final String trans = response.optString("trans", null);

        final boolean isSyllable = text.contains("[from:AppleSyllable]");
        final List<LyricsLine> lines;
        if (isSyllable) {
            lines = LyricifyParser.parseSyllable(text, offset);
        } else {
            lines = LyricifyParser.parseLines(text, offset);
        }

        if (lines.isEmpty()) {
            return null;
        }

        final List<String> creditLines = new ArrayList<>();
        if (writer != null && !writer.isEmpty()) {
            creditLines.add("Written by " + writer);
        }

        final Map<String, List<LyricsLine>> translations;
        final String deviceLang = Locale.getDefault().getLanguage();
        if ("zh".equals(deviceLang) && trans != null && !trans.isEmpty()) {
            final List<String> translatedTexts = LyricifyParser.parseTranslation(trans, offset);
            if (!translatedTexts.isEmpty()) {
                // Pad or trim to match main lines count.
                while (translatedTexts.size() < lines.size()) {
                    translatedTexts.add("");
                }
                final List<LyricsLine> translationLines = new ArrayList<>(lines.size());
                for (int i = 0; i < lines.size(); i++) {
                    final LyricsLine original = lines.get(i);
                    final String tText = translatedTexts.get(i);
                    if (tText.isEmpty()) {
                        translationLines.add(new LyricsLine(
                                original.startTimeMs(), original.endTimeMs(), "", List.of()));
                    } else {
                        translationLines.add(new LyricsLine(
                                original.startTimeMs(), original.endTimeMs(), tText, List.of()));
                    }
                }
                translations = Map.of("zh", Collections.unmodifiableList(translationLines));
            } else {
                translations = null;
            }
        } else {
            translations = null;
        }

        final String sourceUrl = "https://open.spotify.com/track/" + search.trackId;
        final String formatType = isSyllable ? "lys" : "lyl";
        return new Lyrics(
                lines,
                name(),
                true,
                null,
                translations,
                null,
                creditLines.isEmpty() ? null : creditLines,
                text,
                formatType,
                sourceUrl);
    }

    private static String generateUsername() {
        final int len = 5 + RNG.nextInt(8);
        final StringBuilder sb = new StringBuilder(len);
        for (int i = 0; i < len; i++) {
            sb.append((char) ('a' + RNG.nextInt(26)));
        }
        return sb.toString();
    }

    private static String base64NoWrap(String input) {
        return android.util.Base64.encodeToString(
                input.getBytes(StandardCharsets.UTF_8), android.util.Base64.NO_WRAP);
    }

    @Nullable
    private static String readGzipBody(HttpURLConnection conn) {
        try {
            final InputStream raw = conn.getInputStream();
            final InputStream in = new GZIPInputStream(raw);
            final byte[] buf = new byte[4096];
            final StringBuilder sb = new StringBuilder();
            int n;
            while ((n = in.read(buf)) != -1) {
                sb.append(new String(buf, 0, n, StandardCharsets.UTF_8));
            }
            in.close();
            return sb.toString();
        } catch (Exception e) {
            return null;
        }
    }
}
