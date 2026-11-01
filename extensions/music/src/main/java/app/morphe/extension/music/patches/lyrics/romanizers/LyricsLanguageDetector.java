/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.lyrics.romanizers;

import androidx.annotation.Nullable;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import app.morphe.extension.music.patches.lyrics.model.LyricsLine;
import app.morphe.extension.music.patches.lyrics.requests.LyricsRequests;

/**
 * Skips the translation and romanization requests when the lyrics are already written in the
 * language that was asked for.
 *
 * <p>Only the script of each line is read, so nothing is downloaded and no word list is needed.
 * The one limit is that every language written in Latin letters reads as the same script: a
 * Spanish song asked for in English still gets a request, which is what it got before.
 */
public final class LyricsLanguageDetector {

    /** Lines in another script below this are an ad-lib or a borrowed word. */
    private static final int MIN_FOREIGN_LINES = 2;
    /** Characters in another script below this are not something worth a request. */
    private static final int MIN_FOREIGN_LETTERS = 4;
    /** A line shorter than this is an ad-lib, not the language the song is written in. */
    private static final int MIN_LINE_LETTERS = 4;
    /** A line shorter than this says more about punctuation than about its language. */
    private static final int MIN_LETTERS = 2;
    /** Han below this is too little to call the line Chinese. */
    private static final int MIN_HAN = 2;
    /** Kana below this, beside Han, is a loanword rather than a Japanese line. */
    private static final int MIN_KANA = 2;

    private LyricsLanguageDetector() {
    }

    /** Whether translating into {@code language} has anything to do. */
    public static boolean needsTranslation(List<LyricsLine> lines, @Nullable String language) {
        final Category[] allowed = scriptsFor(language, translationScripts());
        return allowed != null && needsRequest(lines, allowed);
    }

    /** Whether romanizing has anything to do: nothing once the lyrics are Latin already. */
    public static boolean needsRomanization(List<LyricsLine> lines, @Nullable String language) {
        final Category[] allowed = scriptsFor(language, romanizationScripts());
        return allowed != null && needsRequest(lines, allowed);
    }

    private static boolean needsRequest(List<LyricsLine> lines, Category[] allowed) {
        int total = 0;
        int foreign = 0;
        int foreignLetters = 0;
        for (LyricsLine line : lines) {
            final int[] found = count(line.text());
            if (letters(found) < MIN_LETTERS) {
                continue;
            }
            total++;
            if (!matchesAny(found, allowed)) {
                foreign++;
                foreignLetters += letters(found);
            }
        }

        if (total == 0 || foreign == 0) {
            return false;
        }
        // A word or a short ad-lib beside the lines the reader reads is not worth a request.
        if (foreignLetters < MIN_FOREIGN_LETTERS) {
            return false;
        }
        if (foreign >= MIN_FOREIGN_LINES) {
            return true;
        }
        // One line in a script nobody reads is the whole song; a short line of an ad-lib is not.
        return foreign == total && foreignLetters >= MIN_LINE_LETTERS;
    }

    @Nullable
    private static Category[] scriptsFor(@Nullable String language, Map<String, Category[]> table) {
        if (language == null) {
            return null;
        }
        final String primary = LyricsRequests.primarySubtag(language);
        return primary.isEmpty() ? null : table.get(primary);
    }

    private static Map<String, Category[]> translationScripts() {
        final Map<String, Category[]> table = new HashMap<>();
        add(table, new Category[]{Category.HAN}, "zh");
        add(table, new Category[]{Category.JAPANESE}, "ja");
        add(table, new Category[]{Category.KOREAN}, "ko");
        add(table, new Category[]{Category.CYRILLIC},
                "ru", "uk", "bg", "be", "kk", "ky", "mk", "mn", "sr");
        add(table, new Category[]{Category.ARABIC}, "ar", "fa", "ur", "ps", "ckb");
        add(table, new Category[]{Category.HEBREW}, "he", "yi");
        add(table, new Category[]{Category.GREEK}, "el");
        add(table, new Category[]{Category.ARMENIAN}, "hy");
        add(table, new Category[]{Category.GEORGIAN}, "ka");
        add(table, new Category[]{Category.ETHIOPIC}, "am", "ti");
        add(table, new Category[]{Category.DEVANAGARI}, "hi", "mr", "ne", "or");
        add(table, new Category[]{Category.THAI}, "th");
        add(table, new Category[]{Category.LATIN},
                "en", "es", "fr", "de", "it", "pt", "nl", "sv", "da", "nb", "fi",
                "pl", "cs", "sk", "sl", "hr", "ro", "tr", "vi", "id", "ms", "tl",
                "sw", "af", "ca", "et", "is", "lt", "lv", "sq", "bs", "eu", "ga",
                "gl", "zu", "az", "uz", "hu");
        return table;
    }

    /** Every romanization target writes its reading in Latin, so one line covers them all. */
    private static Map<String, Category[]> romanizationScripts() {
        final Map<String, Category[]> table = new HashMap<>();
        add(table, new Category[]{Category.LATIN},
                "ja", "zh", "yue", "ko", "ru", "uk", "bg", "be", "mk", "sr", "el",
                "he", "ar", "fa", "ur", "hi", "bn", "as", "gu", "kn", "ml", "mr",
                "ne", "or", "pa", "sa", "si", "ta", "te", "th", "kk", "ky", "mn",
                "ka", "hy");
        return table;
    }

    private static void add(Map<String, Category[]> table, Category[] scripts, String... codes) {
        for (String code : codes) {
            table.put(code, scripts);
        }
    }

    private static boolean matchesAny(int[] found, Category[] allowed) {
        for (Category category : allowed) {
            if (matches(found, category)) {
                return true;
            }
        }
        return false;
    }

    /** Counts one line by script. Characters in no listed script are not counted. */
    private static int[] count(@Nullable String line) {
        final int[] found = new int[Category.values().length];
        if (line == null) {
            return found;
        }
        for (int i = 0; i < line.length(); ) {
            final int cp = Character.codePointAt(line, i);
            i += Character.charCount(cp);
            if (cp > Character.MAX_VALUE) {
                continue;
            }
            final char c = (char) cp;
            if (ScriptDetector.isHan(c)) {
                found[Category.HAN.ordinal()]++;
            } else if (ScriptDetector.isKana(c)) {
                found[Category.JAPANESE.ordinal()]++;
            } else if (ScriptDetector.isAnyHangul(c)) {
                found[Category.KOREAN.ordinal()]++;
            } else if (ScriptDetector.isCyrillic(c)) {
                found[Category.CYRILLIC.ordinal()]++;
            } else if (ScriptDetector.isGreek(c)) {
                found[Category.GREEK.ordinal()]++;
            } else if (ScriptDetector.isHebrew(c)) {
                found[Category.HEBREW.ordinal()]++;
            } else if (ScriptDetector.isArabic(c)) {
                found[Category.ARABIC.ordinal()]++;
            } else if (ScriptDetector.isDevanagari(c)) {
                found[Category.DEVANAGARI.ordinal()]++;
            } else if (ScriptDetector.isThai(c)) {
                found[Category.THAI.ordinal()]++;
            } else if (ScriptDetector.isLatin(c)) {
                found[Category.LATIN.ordinal()]++;
            } else if (ScriptDetector.isGeorgian(c)) {
                found[Category.GEORGIAN.ordinal()]++;
            } else if (ScriptDetector.isArmenian(c)) {
                found[Category.ARMENIAN.ordinal()]++;
            } else if (ScriptDetector.isEthiopic(c)) {
                found[Category.ETHIOPIC.ordinal()]++;
            }
        }
        return found;
    }

    private static int letters(int[] found) {
        int total = 0;
        for (int count : found) {
            total += count;
        }
        return total;
    }

    private static boolean matches(int[] found, Category category) {
        final int han = found[Category.HAN.ordinal()];
        final int kana = found[Category.JAPANESE.ordinal()];
        final int hangul = found[Category.KOREAN.ordinal()];
        switch (category) {
            case HAN:
                return han >= MIN_HAN && kana * MIN_KANA < han;
            case JAPANESE:
                return kana >= MIN_KANA && kana * MIN_KANA >= han;
            case KOREAN:
                return hangul >= MIN_HAN && hangul >= han + kana;
            case LATIN:
                return found[Category.LATIN.ordinal()] >= MIN_LETTERS
                        && letters(found) == found[Category.LATIN.ordinal()];
            default:
                return found[category.ordinal()] >= MIN_HAN;
        }
    }

    private enum Category {
        HAN, JAPANESE, KOREAN, CYRILLIC, GREEK, HEBREW, ARABIC,
        DEVANAGARI, THAI, LATIN, GEORGIAN, ARMENIAN, ETHIOPIC
    }
}
