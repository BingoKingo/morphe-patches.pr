/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/2269
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.lyrics.requests;

import androidx.annotation.Nullable;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;
import org.xmlpull.v1.XmlPullParserFactory;

import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import app.morphe.extension.music.patches.lyrics.LyricsLine;
import app.morphe.extension.music.patches.lyrics.LyricsMerge;
import app.morphe.extension.music.patches.lyrics.Word;

import app.morphe.extension.shared.Logger;

/**
 * Parses Apple Music style TTML into lyric lines.
 *
 * <p>Aligned with the AMLL TypeScript parser ({@code @applemusic-like-lyrics/ttml}):
 * namespace-aware parsing, single-pass body scan, background vocals as regular lines,
 * inline + sidecar translations/romanizations, and Ruby annotation support.
 */
final class TtmlParser {

    private static final Pattern TIME_UNIT = Pattern.compile("(-?\\d+(?:\\.\\d+)?)(ms|h|m|s)");
    private static final Pattern MULTI_SPACE = Pattern.compile("\\s+");

    private static final String NS_TT = "http://www.w3.org/ns/ttml";
    private static final String NS_TTM = "http://www.w3.org/ns/ttml#metadata";
    private static final String NS_ITUNES = "http://music.apple.com/lyric-ttml-internal";
    private static final String NS_AMLL = "http://www.example.com/ns/amll";
    private static final String NS_XML = "http://www.w3.org/XML/1998/namespace";
    private static final String NS_TTS = "http://www.w3.org/ns/ttml#styling";

    private TtmlParser() {
    }

    static final class TtmlResult {
        final List<LyricsLine> lines;
        @Nullable final List<LyricsLine> romanization;
        @Nullable final Map<String, List<LyricsLine>> translations;

        TtmlResult(List<LyricsLine> lines, @Nullable List<LyricsLine> romanization,
                   @Nullable Map<String, List<LyricsLine>> translations) {
            this.lines = lines;
            this.romanization = romanization;
            this.translations = translations;
        }
    }

    private static final class RomajiSyllable {
        final long startMs;
        final long endMs;
        final String text;

        RomajiSyllable(long startMs, long endMs, String text) {
            this.startMs = startMs;
            this.endMs = endMs;
            this.text = text;
        }
    }

    private static final class SidecarTranslation {
        final String text;
        @Nullable final String bgText;

        SidecarTranslation(String text, @Nullable String bgText) {
            this.text = text;
            this.bgText = bgText;
        }
    }

    @Nullable
    static TtmlResult parse(String ttml) {
        if (ttml == null || ttml.isEmpty()) {
            return null;
        }
        try {
            final Map<String, List<RomajiSyllable>> sidecarRoman =
                    parseSidecarTransliterations(ttml);
            final Map<String, SidecarTranslation> sidecarTrans =
                    parseSidecarTranslations(ttml);

            final XmlPullParserFactory factory = XmlPullParserFactory.newInstance();
            factory.setNamespaceAware(true);
            final XmlPullParser p = factory.newPullParser();
            p.setInput(new StringReader(ttml));

            final List<LyricsLine> lines = new ArrayList<>();
            final List<LyricsLine> romanization = new ArrayList<>();
            final Map<String, List<LyricsLine>> translations = new HashMap<>();

            String rootTiming = null;
            boolean inHead = false;
            boolean inBody = false;
            String divSongPart = null;

            int event = p.getEventType();
            while (event != XmlPullParser.END_DOCUMENT) {
                if (event == XmlPullParser.START_TAG) {
                    final String local = localName(p.getName());

                    if ("tt".equals(local)) {
                        rootTiming = getAttr(p, NS_ITUNES, "timing", "itunes:timing");
                    } else if ("head".equals(local)) {
                        inHead = true;
                    } else if ("body".equals(local)) {
                        inBody = true;
                    } else if ("div".equals(local) && inBody && !inHead) {
                        divSongPart = getAttr(p, NS_ITUNES, "songPart", "itunes:songPart");
                        if (divSongPart == null) {
                            divSongPart = getAttr(p, NS_ITUNES, "songPart", "itunes:song-part");
                        }
                    } else if ("p".equals(local) && inBody && !inHead) {
                        final String lineId = getAttr(p, NS_ITUNES, "key", "itunes:key");
                        final String agentId = getAttr(p, NS_TTM, "agent", "ttm:agent");
                        final long pBegin = parseTime(getAttr(p, null, "begin", "begin"));
                        final long pEnd = parseTime(getAttr(p, null, "end", "end"));

                        final ParsedLine pl = processPElement(
                                p, rootTiming, lineId, pBegin, pEnd);

                        if (pl != null && !pl.text.isBlank()) {
                            final LyricsLine line = new LyricsLine(
                                    pl.begin, pl.text, pl.words);
                            lines.add(line);

                            // Build romanization entry
                            final String romaText = buildLineRomaji(
                                    pl.words, sidecarRoman.get(lineId));
                            romanization.add(new LyricsLine(
                                    LyricsLine.NO_TIME, romaText));

                            // Build translation entries
                            buildTranslations(
                                    lineId, sidecarTrans, pl.bgLine,
                                    translations, lines.size());
                        }
                    }
                } else if (event == XmlPullParser.END_TAG) {
                    final String local = localName(p.getName());
                    if ("head".equals(local)) {
                        inHead = false;
                    } else if ("body".equals(local)) {
                        inBody = false;
                    } else if ("div".equals(local)) {
                        divSongPart = null;
                    }
                }
                event = p.next();
            }

            if (lines.isEmpty()) {
                return null;
            }
            final Map<String, List<LyricsLine>> transOut =
                    translations.isEmpty() ? null : translations;
            return new TtmlResult(lines,
                    LyricsMerge.hasText(romanization) ? romanization : null,
                    transOut);
        } catch (XmlPullParserException | IOException ex) {
            Logger.printDebug(() -> "TtmlParser failed", ex);
            return null;
        }
    }

    // ── Sidecar pre-scan ────────────────────────────────────────────────────

    private static Map<String, List<RomajiSyllable>> parseSidecarTransliterations(String ttml)
            throws XmlPullParserException, IOException {
        final Map<String, List<RomajiSyllable>> map = new HashMap<>();
        final XmlPullParserFactory factory = XmlPullParserFactory.newInstance();
        factory.setNamespaceAware(true);
        final XmlPullParser p = factory.newPullParser();
        p.setInput(new StringReader(ttml));

        int event = p.getEventType();
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG) {
                final String local = localName(p.getName());
                if ("transliterations".equals(local)) {
                    collectSidecarTransliterations(p, map);
                }
            }
            event = p.next();
        }
        return map;
    }

    private static void collectSidecarTransliterations(XmlPullParser p,
            Map<String, List<RomajiSyllable>> map)
            throws XmlPullParserException, IOException {
        int depth = 1;
        while (depth > 0) {
            final int event = p.next();
            if (event == XmlPullParser.START_TAG) {
                final String local = localName(p.getName());
                if ("transliteration".equals(local)) {
                    collectOneTransliteration(p, map);
                    continue;
                }
                depth++;
            } else if (event == XmlPullParser.END_TAG) {
                depth--;
            }
        }
    }

    private static void collectOneTransliteration(XmlPullParser p,
            Map<String, List<RomajiSyllable>> map)
            throws XmlPullParserException, IOException {
        int depth = 1;
        while (depth > 0) {
            final int event = p.next();
            if (event == XmlPullParser.START_TAG) {
                final String local = localName(p.getName());
                if ("text".equals(local)) {
                    final String forKey = getAttr(p, null, "for", "for");
                    if (forKey != null && !forKey.isEmpty()) {
                        final List<RomajiSyllable> syllables = parseTransliterationText(p);
                        if (syllables != null && !syllables.isEmpty()) {
                            map.put(forKey, syllables);
                            continue;
                        }
                    }
                    skipElement(p);
                } else {
                    depth++;
                }
            } else if (event == XmlPullParser.END_TAG) {
                depth--;
            }
        }
    }

    private static List<RomajiSyllable> parseTransliterationText(XmlPullParser p)
            throws XmlPullParserException, IOException {
        final List<RomajiSyllable> syllables = new ArrayList<>();
        int depth = 1;
        while (depth > 0) {
            final int event = p.next();
            if (event == XmlPullParser.START_TAG) {
                final String local = localName(p.getName());
                if ("span".equals(local)) {
                    final String begin = getAttr(p, null, "begin", "begin");
                    final String end = getAttr(p, null, "end", "end");
                    if (begin != null && end != null) {
                        final String text = normalizeText(readTextContent(p));
                        if (!text.isEmpty()) {
                            syllables.add(new RomajiSyllable(
                                    parseTime(begin), parseTime(end), text));
                        }
                    } else {
                        skipElement(p);
                    }
                } else {
                    depth++;
                }
            } else if (event == XmlPullParser.END_TAG) {
                depth--;
            } else if (event == XmlPullParser.TEXT) {
                final String text = p.getText();
                if (text != null) {
                    final String trimmed = normalizeText(text);
                    if (!trimmed.isEmpty()) {
                        syllables.add(new RomajiSyllable(0, 0, trimmed));
                    }
                }
            }
        }
        return syllables;
    }

    private static Map<String, SidecarTranslation> parseSidecarTranslations(String ttml)
            throws XmlPullParserException, IOException {
        final Map<String, SidecarTranslation> map = new HashMap<>();
        final XmlPullParserFactory factory = XmlPullParserFactory.newInstance();
        factory.setNamespaceAware(true);
        final XmlPullParser p = factory.newPullParser();
        p.setInput(new StringReader(ttml));

        int event = p.getEventType();
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG) {
                final String local = localName(p.getName());
                if ("translations".equals(local)) {
                    collectSidecarTranslations(p, map);
                }
            }
            event = p.next();
        }
        return map;
    }

    private static void collectSidecarTranslations(XmlPullParser p,
            Map<String, SidecarTranslation> map)
            throws XmlPullParserException, IOException {
        int depth = 1;
        while (depth > 0) {
            final int event = p.next();
            if (event == XmlPullParser.START_TAG) {
                final String local = localName(p.getName());
                if ("translation".equals(local)) {
                    final String lang = getAttr(p, NS_XML, "lang", "xml:lang");
                    if (lang != null && !lang.isEmpty()) {
                        collectOneTranslation(p, lang, map);
                        continue;
                    }
                    skipElement(p);
                } else {
                    depth++;
                }
            } else if (event == XmlPullParser.END_TAG) {
                depth--;
            }
        }
    }

    private static void collectOneTranslation(XmlPullParser p, String lang,
            Map<String, SidecarTranslation> map)
            throws XmlPullParserException, IOException {
        int depth = 1;
        while (depth > 0) {
            final int event = p.next();
            if (event == XmlPullParser.START_TAG) {
                final String local = localName(p.getName());
                if ("text".equals(local)) {
                    final String forKey = getAttr(p, null, "for", "for");
                    if (forKey != null && !forKey.isEmpty()) {
                        final SidecarTranslation st = parseTranslationText(p);
                        if (st != null && !st.text.isEmpty()) {
                            map.put(lang + ":" + forKey, st);
                        }
                        continue;
                    }
                    skipElement(p);
                } else {
                    depth++;
                }
            } else if (event == XmlPullParser.END_TAG) {
                depth--;
            }
        }
    }

    private static SidecarTranslation parseTranslationText(XmlPullParser p)
            throws XmlPullParserException, IOException {
        final StringBuilder mainText = new StringBuilder();
        final StringBuilder bgText = new StringBuilder();
        boolean inBg = false;
        int bgDepth = 0;
        int depth = 1;

        while (depth > 0) {
            final int event = p.next();
            if (event == XmlPullParser.START_TAG) {
                final String local = localName(p.getName());
                final String role = getAttr(p, NS_TTM, "role", "ttm:role");
                if ("span".equals(local) && "x-bg".equals(role)) {
                    inBg = true;
                    bgDepth = depth;
                    depth++;
                } else {
                    depth++;
                }
            } else if (event == XmlPullParser.END_TAG) {
                if (inBg && depth == bgDepth + 1) {
                    inBg = false;
                }
                depth--;
            } else if (event == XmlPullParser.TEXT) {
                final String text = p.getText();
                if (text != null) {
                    final String normalized = normalizeText(text);
                    if (!normalized.isEmpty()) {
                        if (inBg) {
                            bgText.append(normalized);
                        } else {
                            mainText.append(normalized);
                        }
                    }
                }
            }
        }
        final String main = mainText.toString().trim();
        final String bg = bgText.toString().trim();
        if (main.isEmpty() && bg.isEmpty()) {
            return null;
        }
        return new SidecarTranslation(main, bg.isEmpty() ? null : bg);
    }

    private static final class ParsedLine {
        final long begin;
        final String text;
        final List<Word> words;
        @Nullable final LyricsLine bgLine;

        ParsedLine(long begin, String text, List<Word> words, @Nullable LyricsLine bgLine) {
            this.begin = begin;
            this.text = text;
            this.words = words;
            this.bgLine = bgLine;
        }
    }

    private static ParsedLine processPElement(XmlPullParser p, String rootTiming,
            String lineId, long pBegin, long pEnd)
            throws XmlPullParserException, IOException {

        final StringBuilder fullText = new StringBuilder();
        final List<Word> words = new ArrayList<>();

        // Current word state
        boolean inWord = false;
        final StringBuilder wordBuf = new StringBuilder();
        long wordBegin = 0, wordEnd = 0;

        // Wrapper state
        int wrapperDepth = 0;

        // Background vocal state
        boolean inBg = false;
        final List<Word> bgWords = new ArrayList<>();
        final StringBuilder bgFullText = new StringBuilder();
        boolean inBgWord = false;
        final StringBuilder bgWordBuf = new StringBuilder();
        long bgWordBegin = 0, bgWordEnd = 0;
        int bgWrapperDepth = 0;
        String bgAgentId = null;
        long bgBeginMs = 0, bgEndMs = 0;
        // Inline translation/roman within bg
        boolean inBgTranslation = false;
        final StringBuilder bgTransBuf = new StringBuilder();
        boolean inBgRoman = false;
        final StringBuilder bgRomanBuf = new StringBuilder();
        // Inline translation/roman at line level
        boolean inTranslation = false;
        final StringBuilder transBuf = new StringBuilder();
        String transLang = null;
        boolean inRoman = false;
        final StringBuilder romanBuf = new StringBuilder();
        String romanLang = null;

        // Ruby state
        boolean inRubyContainer = false;
        boolean inRubyBase = false;
        final StringBuilder rubyBaseBuf = new StringBuilder();
        boolean inRubyTextContainer = false;
        boolean inRubyText = false;
        final StringBuilder rubyTextBuf = new StringBuilder();
        long rubyTextBegin = 0, rubyTextEnd = 0;
        final List<RomajiSyllable> rubyTags = new ArrayList<>();

        int depth = 1;

        while (depth > 0) {
            final int event = p.next();
            if (event == XmlPullParser.START_TAG) {
                depth++;
                final String local = localName(p.getName());
                if (!"span".equals(local)) {
                    continue;
                }

                final String role = getAttr(p, NS_TTM, "role", "ttm:role");
                final String ruby = getAttr(p, NS_TTS, "ruby", "tts:ruby");
                final String beginAttr = getAttr(p, null, "begin", "begin");
                final String endAttr = getAttr(p, null, "end", "end");

                if (inBg) {
                    // Inside background vocal span
                    if ("x-translation".equals(role)) {
                        inBgTranslation = true;
                        bgTransBuf.setLength(0);
                    } else if ("x-roman".equals(role)) {
                        inBgRoman = true;
                        bgRomanBuf.setLength(0);
                    } else if (beginAttr != null && endAttr != null) {
                        // Timed span inside bg → bg word
                        inBgWord = true;
                        bgWordBuf.setLength(0);
                        bgWordBegin = parseTime(beginAttr);
                        bgWordEnd = parseTime(endAttr);
                    } else {
                        // Wrapper span inside bg
                        bgWrapperDepth++;
                    }
                } else if ("x-bg".equals(role)) {
                    // Entering background vocal
                    inBg = true;
                    bgWords.clear();
                    bgFullText.setLength(0);
                    bgWrapperDepth = 0;
                    bgAgentId = getAttr(p, NS_TTM, "agent", "ttm:agent");
                    bgBeginMs = parseTime(beginAttr);
                    bgEndMs = parseTime(endAttr);
                } else if ("x-translation".equals(role)) {
                    inTranslation = true;
                    transBuf.setLength(0);
                    transLang = getAttr(p, NS_XML, "lang", "xml:lang");
                } else if ("x-roman".equals(role)) {
                    inRoman = true;
                    romanBuf.setLength(0);
                    romanLang = getAttr(p, NS_XML, "lang", "xml:lang");
                } else if ("container".equals(ruby)) {
                    inRubyContainer = true;
                    rubyBaseBuf.setLength(0);
                    rubyTags.clear();
                } else if ("base".equals(ruby) && inRubyContainer) {
                    inRubyBase = true;
                    rubyBaseBuf.setLength(0);
                } else if ("textContainer".equals(ruby) && inRubyContainer) {
                    inRubyTextContainer = true;
                } else if ("text".equals(ruby) && inRubyTextContainer) {
                    inRubyText = true;
                    rubyTextBuf.setLength(0);
                    rubyTextBegin = parseTime(beginAttr);
                    rubyTextEnd = parseTime(endAttr);
                } else if (beginAttr != null && endAttr != null) {
                    // Timed span → word
                    inWord = true;
                    wordBuf.setLength(0);
                    wordBegin = parseTime(beginAttr);
                    wordEnd = parseTime(endAttr);
                } else {
                    // Untimed wrapper span
                    wrapperDepth++;
                }
            } else if (event == XmlPullParser.END_TAG) {
                final String local = localName(p.getName());

                if ("span".equals(local)) {
                    if (inBgTranslation) {
                        // End of inline bg translation
                        inBgTranslation = false;
                    } else if (inBgRoman) {
                        // End of inline bg romanization
                        inBgRoman = false;
                    } else if (inBgWord) {
                        // End of bg word span
                        inBgWord = false;
                        final String normalized = normalizeTextRaw(wordBuf.toString());
                        final boolean startsWithSpace = !normalized.isEmpty()
                                && Character.isWhitespace(normalized.charAt(0));
                        final boolean endsWithSpace = !normalized.isEmpty()
                                && Character.isWhitespace(normalized.charAt(normalized.length() - 1));
                        final String text = normalized.trim();
                        bgFullText.append(normalized);
                        if (startsWithSpace && !bgWords.isEmpty()) {
                            final Word prev = bgWords.get(bgWords.size() - 1);
                            bgWords.set(bgWords.size() - 1,
                                    new Word(prev.startMs(), prev.endMs(), prev.text(),
                                            prev.romaji(), true));
                        }
                        if (!text.isEmpty()) {
                            bgWords.add(new Word(bgWordBegin, bgWordEnd, text, null,
                                    endsWithSpace));
                        }
                    } else if (inBg && bgWrapperDepth > 0) {
                        bgWrapperDepth--;
                    } else if (inBg) {
                        // End of bg span → finalize background vocal
                        inBg = false;
                    } else if (inRubyText && inRubyContainer) {
                        inRubyText = false;
                        final String text = normalizeText(rubyTextBuf.toString());
                        if (!text.isEmpty()) {
                            rubyTags.add(new RomajiSyllable(
                                    rubyTextBegin, rubyTextEnd, text));
                        }
                    } else if (inRubyTextContainer && inRubyContainer) {
                        inRubyTextContainer = false;
                    } else if (inRubyBase && inRubyContainer) {
                        inRubyBase = false;
                    } else if (inRubyContainer) {
                        // End of ruby container → finalize ruby word
                        inRubyContainer = false;
                        final String baseText = normalizeText(rubyBaseBuf.toString());
                        if (!baseText.isEmpty()) {
                            long rBegin = 0, rEnd = 0;
                            if (!rubyTags.isEmpty()) {
                                rBegin = rubyTags.get(0).startMs;
                                rEnd = rubyTags.get(rubyTags.size() - 1).endMs;
                            }
                            // Build romanization from ruby tags
                            final StringBuilder rRoma = new StringBuilder();
                            for (RomajiSyllable rs : rubyTags) {
                                if (rRoma.length() > 0) rRoma.append(' ');
                                rRoma.append(rs.text);
                            }
                            final String romaji = rRoma.length() > 0 ? rRoma.toString() : null;

                            // Add base text as word with timing from ruby tags
                            fullText.append(baseText);
                            words.add(new Word(rBegin, rEnd, baseText, romaji, false));
                        }
                    } else if (inTranslation) {
                        inTranslation = false;
                    } else if (inRoman) {
                        inRoman = false;
                    } else if (inWord) {
                        // End of word span
                        inWord = false;
                        final String normalized = normalizeTextRaw(wordBuf.toString());
                        final boolean startsWithSpace = !normalized.isEmpty()
                                && Character.isWhitespace(normalized.charAt(0));
                        final boolean endsWithSpace = !normalized.isEmpty()
                                && Character.isWhitespace(normalized.charAt(normalized.length() - 1));
                        final String text = normalized.trim();
                        fullText.append(normalized);
                        if (startsWithSpace && !words.isEmpty()) {
                            final Word prev = words.get(words.size() - 1);
                            words.set(words.size() - 1,
                                    new Word(prev.startMs(), prev.endMs(), prev.text(),
                                            prev.romaji(), true));
                        }
                        if (!text.isEmpty()) {
                            words.add(new Word(wordBegin, wordEnd, text, null, endsWithSpace));
                        }
                    } else if (wrapperDepth > 0) {
                        wrapperDepth--;
                    }
                } else {
                }
                depth--;
            } else if (event == XmlPullParser.TEXT) {
                final String raw = p.getText();
                if (raw == null) continue;

                if (inRubyBase) {
                    rubyBaseBuf.append(raw);
                } else if (inRubyText) {
                    rubyTextBuf.append(raw);
                } else if (inBgWord) {
                    wordBuf.append(raw);
                } else if (inBgTranslation) {
                    bgTransBuf.append(raw);
                } else if (inBgRoman) {
                    bgRomanBuf.append(raw);
                } else if (inWord) {
                    wordBuf.append(raw);
                } else if (inTranslation) {
                    transBuf.append(raw);
                } else if (inRoman) {
                    romanBuf.append(raw);
                } else if (!inBg || bgWrapperDepth == 0) {
                    final boolean isFormatting = raw.contains("\n");
                    final String normalized = normalizeTextRaw(raw);

                    if (isFormatting && normalized.trim().isEmpty()) {
                    } else {
                        fullText.append(normalized);
                        if (!isFormatting && normalized.trim().isEmpty()) {
                            if (!words.isEmpty()) {
                                final Word prev = words.get(words.size() - 1);
                                words.set(words.size() - 1,
                                        new Word(prev.startMs(), prev.endMs(), prev.text(),
                                                prev.romaji(), true));
                            }
                            if (!inBg && !bgWords.isEmpty()) {
                                final Word prev = bgWords.get(bgWords.size() - 1);
                                bgWords.set(bgWords.size() - 1,
                                        new Word(prev.startMs(), prev.endMs(), prev.text(),
                                                prev.romaji(), true));
                            }
                        }
                    }
                }
            }
        }

        // Finalize line text from fullText (includes both word text and text node text)
        final String lineText = normalizeText(fullText.toString());
        if (lineText.isBlank()) {
            return null;
        }
        LyricsLine bgLine = null;
        if (!bgWords.isEmpty() || bgFullText.length() > 0) {
            final String bgText = normalizeText(bgFullText.toString());
            if (!bgText.isBlank()) {
                bgLine = new LyricsLine(
                        bgBeginMs > 0 ? bgBeginMs : pBegin,
                        bgText, bgWords);
            }
        }

        return new ParsedLine(pBegin, lineText, words, bgLine);
    }

    private static String buildLineRomaji(List<Word> words,
            @Nullable List<RomajiSyllable> sidecar) {
        // First try per-word romaji from words themselves
        final StringBuilder perWord = new StringBuilder();
        boolean hasPerWord = false;
        for (Word w : words) {
            if (w.romaji() != null && !w.romaji().isEmpty()) {
                hasPerWord = true;
                break;
            }
        }
        if (hasPerWord) {
            for (int i = 0; i < words.size(); i++) {
                final Word w = words.get(i);
                final String r = w.romaji();
                if (r != null && !r.isEmpty()) {
                    if (perWord.length() > 0) perWord.append(' ');
                    perWord.append(r);
                }
            }
            if (perWord.length() > 0) {
                return perWord.toString();
            }
        }

        // Fall back to sidecar transliteration
        if (sidecar != null && !sidecar.isEmpty()) {
            final StringBuilder sb = new StringBuilder();
            for (RomajiSyllable s : sidecar) {
                if (s.text.isEmpty()) continue;
                if (sb.length() > 0) sb.append(' ');
                sb.append(s.text);
            }
            final String result = sb.toString().trim();
            if (!result.isEmpty()) {
                return result;
            }
        }

        return "";
    }

    private static void buildTranslations(String lineId,
            Map<String, SidecarTranslation> sidecarTrans,
            @Nullable LyricsLine bgLine,
            Map<String, List<LyricsLine>> translations, int lineCount) {

        // Collect all languages from sidecar
        final Map<String, StringBuilder> mainByLang = new HashMap<>();
        final Map<String, StringBuilder> bgByLang = new HashMap<>();

        for (Map.Entry<String, SidecarTranslation> entry : sidecarTrans.entrySet()) {
            final String key = entry.getKey();
            final int sep = key.indexOf(':');
            if (sep < 0) continue;
            final String lang = key.substring(0, sep);
            final String forId = key.substring(sep + 1);
            if (!lineId.equals(forId)) continue;

            final SidecarTranslation st = entry.getValue();
            mainByLang.computeIfAbsent(lang, k -> new StringBuilder()).append(st.text);
            if (st.bgText != null) {
                bgByLang.computeIfAbsent(lang, k -> new StringBuilder()).append(st.bgText);
            }
        }

        // Add to translations map
        for (Map.Entry<String, StringBuilder> entry : mainByLang.entrySet()) {
            final String lang = entry.getKey();
            final String text = entry.getValue().toString().trim();
            if (text.isEmpty()) continue;
            final List<LyricsLine> langLines = translations.computeIfAbsent(
                    lang, k -> new ArrayList<>());
            // Pad with empty lines if needed
            while (langLines.size() < lineCount - 1) {
                langLines.add(new LyricsLine(LyricsLine.NO_TIME, ""));
            }
            langLines.add(new LyricsLine(LyricsLine.NO_TIME, text));
        }

        // Ensure all existing languages have entries for this line
        for (Map.Entry<String, List<LyricsLine>> entry : translations.entrySet()) {
            final List<LyricsLine> langLines = entry.getValue();
            while (langLines.size() < lineCount) {
                langLines.add(new LyricsLine(LyricsLine.NO_TIME, ""));
            }
        }
    }

    /**
     * Reads an attribute with three-level fallback: namespace URI → qualified name → localName match.
     * Aligns with AMLL TS {@code getAttr()}.
     */
    private static String getAttr(XmlPullParser p, @Nullable String ns,
            String local, @Nullable String fallbackQName) {
        if (ns != null) {
            final String val = p.getAttributeValue(ns, local);
            if (val != null) return val;
        }
        if (fallbackQName != null) {
            final String val = p.getAttributeValue(null, fallbackQName);
            if (val != null) return val;
        }
        for (int i = 0, n = p.getAttributeCount(); i < n; i++) {
            final String name = p.getAttributeName(i);
            if (name != null) {
                final int colon = name.indexOf(':');
                final String attrLocal = colon >= 0 ? name.substring(colon + 1) : name;
                if (attrLocal.equals(local)) {
                    return p.getAttributeValue(i);
                }
            }
        }
        return null;
    }

    private static String localName(@Nullable String name) {
        if (name == null) return null;
        final int idx = name.indexOf(':');
        return idx >= 0 ? name.substring(idx + 1) : name;
    }

    private static String normalizeTextRaw(@Nullable String text) {
        if (text == null) return "";
        return text.replaceAll(MULTI_SPACE.pattern(), " ");
    }

    private static String normalizeText(@Nullable String text) {
        if (text == null) return "";
        return text.trim().replaceAll(MULTI_SPACE.pattern(), " ");
    }

    private static String readTextContent(XmlPullParser p)
            throws XmlPullParserException, IOException {
        final StringBuilder sb = new StringBuilder();
        int depth = 1;
        while (depth > 0) {
            final int event = p.next();
            if (event == XmlPullParser.START_TAG) {
                depth++;
            } else if (event == XmlPullParser.END_TAG) {
                depth--;
            } else if (event == XmlPullParser.TEXT) {
                sb.append(p.getText());
            }
        }
        return sb.toString();
    }

    private static void skipElement(XmlPullParser p)
            throws XmlPullParserException, IOException {
        int depth = 1;
        while (depth > 0) {
            final int event = p.next();
            if (event == XmlPullParser.START_TAG) {
                depth++;
            } else if (event == XmlPullParser.END_TAG) {
                depth--;
            }
        }
    }

    private static long parseTime(@Nullable String value) {
        if (value == null || value.isBlank()) {
            return 0;
        }
        final String v = value.trim().toLowerCase();
        final Matcher unit = TIME_UNIT.matcher(v);
        if (unit.find()) {
            final double amount = Double.parseDouble(unit.group(1));
            final double multiplier;
            switch (unit.group(2)) {
                case "ms":
                    multiplier = 1;
                    break;
                case "s":
                    multiplier = 1000;
                    break;
                case "m":
                    multiplier = 60_000;
                    break;
                default: // "h"
                    multiplier = 3_600_000;
                    break;
            }
            return Math.max(0, Math.round(amount * multiplier));
        }

        final String[] parts = v.split(":");
        double seconds = 0;
        for (String part : parts) {
            if (part.isEmpty()) {
                return 0;
            }
            try {
                seconds = seconds * 60 + Double.parseDouble(part);
            } catch (NumberFormatException ex) {
                return 0;
            }
        }
        return Math.max(0, Math.round(seconds * 1000));
    }
}
