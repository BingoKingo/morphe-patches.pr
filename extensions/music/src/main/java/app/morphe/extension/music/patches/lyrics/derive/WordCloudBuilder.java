/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.lyrics.derive;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

import app.morphe.extension.music.patches.lyrics.romanizers.CjkWordGroups;
import app.morphe.extension.music.patches.lyrics.romanizers.JapaneseDictionaryStore;
import app.morphe.extension.music.patches.lyrics.romanizers.KuromojiDictStore;
import app.morphe.extension.music.patches.lyrics.romanizers.ScriptDetector;
import app.morphe.extension.music.patches.lyrics.derive.ProperNounFilter;
import app.morphe.extension.shared.Logger;

/** Local, offline word-cloud builder. */
public final class WordCloudBuilder {

    /** Pixel coordinates; no Android types so it stays unit-testable. */
    public record PlacedWord(String word, float sizePx, float leftPx, float topPx) {
    }

    private static final Pattern PUNCTUATION = Pattern.compile("^[\\p{P}\\p{S}]+|[\\p{P}\\p{S}]+$");
    private static final Pattern NUMBERED_WEIGHTED = Pattern.compile(
            "^(?:Line\\s+)?(\\d{1,4})[.)\\]\\-]\\s*(.*?)\\s*\\|\\s*(\\d{1,2})\\s*$",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern NUMBERED_PLAIN = Pattern.compile(
            "^(?:Line\\s+)?(\\d{1,4})[.)\\]\\-]\\s*(.*)$", Pattern.CASE_INSENSITIVE);
    private static final float[] SIZE_LADDER = {11f, 13f, 15.5f, 18f, 22f};

    private WordCloudBuilder() {
    }

    /** Whether a local CJK dictionary is ready (no download triggered). */
    public static boolean hasLocalDictionary() {
        try {
            return KuromojiDictStore.isReady() || JapaneseDictionaryStore.isReady();
        } catch (Exception ex) {
            Logger.printDebug(() -> "Word cloud dictionary check failed", ex);
            return false;
        }
    }

    /** Prefers the local CJK dictionary, then locale-aware ICU/JDK word breaks. */
    @Nullable
    public static List<String> tokenize(String line) {
        return tokenize(line, Locale.ROOT);
    }

    @Nullable
    public static List<String> tokenize(String line, Locale locale) {
        if (line == null || line.trim().isEmpty()) {
            return null;
        }
        List<String> out = new ArrayList<>();
        int[] groups = CjkWordGroups.forLine(line);
        if (groups != null && groups.length == line.length()) {
            int start = 0;
            for (int i = 1; i <= groups.length; i++) {
                if (i == groups.length || groups[i] != groups[start]) {
                    String group = line.substring(start, i);
                    if (group.indexOf(' ') >= 0) {
                        for (String part : group.split("\\s+")) {
                            if (!part.isEmpty()) out.add(part);
                        }
                    } else {
                        out.add(group);
                    }
                    start = i;
                }
            }
        } else {
            java.text.BreakIterator breaks = java.text.BreakIterator.getWordInstance(
                    locale == null ? Locale.ROOT : locale);
            breaks.setText(line);
            List<String> pieces = new ArrayList<>();
            int start = breaks.first();
            for (int end = breaks.next(); end != java.text.BreakIterator.DONE;
                 start = end, end = breaks.next()) {
                if (end <= start) {
                    continue;
                }
                String piece = line.substring(start, end);
                if (piece.codePoints().allMatch(Character::isWhitespace)) {
                    continue;
                }
                int previous = pieces.size() - 1;
                if (isApostrophe(piece) && previous >= 0 && end < line.length()) {
                    pieces.set(previous, pieces.get(previous) + piece + line.substring(end, end + 1));
                    end++;
                } else if (piece.length() > 1 && isApostrophe(piece.substring(0, 1))
                        && previous >= 0) {
                    pieces.set(previous, pieces.get(previous) + piece);
                } else {
                    pieces.add(piece);
                }
            }
            // ICU owns word boundaries. Only when it returns one unbroken run of an unspaced
            // script do we fall back to character unigrams; otherwise its words are kept intact.
            if (pieces.size() == 1
                    && line.codePoints().noneMatch(Character::isWhitespace)
                    && pieces.get(0).codePoints().anyMatch(ScriptDetector::isUnspacedCodePoint)) {
                splitUnspaced(pieces.get(0), out);
            } else {
                out.addAll(pieces);
            }
        }
        return out.isEmpty() ? null : out;
    }

    /** Last resort for an unspaced run that ICU could not break at all. */
    private static void splitUnspaced(String piece, List<String> out) {
        StringBuilder run = new StringBuilder();
        for (int i = 0; i < piece.length(); ) {
            int cp = piece.codePointAt(i);
            int width = Character.charCount(cp);
            if (ScriptDetector.isUnspacedCodePoint(cp)) {
                if (run.length() > 0) {
                    out.add(run.toString());
                    run.setLength(0);
                }
                out.add(new String(Character.toChars(cp)));
            } else if (Character.isLetterOrDigit(cp)) {
                run.appendCodePoint(cp);
            } else if (run.length() > 0) {
                out.add(run.toString());
                run.setLength(0);
            }
            i += width;
        }
        if (run.length() > 0) {
            out.add(run.toString());
        }
    }

    private static boolean isApostrophe(String piece) {
        return "'".equals(piece) || "\u2019".equals(piece);
    }

    public static List<WeightedWord> build(List<String> lines, int maxWords,
                                            Collection<String> stopwords,
                                            @Nullable Collection<String> banned) {
        return build(lines, maxWords, stopwords, banned, (ProperNounFilter) null, null);
    }

    public static List<WeightedWord> build(List<String> lines, int maxWords,
                                            Collection<String> stopwords,
                                            @Nullable Collection<String> banned,
                                            @Nullable Collection<String> properNouns,
                                            @Nullable String language) {
        ProperNounFilter filter = properNouns == null
                ? null : new ProperNounFilter(properNouns, null);
        return build(lines, maxWords, stopwords, banned, filter, language);
    }

    public static List<WeightedWord> build(List<String> lines, int maxWords,
                                            Collection<String> stopwords,
                                            @Nullable Collection<String> banned,
                                            @Nullable ProperNounFilter filter,
                                            @Nullable String language) {
        if (lines == null || lines.isEmpty() || maxWords <= 0) {
            return List.of();
        }
        Locale locale = language == null || language.trim().isEmpty()
                ? Locale.ROOT : Locale.forLanguageTag(language);
        Map<String, Integer> counts = new HashMap<>();
        for (String line : lines) {
            List<String> tokens = tokenize(line, locale);
            if (tokens == null) continue;
            String lower = line.toLowerCase(Locale.ROOT);
            List<int[]> phraseRanges = phraseRanges(lower, filter);
            int cursor = 0;
            for (String raw : tokens) {
                String cleaned = clean(raw);
                int at = lower.indexOf(cleaned, cursor);
                if (at < 0) at = cursor;
                int tokenEnd = at + cleaned.length();
                cursor = Math.max(cursor, tokenEnd);
                if (cleaned.isEmpty() || tooShort(cleaned)
                        || WordCloudStopwords.isStopword(cleaned, stopwords)) {
                    continue;
                }
                if (banned != null && banned.contains(cleaned)) {
                    continue;
                }
                if (filter != null) {
                    if (filter.containsToken(cleaned) && excludedProper(cleaned, startsWithUpper(raw))) {
                        continue;
                    }
                    if (insidePhrase(phraseRanges, at, tokenEnd)
                            && phraseCapitalized(line, at, tokenEnd)) {
                        continue;
                    }
                }
                counts.merge(cleaned, 1, Integer::sum);
            }
        }
        if (counts.isEmpty()) {
            return List.of();
        }
        List<Map.Entry<String, Integer>> ranked = new ArrayList<>(counts.entrySet());
        ranked.sort(Comparator.<Map.Entry<String, Integer>>comparingInt(Map.Entry::getValue).reversed()
                .thenComparing(e -> e.getKey().length(), Comparator.reverseOrder())
                .thenComparing(Map.Entry::getKey));
        int n = Math.min(maxWords, ranked.size());
        List<WeightedWord> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            int weight = Math.max(1, 5 - (i * 4) / Math.max(1, n - 1));
            out.add(new WeightedWord(ranked.get(i).getKey(), weight));
        }
        return out;
    }

    @NonNull
    private static List<int[]> phraseRanges(String lowerLine, @Nullable ProperNounFilter filter) {
        if (filter == null) {
            return List.of();
        }
        java.util.regex.Matcher words = Pattern.compile("[\\p{L}\\p{N}]+").matcher(lowerLine);
        List<Integer> starts = new ArrayList<>();
        List<Integer> ends = new ArrayList<>();
        while (words.find()) {
            starts.add(words.start());
            ends.add(words.end());
        }
        List<int[]> ranges = new ArrayList<>();
        for (int i = 0; i < starts.size(); i++) {
            for (int len = 2; len <= 3 && i + len <= starts.size(); len++) {
                StringBuilder phrase = new StringBuilder();
                for (int j = i; j < i + len; j++) {
                    if (j > i) phrase.append(' ');
                    phrase.append(lowerLine, starts.get(j), ends.get(j));
                }
                if (filter.mightBePhrase(phrase.toString())) {
                    ranges.add(new int[]{starts.get(i), ends.get(i + len - 1)});
                }
            }
        }
        return ranges;
    }

    private static boolean insidePhrase(List<int[]> ranges, int start, int end) {
        for (int[] range : ranges) {
            if (start < range[1] && end > range[0]) return true;
        }
        return false;
    }

    private static boolean phraseCapitalized(String original, int start, int end) {
        boolean hasCase = false;
        for (int i = start; i < end && i < original.length(); i++) {
            char c = original.charAt(i);
            if (Character.isUpperCase(c)) return true;
            if (Character.isLowerCase(c)) hasCase = true;
        }
        return !hasCase && end - start >= 2;
    }

    @NonNull
    private static String clean(String token) {
        return PUNCTUATION.matcher(token).replaceAll("").toLowerCase(Locale.ROOT).trim();
    }

    private static boolean tooShort(String cleaned) {
        if (cleaned.length() >= 2) return false;
        return cleaned.codePoints().noneMatch(cp ->
                ScriptDetector.isCjkCodePoint(cp) || ScriptDetector.isUnspacedCodePoint(cp));
    }

    private static boolean startsWithUpper(String token) {
        for (int i = 0; i < token.length(); i++) {
            char c = token.charAt(i);
            if (Character.isLetter(c)) return Character.isUpperCase(c);
        }
        return false;
    }

    private static boolean excludedProper(String cleaned, boolean capitalized) {
        boolean hasCase = !cleaned.equals(cleaned.toUpperCase(Locale.ROOT));
        return hasCase ? capitalized : cleaned.length() >= 2;
    }

    /** Converts legacy dot-joined keyword caches back into weighted lines. */
    public static String normalizeCachedKeywords(@Nullable String cached) {
        if (cached == null || cached.indexOf('\n') >= 0 || cached.indexOf('|') >= 0) {
            return cached;
        }
        String[] parts = cached.split("\\s*·\\s*");
        if (parts.length <= 1) {
            return cached;
        }
        StringBuilder out = new StringBuilder();
        int count = 0;
        for (String part : parts) {
            String word = part.trim();
            if (word.isEmpty()) {
                continue;
            }
            count++;
            out.append(count).append(". ").append(word).append('|')
                    .append(Math.max(1, 5 - ((count - 1) * 4) / Math.max(1, parts.length - 1)))
                    .append('\n');
        }
        return out.toString();
    }

    /** Parses weighted AI keyword lines; missing weights fall back to rank. */
    @Nullable
    public static List<WeightedWord> parseWeightedKeywords(@Nullable String response, int maxItems) {
        if (response == null || maxItems <= 0) {
            return null;
        }
        String[] raw = response.split("\n", -1);
        List<WeightedWord> out = new ArrayList<>(maxItems);
        int fallbackRank = 0;
        for (String line : raw) {
            String t = line == null ? "" : line.trim();
            if (t.isEmpty()) continue;
            String word = t;
            int weight = -1;
            var m = NUMBERED_WEIGHTED.matcher(t);
            if (m.matches()) {
                word = m.group(2);
                try {
                    weight = Integer.parseInt(m.group(3));
                } catch (NumberFormatException ignored) {
                }
            } else {
                var plain = NUMBERED_PLAIN.matcher(t);
                if (plain.matches()) {
                    word = plain.group(2);
                } else {
                    var pipe = t.split("\\|", 2);
                    if (pipe.length == 2) {
                        word = pipe[0];
                        try {
                            weight = Integer.parseInt(pipe[1].trim());
                        } catch (NumberFormatException ignored) {
                        }
                    }
                }
            }
            word = word.replaceFirst("^[-*•]\\s*", "").trim();
            if (word.isEmpty() || "null".equalsIgnoreCase(word)
                    || OpenAIClient.isSkipToken(word)) continue;
            if (weight < 1 || weight > 5) {
                weight = Math.max(1, 5 - (fallbackRank * 4) / Math.max(1, maxItems - 1));
            }
            out.add(new WeightedWord(word, weight));
            fallbackRank++;
            if (out.size() >= maxItems) break;
        }
        out.sort(Comparator.comparingInt(WeightedWord::weight).reversed());
        return out.isEmpty() ? null : out;
    }

    /** Float math keeps the layout unit-testable without Android; long words shrink before being skipped. */
    public static List<PlacedWord> layoutCloud(List<WeightedWord> words,
                                               float x, float y, float width, float height,
                                               float scale) {
        List<PlacedWord> out = new ArrayList<>();
        if (words == null || words.isEmpty() || width <= 0 || height <= 0) {
            return out;
        }
        List<float[]> occupied = new ArrayList<>();
        float cx = x + width / 2f;
        float cy = y + height / 2f;
        // Spiral density must follow the destination rect, not the canvas scale, so the same
        // layout fills both the 900px preview and the 1440px export identically.
        float radiusStep = Math.max(0.8f, Math.min(width, height) / 120f);
        float gap = 3f * scale;
        for (WeightedWord word : words) {
            if (word == null || word.word() == null || word.word().isEmpty()) continue;
            float size = SIZE_LADDER[Math.max(0, Math.min(4, word.weight() - 1))] * scale;
            float minSize = Math.max(7f, 8f * scale);
            PlacedWord placed = null;
            for (int shrink = 0; shrink < 6 && placed == null; shrink++) {
                float w = measureWord(word.word(), size);
                float h = size * 1.25f;
                for (int step = 0; step < 600; step++) {
                    double angle = step * 0.35;
                    double radius = step * radiusStep;
                    float left = (float) (cx + Math.cos(angle) * radius - w / 2f);
                    float top = (float) (cy + Math.sin(angle) * radius - h / 2f);
                    if (left < x || top < y || left + w > x + width || top + h > y + height) {
                        continue;
                    }
                    boolean collides = false;
                    for (float[] r : occupied) {
                        if (left - gap < r[2] && left + w + gap > r[0]
                                && top - gap < r[3] && top + h + gap > r[1]) {
                            collides = true;
                            break;
                        }
                    }
                    if (!collides) {
                        occupied.add(new float[]{left, top, left + w, top + h});
                        placed = new PlacedWord(word.word(), size, left, top + size);
                        break;
                    }
                }
                if (placed == null && size > minSize) {
                    size = Math.max(minSize, size * 0.88f);
                } else {
                    break;
                }
            }
            if (placed != null) {
                out.add(placed);
            }
        }
        return out;
    }

    /** Approximate width; avoids Android font metrics so layout stays testable. */
    private static float measureWord(String word, float size) {
        float w = 0f;
        for (int i = 0; i < word.length(); ) {
            int cp = word.codePointAt(i);
            w += (cp > 0x2E80) ? size : size * 0.56f;
            i += Character.charCount(cp);
        }
        return w;
    }
}
