/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/2625
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.lyrics.parsers;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import app.morphe.extension.music.patches.lyrics.TextTrim;
import app.morphe.extension.music.patches.lyrics.model.Lyrics;
import app.morphe.extension.music.patches.lyrics.model.LyricsLine;
import app.morphe.extension.music.patches.lyrics.model.Word;
import app.morphe.extension.shared.Logger;

public final class LyricsFileParser {

    private static final String[] CREDIT_META_KEYS = {"title", "artist", "album"};

    private LyricsFileParser() {
    }

    @Nullable
    public static Lyrics parse(@Nullable String yaml, String providerName) {
        return parse(yaml, providerName, null);
    }

    @Nullable
    public static Lyrics parse(@Nullable String yaml, String providerName,
                               @Nullable String sourceUrl) {
        if (yaml == null || yaml.isEmpty()) {
            return null;
        }

        List<String> lines = new ArrayList<>();
        for (String raw : yaml.split("\\r?\\n")) {
            if (!raw.trim().isEmpty()) {
                lines.add(raw.replace("\t", "    "));
            }
        }
        if (lines.isEmpty()) {
            return null;
        }

        Cursor cursor = new Cursor();
        Object root = readValue(lines, cursor, 0);
        if (!(root instanceof Map<?, ?> top)) {
            return null;
        }

        // FIXME: reject files whose "version" is not exactly 1.0 once all files migrate

        List<String> creditLines = new ArrayList<>();
        boolean instrumental = false;
        Object metaObject = top.get("metadata");
        if (metaObject instanceof Map<?, ?> metadata) {
            instrumental = isInstrumental(metadata.get("instrumental"));
            creditLines.addAll(creditLines(metadata));
        }

        List<LyricsLine> parsedLines = parsedLines(top);
        List<String> creditLinesOut = creditLines.isEmpty() ? null : creditLines;

        if (instrumental) {
            return parsedLines.isEmpty()
                    ? Lyrics.NOT_FOUND
                    : fileLyrics(parsedLines, providerName, true, creditLinesOut, yaml, sourceUrl);
        }

        if (isSynced(parsedLines)) {
            return fileLyrics(parsedLines, providerName, true, creditLinesOut, yaml, sourceUrl);
        }

        return plainLyrics(top, providerName, creditLinesOut, yaml, sourceUrl);
    }

    /** Whether the metadata marks the track as having no lyrics to sing. */
    private static boolean isInstrumental(@Nullable Object value) {
        if (value instanceof Boolean) {
            return (Boolean) value;
        }
        if (value instanceof String) {
            return Boolean.parseBoolean((String) value);
        }
        return false;
    }

    /** The "title: …", "artist: …" and "album: …" lines the file credits. */
    private static List<String> creditLines(Map<?, ?> metadata) {
        List<String> creditLines = new ArrayList<>();
        for (String key : CREDIT_META_KEYS) {
            Object value = metadata.get(key);
            if (value instanceof String && !((String) value).isEmpty()) {
                creditLines.add(key + ": " + value);
            }
        }
        return creditLines;
    }

    /** The timed lines of a file, in file order, skipping entries that are not usable lines. */
    private static List<LyricsLine> parsedLines(Map<?, ?> top) {
        List<LyricsLine> parsedLines = new ArrayList<>();
        Object linesObject = top.get("lines");
        if (linesObject instanceof List) {
            for (Object item : (List<?>) linesObject) {
                LyricsLine line = toLine(item);
                if (line != null) {
                    parsedLines.add(line);
                }
            }
        }
        return parsedLines;
    }

    private static boolean isSynced(List<LyricsLine> parsedLines) {
        return parsedLines.stream().anyMatch(line -> line.startTimeMs() != LyricsLine.NO_TIME);
    }

    /** A file without line timings may still carry a plain-text block. */
    @Nullable
    private static Lyrics plainLyrics(Map<?, ?> top, String providerName,
                                      @Nullable List<String> creditLines, String yaml,
                                      @Nullable String sourceUrl) {
        Object plainObject = top.get("plain");
        if (plainObject instanceof String) {
            List<LyricsLine> plain = LRCParser.parsePlain((String) plainObject);
            if (!plain.isEmpty()) {
                return fileLyrics(plain, providerName, false, creditLines, yaml, sourceUrl);
            }
        }
        return Lyrics.NOT_FOUND;
    }

    private static Lyrics fileLyrics(List<LyricsLine> lines, String providerName, boolean synced,
                                     @Nullable List<String> creditLines, String yaml,
                                     @Nullable String sourceUrl) {
        return new Lyrics(lines, providerName, synced, null, null, null,
                creditLines, yaml, "lyricsfile.yaml", sourceUrl);
    }

    @Nullable
    private static LyricsLine toLine(Object item) {
        if (!(item instanceof Map<?, ?> map)) {
            return null;
        }

        List<Word> words = words(map.get("words"));
        String text = textOf(map.get("text"), words);
        if (words == null) {
            // Only the timings are given up: the text is already built from the words.
            words = List.of();
        }

        LRCParser.inferMissingWordEnds(words, 800);

        return new LyricsLine(asLong(map.get("start_ms")), text, words);
    }

    /**
     * The line's words, or null when any of them has no start of its own: such a word has nothing
     * to be lit by, and the ends its neighbours lend it would be counted off a number that is not
     * a time. The line then keeps its own timing and is sung in one go.
     */
    @Nullable
    private static List<Word> words(@Nullable Object wordsObject) {
        List<Word> words = new ArrayList<>();
        if (!(wordsObject instanceof List)) {
            return words;
        }
        for (Object wordObject : (List<?>) wordsObject) {
            if (!(wordObject instanceof Map<?, ?> wordMap)) {
                continue;
            }
            long start = asLong(wordMap.get("start_ms"));
            if (start < 0) {
                return null;
            }
            Object wordText = wordMap.get("text");
            String wordString = wordText instanceof String ? (String) wordText : "";
            words.add(new Word(start, asLong(wordMap.get("end_ms")), wordString));
        }
        return words;
    }

    /** The line's own text, or the words joined by spaces when it has none. */
    private static String textOf(@Nullable Object textObject, List<Word> words) {
        String text = textObject instanceof String ? (String) textObject : "";
        if (!text.isEmpty()) {
            return text;
        }
        StringBuilder builder = new StringBuilder();
        for (Word word : words) {
            String wordText = word.text();
            if (wordText.isEmpty()) {
                continue;
            }
            //noinspection SizeReplaceableByIsEmpty
            if (builder.length() > 0) {
                builder.append(' ');
            }
            builder.append(wordText);
        }
        return builder.toString();
    }

    private static long asLong(Object value) {
        if (value instanceof Long) {
            return (Long) value;
        }
        if (value instanceof Integer) {
            return (Integer) value;
        }
        if (value instanceof String) {
            try {
                return Long.parseLong(((String) value).trim());
            } catch (NumberFormatException ex) {
                Logger.printDebug(() -> "Could not parse long value in LyricsFileParser", ex);
                return LyricsLine.NO_TIME;
            }
        }
        return LyricsLine.NO_TIME;
    }

    private static Object readValue(List<String> lines, Cursor cursor, int indent) {
        if (cursor.index >= lines.size()) {
            return new LinkedHashMap<>();
        }
        if (isSequenceItem(lines.get(cursor.index), indent)) {
            return readSequence(lines, cursor, indent);
        }
        return readMapping(lines, cursor, indent);
    }

    private static boolean isSequenceItem(String line, int indent) {
        return indentOf(line) == indent && isSequenceLine(line);
    }

    private static boolean isSequenceLine(String line) {
        return line.trim().startsWith("- ");
    }

    private static Object readSequence(List<String> lines, Cursor cursor, int indent) {
        List<Object> sequence = new ArrayList<>();
        while (cursor.index < lines.size()) {
            String line = lines.get(cursor.index);
            if (indentOf(line) != indent || !isSequenceLine(line)) {
                break;
            }
            String rest = line.trim().substring(2).trim();
            if (rest.isEmpty()) {
                cursor.index++;
                if (cursor.index >= lines.size()) {
                    break;
                }
                sequence.add(readValue(lines, cursor, indent + 2));
            } else {
                lines.set(cursor.index, TextTrim.spaces(indent + 2) + rest);
                sequence.add(readMapping(lines, cursor, indent + 2));
            }
        }
        return sequence;
    }

    private static Object readMapping(List<String> lines, Cursor cursor, int indent) {
        Map<String, Object> map = new LinkedHashMap<>();
        while (cursor.index < lines.size()) {
            String line = lines.get(cursor.index);
            if (indentOf(line) != indent) {
                break;
            }
            int colon = line.indexOf(':');
            if (colon < 0) {
                cursor.index++;
                continue;
            }
            String key = line.substring(indent, colon).trim();
            String value = line.substring(colon + 1).trim();
            cursor.index++;

            map.put(key, readMappingValue(lines, cursor, indent, value));
        }
        return map;
    }

    /** What a mapping key holds: a nested block, a block scalar, or a plain value. */
    private static Object readMappingValue(List<String> lines, Cursor cursor, int indent,
                                           String value) {
        if (value.isEmpty()) {
            if (cursor.index < lines.size() && indentOf(lines.get(cursor.index)) > indent) {
                return readValue(lines, cursor, indentOf(lines.get(cursor.index)));
            }
            return "";
        }
        if (isBlockScalar(value)) {
            return readBlockScalar(lines, cursor, indent);
        }
        return unquote(value);
    }

    private static boolean isBlockScalar(String value) {
        return value.equals("|") || value.equals(">") || value.startsWith("|-")
                || value.startsWith(">-") || value.startsWith("|+") || value.startsWith(">+");
    }

    private static String readBlockScalar(List<String> lines, Cursor cursor, int indent) {
        if (cursor.index >= lines.size()) {
            return "";
        }
        // The first line of the block sets the indentation stripped from all of them.
        int base = indentOf(lines.get(cursor.index));
        if (base <= indent) {
            return "";
        }

        StringBuilder builder = new StringBuilder();
        while (cursor.index < lines.size()) {
            String line = lines.get(cursor.index);
            if (indentOf(line) <= indent) {
                break;
            }
            //noinspection SizeReplaceableByIsEmpty
            if (builder.length() > 0) {
                builder.append('\n');
            }
            int strip = Math.min(base, line.length());
            builder.append(line.substring(strip));
            cursor.index++;
        }
        return builder.toString();
    }

    private static String unquote(String value) {
        String string = value.trim();
        if (isSingleQuoted(string)) {
            return string.substring(1, string.length() - 1).replace("''", "'");
        }
        if (isDoubleQuoted(string)) {
            return string.substring(1, string.length() - 1)
                    .replace("\\n", "\n")
                    .replace("\\t", "\t")
                    .replace("\\\"", "\"")
                    .replace("\\\\", "\\");
        }
        return string;
    }

    private static boolean isSingleQuoted(String string) {
        return string.length() >= 2 && string.startsWith("'") && string.endsWith("'");
    }

    private static boolean isDoubleQuoted(String string) {
        return string.length() >= 2 && string.startsWith("\"") && string.endsWith("\"");
    }

    private static int indentOf(String line) {
        int count = 0;
        while (count < line.length() && line.charAt(count) == ' ') {
            count++;
        }
        return count;
    }

    private static final class Cursor {
        int index;
    }
}
