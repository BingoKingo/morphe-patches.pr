/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/2625
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.lyrics.requests;

import android.util.Base64;

import androidx.annotation.Nullable;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserFactory;

import java.io.ByteArrayInputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import app.morphe.extension.music.patches.lyrics.model.Lyrics;
import app.morphe.extension.music.patches.lyrics.model.LyricsLine;
import app.morphe.extension.music.patches.lyrics.model.TrackInfo;
import app.morphe.extension.music.patches.lyrics.model.Word;
import app.morphe.extension.music.patches.lyrics.parsers.LRCParser;
import app.morphe.extension.music.patches.lyrics.romanizers.ScriptDetector;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.requests.Requester;

public final class PetitLyricsProvider implements LyricsProvider {

    private static final String API_URL = "https://p0.petitlyrics.com/api/GetPetitLyricsData.php";
    private static final String CLIENT_APP_ID = "p1110417";
    private static final String TERMINAL_TYPE = "10";
    private static final String SOURCE_URL_PREFIX = "https://petitlyrics.com/lyrics/";

    private static final long REQUEST_THROTTLE_MS = 500;
    private static final AtomicLong lastRequestTime = new AtomicLong(0);

    private static final int TIER_UNSYNCED = 1;
    private static final int TIER_LINE_SYNC = 2;
    private static final int TIER_WORD_SYNC = 3;

    private static final int LSY_KEY_SWITCH_FLAG_OFF = 0x19;
    private static final int LSY_PROTECTION_ID_OFF = 0x1a;
    private static final int LSY_LINE_COUNT_OFF = 0x38;
    private static final int LSY_LINE_LENGTH_OFF = 0x42;
    private static final int LSY_PAYLOAD_START = 0xcc;
    private static final int LSY_MIN_HEADER_LEN = LSY_PAYLOAD_START;
    private static final int LSY_MAX_LINES = 10_000;
    private static final int LSY_WRAP_CS = 65_536;

    private static int scoreOf(Song song, TrackInfo track) {
        return LyricsRequests.scoreTrackCandidate(
                song.title, song.artist, song.durationMs / 1000, track);
    }

    @Nullable
    private static String sourceUrl(@Nullable String lyricsId) {
        if (lyricsId == null || lyricsId.trim().isEmpty()) {
            return null;
        }
        return SOURCE_URL_PREFIX + lyricsId.trim();
    }

    private static List<String> creditLinesOf(Song song) {
        List<String> credits = new ArrayList<>(4);
        addCredit(credits, "Artist", song.artist);
        addCredit(credits, "Album", song.album);
        addCredit(credits, "Writer", song.writer);
        addCredit(credits, "Composer", song.composer);
        return credits;
    }

    private static void addCredit(List<String> credits, String label, @Nullable String value) {
        if (value == null) {
            return;
        }
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            return;
        }
        credits.add(label + ": " + trimmed);
    }

    @Nullable
    private static List<String> mergeCredits(@Nullable List<String> primary,
                                             @Nullable List<String> secondary) {
        if (primary == null || primary.isEmpty()) {
            return secondary == null || secondary.isEmpty() ? null : secondary;
        }
        if (secondary == null || secondary.isEmpty()) {
            return primary;
        }
        List<String> merged = new ArrayList<>(primary.size() + secondary.size());
        merged.addAll(primary);
        for (String line : secondary) {
            if (!merged.contains(line)) {
                merged.add(line);
            }
        }
        return merged;
    }

    /**
     * The song a {@code </song>} closes, kept when it has lyrics to offer.
     *
     * @param current The song being read, if any.
     * @return The song still being read, or null once a song has closed.
     */
    @Nullable
    private static Song closeSongTag(Response response, @Nullable Song current, String tag) {
        if (current == null || !"song".equals(tag)) {
            return current;
        }
        if (current.lyricsData != null && !current.lyricsData.isEmpty()) {
            response.songs.add(current);
        }
        return null;
    }

    /** Puts one finished value where its field belongs. Unknown fields are ignored. */
    private static void applySongField(Song current, @Nullable String field, String value) {
        if (field == null) {
            return;
        }
        switch (field) {
            case "lyricsId" -> current.lyricsId = value;
            case "title" -> current.title = value;
            case "artist" -> current.artist = value;
            case "album" -> current.album = value;
            case "writer" -> current.writer = value;
            case "composer" -> current.composer = value;
            case "duration" -> current.durationMs = parseDurationMs(value);
            case "lyricsData" -> current.lyricsData = value.trim();
            default -> {
            }
        }
    }

    /** A song's length from its tag, and 0 when it is not a number. */
    private static long parseDurationMs(String value) {
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    @Nullable
    private static Response parseResponse(String xml) {
        try {
            XmlPullParser parser = XmlPullParserFactory.newInstance().newPullParser();
            parser.setInput(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)),
                    "UTF-8");

            Response response = new Response();
            Song current = null;
            StringBuilder text = new StringBuilder();
            String field = null;
            int event = parser.getEventType();
            while (event != XmlPullParser.END_DOCUMENT) {
                switch (event) {
                    case XmlPullParser.START_TAG -> {
                        field = parser.getName();
                        if ("song".equals(field)) {
                            current = new Song();
                        } else {
                            text.setLength(0);
                        }
                    }
                    case XmlPullParser.TEXT -> {
                        if (field != null && current != null) {
                            text.append(parser.getText());
                        } else if ("status".equals(field)) {
                            text.append(parser.getText());
                        }
                    }
                    case XmlPullParser.END_TAG -> {
                        String tag = parser.getName();
                        current = closeSongTag(response, current, tag);
                        if (current != null) {
                            applySongField(current, field, text.toString());
                        } else if ("status".equals(tag)) {
                            response.status = text.toString();
                        }
                        field = null;
                    }
                    default -> {
                    }
                }
                event = parser.next();
            }
            if (!"00000000".equals(response.status)) {
                return null;
            }
            return response;
        } catch (Exception ex) {
            Logger.printDebug(() -> "PetitLyrics XML parse failed", ex);
            return null;
        }
    }

    private static int classifyPayload(byte[] raw) {
        byte[] root = xmlRootPrefix(raw);
        if (root.length >= 4 && root[0] == '<' && root[1] == 'w' && root[2] == 's'
                && root[3] == 'y') {
            return TIER_WORD_SYNC;
        }
        if (containsNul(raw) || !isValidUtf8(raw)) {
            return TIER_LINE_SYNC;
        }
        return TIER_UNSYNCED;
    }

    private static byte[] xmlRootPrefix(byte[] raw) {
        int i = 0;
        if (raw.length >= 3 && (raw[0] & 0xFF) == 0xEF && (raw[1] & 0xFF) == 0xBB
                && (raw[2] & 0xFF) == 0xBF) {
            i = 3;
        }
        while (i < raw.length) {
            byte b = raw[i];
            if (b == ' ' || b == '\t' || b == '\r' || b == '\n') {
                i++;
                continue;
            }
            if (i + 1 < raw.length && raw[i] == '<' && raw[i + 1] == '?') {
                int end = indexOf(raw, "?>".getBytes(StandardCharsets.US_ASCII), i);
                if (end < 0) {
                    break;
                }
                i = end + 2;
                continue;
            }
            if (i + 3 < raw.length && raw[i] == '<' && raw[i + 1] == '!'
                    && raw[i + 2] == '-' && raw[i + 3] == '-') {
                int end = indexOf(raw, "-->".getBytes(StandardCharsets.US_ASCII), i);
                if (end < 0) {
                    break;
                }
                i = end + 3;
                continue;
            }
            break;
        }
        byte[] rest = new byte[raw.length - i];
        System.arraycopy(raw, i, rest, 0, rest.length);
        return rest;
    }

    private static int indexOf(byte[] haystack, byte[] needle, int from) {
        outer:
        for (int i = from; i <= haystack.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }

    private static boolean containsNul(byte[] raw) {
        for (byte b : raw) {
            if (b == 0) {
                return true;
            }
        }
        return false;
    }

    private static boolean isValidUtf8(byte[] raw) {
        try {
            StandardCharsets.UTF_8.decode(java.nio.ByteBuffer.wrap(raw));
            return true;
        } catch (Exception ex) {
            return false;
        }
    }

    /**
     * Reads the text of one tag of a word-synced sheet: a word's own text or times, or the line's
     * text.
     *
     * @return The word's times, with the one this tag carried replaced.
     */
    private static WsyTimes readWsyText(String tag, String text, boolean inLine, boolean inWord,
                                         StringBuilder lineText, StringBuilder wordText,
                                         long wordStart, long wordEnd) {
        if (!inWord) {
            if (inLine && "linestring".equals(tag)) {
                lineText.append(text);
            }
            return new WsyTimes(wordStart, wordEnd);
        }
        return switch (tag) {
            case "wordstring" -> {
                wordText.append(text);
                yield new WsyTimes(wordStart, wordEnd);
            }
            case "starttime" -> new WsyTimes(parseLong(text), wordEnd);
            case "endtime" -> new WsyTimes(wordStart, parseLong(text));
            default -> new WsyTimes(wordStart, wordEnd);
        };
    }

    /** A word's times, before and after one tag of the sheet was read. */
    private record WsyTimes(long start, long end) {
    }

    /**
     * One finished {@code <word>} of a word-synced sheet.
     *
     * @return The token, or null when the word had no text.
     */
    @Nullable
    private static RawToken finishWsyToken(String text, long wordStart, long wordEnd) {
        if (text.isEmpty()) {
            return null;
        }
        final long start = Math.max(0, wordStart);
        return new RawToken(text, start, Math.max(start, wordEnd));
    }

    /**
     * One finished {@code <line>} of a word-synced sheet, its readings merged into words.
     *
     * @return The line, or null when it has neither a reading nor any word.
     */
    @Nullable
    private static RawWsyLine finishWsyLine(List<RawToken> rawTokens, String rawLineText) {
        if (rawTokens.isEmpty()) {
            return null;
        }
        String lineStr = cleanLineText(rawLineText);
        List<Word> mergedWords = mergeWsyTokens(rawTokens);
        if (mergedWords.isEmpty()) {
            return null;
        }
        if (lineStr.isEmpty()) {
            lineStr = joinedWordText(mergedWords);
        }
        return new RawWsyLine(lineStr, mergedWords);
    }

    private static String cleanLineText(String raw) {
        return raw.replace('\u2005', ' ')
                .replace('\u3000', ' ')
                .replace('\u00A0', ' ')
                .trim();
    }

    /**
     * Merges one line's tokens into words: a whitespace token marks the word before it as spaced
     * out, and neighbouring tokens outside CJK with no space between them become one word.
     */
    private static List<Word> mergeWsyTokens(List<RawToken> rawTokens) {
        List<Word> mergedWords = new ArrayList<>();
        for (RawToken tok : rawTokens) {
            String token = tok.text();
            if (isWhitespaceToken(token)) {
                markLastWordSpaced(mergedWords);
                continue;
            }
            if (canMergeIntoLast(mergedWords, token)) {
                mergeIntoLast(mergedWords, tok, token);
            } else {
                mergedWords.add(new Word(tok.startMs(), tok.endMs(), token, null, false));
            }
        }
        return mergedWords;
    }

    private static void markLastWordSpaced(List<Word> words) {
        if (words.isEmpty()) {
            return;
        }
        Word prev = words.get(words.size() - 1);
        words.set(words.size() - 1,
                new Word(prev.startMs(), prev.endMs(), prev.text(), prev.romaji(), true));
    }

    private static boolean canMergeIntoLast(List<Word> words, String token) {
        if (words.isEmpty()) {
            return false;
        }
        Word prev = words.get(words.size() - 1);
        return !prev.endsWithSpace()
                && !ScriptDetector.containsCjk(token)
                && !ScriptDetector.containsCjk(prev.text());
    }

    private static void mergeIntoLast(List<Word> words, RawToken tok, String token) {
        Word prev = words.get(words.size() - 1);
        words.set(words.size() - 1, new Word(
                prev.startMs(), Math.max(prev.endMs(), tok.endMs()),
                prev.text() + token, null, false));
    }

    private static String joinedWordText(List<Word> words) {
        StringBuilder joined = new StringBuilder();
        for (Word w : words) {
            joined.append(w.text());
            if (w.endsWithSpace()) {
                joined.append(' ');
            }
        }
        return joined.toString().trim();
    }

    private static boolean isWhitespaceToken(@Nullable String s) {
        if (s == null || s.isEmpty()) return true;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (!Character.isWhitespace(c) && c != 0x2005 && c != 0x3000 && c != 0xA0) {
                return false;
            }
        }
        return true;
    }

    private static boolean hasOpen(@Nullable String s) {
        return s != null && (s.contains("(") || s.contains("（") || s.contains("["));
    }

    private static boolean hasClose(@Nullable String s) {
        return s != null && (s.contains(")") || s.contains("）") || s.contains("]"));
    }

    private static int indexOfAny(@Nullable String s, String chars) {
        if (s == null) return -1;
        for (int i = 0; i < s.length(); i++) {
            if (chars.indexOf(s.charAt(i)) >= 0) return i;
        }
        return -1;
    }

    private static int lastIndexOfAny(@Nullable String s, String chars) {
        if (s == null) return -1;
        for (int i = s.length() - 1; i >= 0; i--) {
            if (chars.indexOf(s.charAt(i)) >= 0) return i;
        }
        return -1;
    }

    private static void addSanitizedLine(List<LyricsLine> lines, String text, List<Word> words, boolean isBg) {
        if (text.isEmpty() || words.isEmpty()) {
            return;
        }
        if (isBg) {
            while ((text.startsWith("(") && text.endsWith(")"))
                    || (text.startsWith("（") && text.endsWith("）"))
                    || (text.startsWith("[") && text.endsWith("]"))) {
                text = text.substring(1, text.length() - 1).trim();
            }
            if (!words.isEmpty()) {
                Word first = words.get(0);
                String ft = first.text().trim();
                if ("(".equals(ft) || "（".equals(ft) || "[".equals(ft)) {
                    words.remove(0);
                } else if (ft.startsWith("(") || ft.startsWith("（") || ft.startsWith("[")) {
                    words.set(0, new Word(first.startMs(), first.endMs(), ft.substring(1).trim(), first.romaji(), first.endsWithSpace()));
                }
            }
            if (!words.isEmpty()) {
                int lastIdx = words.size() - 1;
                Word last = words.get(lastIdx);
                String lt = last.text().trim();
                if (")".equals(lt) || "）".equals(lt) || "]".equals(lt)) {
                    words.remove(lastIdx);
                } else if (lt.endsWith(")") || lt.endsWith("）") || lt.endsWith("]")) {
                    words.set(lastIdx, new Word(last.startMs(), last.endMs(), lt.substring(0, lt.length() - 1).trim(), last.romaji(), last.endsWithSpace()));
                }
            }
        }
        if (text.isEmpty() || words.isEmpty()) {
            return;
        }
        long start = words.get(0).startMs();
        long endMs = words.get(words.size() - 1).endMs();
        if (endMs <= start) {
            endMs = start + Math.max(2500L, text.length() * 200L);
        }
        lines.add(new LyricsLine(start, endMs, text, new ArrayList<>(words), null, false, isBg, null));
    }

    private static List<LyricsLine> parseWsy(byte[] payload) {
        try {
            XmlPullParser parser = XmlPullParserFactory.newInstance().newPullParser();
            parser.setInput(new ByteArrayInputStream(payload), "UTF-8");

            List<RawWsyLine> rawLines = new ArrayList<>();
            List<RawToken> rawTokens = new ArrayList<>();
            StringBuilder lineText = new StringBuilder();
            StringBuilder wordText = new StringBuilder();
            long wordStart = 0;
            long wordEnd = 0;
            String tag = null;
            boolean inLine = false;
            boolean inWord = false;

            int event = parser.getEventType();
            while (event != XmlPullParser.END_DOCUMENT) {
                switch (event) {
                    case XmlPullParser.START_TAG -> {
                        tag = parser.getName();
                        if ("line".equals(tag)) {
                            inLine = true;
                            rawTokens.clear();
                            lineText.setLength(0);
                        } else if (inLine && "word".equals(tag)) {
                            inWord = true;
                            wordText.setLength(0);
                            wordStart = 0;
                            wordEnd = 0;
                        }
                    }
                    case XmlPullParser.TEXT -> {
                        if (tag == null) {
                            break;
                        }
                        WsyTimes times = readWsyText(tag, parser.getText(), inLine, inWord,
                                lineText, wordText, wordStart, wordEnd);
                        wordStart = times.start();
                        wordEnd = times.end();
                    }
                    case XmlPullParser.END_TAG -> {
                        String end = parser.getName();
                        if (inWord && "word".equals(end)) {
                            inWord = false;
                            RawToken token = finishWsyToken(wordText.toString(), wordStart, wordEnd);
                            if (token != null) {
                                rawTokens.add(token);
                            }
                        } else if (inLine && "line".equals(end)) {
                            inLine = false;
                            RawWsyLine line = finishWsyLine(rawTokens, lineText.toString());
                            if (line != null) {
                                rawLines.add(line);
                            }
                        }
                        tag = null;
                    }
                    default -> {
                    }
                }
                event = parser.next();
            }

            List<LyricsLine> lines = new ArrayList<>();
            int i = 0;
            final int rawCount = rawLines.size();
            while (i < rawCount) {
                i = appendWsyLineGroup(lines, rawLines, i, rawCount);
                if (i < 0) return List.of();
            }

            lines.sort(Comparator.comparingLong(LyricsLine::startTimeMs));
            LRCParser.markEchoesAsBackground(lines);
            return lines;
        } catch (Exception ex) {
            Logger.printDebug(() -> "PetitLyrics WSY parse failed", ex);
            return List.of();
        }
    }

    /**
     * Adds the lines one position of a word-synced sheet stands for, and says where to read on.
     *
     * <p>A bracket opened on one line and closed on the next is one background vocal, a bracketed
     * part at the start or the end of a line is a second vocal, and anything else is a line of its
     * own.
     *
     * @return The next position to read, or -1 when the sheet can no longer be trusted.
     */
    private static int appendWsyLineGroup(List<LyricsLine> lines, List<RawWsyLine> rawLines, int i,
                                          int rawCount) {
        RawWsyLine cur = rawLines.get(i);
        List<Word> words = new ArrayList<>(cur.words);
        String text = cur.text;
        if (text.isEmpty() || words.isEmpty()) {
            return i + 1;
        }
        RawWsyLine nxt = i + 1 < rawCount ? rawLines.get(i + 1) : null;

        if (nxt != null && isBracketContinued(text, nxt.text)) {
            return appendContinuedBracket(lines, text, words, nxt);
        }

        if (appendSplitBracket(lines, text, words)) {
            return i + 1;
        }

        addSanitizedLine(lines, text, words, LRCParser.hasOuterParentheses(text));
        return i + 1;
    }

    /** A line that opens a bracket the next line closes. */
    private static boolean isBracketContinued(String text, String nextText) {
        return hasOpen(text) && !hasClose(text) && hasClose(nextText) && !hasOpen(nextText);
    }

    /**
     * Adds the background vocal a bracket spans over two lines as one line, or as a lead line and
     * a background line when the bracket does not open the first one.
     *
     * @return the index the caller continues at, or -1 when the line could not be split.
     */
    private static int appendContinuedBracket(List<LyricsLine> lines, String text,
                                              List<Word> words, RawWsyLine nxt) {
        List<Word> nxtWords = new ArrayList<>(nxt.words);
        int pPos = indexOfAny(text, "(（[");
        if (pPos == 0) {
            List<Word> combWords = new ArrayList<>(words);
            combWords.addAll(nxtWords);
            String combText = (text + " " + nxt.text).trim();
            addSanitizedLine(lines, combText, combWords, true);
            return 0;
        }

        int splitWi = firstOpeningWordIndex(words);
        if (splitWi <= 0) {
            return -1;
        }
        List<Word> mainWords = new ArrayList<>(words.subList(0, splitWi));
        Word lastMain = mainWords.get(mainWords.size() - 1);
        mainWords.set(mainWords.size() - 1, new Word(
                lastMain.startMs(), lastMain.endMs(), lastMain.text(), lastMain.romaji(), false));
        List<Word> secWords = new ArrayList<>(words.subList(splitWi, words.size()));
        secWords.addAll(nxtWords);
        String mainText = (pPos > 0 ? text.substring(0, pPos) : text).trim();
        String secText = ((pPos >= 0 ? text.substring(pPos) : "") + " " + nxt.text).trim();
        addSanitizedLine(lines, mainText, mainWords, false);
        addSanitizedLine(lines, secText, secWords, true);
        return 0;
    }

    /**
     * Splits a line whose bracket only covers its start or its end into a lead line and a
     * background line.
     *
     * @return true when the line was split, so the caller moves on to the next line.
     */
    private static boolean appendSplitBracket(List<LyricsLine> lines, String text,
                                              List<Word> words) {
        int pOpen = indexOfAny(text, "(（[");
        int pClose = lastIndexOfAny(text, ")）]");

        if (pOpen > 0 && pClose > pOpen && pClose >= text.length() - 2) {
            return appendBracketAtEnd(lines, text, words, pOpen);
        }

        if (pOpen == 0 && 0 < pClose && pClose < text.length() - 2) {
            return appendBracketAtStart(lines, text, words, pClose);
        }
        return false;
    }

    /** A line that ends on its bracketed word: the text before it, then the vocal in brackets. */
    private static boolean appendBracketAtEnd(List<LyricsLine> lines, String text,
                                              List<Word> words, int pOpen) {
        int splitWi = firstOpeningWordIndex(words);
        if (splitWi <= 0) {
            return false;
        }
        List<Word> mainWords = new ArrayList<>(words.subList(0, splitWi));
        Word lastMain = mainWords.get(mainWords.size() - 1);
        mainWords.set(mainWords.size() - 1, new Word(
                lastMain.startMs(), lastMain.endMs(), lastMain.text(), lastMain.romaji(), false));
        List<Word> secWords = new ArrayList<>(words.subList(splitWi, words.size()));
        addSanitizedLine(lines, text.substring(0, pOpen).trim(), mainWords, false);
        addSanitizedLine(lines, text.substring(pOpen).trim(), secWords, true);
        return true;
    }

    /** A line that starts on its bracketed word: the vocal in brackets, then the text after it. */
    private static boolean appendBracketAtStart(List<LyricsLine> lines, String text,
                                                List<Word> words, int pClose) {
        int splitWi = firstClosingWordIndex(words);
        if (splitWi <= 0 || splitWi >= words.size()) {
            return false;
        }
        List<Word> secWords = new ArrayList<>(words.subList(0, splitWi));
        Word lastSec = secWords.get(secWords.size() - 1);
        secWords.set(secWords.size() - 1, new Word(
                lastSec.startMs(), lastSec.endMs(), lastSec.text(), lastSec.romaji(), false));
        List<Word> mainWords = new ArrayList<>(words.subList(splitWi, words.size()));
        addSanitizedLine(lines, text.substring(0, pClose + 1).trim(), secWords, true);
        addSanitizedLine(lines, text.substring(pClose + 1).trim(), mainWords, false);
        return true;
    }

    /** Index of the first word that opens a bracket, or -1 when none does. */
    private static int firstOpeningWordIndex(List<Word> words) {
        for (int wi = 0; wi < words.size(); wi++) {
            if (hasOpen(words.get(wi).text())) {
                return wi;
            }
        }
        return -1;
    }

    /** One past the first word that closes a bracket, or -1 when none does. */
    private static int firstClosingWordIndex(List<Word> words) {
        for (int wi = 0; wi < words.size(); wi++) {
            if (hasClose(words.get(wi).text())) {
                return wi + 1;
            }
        }
        return -1;
    }


    private static long parseLong(String value) {
        try {
            return Long.parseLong(value.trim());
        } catch (Exception ex) {
            return 0;
        }
    }

    private static List<Long> decodeLsyTimings(byte[] raw) {
        if (raw.length < LSY_MIN_HEADER_LEN) {
            return List.of();
        }
        ByteBuffer buf = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN);
        int lineCount = buf.getInt(LSY_LINE_COUNT_OFF);
        if (lineCount <= 0 || lineCount > LSY_MAX_LINES) {
            return List.of();
        }
        int end = LSY_PAYLOAD_START + 2 * lineCount;
        if (raw.length < end) {
            return List.of();
        }
        int lineLength = buf.getShort(LSY_LINE_LENGTH_OFF) & 0xFFFF;
        if (lineLength > 0) {
            int want = LSY_PAYLOAD_START + 2 * lineCount + lineLength * lineCount;
            if (raw.length != want) {
                return List.of();
            }
        }
        boolean switchFlag = raw[LSY_KEY_SWITCH_FLAG_OFF] != 0;
        int protectionId = buf.getShort(LSY_PROTECTION_ID_OFF) & 0xFFFF;
        int key = deriveLsyKey(protectionId, switchFlag);

        List<Long> out = new ArrayList<>(lineCount);
        int wraps = 0;
        int prev = 0;
        for (int i = 0; i < lineCount; i++) {
            int cs = (buf.getShort(LSY_PAYLOAD_START + 2 * i) & 0xFFFF) ^ key;
            if (i > 0 && cs < prev) {
                wraps++;
            }
            prev = cs;
            out.add(cs + (long) wraps * LSY_WRAP_CS);
        }
        return out;
    }

    private static int deriveLsyKey(int protectionId, boolean switchFlag) {
        if (!switchFlag) {
            return protectionId;
        }
        int k = protectionId;
        return (k & 0x0003)
                | ((k & 0x000c) << 2)
                | ((k & 0x0030) >> 2)
                | ((k & 0x00c0) << 2)
                | ((k & 0x0300) >> 2)
                | ((k & 0x0c00) << 2)
                | ((k & 0x3000) >> 2)
                | (k & 0xc000);
    }

    private static List<LyricsLine> zipLineSync(List<Long> timingsCs, String text) {
        String normalized = text.replace("\r\n", "\n");
        String[] rows = normalized.split("\n", -1);
        int count = rows.length;
        if (count != timingsCs.size()) {
            while (count > 0 && rows[count - 1].trim().isEmpty()) {
                count--;
            }
        }
        if (count != timingsCs.size()) {
            final int finalCount = count;
            Logger.printDebug(() -> "PetitLyrics line-sync mismatch: "
                    + timingsCs.size() + " timings vs " + finalCount + " lines");
            return List.of();
        }
        List<LyricsLine> lines = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            String row = rows[i].replace('\u2005', ' ').replace('\u3000', ' ').replace('\u00A0', ' ').trim();
            if (row.isEmpty()) {
                continue;
            }
            long startMs = timingsCs.get(i) * 10;
            long endMs = (i + 1 < count) ? timingsCs.get(i + 1) * 10 : startMs + 3000;
            if (endMs <= startMs) {
                endMs = startMs + 3000;
            }
            boolean isBg = LRCParser.hasOuterParentheses(row);
            String lineText = row;
            if (isBg && ((row.startsWith("(") && row.endsWith(")"))
                    || (row.startsWith("（") && row.endsWith("）"))
                    || (row.startsWith("[") && row.endsWith("]")))) {
                lineText = row.substring(1, row.length() - 1).trim();
            }
            lines.add(new LyricsLine(startMs, endMs, lineText, List.of(), null, false, isBg, null));
        }
        LRCParser.markEchoesAsBackground(lines);
        return lines;
    }

    private static String buildLrc(List<LyricsLine> lines) {
        StringBuilder sb = new StringBuilder(50 * lines.size());
        for (LyricsLine line : lines) {
            sb.append(LRCParser.formatLine(line))
                    .append('\n');
        }
        return sb.toString();
    }

    private static String toUtf8(byte[] payload) {
        try {
            return new String(payload, StandardCharsets.UTF_8).trim();
        } catch (Exception ex) {
            return "";
        }
    }

    private static String toUtf8Raw(byte[] payload) {
        try {
            return new String(payload, StandardCharsets.UTF_8);
        } catch (Exception ex) {
            return "";
        }
    }

    @Override
    public String name() {
        return "PetitLyrics";
    }

    @Nullable
    @Override
    public FetchResult fetch(TrackInfo track) throws Exception {
        Stash stash = new Stash();

        collectTier(track, TIER_WORD_SYNC, stash);
        if (stash.wordLines != null) {
            return resultOf(stash, tryWordSync(stash), track);
        }

        collectTier(track, TIER_LINE_SYNC, stash);
        Lyrics result = tryWordSync(stash);
        if (result != null) {
            return resultOf(stash, result, track);
        }
        result = tryLineSync(track, stash);
        if (result != null) {
            return resultOf(stash, result, track);
        }

        if (stash.plainText == null && !stash.plainRequested) {
            collectTier(track, TIER_UNSYNCED, stash);
        }
        if (stash.plainText == null && stash.zipText != null) {
            stash.plainText = stash.zipText;
            if (stash.sourceLyricsId == null) {
                stash.sourceLyricsId = stash.zipLyricsId;
            }
        }
        return resultOf(stash, tryText(stash), track);
    }

    /** Wraps a built answer with where it came from, or null when there is no answer. */
    @Nullable
    private FetchResult resultOf(Stash stash, @Nullable Lyrics lyrics, TrackInfo track) {
        return lyrics == null ? null
                : FetchResult.of(lyrics, stash.sourceTitle, stash.sourceArtist,
                stash.sourceDurationSec, track);
    }

    private void collectTier(TrackInfo track, int lyricsType, Stash stash) {
        if (lyricsType == TIER_UNSYNCED) {
            if (stash.plainRequested) {
                return;
            }
            stash.plainRequested = true;
        }
        try {
            Response response = request(track, lyricsType);
            collect(response, track, stash);
        } catch (Exception ex) {
            Logger.printDebug(
                    () -> "PetitLyrics request failed for type " + lyricsType, ex);
        }
    }

    private void collect(@Nullable Response response, TrackInfo track, Stash stash) {
        if (response == null || response.songs.isEmpty()) {
            return;
        }
        List<Song> sorted = gate(response.songs, track);

        if (stash.creditLines == null) {
            collectCredits(sorted, stash);
        }

        if (stash.lsyLyricsId != null && stash.zipText == null) {
            collectZipText(response, sorted, stash);
        }

        for (Song song : sorted) {
            if (song.lyricsData == null || song.lyricsData.isEmpty()) {
                continue;
            }
            byte[] payload = decodePayload(song);
            if (payload == null) {
                continue;
            }

            int tier = classifyPayload(payload);
            switch (tier) {
                case TIER_WORD_SYNC -> {
                    if (stash.wordLines == null) {
                        collectWordSync(payload, song, stash);
                    }
                }
                case TIER_LINE_SYNC -> {
                    if (stash.lsyTimings == null) {
                        collectLineSync(payload, song, stash);
                    }
                }
                default -> {
                    if (stash.plainText == null) {
                        collectPlain(payload, song, stash);
                    }
                }
            }
        }
    }

    /** The songs that score high enough, best first, or the best one when none does. */
    private static List<Song> gate(List<Song> songs, TrackInfo track) {
        List<Song> sorted = new ArrayList<>(songs);
        sorted.sort((a, b) -> Integer.compare(scoreOf(b, track), scoreOf(a, track)));
        List<Song> gated = new ArrayList<>();
        for (Song song : sorted) {
            if (scoreOf(song, track) >= LyricsRequests.SOFT_MIN) {
                gated.add(song);
            }
        }
        if (gated.isEmpty() && !sorted.isEmpty()) {
            gated.add(sorted.get(0));
        }
        return gated;
    }

    private static void collectCredits(List<Song> sorted, Stash stash) {
        for (Song song : sorted) {
            List<String> credits = creditLinesOf(song);
            if (!credits.isEmpty()) {
                stash.creditLines = credits;
                if (stash.sourceLyricsId == null) {
                    stash.sourceLyricsId = song.lyricsId;
                }
                return;
            }
        }
    }

    /** The plain text of the track that already answered with line timings. */
    private static void collectZipText(Response response, List<Song> sorted, Stash stash) {
        Song idMatch = songWithLyricsData(sorted, stash.lsyLyricsId);
        if (idMatch == null && response.songs.size() == 1) {
            idMatch = response.songs.get(0);
        }
        if (idMatch == null || idMatch.lyricsData == null || idMatch.lyricsData.isEmpty()) {
            return;
        }

        try {
            byte[] payload = Base64.decode(idMatch.lyricsData, Base64.DEFAULT);
            if (payload == null || payload.length == 0
                    || classifyPayload(payload) != TIER_UNSYNCED) {
                return;
            }
            String text = toUtf8Raw(payload);
            if (text.trim().isEmpty()) {
                return;
            }
            stash.zipText = text;
            stash.zipLyricsId = idMatch.lyricsId;
            stash.sourceTitle = idMatch.title;
            stash.sourceArtist = idMatch.artist;
            stash.sourceDurationSec = idMatch.durationMs / 1000;
            if (stash.sourceLyricsId == null) {
                stash.sourceLyricsId = idMatch.lyricsId;
            }
        } catch (Exception ex) {
            Logger.printDebug(() -> "PetitLyrics zip text decode failed", ex);
        }
    }

    /** The first song with lyrics data whose id is {@code lyricsId}, or null. */
    @Nullable
    private static Song songWithLyricsData(List<Song> sorted, String lyricsId) {
        for (Song song : sorted) {
            if (song.lyricsData == null || song.lyricsData.isEmpty()) {
                continue;
            }
            if (lyricsId.equals(song.lyricsId)) {
                return song;
            }
        }
        return null;
    }

    /** The lyrics data of a song, or null when it is missing or cannot be decoded. */
    @Nullable
    private static byte[] decodePayload(Song song) {
        try {
            byte[] payload = Base64.decode(song.lyricsData, Base64.DEFAULT);
            return payload == null || payload.length == 0 ? null : payload;
        } catch (Exception ex) {
            Logger.printDebug(() -> "PetitLyrics base64 decode failed", ex);
            return null;
        }
    }

    private static void collectWordSync(byte[] payload, Song song, Stash stash) {
        List<LyricsLine> lines = parseWsy(payload);
        if (lines.isEmpty()) {
            return;
        }
        stash.wordLines = lines;
        stash.wsyRaw = toUtf8(payload);
        rememberSource(stash, song);
    }

    private static void collectLineSync(byte[] payload, Song song, Stash stash) {
        List<Long> timings = decodeLsyTimings(payload);
        if (timings.isEmpty()) {
            return;
        }
        stash.lsyTimings = timings;
        stash.lsyLyricsId = song.lyricsId;
        rememberSource(stash, song);
    }

    private static void collectPlain(byte[] payload, Song song, Stash stash) {
        String text = toUtf8Raw(payload);
        if (text.trim().isEmpty()) {
            return;
        }
        stash.plainText = text;
        rememberSource(stash, song);
    }

    /** Records the song the lyrics came from, so the answer credits the right track. */
    private static void rememberSource(Stash stash, Song song) {
        stash.sourceLyricsId = song.lyricsId;
        stash.sourceTitle = song.title;
        stash.sourceArtist = song.artist;
        stash.sourceDurationSec = song.durationMs / 1000;
    }

    @Nullable
    private Lyrics tryWordSync(Stash stash) {
        if (stash.wordLines == null || stash.wordLines.isEmpty()) {
            return null;
        }
        String wsyRaw = stash.wsyRaw != null && !stash.wsyRaw.isEmpty()
                ? stash.wsyRaw
                : null;
        return new Lyrics(stash.wordLines, name(), true, null, null, null,
                stash.creditLines,
                wsyRaw, wsyRaw != null ? "wsy" : null, sourceUrl(stash.sourceLyricsId));
    }

    @Nullable
    private Lyrics tryLineSync(TrackInfo track, Stash stash) {
        if (stash.lsyTimings == null || stash.lsyTimings.isEmpty()) {
            return null;
        }
        if (stash.zipText == null) {
            collectTier(track, TIER_UNSYNCED, stash);
            if (stash.zipText == null) {
                return null;
            }
        }
        List<LyricsLine> lines = zipLineSync(stash.lsyTimings, stash.zipText);
        if (lines.isEmpty()) {
            return null;
        }
        String lrc = buildLrc(lines);
        return new Lyrics(lines, name(), true, null, null, null, stash.creditLines,
                lrc, "lrc", sourceUrl(stash.sourceLyricsId));
    }

    @Nullable
    private Lyrics tryText(Stash stash) {
        if (stash.plainText == null || stash.plainText.isEmpty()) {
            return null;
        }
        String text = stash.plainText;
        LRCParser.LrcParseResult lrc = LRCParser.parseSyncedWithCreditLines(text);
        if (!lrc.lines.isEmpty()) {
            List<String> credits = mergeCredits(stash.creditLines, lrc.creditLines);
            assert credits != null;
            return new Lyrics(lrc.lines, name(), true, null, null, null,
                    credits.isEmpty() ? null : credits,
                    text, "lrc", sourceUrl(stash.sourceLyricsId));
        }
        List<LyricsLine> plain = LRCParser.parsePlain(text);
        if (plain.isEmpty()) {
            return null;
        }
        return new Lyrics(plain, name(), false, null, null, null, stash.creditLines,
                text, "txt", sourceUrl(stash.sourceLyricsId));
    }

    @Nullable
    private Response request(TrackInfo track, int lyricsType) throws Exception {
        LyricsRequests.throttle(lastRequestTime, REQUEST_THROTTLE_MS);

        String form = "clientAppId=" + LyricsRequests.encode(CLIENT_APP_ID) +
                "&terminalType=" + LyricsRequests.encode(TERMINAL_TYPE) +
                "&lyricsType=" + lyricsType +
                "&key_title=" + LyricsRequests.encode(track.title()) +
                "&key_artist=" + LyricsRequests.encode(track.artist()) +
                "&key_album=" + LyricsRequests.encode(track.album());

        var connection = LyricsRequests.postForm(API_URL, form);
        int httpCode = connection.getResponseCode();
        if (httpCode != Requester.HTTP_STATUS_CODE_SUCCESS) {
            LyricsRequests.logFailure(name(), connection);
            return null;
        }

        String xml;
        try {
            xml = LyricsRequests.parseGzipString(connection);
        } catch (Exception ex) {
            Logger.printDebug(() -> "PetitLyrics response read failed", ex);
            connection.disconnect();
            return null;
        }
        return parseResponse(xml);
    }

    private record RawToken(String text, long startMs, long endMs) {
    }

    private record RawWsyLine(String text, List<Word> words) {
    }

    private static final class Stash {
        @Nullable
        List<LyricsLine> wordLines;
        @Nullable
        String wsyRaw;
        @Nullable
        List<Long> lsyTimings;
        @Nullable
        String lsyLyricsId;
        @Nullable
        String zipText;
        @Nullable
        String zipLyricsId;
        @Nullable
        String plainText;
        boolean plainRequested;
        @Nullable
        List<String> creditLines;
        @Nullable
        String sourceLyricsId;
        @Nullable
        String sourceTitle;
        @Nullable
        String sourceArtist;
        long sourceDurationSec;
    }

    private static final class Response {
        final List<Song> songs = new ArrayList<>();
        String status;
    }

    private static final class Song {
        String lyricsId;
        String title;
        String artist;
        String album;
        String writer;
        String composer;
        long durationMs;
        String lyricsData;
    }
}
