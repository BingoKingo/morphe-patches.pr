/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/2269
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.lyrics;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.translation.TextTranslator;

/**
 * Translates lyrics line by line into the device language.
 */
public final class LyricsTranslator {

    public interface Callback {
        /**
         * Called on the main thread with one translated line per original line,
         * or {@code null} if the translation failed.
         */
        void onTranslated(@Nullable List<String> translatedLines, boolean fromGoogle);
    }

    /** Separate from the lyrics executor, so a translation never delays a lyrics lookup. */
    private static final ExecutorService executor = Executors.newSingleThreadExecutor();

    private LyricsTranslator() {
    }

    public static String deviceLanguage() {
        return Locale.getDefault().getLanguage();
    }

    @Nullable
    private static List<String> embeddedTranslation(Lyrics lyrics, String target, int lineCount) {
        final Map<String, List<LyricsLine>> byLang = lyrics.translations();
        if (byLang == null || byLang.isEmpty()) {
            return null;
        }
        final String targetLang = primarySubtag(target);
        for (Map.Entry<String, List<LyricsLine>> entry : byLang.entrySet()) {
            if (!primarySubtag(entry.getKey()).equals(targetLang)) {
                continue;
            }
            final List<LyricsLine> lines = entry.getValue();
            if (lines == null || lines.size() != lineCount || !LyricsMerge.hasText(lines)) {
                continue;
            }
            final List<String> out = new ArrayList<>(lines.size());
            for (LyricsLine line : lines) {
                final String text = line.text();
                out.add(text == null ? "" : text);
            }
            return out;
        }
        return null;
    }

    /** Returns the primary language subtag of a BCP-47 tag, lower-cased (e.g. {@code zh-Hans} -> {@code zh}). */
    private static String primarySubtag(String lang) {
        if (lang == null) {
            return "";
        }
        final int idx = lang.indexOf('-');
        return (idx >= 0 ? lang.substring(0, idx) : lang).toLowerCase(Locale.ROOT);
    }

    /**
     * Translates the lyrics of a track, using the cache when possible.
     */
    public static void translate(TrackInfo track, Lyrics lyrics, String source, Callback callback) {
        Utils.verifyOnMainThread();

        List<String> lines = new ArrayList<>(lyrics.lines().size());
        for (LyricsLine line : lyrics.lines()) {
            lines.add(line.text());
        }

        final String language = deviceLanguage();

        final List<String> embedded = embeddedTranslation(lyrics, language, lines.size());
        if (embedded != null) {
            // The provider already ships this translation; do not attribute it to Google.
            Utils.runOnMainThread(() -> callback.onTranslated(embedded, false));
            return;
        }

        executor.execute(() -> {
            List<String> translated = LyricsCache.getTranslation(track, source, language, lines.size());
            if (translated == null) {
                translated = translateOnline(lines, language);
                if (translated != null) {
                    LyricsCache.putTranslation(track, source, language, translated);
                }
            }

            final List<String> result = translated;
            Utils.runOnMainThread(() -> callback.onTranslated(result, result != null));
        });
    }

    /**
     * @return One line per input line, or {@code null} if any batch failed or came
     * back with a different number of lines than it was given.
     */
    @Nullable
    private static List<String> translateOnline(List<String> lines, String language) {
        return LyricsMerge.mapLinesOnline(
                lines, b -> {
                    try {
                        return TextTranslator.translate(b, language);
                    } catch (Exception ex) {
                        throw new RuntimeException(ex);
                    }
                }, "translation", "translate");
    }
}
