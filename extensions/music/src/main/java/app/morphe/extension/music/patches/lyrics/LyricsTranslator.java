/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/2269
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.lyrics;

import android.util.Log;

import androidx.annotation.Nullable;

import java.io.IOException;
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

public final class LyricsTranslator {

    private static final ExecutorService executor = Executors.newSingleThreadExecutor();

    private static final String TAG = "MorpheLyricsTranslator";

    private static final String POLLINATIONS_URL = "https://text.pollinations.ai/openai";
    private static final int POLLINATIONS_CONNECT_TIMEOUT = 10_000;
    private static final int POLLINATIONS_READ_TIMEOUT = 45_000;
    private static final int POLLINATIONS_MAX_CHARS = 3_000;
    private static final int POLLINATIONS_MAX_ATTEMPTS = 2;

    public interface Callback {
        void onTranslated(@Nullable List<String> translatedLines,
                          boolean fromGoogle, boolean fromPollinations);
    }

    private LyricsTranslator() {
    }

    public static String deviceLanguage() {
        return Locale.getDefault().getLanguage();
    }

    @Nullable
    private static List<String> embeddedTranslation(Lyrics lyrics, String target, int lineCount) {
        Map<String, List<LyricsLine>> byLang = lyrics.translations();
        if (byLang == null || byLang.isEmpty()) {
            return null;
        }
        String targetLang = primarySubtag(target);
        for (Map.Entry<String, List<LyricsLine>> entry : byLang.entrySet()) {
            if (!primarySubtag(entry.getKey()).equals(targetLang)) {
                continue;
            }
            List<LyricsLine> lines = entry.getValue();
            if (lines == null || lines.size() != lineCount || !LyricsMerge.hasText(lines)) {
                continue;
            }
            //noinspection ExtractMethodRecommender
            List<String> out = new ArrayList<>(lines.size());
            for (LyricsLine line : lines) {
                String text = line.text();
                out.add(text == null ? "" : text);
            }
            List<LyricsLine> allLines = lyrics.lines();
            for (int i = 0; i < out.size() && i < allLines.size(); i++) {
                if (allLines.get(i).isBG()) {
                    for (int j = i - 1; j >= 0; j--) {
                        if (!allLines.get(j).isBG() && j < out.size()) {
                            String parentTrans = out.get(j);
                            if (parentTrans != null && !parentTrans.isEmpty()) {
                                out.set(i, parentTrans);
                            }
                            break;
                        }
                    }
                }
            }
            return out;
        }
        return null;
    }

    private static String primarySubtag(String lang) {
        if (lang == null) {
            return "";
        }
        final int idx = lang.indexOf('-');
        return (idx >= 0 ? lang.substring(0, idx) : lang).toLowerCase(Locale.ROOT);
    }

    public static void translate(TrackInfo track, Lyrics lyrics, String source, Callback callback) {
        Utils.verifyOnMainThread();

        Log.d(TAG, "translate: called, track=" + track.title() + " / " + track.artist()
                + " source=" + source + " lines=" + lyrics.lines().size());

        List<String> lines = new ArrayList<>(lyrics.lines().size());
        for (LyricsLine line : lyrics.lines()) {
            lines.add(line.text());
        }

        String language = deviceLanguage();
        Log.d(TAG, "translate: deviceLanguage=" + language);

        List<String> embedded = embeddedTranslation(lyrics, language, lines.size());
        if (embedded != null) {
            Log.d(TAG, "translate: using embedded translation, returning early");
            Utils.runOnMainThread(() -> callback.onTranslated(embedded, false, false));
            return;
        }
        Log.d(TAG, "translate: no embedded translation available");

        executor.execute(() -> {
            Log.d(TAG, "translate: executor started, pollinations=" + Settings.LYRICS_USE_POLLINATIONS.get());
            if (Settings.LYRICS_USE_POLLINATIONS.get()) {
                Log.d(TAG, "translate: Pollinations enabled, trying Pollinations first");
                List<String> polCached = LyricsCache.getTranslationPollination(
                        track, source, language, lines.size());
                if (polCached != null) {
                    Log.d(TAG, "translate: Pollinations cache hit, returning cached result");
                    Utils.runOnMainThread(() -> callback.onTranslated(polCached, false, true));
                    return;
                }
                Log.d(TAG, "translate: Pollinations cache miss, calling API");

                List<String> polResult = pollinationsTranslate(
                        lines, language, track.title(), track.artist());
                if (polResult != null) {
                    Log.d(TAG, "translate: Pollinations API success, result lines=" + polResult.size());
                    LyricsCache.putTranslationPollination(track, source, language, polResult);
                    Utils.runOnMainThread(() -> callback.onTranslated(polResult, false, true));
                    return;
                }
                Log.d(TAG, "translate: Pollinations API failed, falling back to Google");
            } else {
                Log.d(TAG, "translate: Pollinations disabled, going straight to Google");
            }

            Log.d(TAG, "translate: trying Google cache");
            List<String> translated = LyricsCache.getTranslation(track, source, language, lines.size());
            if (translated != null) {
                Log.d(TAG, "translate: Google cache hit");
            } else {
                Log.d(TAG, "translate: Google cache miss, calling Google Translate");
                translated = translateOnline(lines, language);
                if (translated != null) {
                    Log.d(TAG, "translate: Google Translate success, result lines=" + translated.size());
                    LyricsCache.putTranslation(track, source, language, translated);
                } else {
                    Log.d(TAG, "translate: Google Translate returned null");
                }
            }

            List<String> result = translated;
            Utils.runOnMainThread(() -> callback.onTranslated(result, result != null, false));
        });
    }

    @Nullable
    private static List<String> translateOnline(List<String> lines, String language) {
        return LyricsMerge.mapLinesOnline(
                lines, b -> {
                    try {
                        return TextTranslator.translate(b, language);
                    } catch (Exception ex) {
                        throw new RuntimeException(ex);
                    }
                });
    }

    @Nullable
    private static List<String> pollinationsTranslate(List<String> lines, String language,
            String title, String artist) {
        int totalChars = 0;
        for (String line : lines) {
            totalChars += line.length() + 1;
        }
        if (totalChars > POLLINATIONS_MAX_CHARS) {
            Log.d(TAG, "pollinationsTranslate: skipped, totalChars=" + totalChars + " > " + POLLINATIONS_MAX_CHARS);
            return null;
        }
        String prompt = buildTranslatePrompt(lines, language, title, artist);
        Log.d(TAG, "pollinationsTranslate: sending request, lang=" + language + " lines=" + lines.size());
        String response = pollinationsRequest(prompt);
        if (response == null) {
            Log.d(TAG, "pollinationsTranslate: pollinationsRequest returned null");
            return null;
        }
        Log.d(TAG, "pollinationsTranslate: response=\n" + response);
        String[] result = response.split("\n", -1);
        int end = result.length;
        while (end > 0 && result[end - 1].trim().isEmpty()) {
            end--;
        }
        if (end == 0) {
            Log.d(TAG, "pollinationsTranslate: empty response after trim");
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
            Log.d(TAG, "pollinationsTranslate: AI refused (same language), falling back to Google");
            return null;
        }
        if (end > lines.size() + 2 || end < lines.size() - 2) {
            Log.d(TAG, "pollinationsTranslate: line count mismatch, expected=" + lines.size() + " got=" + end);
            return null;
        }
        List<String> out = new ArrayList<>(lines.size());
        for (int i = 0; i < lines.size(); i++) {
            out.add(i < end ? result[i].trim() : "");
        }
        Log.d(TAG, "pollinationsTranslate: success, " + out.size() + " lines");
        return out;
    }

    private static String buildTranslatePrompt(List<String> lines, String targetLang,
            String title, String artist) {
        StringBuilder sb = new StringBuilder(200 + lines.size() * 50);
        sb.append("You are a professional lyrics translator. Translate each line below to ")
                .append(targetLang).append(", preserving the emotional tone and poetic style of the original.\n");
        sb.append("Song: ").append(title).append(" by ").append(artist).append("\n\n");
        sb.append("Output format: Number every translated line, like:\n");
        sb.append("1. first translation\n");
        sb.append("2. second translation\n\n");
        sb.append("CRITICAL RULES:\n");
        sb.append("- Output exactly ").append(lines.size()).append(" numbered lines (same as input count)\n");
        sb.append("- Number format: \"N. translated text\" (number, period, space, text)\n");
        sb.append("- Do NOT include the original lyrics in your output\n");
        sb.append("- Do NOT use \"Line N:\" format\n");
        sb.append("- If the lyrics are already in ").append(targetLang)
                .append(", output one \"SKIP\" per line\n\n");
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
            system.put("content", "You are a translator. Output ONLY the translation. No reasoning, no explanations, no step-by-step thinking.");
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
}
