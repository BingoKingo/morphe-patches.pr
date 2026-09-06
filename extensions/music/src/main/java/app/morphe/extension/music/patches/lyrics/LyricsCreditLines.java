/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/2269
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.lyrics;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import app.morphe.extension.music.patches.lyrics.requests.CharactersConverter;
import app.morphe.extension.music.settings.Settings;

public final class LyricsCreditLines {

    private static final int MAX_ARTIST_SONG_LINE_LENGTH = 80;

    private static final Pattern ARTIST_SONG_PATTERN =
            Pattern.compile("^[^\\-]+\\s*-\\s*[^\\-]+$");

    private LyricsCreditLines() {
    }

    public static List<LyricsLine> removeCreditLines(List<LyricsLine> lines, List<String> outCreditLines) {
        if (lines == null || lines.isEmpty()) {
            return lines;
        }

        List<LyricsLine> result = new ArrayList<>(lines.size());
        for (LyricsLine line : lines) {
            String text = line.text().trim();
            if (isCreditLine(text)) {
                if (!text.isEmpty()) {
                    outCreditLines.add(text);
                }
            } else {
                result.add(line);
            }
        }
        return result;
    }

    public static boolean isCreditLine(String text) {
        if (text == null || text.isEmpty()) {
            return true;
        }
        if (text.contains("  ")) {
            return false;
        }
        if (text.length() <= MAX_ARTIST_SONG_LINE_LENGTH
                && ARTIST_SONG_PATTERN.matcher(text.trim()).matches()) {
            return true;
        }
        if (countChar(text, '/') > 3) {
            return true;
        }
        String setting = Settings.LYRICS_CREDIT_LINE_REGEX.get();
        if (setting.isBlank()) {
            return false;
        }

        String normalized = CharactersConverter.normalize(text)
                .replace('|', ':')
                .replace('－', ':')
                .replace('—', ':')
                .replace('·', ':')
                .replace('~', ':')
                .replace('@', ':')
                .replace('/', ':')
                .replace('\\', ':')
                .replace('&', ':')
                .replaceAll("\\s+:", ":");

        int sepIdx = normalized.indexOf(':');
        if (sepIdx >= 0) {
            if (normalized.substring(sepIdx + 1).trim().isEmpty()) {
                return false;
            }
        }
        String beforeSep = sepIdx >= 0 ? normalized.substring(0, sepIdx) : normalized;

        String[] markers = setting.split(",");
        for (String marker : markers) {
            marker = marker.trim();
            if (marker.isEmpty()) {
                continue;
            }

            Set<String> variants = new LinkedHashSet<>(4);
            variants.add(marker);
            variants.add(CharactersConverter.toTraditional(marker));
            variants.add(CharactersConverter.toSimplified(marker));
            variants.add(CharactersConverter.normalize(marker));

            for (String variant : variants) {
                if (beforeSep.startsWith(variant)) {
                    if (beforeSep.length() == variant.length()) {
                        return true; // exact match
                    }
                    if (sepIdx >= 0) {
                        // Only match if the next character is a word boundary (space,
                        // punctuation, etc.), not a letter/digit/CJK character.
                        // This prevents false positives like "词不达意:..." matching marker "词".
                        char next = beforeSep.charAt(variant.length());
                        if (!Character.isLetterOrDigit(next)) {
                            return true;
                        }
                    }
                }
                if (sepIdx >= 0) {
                    int idx = beforeSep.indexOf(" " + variant);
                    if (idx >= 0) {
                        int afterIdx = idx + 1 + variant.length();
                        if (afterIdx == beforeSep.length()) {
                            return true; // variant at end of beforeSep
                        }
                        char next = beforeSep.charAt(afterIdx);
                        if (!Character.isLetterOrDigit(next)) {
                            return true; // word boundary after variant
                        }
                    }
                }
            }
        }
        return false;
    }

    private static int countChar(String text, char c) {
        int count = 0;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == c) {
                count++;
            }
        }
        return count;
    }
}