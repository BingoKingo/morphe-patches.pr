/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/2625
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.lyrics.requests;

import android.util.Base64;

import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import app.morphe.extension.music.patches.lyrics.model.Lyrics;
import app.morphe.extension.music.patches.lyrics.model.LyricsLine;
import app.morphe.extension.music.patches.lyrics.model.LyricsMerge;
import app.morphe.extension.music.patches.lyrics.model.TrackInfo;
import app.morphe.extension.music.patches.lyrics.model.Word;
import app.morphe.extension.music.patches.lyrics.parsers.LRCParser;
import app.morphe.extension.music.patches.lyrics.parsers.LyricsCrypto;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.requests.Requester;

/**
 * NetEase Cloud Music lyrics. Uses the EAPI endpoint, which exposes word-synced
 * (YRC) lyrics when available, otherwise line-synced LRC. Translations and
 * romanizations are intentionally ignored.
 */
public final class NetEaseProvider implements LyricsProvider {

    private static final String EAPI_HOST = "https://interface.music.163.com";
    private static final String EAPI_KEY = "e82ckenh8dichen8";
    private static final String REGISTER_PATH = "/eapi/register/anonimous";

    private static final String NETEASE_USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; WOW64) "
            + "AppleWebKit/537.36 (KHTML, like Gecko) Safari/537.36 Chrome/91.0.4472.164 "
            + "NeteaseMusicDesktop/3.1.3.203419";
    private static final String APP_VER = "3.1.3.203419";
    private static final String DEVICEID_XOR_KEY = "3go8&$8*3*3h0k(2)2";

    private static final String[] MOTHERBOARD_MODES = {
            "MS-iCraft B760M WIFI", "ASUS ROG STRIX Z790", "MSI MAG B550 TOMAHAWK",
            "ASRock X670E Taichi", "GIGABYTE Z790 AORUS ELITE"
    };

    private static final Random RANDOM = new Random();

    private static final String deviceId = randomChars(32, "0123456789abcdef");
    private static final String clientSign = generateClientSign();
    private static final String osver = "Microsoft-Windows-10--build-" + (20000 + RANDOM.nextInt(10000)) + "-64bit";
    private static final String mode = MOTHERBOARD_MODES[RANDOM.nextInt(MOTHERBOARD_MODES.length)];
    private static final Map<String, String> cookieJar = new HashMap<>();
    private static final Pattern YRC_LINE = Pattern.compile("^\\[(\\d+),(\\d+)](.*)");
    private static final Pattern YRC_WORD = Pattern.compile("\\((\\d+),(\\d+),\\d+\\)([^()]*)");
    private static final Pattern LRC_TIME = Pattern.compile("\\[(\\d+):(\\d{2})(?:[.:](\\d{1,3}))?]");
    private static final Pattern RICH_JSON = Pattern.compile("^\\s*\\{\"");
    private static boolean initialized;

    /** YRC files carry the credits as JSON lines mixed in with the timed lines. */
    private static List<String> parseYrcCredits(String yrc) {
        List<String> creditLines = new ArrayList<>();
        if (yrc.isEmpty()) {
            return creditLines;
        }
        for (String rawLine : yrc.split("\\r?\\n")) {
            String credit = yrcCreditLine(rawLine.trim());
            if (credit != null) {
                creditLines.add(credit);
            }
        }
        return creditLines;
    }

    /**
     * The credit one YRC line carries, credits being the JSON lines mixed in with the timed ones.
     *
     * @return The credit text, or null when the line is timed or has no text.
     */
    @Nullable
    private static String yrcCreditLine(String trimmed) {
        if (!trimmed.startsWith("{")) {
            return null;
        }
        try {
            String value = jsonPartsText(new JSONObject(trimmed));
            return value == null || value.isEmpty() ? null : value;
        } catch (Exception ex) {
            Logger.printDebug(() -> "Could not parse NetEase user description", ex);
            return null;
        }
    }

    /** The text of a NetEase {@code "c"} parts array, or null when the object has none. */
    @Nullable
    private static String jsonPartsText(JSONObject obj) {
        JSONArray parts = obj.optJSONArray("c");
        if (parts == null) {
            return null;
        }
        return joinPartTexts(parts);
    }

    /** The concatenated non-empty {@code tx} of every part in a NetEase parts array. */
    private static String joinPartTexts(JSONArray parts) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.length(); i++) {
            JSONObject part = parts.optJSONObject(i);
            if (part == null) {
                continue;
            }
            String tx = part.optString("tx", "");
            if (!tx.isEmpty()) {
                sb.append(tx);
            }
        }
        return sb.toString().trim();
    }

    private static String optLyric(JSONObject root, String key) {
        JSONObject section = root.optJSONObject(key);
        return section == null ? "" : section.optString("lyric", "");
    }

    private static String filterJsonLines(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        StringBuilder builder = new StringBuilder(text.length());
        for (String rawLine : text.split("\\r?\\n")) {
            String trimmed = rawLine.trim();
            if (trimmed.startsWith("{")) {
                continue;
            }
            //noinspection SizeReplaceableByIsEmpty
            if (builder.length() > 0) {
                builder.append('\n');
            }
            builder.append(rawLine);
        }
        return builder.toString();
    }

    private static List<JSONObject> searchCandidates(String keyword) {
        List<JSONObject> candidates = new ArrayList<>();
        try {
            candidates.addAll(searchByEapi(keyword));
        } catch (Exception ex) {
            Logger.printDebug(() -> "Could not search NetEase by EAPI", ex);
            try {
                candidates.addAll(searchByCloudSearch(keyword));
            } catch (Exception ex2) {
                Logger.printDebug(() -> "Could not search NetEase by CloudSearch", ex2);
            }
        }
        return candidates;
    }

    private static List<JSONObject> searchAll(String keyword, TrackInfo track) {
        List<JSONObject> candidates = searchCandidates(keyword);
        if (candidates.isEmpty()) {
            return new ArrayList<>();
        }
        List<JSONObject> scored = new ArrayList<>(candidates);
        scored.sort((a, b) -> scoreCandidate(b, track) - scoreCandidate(a, track));
        List<JSONObject> passed = new ArrayList<>();
        for (JSONObject item : scored) {
            if (scoreCandidate(item, track) >= LyricsRequests.SOFT_MIN) {
                passed.add(item);
            }
        }
        return passed.isEmpty() ? List.of(scored.get(0)) : passed;
    }

    @Nullable
    private static JSONObject searchBest(String keyword, TrackInfo track) {
        JSONObject best = null;
        int bestScore = -1;
        for (JSONObject candidate : searchCandidates(keyword)) {
            int score = scoreCandidate(candidate, track);
            if (score > bestScore) {
                bestScore = score;
                best = candidate;
            }
        }
        if (best == null || bestScore < LyricsRequests.SOFT_MIN) {
            return null;
        }
        return best;
    }

    private static int scoreCandidate(JSONObject song, TrackInfo track) {
        String title = song.optString("name", "");
        String artist = song.optString("artist", "");
        final long durationMs = song.optLong("duration", 0);
        return LyricsRequests.scoreTrackCandidate(title, artist,
                durationMs > 0 ? durationMs / 1000 : 0, track);
    }

    private static List<JSONObject> searchByEapi(String keyword) throws IOException, JSONException {
        JSONObject root = eapiRequest("/eapi/search/song/list/page", new JSONObject()
                .put("limit", "30")
                .put("offset", "0")
                .put("keyword", keyword)
                .put("scene", "NORMAL")
                .put("needCorrect", "true"));

        return mapSongs(root, "data", "resources");
    }

    /**
     * The songs a search response carries, in mapped form.
     *
     * @param section The object holding the array, absent on some responses.
     * @param key The array's name.
     */
    private static List<JSONObject> mapSongs(JSONObject root, String section, String key)
            throws JSONException {
        JSONObject holder = root.optJSONObject(section);
        JSONArray songs = holder == null ? null : holder.optJSONArray(key);
        List<JSONObject> mapped = new ArrayList<>();
        if (songs == null) {
            return mapped;
        }
        for (int i = 0; i < songs.length(); i++) {
            addMappedSong(songs.optJSONObject(i), mapped);
        }
        return mapped;
    }

    private static List<JSONObject> searchByCloudSearch(String keyword) throws IOException, JSONException {
        String form = "s=" + LyricsRequests.encode(keyword) + "&type=1&offset=0&limit=30";
        HttpURLConnection connection = LyricsRequests.postForm(
                "https://music.163.com/api/cloudsearch/pc", form);
        if (connection.getResponseCode() != Requester.HTTP_STATUS_CODE_SUCCESS) {
            LyricsRequests.logFailure("NetEase", connection);
            return new ArrayList<>();
        }

        JSONObject root = Requester.parseJSONObject(connection);
        return mapSongs(root, "result", "songs");
    }

    /** Adds {@code song} to {@code out} in mapped form, skipping null and unidentifiable entries. */
    private static void addMappedSong(@Nullable JSONObject song, List<JSONObject> out) throws JSONException {
        if (song == null) {
            return;
        }
        JSONObject mapped = mapSong(song);
        if (mapped != null) {
            out.add(mapped);
        }
    }

    @Nullable
    private static JSONObject mapSong(JSONObject song) throws JSONException {
        if (song == null || !song.has("id")) {
            return null;
        }

        JSONObject mapped = new JSONObject();
        mapped.put("id", song.optLong("id"));
        mapped.put("name", song.optString("name", ""));

        JSONArray artists = song.optJSONArray("artists") != null
                ? song.optJSONArray("artists") : song.optJSONArray("ar");
        mapped.put("artist", joinArtists(artists));

        JSONObject album = song.optJSONObject("album") != null
                ? song.optJSONObject("album") : song.optJSONObject("al");
        mapped.put("album", album == null ? "" : album.optString("name", ""));

        long duration = song.optLong("duration", song.optLong("dt", 0));
        mapped.put("duration", duration);

        return mapped;
    }

    private static String joinArtists(JSONArray artists) {
        if (artists == null) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < artists.length(); i++) {
            JSONObject artist = artists.optJSONObject(i);
            if (artist == null) {
                continue;
            }
            String name = artist.optString("name", "");
            if (!name.isEmpty()) {
                //noinspection SizeReplaceableByIsEmpty
                if (builder.length() > 0) {
                    builder.append('/');
                }
                builder.append(name);
            }
        }
        return builder.toString();
    }

    private static void ensureInit() {
        if (initialized) {
            return;
        }
        resetPreCookies();
        try {
            String username = getAnonimousUsername();
            JSONObject root = registerAnonimous(
                    new JSONObject().put("username", username).put("e_r", true));
            if (!"200".equals(String.valueOf(root.opt("code")))) {
                throw new IOException("NetEase anonymous login failed: " + root);
            }
            if (!cookieJar.containsKey("WNMCID")) {
                cookieJar.put("WNMCID", randomChars(6, "abcdefghijklmnopqrstuvwxyz") + "." + System.currentTimeMillis() + ".01.0");
            }
            initialized = true;
        } catch (Exception ex) {
            Logger.printInfo(() -> "NetEase anonymous login error", ex);
        }
    }

    private static void resetPreCookies() {
        cookieJar.clear();
        cookieJar.put("os", "pc");
        cookieJar.put("deviceId", deviceId);
        cookieJar.put("osver", osver);
        cookieJar.put("clientSign", clientSign);
        cookieJar.put("channel", "netease");
        cookieJar.put("mode", mode);
        cookieJar.put("appver", APP_VER);
    }

    private static JSONObject eapiRequest(String path, JSONObject params) throws IOException, JSONException {
        try {
            ensureInit();
        } catch (Exception ex) {
            Logger.printDebug(() -> "Ensure NetEase init failed", ex);
            if (cookieJar.isEmpty()) {
                resetPreCookies();
            }
        }

        String decrypted = postEapi(path, params);
        if (decrypted.isEmpty()) {
            return new JSONObject();
        }

        JSONObject root = new JSONObject(decrypted);
        String code = String.valueOf(root.opt("code"));
        if ("301".equals(code) || "401".equals(code)) {
            initialized = false;
            cookieJar.clear();
            throw new IOException("NetEase session invalid: " + decrypted);
        }
        return root;
    }

    private static JSONObject registerAnonimous(JSONObject params) throws IOException, JSONException {
        String decrypted = postEapi(REGISTER_PATH, params);
        return decrypted.isEmpty() ? new JSONObject() : new JSONObject(decrypted);
    }

    /**
     * Posts one EAPI call and decrypts its answer.
     *
     * @return The decrypted body, empty when the call did not answer with a body.
     */
    private static String postEapi(String path, JSONObject params) throws IOException, JSONException {
        String body = "params=" + buildEapiParams(path, params);
        Map<String, String> headers = eapiHeaders();
        HttpURLConnection connection = LyricsRequests.postForm(EAPI_HOST + path, body, headers);
        int httpCode = connection.getResponseCode();
        if (httpCode != 200) {
            LyricsRequests.logFailure("NetEase", connection);
            return "";
        }
        captureCookies(connection);
        return decryptEapiResponse(connection);
    }

    /** The request headers every EAPI call carries, including the current cookie jar. */
    private static Map<String, String> eapiHeaders() {
        Map<String, String> headers = new HashMap<>();
        headers.put("Referer", "https://music.163.com/");
        headers.put("User-Agent", NETEASE_USER_AGENT);
        headers.put("Accept", "*/*");
        headers.put("Host", "interface.music.163.com");
        String cookie = cookieHeader();
        if (!cookie.isEmpty()) {
            headers.put("Cookie", cookie);
        }
        return headers;
    }

    private static String buildEapiParams(String path, JSONObject params) throws JSONException {
        JSONObject header = new JSONObject();
        header.put("clientSign", clientSign);
        header.put("osver", osver);
        header.put("deviceId", deviceId);
        header.put("os", "pc");
        header.put("appver", APP_VER);
        header.put("requestId", String.valueOf(System.currentTimeMillis()));

        params.put("header", header.toString());
        if (!params.has("e_r")) {
            params.put("e_r", true);
        }

        String actualPath = path.replace("/eapi/", "/api/");
        String paramsText = params.toString();
        String digest = LyricsCrypto.md5Hex(
                "nobody" + actualPath + "use" + paramsText + "md5forencrypt");
        String data = actualPath + "-36cd479b6b5-" + paramsText + "-36cd479b6b5-" + digest;
        return LyricsCrypto.aesEcbPkcs5EncryptHex(data, EAPI_KEY);
    }

    private static String decryptEapiResponse(HttpURLConnection connection) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (InputStream in = connection.getInputStream()) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) {
                out.write(buf, 0, n);
            }
        }
        byte[] raw = out.toByteArray();
        String base64 = Base64.encodeToString(raw, Base64.NO_WRAP);
        return LyricsCrypto.aesEcbPkcs5DecryptBase64ToString(base64, EAPI_KEY);
    }

    private static String getAnonimousUsername() {
        StringBuilder xored = new StringBuilder();
        for (int i = 0; i < deviceId.length(); i++) {
            char left = deviceId.charAt(i);
            char right = DEVICEID_XOR_KEY.charAt(i % DEVICEID_XOR_KEY.length());
            xored.append((char) (left ^ right));
        }
        byte[] md5 = LyricsCrypto.md5Bytes(xored.toString().getBytes(StandardCharsets.UTF_8));
        String base64Md5 = Base64.encodeToString(md5, Base64.NO_WRAP);
        String combined = deviceId + " " + base64Md5;
        return Base64.encodeToString(combined.getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP);
    }

    private static void captureCookies(HttpURLConnection connection) {
        List<String> setCookies = setCookies(connection);
        if (setCookies == null) {
            return;
        }
        for (String line : setCookies) {
            Map.Entry<String, String> pair = cookiePair(line);
            if (pair != null) {
                cookieJar.put(pair.getKey(), pair.getValue());
            }
        }
    }

    /** The {@code Set-Cookie} values of a response, or null when the header is absent. */
    @Nullable
    private static List<String> setCookies(HttpURLConnection connection) {
        Map<String, List<String>> headerFields = connection.getHeaderFields();
        List<String> setCookies = headerFields.get("Set-Cookie");
        if (setCookies == null) {
            setCookies = headerFields.get("set-cookie");
        }
        return setCookies;
    }

    /** The non-empty {@code name=value} of one {@code Set-Cookie} line, or null. */
    @Nullable
    private static Map.Entry<String, String> cookiePair(@Nullable String line) {
        if (line == null) {
            return null;
        }
        String pair = line.split(";", 2)[0];
        int index = pair.indexOf('=');
        if (index <= 0) {
            return null;
        }
        String key = pair.substring(0, index).trim();
        String value = pair.substring(index + 1).trim();
        if (key.isEmpty() || value.isEmpty()) {
            return null;
        }
        return Map.entry(key, value);
    }

    private static String cookieHeader() {
        StringBuilder builder = new StringBuilder();
        for (Map.Entry<String, String> entry : cookieJar.entrySet()) {
            //noinspection SizeReplaceableByIsEmpty
            if (builder.length() > 0) {
                builder.append("; ");
            }
            builder.append(entry.getKey()).append('=').append(entry.getValue());
        }
        return builder.toString();
    }

    private static List<LyricsLine> parseNeteaseOriginalLyrics(@Nullable String yrc, @Nullable String lrc) {
        boolean hasYrc = yrc != null && !yrc.isEmpty();
        if (hasYrc) {
            List<LyricsLine> lines = parseYrc(yrc);
            if (!lines.isEmpty()) {
                return lines;
            }
        }

        if (lrc != null && RICH_JSON.matcher(lrc).find()) {
            return parseMixedNeteaseLyrics(lrc);
        }

        return parseLrc(lrc);
    }

    @SuppressWarnings("SizeReplaceableByIsEmpty")
    private static List<LyricsLine> parseYrc(String text) {
        List<LyricsLine> lines = new ArrayList<>();
        if (text == null || text.isEmpty()) {
            return lines;
        }

        for (String rawLine : text.split("\\r?\\n")) {
            String line = rawLine.trim();
            if (line.startsWith("{")) {
                continue;
            }

            Matcher lineMatch = YRC_LINE.matcher(line);
            if (!lineMatch.matches()) {
                continue;
            }

            LyricsLine yrcLine = yrcLine(lineMatch);
            if (yrcLine != null) {
                lines.add(yrcLine);
            }
        }

        lines.sort(Comparator.comparingLong(LyricsLine::startTimeMs));
        return lines;
    }

    /** One matched YRC line with its timed words, or null when it holds no text. */
    @Nullable
    private static LyricsLine yrcLine(Matcher lineMatch) {
        long lineStart = Long.parseLong(Objects.requireNonNull(lineMatch.group(1)));
        long lineDuration = Long.parseLong(Objects.requireNonNull(lineMatch.group(2)));
        long lineEnd = lineStart + lineDuration;
        String content = Objects.requireNonNull(lineMatch.group(3));

        List<Long> starts = new ArrayList<>();
        List<Long> durations = new ArrayList<>();
        List<int[]> marks = new ArrayList<>();
        Matcher wordMatch = YRC_WORD.matcher(content);
        while (wordMatch.find()) {
            starts.add(Long.parseLong(Objects.requireNonNull(wordMatch.group(1))));
            durations.add(Long.parseLong(Objects.requireNonNull(wordMatch.group(2))));
            marks.add(new int[]{wordMatch.start(), wordMatch.start(3)});
        }

        StringBuilder full = new StringBuilder();
        if (!marks.isEmpty() && marks.get(0)[0] > 0) {
            full.append(content, 0, marks.get(0)[0]);
        }
        List<Word> words = yrcWords(starts, durations, marks, content, full);
        if (words.isEmpty() && full.length() == 0 && !content.isEmpty()) {
            full.append(content);
        }

        String fullText = full.toString().trim();
        if (fullText.isEmpty()) {
            return null;
        }
        return new LyricsLine(lineStart, lineEnd, fullText, words);
    }

    /**
     * Appends every YRC word to {@code full} and returns the timed words, skipping the
     * timed spaces that only belong to the line text.
     */
    private static List<Word> yrcWords(List<Long> starts, List<Long> durations,
                                       List<int[]> marks, String content, StringBuilder full) {
        List<Word> words = new ArrayList<>();
        for (int i = 0; i < starts.size(); i++) {
            long wordStart = starts.get(i);
            long wordEnd = wordStart + durations.get(i);
            int segmentStart = marks.get(i)[1];
            int segmentEnd = i + 1 < marks.size() ? marks.get(i + 1)[0] : content.length();
            String wordText = content.substring(segmentStart, segmentEnd);
            full.append(wordText);
            String trimmed = wordText.trim();
            if (trimmed.isEmpty()) {
                // A timed space between words is kept in the line text only.
                continue;
            }
            boolean endsWithSpace =
                    Character.isWhitespace(wordText.charAt(wordText.length() - 1));
            words.add(new Word(wordStart, wordEnd, trimmed, null, endsWithSpace));
        }
        return words;
    }

    /** The timed lines of a plain LRC body. */
    private static List<LyricsLine> parseLrc(String text) {
        return toSortedLines(text, true, false);
    }

    /** The lines of a body that may also carry its timed items as JSON, as NetEase writes them. */
    private static List<LyricsLine> parseMixedNeteaseLyrics(String text) {
        return toSortedLines(text, true, true);
    }

    /**
     * The sorted lines of a NetEase lyric body.
     *
     * @param timed The timing of the lines a plain LRC gives.
     * @param mixed Whether lines may also be the JSON items of a mixed body.
     */
    private static List<LyricsLine> toSortedLines(String text, boolean timed, boolean mixed) {
        if (text == null || text.isEmpty()) {
            return new ArrayList<>();
        }
        return toSortedLines(parseItems(text, timed, mixed));
    }

    /** The items one line of a NetEase lyric body adds, and none when it carries neither. */
    private static List<Item> parseItems(String text, boolean timed, boolean mixed) {
        List<Item> items = new ArrayList<>();
        for (String rawLine : text.split("\\r?\\n")) {
            String line = rawLine.trim();
            if (line.isEmpty()) {
                continue;
            }
            if (mixed && line.charAt(0) == '{') {
                Item item = mixedJsonItem(line);
                if (item != null) {
                    items.add(item);
                }
                continue;
            }
            if (timed) {
                addTimeTaggedLine(line, items);
            }
        }
        return items;
    }

    /** The timed item of a NetEase JSON mixed-lyric line, or null when it carries no text. */
    @Nullable
    private static Item mixedJsonItem(String line) {
        try {
            JSONObject obj = new JSONObject(line);
            long start = obj.optLong("t", 0);
            JSONArray parts = obj.optJSONArray("c");
            if (parts == null) {
                return null;
            }
            String value = joinPartTexts(parts);
            return value.isEmpty() ? null : new Item(start, value);
        } catch (Exception ex) {
            Logger.printDebug(() -> "Could not parse NetEase LRC item", ex);
            return null;
        }
    }

    /**
     * Adds one line of {@code [mm:ss.xx] text}, once per timestamp it carries. A line with no
     * timestamp, or with nothing after the last one, adds nothing.
     */
    private static void addTimeTaggedLine(String line, List<Item> items) {
        Matcher timeMatch = LRC_TIME.matcher(line);
        List<Long> starts = new ArrayList<>();
        int lastEnd = -1;
        while (timeMatch.find()) {
            starts.add(parseTimeMs(timeMatch.group(1), timeMatch.group(2), timeMatch.group(3)));
            lastEnd = timeMatch.end();
        }
        if (starts.isEmpty()) {
            return;
        }
        String content = line.substring(lastEnd).trim();
        if (content.isEmpty()) {
            return;
        }
        for (Long start : starts) {
            items.add(new Item(start, content));
        }
    }

    private static List<LyricsLine> toSortedLines(List<Item> items) {
        items.sort(Comparator.comparingLong(Item::start));
        List<LyricsLine> lines = new ArrayList<>(items.size());
        for (Item item : items) {
            lines.add(new LyricsLine(item.start(), item.text()));
        }
        return lines;
    }

    private static long parseTimeMs(String minutes, String seconds, String fraction) {
        long min = Long.parseLong(minutes);
        long sec = Long.parseLong(seconds);
        StringBuilder fractionText = new StringBuilder(fraction == null ? "0" : fraction);
        while (fractionText.length() < 3) {
            fractionText.append('0');
        }
        return (min * 60 + sec) * 1000 + Long.parseLong(fractionText.substring(0, 3));
    }

    private static String randomChars(int length, String alphabet) {
        StringBuilder builder = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            builder.append(alphabet.charAt(RANDOM.nextInt(alphabet.length())));
        }
        return builder.toString();
    }

    private static String randomMac() {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < 6; i++) {
            if (i > 0) {
                builder.append(':');
            }
            builder.append(String.format("%02X", RANDOM.nextInt(256)));
        }
        return builder.toString();
    }

    private static String generateClientSign() {
        return randomMac() + "@@@" + randomChars(8, "ABCDEFGHIJKLMNOPQRSTUVWXYZ") + "@@@@@@" + randomChars(64, "0123456789abcdef");
    }

    @Override
    public String name() {
        return "NetEase";
    }

    @Override
    public boolean hasCandidates() {
        return true;
    }

    @Nullable
    @Override
    public FetchResult fetch(TrackInfo track) throws Exception {
        String keyword = track.title() + " " + track.artist();
        JSONObject song = searchBest(keyword, track);
        if (song == null || !song.has("id")) {
            return null;
        }
        Lyrics lyrics = fetchFromSong(song);
        if (lyrics == null) {
            return null;
        }
        return FetchResult.of(lyrics, songTitle(song), songArtist(song), songDurationSeconds(song),
                track);
    }

    private static String songTitle(JSONObject song) {
        return song.optString("name", "");
    }

    private static String songArtist(JSONObject song) {
        return song.optString("artist", "");
    }

    /** The song's length in whole seconds, or 0 when the search did not say. */
    private static int songDurationSeconds(JSONObject song) {
        final long durationMs = song.optLong("duration", 0);
        return durationMs > 0 ? (int) (durationMs / 1000) : 0;
    }

    @Override
    public List<Lyrics.ScoredLyrics> fetchCandidates(TrackInfo track) throws Exception {
        String keyword = track.title() + " " + track.artist();
        List<JSONObject> songs = searchAll(keyword, track);

        List<Lyrics.ScoredLyrics> scored = new ArrayList<>();
        for (JSONObject song : songs) {
            if (scored.size() >= LyricsRequests.MAX_CANDIDATES) {
                break;
            }
            Lyrics.ScoredLyrics candidate = scoredCandidate(song, track);
            if (candidate != null) {
                scored.add(candidate);
            }
        }

        return Lyrics.sortScoredByScore(scored);
    }

    /** The scored lyrics of one search result, or null when it has none. */
    @Nullable
    private Lyrics.ScoredLyrics scoredCandidate(@Nullable JSONObject song, TrackInfo track) {
        if (song == null || !song.has("id")) {
            return null;
        }
        try {
            Lyrics lyrics = fetchFromSong(song);
            if (lyrics == null) {
                return null;
            }
            int score = LyricsRequests.scoreLyricsCandidate(
                    songTitle(song), songArtist(song), songDurationSeconds(song), lyrics, track);
            return new Lyrics.ScoredLyrics(score, lyrics);
        } catch (Exception ex) {
            Logger.printDebug(() -> "Could not fetch NetEase lyrics for a song", ex);
            return null;
        }
    }

    @Nullable
    private Lyrics fetchFromSong(JSONObject song) throws Exception {
        JSONObject root = eapiRequest("/eapi/song/lyric/v1", new JSONObject()
                .put("id", song.getLong("id"))
                .put("lv", "-1")
                .put("tv", "-1")
                .put("rv", "-1")
                .put("yv", "-1"));

        String yrc = optLyric(root, "yrc");
        final boolean isYrc = !yrc.isEmpty();
        String lrc = optLyric(root, "lrc");
        String tlyric = optLyric(root, "tlyric");
        String romalrc = optLyric(root, "romalrc");

        List<LyricsLine> lines = parseNeteaseOriginalLyrics(yrc, lrc);
        if (lines.isEmpty()) {
            return null;
        }

        List<String> creditLines = parseYrcCredits(yrc);

        List<LyricsLine> romaLines = romalrc.isEmpty() ? null : LRCParser.parseSynced(romalrc);
        List<LyricsLine> romanization = LyricsMerge.mergeRomanization(lines, romaLines);

        Map<String, List<LyricsLine>> translations = translationsOf(lines, tlyric);

        String rawFormat = filterJsonLines(yrc.isEmpty() ? lrc : yrc);
        String formatType = isYrc ? "yrc" : "lrc";
        long songId = song.getLong("id");
        String sourceUrl = "https://music.163.com/song?id=" + songId;
        return new Lyrics(lines, name(), true, romanization, translations, null,
                creditLines.isEmpty() ? null : creditLines, rawFormat, formatType, sourceUrl);
    }

    /**
     * NetEase writes this translation itself, in the Chinese the mainland uses, so it is only
     * kept when that is the user's language.
     */
    @Nullable
    private static Map<String, List<LyricsLine>> translationsOf(List<LyricsLine> lines, String tlyric) {
        if (!LyricsRequests.isSimplifiedChinese()) {
            return null;
        }
        List<LyricsLine> transLines = tlyric.isEmpty() ? null : LRCParser.parseSynced(tlyric);
        List<LyricsLine> translation = LyricsMerge.mergeRomanization(lines, transLines);
        return LyricsMerge.singleLanguageTranslations(translation, LyricsRequests.SIMPLIFIED_CHINESE);
    }

    private record Item(long start, String text) {
    }
}
