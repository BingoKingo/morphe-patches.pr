/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/2269
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.lyrics.derive;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import app.morphe.extension.music.patches.lyrics.model.Lyrics;
import app.morphe.extension.music.patches.lyrics.model.LyricsLine;
import app.morphe.extension.music.patches.lyrics.model.LyricsMerge;
import app.morphe.extension.music.patches.lyrics.model.TrackInfo;
import app.morphe.extension.music.patches.lyrics.requests.LyricsRequests;
import app.morphe.extension.music.patches.lyrics.storage.LyricsCache;
import app.morphe.extension.music.settings.Settings;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.translation.TextTranslator;

public final class LyricsTranslator {

    private static final ExecutorService executor = Executors.newSingleThreadExecutor();

    private LyricsTranslator() {
    }

    @Nullable
    private static List<String> embeddedTranslation(Lyrics lyrics, String target, int lineCount) {
        Map<String, List<LyricsLine>> byLang = lyrics.translations();
        if (byLang == null || byLang.isEmpty()) {
            return null;
        }
        String targetLang = LyricsRequests.primarySubtag(target);
        final String targetScript = LyricsRequests.resolvedScript(target);
        for (Map.Entry<String, List<LyricsLine>> entry : byLang.entrySet()) {
            String key = entry.getKey();
            if (!isUsableTranslation(entry.getValue(), key, targetLang, targetScript, lineCount)) {
                continue;
            }
            return embeddedTranslationLines(lyrics, entry.getValue(),
                    LyricsMerge.backgroundTrack(byLang, key));
        }
        return null;
    }

    /** Whether an embedded translation is for the target language and lines up with the lyrics. */
    private static boolean isUsableTranslation(@Nullable List<LyricsLine> lines, String key,
                                               String targetLang, String targetScript,
                                               int lineCount) {
        return !key.startsWith(LyricsMerge.BACKGROUND_TRACK_PREFIX)
                && LyricsRequests.primarySubtag(key).equals(targetLang)
                && LyricsRequests.resolvedScript(key).equals(targetScript)
                && lines != null && lines.size() == lineCount && LyricsMerge.hasText(lines);
    }

    /** For background lines without a translation of their own, fall back to the parent line. */
    private static List<String> embeddedTranslationLines(Lyrics lyrics, List<LyricsLine> lines,
                                                         @Nullable List<LyricsLine> backgroundLines) {
        List<String> out = new ArrayList<>(lines.size());
        for (LyricsLine line : lines) {
            String text = line.text();
            out.add(text == null ? "" : text);
        }
        List<LyricsLine> allLines = lyrics.lines();
        final boolean fallback = LyricsMerge.hasBackgroundTrack(lyrics, lyrics.translations());
        for (int i = 0; i < out.size() && i < allLines.size(); i++) {
            if (!allLines.get(i).isBG()) {
                continue;
            }
            final String own = LyricsMerge.textAt(backgroundLines, i);
            if (!own.isEmpty()) {
                out.set(i, own);
                continue;
            }
            if (!fallback) {
                continue;
            }
            copyParentTranslation(allLines, out, i);
        }
        return out;
    }

    /** Gives a background line the translation of the nearest lead line above it. */
    private static void copyParentTranslation(List<LyricsLine> allLines, List<String> out, int i) {
        for (int j = i - 1; j >= 0; j--) {
            if (!allLines.get(j).isBG() && j < out.size()) {
                String parentTrans = out.get(j);
                if (parentTrans != null && !parentTrans.isEmpty()) {
                    out.set(i, parentTrans);
                }
                return;
            }
        }
    }

    public static void translate(TrackInfo track, Lyrics lyrics, String source, Callback callback) {
        Utils.verifyOnMainThread();

        List<String> lines = new ArrayList<>(lyrics.lines().size());
        for (LyricsLine line : lyrics.lines()) {
            lines.add(line.text());
        }

        String language = LyricsRequests.translationLanguage();
        List<String> providers = AiProviders.enabledProviders(
                Settings.LYRICS_TRANSLATION_PROVIDERS.get(),
                AiProviders.TRANSLATION_ORDER);
        if (providers.isEmpty()) {
            callback.onTranslated(null, false, false, null);
            return;
        }

        final List<String> embedded = providers.contains(AiProviders.EMBEDDED)
                ? embeddedTranslation(lyrics, language, lines.size())
                : null;

        // Embedded translation is already in memory, so keep the old synchronous result.
        if (AiProviders.EMBEDDED.equals(providers.get(0)) && embedded != null) {
            callback.onTranslated(embedded, false, false, null);
            return;
        }

        executor.execute(() -> {
            try {
                for (String provider : providers) {
                    ProviderTranslation result;
                    try {
                        result = AiProviders.EMBEDDED.equals(provider)
                                ? (embedded == null ? null
                                   : new ProviderTranslation(embedded, false, false, null))
                                : translateWithProvider(
                                provider, track, source, language, lines);
                    } catch (Exception ex) {
                        Logger.printInfo(() -> "translation provider failed: " + provider, ex);
                        continue;
                    }
                    if (result != null) {
                        Utils.runOnMainThread(() -> callback.onTranslated(
                                result.lines(), result.fromGoogle(), result.fromAI(),
                                result.model()));
                        return;
                    }
                }
                Utils.runOnMainThread(() -> callback.onTranslated(null, false, false, null));
            } catch (Exception ex) {
                Logger.printInfo(() -> "translate failure", ex);
                Utils.runOnMainThread(() -> callback.onTranslated(null, false, false, null));
            }
        });
    }

    @Nullable
    private static ProviderTranslation translateWithProvider(
            String provider, TrackInfo track, String source, String language,
            List<String> lines) {
        if (AiProviders.GOOGLE.equals(provider)) {
            return googleTranslation(track, source, language, lines);
        }
        if (!AiProviders.DEEPL.equals(provider) && !AiProviders.isAi(provider)) {
            return null;
        }
        return aiTranslation(provider, track, source, language, lines);
    }

    /** The cached Google translation when there is one, else a fresh lookup that is cached. */
    @Nullable
    private static ProviderTranslation googleTranslation(TrackInfo track, String source,
                                                         String language, List<String> lines) {
        List<String> translated = LyricsCache.getTranslation(track, source, language, lines);
        if (hasTranslation(translated, lines)) {
            return new ProviderTranslation(sanitizeTranslation(translated), true, false, null);
        }

        translated = translateOnline(lines, language);
        if (!hasTranslation(translated, lines)) {
            return null;
        }
        translated = sanitizeTranslation(translated);
        LyricsCache.putTranslation(track, source, language, lines, translated);
        return new ProviderTranslation(translated, true, false, null);
    }

    /** The cached answer of a DeepL or AI provider when there is one, else a fresh request. */
    @Nullable
    private static ProviderTranslation aiTranslation(String provider, TrackInfo track, String source,
                                                     String language, List<String> lines) {
        final boolean deepl = AiProviders.DEEPL.equals(provider);

        List<String> cached = LyricsCache.getTranslationAI(
                track, source, language, lines, provider);
        if (hasTranslation(cached, lines)) {
            // The cache key includes the model that produced it; DeepL has no model.
            return new ProviderTranslation(sanitizeTranslation(cached), false, true,
                    deepl ? "DeepL" : Settings.LYRICS_AI_MODEL.get());
        }

        AiProviders.Result ai = deepl
                ? deepLTranslate(lines, language)
                : AiProviders.mapLines(provider, OpenAIClient.renderPrompt(
                Settings.LYRICS_AI_TRANSLATION_PROMPT.get(), "translation",
                language, track.title(), track.artist(), lines), lines);
        if (!hasUsableAnswer(ai, lines)) {
            return null;
        }

        List<String> translated = sanitizeTranslation(ai.lines());
        LyricsCache.putTranslationAI(track, source, language, lines, translated, provider);
        return new ProviderTranslation(translated, false, true, ai.model());
    }

    private static boolean hasUsableAnswer(@Nullable AiProviders.Result ai, List<String> lines) {
        return ai != null && ai.lines() != null && !ai.lines().isEmpty()
                && hasTranslation(ai.lines(), lines);
    }

    private static List<String> sanitizeTranslation(List<String> lines) {
        List<String> clean = new ArrayList<>(lines.size());
        for (String line : lines) {
            clean.add(line == null ? "" : line);
        }
        return clean;
    }

    /** A translation is usable if at least one line differs from the source. */
    private static boolean hasTranslation(@Nullable List<String> translated,
                                          List<String> source) {
        if (translated == null || translated.size() != source.size()) {
            return false;
        }
        for (int i = 0; i < translated.size(); i++) {
            String text = translated.get(i);
            if (text != null) {
                text = text.trim();
                if (!text.isEmpty() && !text.equals(source.get(i).trim())) {
                    return true;
                }
            }
        }
        return false;
    }

    @Nullable
    private static AiProviders.Result deepLTranslate(List<String> lines, String language) {
        String apiKey = Settings.LYRICS_DEEPL_API_KEY.get();
        if (apiKey.isEmpty()) {
            return null;
        }
        List<String> translated = DeepLClient.translate(lines, language, apiKey);
        return translated == null ? null : new AiProviders.Result(translated, "DeepL");
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

    public interface Callback {
        void onTranslated(@Nullable List<String> translatedLines,
                          boolean fromGoogle, boolean fromAI, @Nullable String aiModel);
    }

    private record ProviderTranslation(List<String> lines, boolean fromGoogle,
                                       boolean fromAI, @Nullable String model) {
    }
}
