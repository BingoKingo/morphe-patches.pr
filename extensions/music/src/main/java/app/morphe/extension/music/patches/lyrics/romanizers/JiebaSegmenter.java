/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.lyrics.romanizers;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.Arrays;

import app.morphe.extension.music.patches.lyrics.utils.CharactersConverter;
import app.morphe.extension.shared.Logger;

/** Most probable path through the jieba dict; unknown stretches stay single. */
public final class JiebaSegmenter {

    /** Injectable so the mapping can be tested without Android ICU. */
    public interface Normalizer {
        @NonNull
        String normalize(@NonNull String value);
    }

    /** Log of the sum of all frequencies in the pinned jieba dictionary. */
    private static final double LOG_TOTAL_FREQUENCY = Math.log(60_101_967);

    private JiebaSegmenter() {
    }

    /** {@return one group id per Han character and -1 elsewhere, or null when the dictionary is not loaded} */
    @Nullable
    public static int[] wordGroups(String line) {
        try (WordReadingTable table = JiebaSegmentStore.words()) {
            return table == null ? null : wordGroups(line, table);
        }
    }

    /** Normalizes Chinese text for the simplified jieba word table, preserving indexes. */
    @NonNull
    public static String forWordTable(@NonNull String text) {
        return forWordTable(text, CharactersConverter::toSimplified);
    }

    /**
     * Converts each character on its own and keeps the original when the conversion changes the
     * character count, so word boundaries still map to the original text.
     */
    @NonNull
    public static String forWordTable(@NonNull String text, @NonNull Normalizer normalizer) {
        StringBuilder out = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); ) {
            int codePoint = text.codePointAt(i);
            int width = Character.charCount(codePoint);
            if (width == 1) {
                String converted = normalizer.normalize(new String(Character.toChars(codePoint)));
                if (converted != null && converted.length() == 1) {
                    out.append(converted.charAt(0));
                    i += width;
                    continue;
                }
            }
            out.appendCodePoint(codePoint);
            i += width;
        }
        return out.toString();
    }

    @Nullable
    static int[] wordGroups(String line, WordReadingTable table) {
        return wordGroups(line, table, JiebaSegmenter::forWordTable);
    }

    @Nullable
    static int[] wordGroups(String line, Normalizer normalize) {
        try (WordReadingTable table = JiebaSegmentStore.words()) {
            return table == null ? null : wordGroups(line, table, normalize);
        }
    }

    /** Same segmentation with a custom lookup normalizer, kept for tests without Android ICU. */
    @Nullable
    static int[] wordGroups(String line, WordReadingTable table,
                            Normalizer normalize) {
        if (line == null || line.isEmpty() || table == null) {
            return null;
        }
        String lookup = normalize.normalize(line);
        final int n = line.length();
        final double[] best = new double[n + 1];
        final int[] next = new int[n + 1];
        best[n] = 0.0;
        for (int i = n - 1; i >= 0; i--) {
            // No word covers this character, so it counts as a word seen once.
            double score = best[i + 1] - LOG_TOTAL_FREQUENCY;
            int end = i + 1;
            final int max = Math.min(n, i + WordReadingTable.MAX_WORD_LENGTH);
            for (int j = i + 1; j <= max; j++) {
                final String frequency = table.reading(lookup.substring(i, j));
                if (frequency == null) continue;
                final double weight = frequency(frequency);
                if (weight <= 0.0) continue;
                // Every word costs the total, otherwise the path with the most words always wins.
                final double candidate = Math.log(weight) - LOG_TOTAL_FREQUENCY + best[j];
                if (candidate > score) {
                    score = candidate;
                    end = j;
                }
            }
            best[i] = score;
            next[i] = end;
        }

        final int[] groups = new int[n];
        Arrays.fill(groups, -1);
        int group = 0;
        for (int i = 0; i < n; i = next[i]) {
            boolean grouped = false;
            for (int j = i; j < next[i]; j++) {
                if (ScriptDetector.isHan(line.charAt(j))) {
                    groups[j] = group;
                    grouped = true;
                }
            }
            if (grouped) {
                group++;
            }
        }
        return groups;
    }

    private static double frequency(String value) {
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException ex) {
            Logger.printDebug(() -> "Jieba frequency is not a number: " + value, ex);
            return 0.0;
        }
    }
}
