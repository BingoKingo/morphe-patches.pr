/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.lyrics.romanizers;

import androidx.annotation.Nullable;

import java.util.List;

/**
 * Decides which script a line is written in, since a romanizer is only correct for one of them
 * and the wrong one reads as a translation of something else. Lines are judged on their own:
 * a song mixes scripts, which is what a verdict for the whole song gets wrong.
 *
 * <p>The counters are indexed by {@link Script}, so a new script only needs a line in {@link #classify}.
 */
public final class ScriptDetector {

    public enum Script {
        /** Han with too little kana to be Japanese, which is how Chinese reads. */
        HAN,
        JAPANESE,
        KOREAN,
        GREEK,
        /** Latin script, digits or punctuation only: nothing here needs a romanizer. */
        OTHER
    }

    private static final Script[] SCRIPTS = Script.values();

    /** Kana below this, in a line of Han characters, is a loanword rather than Japanese. */
    private static final int MIN_KANA = 3;
    private static final int KANA_PER_HAN = 6;

    private ScriptDetector() {
    }

    @Nullable
    public static Script detect(String line) {
        if (line == null) return null;
        return classify(count(line));
    }

    public static Script detect(List<String> lines) {
        int[] total = new int[SCRIPTS.length];
        for (String line : lines) {
            if (line == null) continue;
            add(total, count(line));
        }
        return classify(total);
    }

    private static int[] count(CharSequence line) {
        int[] found = new int[SCRIPTS.length];
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (isHan(c)) {
                found[Script.HAN.ordinal()]++;
            } else if (isKana(c)) {
                found[Script.JAPANESE.ordinal()]++;
            } else if (c >= 0xAC00 && c <= 0xD7A3) {
                found[Script.KOREAN.ordinal()]++;
            } else if (isGreek(c)) {
                found[Script.GREEK.ordinal()]++;
            }
        }
        return found;
    }

    private static void add(int[] total, int[] found) {
        for (int i = 0; i < found.length; i++) {
            total[i] += found[i];
        }
    }

    private static Script classify(int[] found) {
        int han = found[Script.HAN.ordinal()];
        int kana = found[Script.JAPANESE.ordinal()];
        int hangul = found[Script.KOREAN.ordinal()];
        int greek = found[Script.GREEK.ordinal()];

        if (hangul > 0 && hangul * 2 >= han + kana) return Script.KOREAN;
        // Kana with no Han at all is Japanese: nothing else that uses kana has none.
        if (kana > 0 && (han == 0 || (kana >= MIN_KANA && kana * KANA_PER_HAN >= han))) {
            return Script.JAPANESE;
        }
        if (han > 0) return Script.HAN;
        return greek > 0 ? Script.GREEK : Script.OTHER;
    }

    /** The blocks a modern lyric line is written with. */
    static boolean isHan(char c) {
        return (c >= 0x4E00 && c <= 0x9FFF)
                || (c >= 0x3400 && c <= 0x4DBF)
                || (c >= 0xF900 && c <= 0xFAFF);
    }

    static boolean isKana(char c) {
        return (c >= 0x3040 && c <= 0x309F)
                || (c >= 0x30A0 && c <= 0x30FF)
                || (c >= 0x31F0 && c <= 0x31FF);
    }

    static boolean isGreek(char c) {
        return (c >= 0x0370 && c <= 0x03FF) || (c >= 0x1F00 && c <= 0x1FFF);
    }
}