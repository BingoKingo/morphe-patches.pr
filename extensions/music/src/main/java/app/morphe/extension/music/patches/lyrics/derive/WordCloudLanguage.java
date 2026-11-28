/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.lyrics.derive;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.Collection;
import java.util.List;
import java.util.Locale;

import app.morphe.extension.music.patches.lyrics.romanizers.ScriptDetector;
import app.morphe.extension.music.patches.lyrics.romanizers.StopwordsIsoStore;
import app.morphe.extension.shared.settings.BaseSettings;

/** Picks the ISO 639-1 language whose stopword table best fits the selected lyrics. */
public final class WordCloudLanguage {

    private WordCloudLanguage() {
    }

    @NonNull
    public static String detect(@Nullable Collection<String> lines) {
        if (lines == null || lines.isEmpty()) {
            return fallback();
        }
        String script = byScript(lines);
        if (script != null) {
            return script;
        }
        String scored = byStopwords(lines);
        return scored != null ? scored : fallback();
    }

    @Nullable
    private static String byScript(Collection<String> lines) {
        boolean kana = false;
        boolean han = false;
        for (String line : lines) {
            if (line == null) {
                continue;
            }
            kana |= ScriptDetector.containsKana(line);
            han |= ScriptDetector.contains(line, Character.UnicodeScript.HAN);
            if (ScriptDetector.contains(line, Character.UnicodeScript.HANGUL)) return "ko";
            if (ScriptDetector.contains(line, Character.UnicodeScript.GREEK)) return "el";
            if (ScriptDetector.contains(line, Character.UnicodeScript.HEBREW)) return "he";
            if (ScriptDetector.contains(line, Character.UnicodeScript.ARMENIAN)) return "hy";
            if (ScriptDetector.contains(line, Character.UnicodeScript.GEORGIAN)) return "ka";
            if (ScriptDetector.contains(line, Character.UnicodeScript.THAI)) return "th";
            if (ScriptDetector.contains(line, Character.UnicodeScript.LAO)) return "lo";
            if (ScriptDetector.contains(line, Character.UnicodeScript.KHMER)) return "km";
            if (ScriptDetector.contains(line, Character.UnicodeScript.MYANMAR)) return "my";
        }
        if (kana) return "ja";
        if (han) return "zh";
        return null;
    }

    @Nullable
    private static String byStopwords(Collection<String> lines) {
        if (!StopwordsIsoStore.isReady()) {
            return null;
        }
        String best = null;
        int bestScore = 0;
        for (String language : StopwordsIsoStore.languages()) {
            java.util.Set<String> words = StopwordsIsoStore.forLanguage(language);
            int score = 0;
            for (String line : lines) {
                if (line == null) continue;
                for (String token : line.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}]+")) {
                    if (!token.isEmpty() && words.contains(token)) {
                        score++;
                    }
                }
            }
            if (score > bestScore) {
                bestScore = score;
                best = language;
            }
        }
        return best;
    }

    @NonNull
    private static String fallback() {
        try {
            String code = BaseSettings.MORPHE_LANGUAGE.get().getLocale().getLanguage();
            return code == null || code.isEmpty() ? "en" : code.toLowerCase(Locale.ROOT);
        } catch (Throwable ex) {
            return "en";
        }
    }
}
