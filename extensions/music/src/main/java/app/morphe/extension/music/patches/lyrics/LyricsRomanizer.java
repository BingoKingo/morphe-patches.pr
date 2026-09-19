/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/2625
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.lyrics;

import android.util.Log;

import androidx.annotation.Nullable;

import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.json.JSONArray;
import org.json.JSONObject;

import app.morphe.extension.music.settings.Settings;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.requests.Requester;
import app.morphe.extension.shared.translation.TextTranslator;

/**
 * Romanizes (transliterates to Latin script) the lyrics line by line, so the pronunciation
 * of non-Latin scripts can be shown above each line.
 */
public final class LyricsRomanizer {

    private static final String TAG = "MorpheLyricsRomanizer";

    public interface Callback {
        /**
         * Called on the main thread with one romanized line per original line,
         * or {@code null} if the romanization failed.
         *
         * @param fromGoogle       {@code true} when the result came from Google, in which case
         *                         the UI may attribute it; {@code false} for the embedded source.
         * @param fromPollinations {@code true} when the result came from Pollinations AI.
         * @param perWord          {@code true} when the source ships per-word romanization (carried
         *                         on each {@link Word}); the UI should render it above each word.
         */
        void onRomanized(@Nullable List<LyricsLine> romanizedLines,
                         boolean fromGoogle, boolean fromPollinations, boolean perWord);
    }

    private static final String POLLINATIONS_URL = "https://text.pollinations.ai/openai";
    private static final int POLLINATIONS_CONNECT_TIMEOUT = 10_000;
    private static final int POLLINATIONS_READ_TIMEOUT = 45_000;
    private static final int POLLINATIONS_MAX_CHARS = 3_000;
    private static final int POLLINATIONS_MAX_ATTEMPTS = 2;

    /** Separate from the lyrics executor, so a romanization never delays a lyrics lookup. */
    private static final ExecutorService executor = Executors.newSingleThreadExecutor();

    private LyricsRomanizer() {
    }

    /**
     * Romanizes the lyrics of a track, preferring an embedded romanization and falling back
     * to Pollinations then Google (with the on-disk cache) when the source provides none.
     */
    public static void romanize(TrackInfo track, Lyrics lyrics, String source, Callback callback) {
        Utils.verifyOnMainThread();

        Log.d(TAG, "romanize: called, track=" + track.title() + " / " + track.artist()
                + " source=" + source + " lines=" + lyrics.lines().size());

        List<LyricsLine> embedded = lyrics.romanization();
        if (embedded == null || embedded.isEmpty()) {
            Map<String, List<LyricsLine>> romanizations = lyrics.romanizations();
            if (romanizations != null && !romanizations.isEmpty()) {
                embedded = collectMatchingRomanizations(romanizations, lyrics.lines());
            }
        }

        final boolean perWord = LyricsMerge.anyWordHasRomaji(lyrics.lines());
        if (LyricsMerge.hasText(embedded) || perWord) {
            Log.d(TAG, "romanize: using embedded/perWord romanization, returning early");
            List<LyricsLine> result = embedded;
            Utils.runOnMainThread(() -> callback.onRomanized(result, false, false, perWord));
            return;
        }
        Log.d(TAG, "romanize: no embedded romanization available");

        List<String> lines = new ArrayList<>(lyrics.lines().size());
        for (LyricsLine line : lyrics.lines()) {
            String text = line.text();
            lines.add(text != null ? text : "");
        }
        Log.d(TAG, "romanize: deviceLanguage=" + deviceLanguage());

        executor.execute(() -> {
            Log.d(TAG, "romanize: executor started, pollinations=" + Settings.LYRICS_USE_POLLINATIONS.get());
            if (Settings.LYRICS_USE_POLLINATIONS.get()) {
                Log.d(TAG, "romanize: Pollinations enabled, trying Pollinations first");
                List<LyricsLine> polCached = LyricsCache.getRomanizationPollination(
                        track, source, lines.size());
                if (polCached != null) {
                    Log.d(TAG, "romanize: Pollinations cache hit, returning cached result");
                    Utils.runOnMainThread(() -> callback.onRomanized(polCached, false, true, false));
                    return;
                }
                Log.d(TAG, "romanize: Pollinations cache miss, calling API");

                List<String> polResult = pollinationsRomanize(
                        lines, deviceLanguage(), track.title(), track.artist());
                if (polResult != null) {
                    Log.d(TAG, "romanize: Pollinations API success, result lines=" + polResult.size());
                    List<LyricsLine> polLines = toLines(polResult);
                    LyricsCache.putRomanizationPollination(track, source, polLines);
                    Utils.runOnMainThread(() -> callback.onRomanized(polLines, false, true, false));
                    return;
                }
                Log.d(TAG, "romanize: Pollinations API failed, falling back to Google");
            } else {
                Log.d(TAG, "romanize: Pollinations disabled, going straight to Google");
            }

            Log.d(TAG, "romanize: trying Google cache");
            List<LyricsLine> romanized = LyricsCache.getRomanization(track, source, lines.size());
            if (romanized != null) {
                Log.d(TAG, "romanize: Google cache hit");
            } else {
                Log.d(TAG, "romanize: Google cache miss, calling Google Romanize");
                List<String> romanizedText = romanizeOnline(lines);
                if (romanizedText != null) {
                    Log.d(TAG, "romanize: Google Romanize success, result lines=" + romanizedText.size());
                    romanized = toLines(romanizedText);
                    LyricsCache.putRomanization(track, source, romanized);
                } else {
                    Log.d(TAG, "romanize: Google Romanize returned null");
                }
            }

            List<LyricsLine> result = romanized;
            Utils.runOnMainThread(() -> callback.onRomanized(result, result != null, false, false));
        });
    }

    private static List<LyricsLine> collectMatchingRomanizations(
            Map<String, List<LyricsLine>> romanizations, List<LyricsLine> allLines) {
        String langTag = Locale.getDefault().toLanguageTag();
        String langCode = langTag.contains("-")
                ? langTag.substring(0, langTag.indexOf("-")) : langTag;

        List<String> matchedKeys = new ArrayList<>();
        for (String key : romanizations.keySet()) {
            if (key.startsWith("bg:")) continue;
            if (key.equals(langTag) || key.equals(langCode)
                    || key.startsWith(langCode + "-") || key.startsWith(langCode + "_")) {
                matchedKeys.add(key);
            }
        }

        if (matchedKeys.isEmpty()) {
            for (String key : romanizations.keySet()) {
                if (!key.startsWith("bg:")) {
                    matchedKeys.add(key);
                }
            }
        }

        if (matchedKeys.isEmpty()) {
            return null;
        }

        // Find the line count from the first matching language
        final int lineCount;
        {
            List<LyricsLine> first = romanizations.get(matchedKeys.get(0));
            lineCount = (first != null) ? first.size() : 0;
        }
        if (lineCount == 0) {
            return null;
        }

        // Merge multi-language romanizations line by line
        List<LyricsLine> result = new ArrayList<>(lineCount);
        for (int i = 0; i < lineCount; i++) {
            StringBuilder merged = new StringBuilder();
            for (String key : matchedKeys) {
                List<LyricsLine> langLines = romanizations.get(key);
                if (langLines == null || i >= langLines.size()) continue;
                String text = langLines.get(i).text();
                if (text == null) continue;
                text = text.trim();
                if (!text.isEmpty()) {
                    if (merged.length() > 0) merged.append('\n');
                    merged.append(text);
                }
            }
            result.add(new LyricsLine(LyricsLine.NO_TIME, merged.toString()));
        }

        // Fill BG lines with their parent's romanization
        if (allLines != null) {
            for (int i = 0; i < result.size() && i < allLines.size(); i++) {
                if (allLines.get(i).isBG()) {
                    String bgRoma = result.get(i).text();
                    if (bgRoma == null || bgRoma.isEmpty()) {
                        for (int j = i - 1; j >= 0; j--) {
                            if (!allLines.get(j).isBG() && j < result.size()) {
                                String parentRoma = result.get(j).text();
                                if (parentRoma != null && !parentRoma.isEmpty()) {
                                    result.set(i, new LyricsLine(LyricsLine.NO_TIME, parentRoma));
                                }
                                break;
                            }
                        }
                    }
                }
            }
        }

        return result;
    }

    private static List<LyricsLine> toLines(List<String> texts) {
        List<LyricsLine> result = new ArrayList<>(texts.size());
        for (String text : texts) {
            if (text == null || text.equals("null")) {
                text = "";
            }
            result.add(new LyricsLine(LyricsLine.NO_TIME, text));
        }
        return result;
    }

    /**
     * @return One line per input line, or {@code null} if any batch failed or came
     * back with a different number of lines than it was given.
     */
    @Nullable
    private static List<String> romanizeOnline(List<String> lines) {
        return LyricsMerge.mapLinesOnline(lines,
                l -> {
                    try {
                        return TextTranslator.romanize(l);
                    } catch (Exception ex) {
                        throw new RuntimeException(ex);
                    }
                });
    }

    @Nullable
    private static List<String> pollinationsRomanize(List<String> lines, String targetLanguage,
            String title, String artist) {
        int totalChars = 0;
        for (String line : lines) {
            totalChars += line.length() + 1;
        }
        if (totalChars > POLLINATIONS_MAX_CHARS) {
            Log.d(TAG, "pollinationsRomanize: skipped, totalChars=" + totalChars + " > " + POLLINATIONS_MAX_CHARS);
            return null;
        }
        String prompt = buildRomanizePrompt(lines, targetLanguage, title, artist);
        Log.d(TAG, "pollinationsRomanize: sending request, lang=" + targetLanguage + " lines=" + lines.size());
        String response = pollinationsRequest(prompt);
        if (response == null) {
            Log.d(TAG, "pollinationsRomanize: pollinationsRequest returned null");
            return null;
        }
        Log.d(TAG, "pollinationsRomanize: response=\n" + response);
        String[] result = response.split("\n", -1);
        int end = result.length;
        while (end > 0 && result[end - 1].trim().isEmpty()) {
            end--;
        }
        if (end == 0) {
            Log.d(TAG, "pollinationsRomanize: empty response after trim");
            return null;
        }
        boolean allSkip = true;
        for (int i = 0; i < end; i++) {
            if (!result[i].trim().equalsIgnoreCase("SKIP")) {
                allSkip = false;
                break;
            }
        }
        if (allSkip) {
            Log.d(TAG, "pollinationsRomanize: AI refused (already Latin script)");
            return null;
        }
        if (end > lines.size() + 2 || end < lines.size() - 2) {
            Log.d(TAG, "pollinationsRomanize: line count mismatch, expected=" + lines.size() + " got=" + end);
            return null;
        }
        List<String> out = new ArrayList<>(lines.size());
        for (int i = 0; i < lines.size(); i++) {
            out.add(i < end ? result[i].trim() : "");
        }
        Log.d(TAG, "pollinationsRomanize: success, " + out.size() + " lines");
        return out;
    }

    private static String buildRomanizePrompt(List<String> lines, String targetLang,
            String title, String artist) {
        StringBuilder sb = new StringBuilder(200 + lines.size() * 50);
        sb.append("You are a professional romanization specialist. Convert each line below to ")
                .append(targetLang).append(" Latin script.\n");
        sb.append("Song: ").append(title).append(" by ").append(artist).append("\n\n");
        sb.append("Output format: Number every romanized line, like:\n");
        sb.append("1. first romanization\n");
        sb.append("2. second romanization\n\n");
        sb.append("CRITICAL RULES:\n");
        sb.append("- Output exactly ").append(lines.size()).append(" numbered lines (same as input count)\n");
        sb.append("- Number format: \"N. romanized text\" (number, period, space, text)\n");
        sb.append("- Do NOT include the original lyrics in your output\n");
        sb.append("- Do NOT use \"Line N:\" format\n");
        sb.append("- Use standard ").append(targetLang).append(" romanization without tone marks\n");
        sb.append("- If the lyrics are already in Latin script, output one \"SKIP\" per line\n\n");
        for (String line : lines) {
            sb.append(line).append("\n");
        }
        return sb.toString();
    }

    @Nullable
    private static String pollinationsRequest(String prompt) {
        Utils.verifyOffMainThread();
        try {
            JSONObject body = new JSONObject();
            body.put("model", "openai");
            JSONArray messages = new JSONArray();
            JSONObject system = new JSONObject();
            system.put("role", "system");
            system.put("content", "You are a romanization specialist. Output ONLY the romanized text. No reasoning, no explanations, no step-by-step thinking.");
            messages.put(system);
            JSONObject msg = new JSONObject();
            msg.put("role", "user");
            msg.put("content", prompt);
            messages.put(msg);
            body.put("messages", messages);
            byte[] bodyBytes = body.toString().getBytes(StandardCharsets.UTF_8);

            for (int attempt = 0; attempt < POLLINATIONS_MAX_ATTEMPTS; attempt++) {
                if (attempt > 0) {
                    sleepQuietly(1000L * attempt);
                }
                HttpURLConnection connection = null;
                try {
                    Log.d(TAG, "pollinationsRequest: attempt=" + (attempt + 1) + "/" + POLLINATIONS_MAX_ATTEMPTS);
                    connection = Requester.openConnection(POLLINATIONS_URL);
                    connection.setRequestMethod("POST");
                    connection.setConnectTimeout(POLLINATIONS_CONNECT_TIMEOUT);
                    connection.setReadTimeout(POLLINATIONS_READ_TIMEOUT);
                    connection.setDoOutput(true);
                    connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
                    connection.setRequestProperty("Content-Length", String.valueOf(bodyBytes.length));
                    try (java.io.OutputStream os = connection.getOutputStream()) {
                        os.write(bodyBytes);
                    }
                    final int code = connection.getResponseCode();
                    Log.d(TAG, "pollinationsRequest: HTTP " + code);
                    if (code == 200) {
                        String responseStr = Requester.parseString(connection);
                        Log.d(TAG, "pollinationsRequest: raw response=\n" + responseStr);
                        JSONObject json = new JSONObject(responseStr);
                        JSONObject message = json.getJSONArray("choices")
                                .getJSONObject(0)
                                .getJSONObject("message");
                        String content = message.optString("content", null);
                        if (content != null && !content.isEmpty()) {
                            Log.d(TAG, "pollinationsRequest: extracted content=\n" + content);
                            return content;
                        }
                        String reasoning = message.optString("reasoning", null);
                        if (reasoning == null || reasoning.isEmpty()) {
                            Log.d(TAG, "pollinationsRequest: both content and reasoning are empty");
                            return null;
                        }
                        Log.d(TAG, "pollinationsRequest: no content field, using reasoning, length=" + reasoning.length());
                        String extracted = extractLastNumberedBlock(reasoning);
                        if (extracted != null) {
                            Log.d(TAG, "pollinationsRequest: extracted from reasoning:\n" + extracted);
                            return extracted;
                        }
                        Log.d(TAG, "pollinationsRequest: could not extract numbered block, using raw reasoning");
                        return reasoning;
                    }
                    if (code == 429) {
                        Log.d(TAG, "pollinationsRequest: rate limited, skipping retries");
                        break;
                    }
                    Log.d(TAG, "pollinationsRequest: failed with HTTP " + code);
                } catch (Exception e) {
                    Log.e(TAG, "pollinationsRequest: exception on attempt " + (attempt + 1), e);
                } finally {
                    if (connection != null) {
                        connection.disconnect();
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "pollinationsRequest: failed to build request", e);
        }
        return null;
    }

    @Nullable
    private static String extractLastNumberedBlock(String text) {
        String[] lines = text.split("\n", -1);
        java.util.List<java.util.List<String>> blocks = new java.util.ArrayList<>();
        java.util.List<String> currentBlock = new java.util.ArrayList<>();
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.matches("^\\d+\\.\\s+.+") && !trimmed.startsWith("Line")) {
                currentBlock.add(trimmed);
                continue;
            }
            if (!currentBlock.isEmpty()) {
                blocks.add(currentBlock);
                currentBlock = new java.util.ArrayList<>();
            }
        }
        if (!currentBlock.isEmpty()) {
            blocks.add(currentBlock);
        }
        if (blocks.isEmpty()) {
            return null;
        }
        java.util.List<String> lastBlock = blocks.get(blocks.size() - 1);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < lastBlock.size(); i++) {
            if (i > 0) sb.append("\n");
            sb.append(lastBlock.get(i).replaceFirst("^\\d+\\.\\s+", ""));
        }
        return sb.toString();
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    public static String deviceLanguage() {
        return Locale.getDefault().getLanguage();
    }
}
