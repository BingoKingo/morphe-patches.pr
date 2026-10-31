/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/2625
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.lyrics.requests;

import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import app.morphe.extension.music.patches.lyrics.model.Lyrics;
import app.morphe.extension.music.patches.lyrics.model.LyricsLine;
import app.morphe.extension.music.patches.lyrics.model.TrackInfo;
import app.morphe.extension.music.patches.lyrics.model.Word;
import app.morphe.extension.music.patches.lyrics.parsers.TTMLParser;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.requests.Requester;

public final class BinimumProvider implements LyricsProvider {

    private static final String API_URL = "https://lyricsplus.binimum.org/v2/lyrics/get";
    private static final String LEGACY_BASE_URL = "https://lyrics-api.binimum.org/";

    private static final String BROWSER_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36";

    @Nullable
    private static LyricsLine buildLineFromCandidates(
            List<WordCandidate> candidates,
            long lineStart,
            long lineEnd,
            long lineDuration,
            @Nullable String rawFallbackText,
            @Nullable String agentId,
            boolean isDuet,
            boolean isBackground,
            @Nullable String songPart
    ) {
        if (candidates.isEmpty()) {
            return null;
        }

        List<Word> words = new ArrayList<>(candidates.size());
        StringBuilder textBuf = new StringBuilder();

        for (WordCandidate candidate : candidates) {
            long startTime = candidate.syl.optLong("time");
            long duration = candidate.syl.optLong("duration");
            long endTime = startTime + duration;
            String text = candidate.syl.optString("text", "");
            boolean endsWithSpace = text.endsWith(" ") || text.endsWith("\t");

            String cleanWord = isBackground ? text.replaceAll("[()（）]", "").trim() : text.trim();
            String cleanRoma = candidate.romaji;
            if (isBackground && cleanRoma != null) {
                cleanRoma = cleanRoma.replaceAll("[()（）]", "").trim();
            }

            if (!cleanWord.isEmpty()) {
                words.add(new Word(startTime, endTime, cleanWord, cleanRoma, endsWithSpace));
                textBuf.append(cleanWord);
                if (endsWithSpace) {
                    textBuf.append(' ');
                }
            }
        }

        String fullText = textBuf.toString().trim();

        // Words with no spaces between them in a spaced script are joined with spaces.
        if (fullText.indexOf(' ') < 0 && words.size() > 1 && !TTMLParser.isContinuousCjk(words.get(0).text())) {
            StringBuilder reconstructed = new StringBuilder();
            for (int i = 0; i < words.size(); i++) {
                Word word = words.get(i);
                if (i > 0) {
                    reconstructed.append(' ');
                }
                reconstructed.append(word.text());
                boolean hasSpace = (i < words.size() - 1);
                words.set(i, new Word(word.startMs(), word.endMs(), word.text(), word.romaji(), hasSpace));
            }
            fullText = reconstructed.toString();
        }

        // The last word ends the line, so it has no space after it.
        if (!words.isEmpty()) {
            Word lastWord = words.get(words.size() - 1);
            if (lastWord.endsWithSpace()) {
                words.set(words.size() - 1, new Word(lastWord.startMs(), lastWord.endMs(), lastWord.text(), lastWord.romaji(), false));
            }
        }

        if (fullText.isEmpty() && rawFallbackText != null && !rawFallbackText.isEmpty()) {
            fullText = rawFallbackText.trim();
        }

        if (fullText.isEmpty() && words.isEmpty()) {
            return null;
        }

        long start = words.isEmpty() ? lineStart : words.get(0).startMs();
        long end = words.isEmpty() ? lineEnd : words.get(words.size() - 1).endMs();
        if (end < start && lineDuration > 0) {
            end = start + lineDuration;
        }

        return new LyricsLine(start, end, fullText, words, agentId, isDuet, isBackground, songPart);
    }

    private static boolean isAgentDuet(@Nullable String agentId) {
        if (agentId == null || !agentId.matches("v\\d{1,6}")) {
            return false;
        }
        int num = Integer.parseInt(agentId.substring(1));
        return num > 0 && num % 2 == 0;
    }

    @Override
    public String name() {
        return "BiniLyrics";
    }

    @Nullable
    @Override
    public FetchResult fetch(TrackInfo track) throws Exception {
        if (track.title().isEmpty() || track.artist().isEmpty()) {
            return null;
        }

        // The v2 API is asked first, for the sources it merges.
        try {
            Lyrics lyrics = fetchFromApi(track);
            if (lyrics != null && !lyrics.lines().isEmpty()) {
                return FetchResult.of(lyrics, track);
            }
        } catch (Exception ex) {
            Logger.printDebug(() -> "BiniLyrics v2 fetch failed", ex);
        }

        // A title with a video tag is asked again without it.
        final String cleanTitle = track.title()
                .replace("(Official Video)", "")
                .replace("(Official Music Video)", "")
                .replace("(Official Audio)", "")
                .trim();
        if (!cleanTitle.equals(track.title())) {
            TrackInfo cleanTrack = new TrackInfo(cleanTitle, track.artist(), track.album(), track.durationSeconds());
            try {
                Lyrics lyrics = fetchFromApi(cleanTrack);
                if (lyrics != null && !lyrics.lines().isEmpty()) {
                    return FetchResult.of(lyrics, cleanTrack);
                }
            } catch (Exception ex) {
                Logger.printDebug(() -> "BiniLyrics v2 fetch failed for the cleaned title", ex);
            }
        }

        // The legacy API is the last resort.
        try {
            String lyricsUrl = resolveLyricsUrl(track);
            if (lyricsUrl != null) {
                HttpURLConnection connection = null;
                try {
                    connection = LyricsRequests.openConnection(lyricsUrl);
                    if (connection.getResponseCode() == Requester.HTTP_STATUS_CODE_SUCCESS) {
                        String ttml = Requester.parseString(connection);
                        return FetchResult.blind(TTMLParser.ttmlToLyrics(ttml, name(), lyricsUrl));
                    }
                } finally {
                    if (connection != null) {
                        connection.disconnect();
                    }
                }
            }
        } catch (Exception ex) {
            Logger.printDebug(() -> "Legacy Binimum LRCRED fetch failed", ex);
        }

        return null;
    }

    @Nullable
    private Lyrics fetchFromApi(TrackInfo track) throws IOException, JSONException {
        StringBuilder url = new StringBuilder(API_URL);
        url.append("?title=").append(LyricsRequests.encode(track.title()));
        url.append("&artist=").append(LyricsRequests.encode(track.artist()));
        if (!track.album().isEmpty()) {
            url.append("&album=").append(LyricsRequests.encode(track.album()));
        }
        if (track.durationSeconds() > 0) {
            url.append("&duration=").append(track.durationSeconds());
        }
        url.append("&source=apple,lyricsplus,qq");

        HttpURLConnection connection = null;
        try {
            connection = LyricsRequests.openConnection(url.toString());
            connection.setRequestProperty("User-Agent", BROWSER_USER_AGENT);
            connection.setRequestProperty("Accept", "application/json");
            final int code = connection.getResponseCode();
            if (code != Requester.HTTP_STATUS_CODE_SUCCESS) {
                return null;
            }
            JSONObject root = Requester.parseJSONObject(connection);
            return parseLyricsJson(root, url.toString());
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    @Nullable
    private Lyrics parseLyricsJson(JSONObject root, String sourceUrl) {
        if (root == null) {
            return null;
        }
        JSONArray lyricsArray = root.optJSONArray("lyrics");
        if (lyricsArray == null || lyricsArray.length() == 0) {
            return null;
        }

        JSONObject metadata = root.optJSONObject("metadata");
        List<String> songwriters = metadata == null ? null : songwritersOf(metadata);
        JSONArray songPartsJson = metadata == null ? null : metadata.optJSONArray("songParts");

        List<LyricsLine> lines = new ArrayList<>();
        List<LyricsLine> romanization = new ArrayList<>();
        Map<String, List<LyricsLine>> translations = new HashMap<>();
        boolean hasAnyWordSync = false;

        for (int i = 0; i < lyricsArray.length(); i++) {
            JSONObject item = lyricsArray.optJSONObject(i);
            if (item == null) continue;

            final LineParse parsed = parseItem(item, songPartsJson, lines);
            if (!parsed.words().isEmpty()) hasAnyWordSync = true;

            // The transliteration and translation of the line, if any.
            String transliterationText = parsed.transliterationText();
            if (hasText(transliterationText)) {
                romanization.add(new LyricsLine(LyricsLine.NO_TIME, transliterationText.trim()));
            }
            if (hasText(parsed.translationText())) {
                translations.computeIfAbsent("default", k -> new ArrayList<>())
                        .add(new LyricsLine(LyricsLine.NO_TIME, parsed.translationText().trim()));
            }
        }

        if (lines.isEmpty()) {
            return null;
        }

        boolean isSynced = lines.get(0).startTimeMs() != LyricsLine.NO_TIME;
        return new Lyrics(lines, name(), isSynced,
                romanization.isEmpty() ? null : romanization,
                translations.isEmpty() ? null : translations,
                null,
                songwriters,
                null,
                hasAnyWordSync ? "syllable" : "line",
                sourceUrl);
    }

    /** What one entry of the lyrics array contributed to the lyric. */
    private record LineParse(List<LyricsLine> words, @Nullable String transliterationText,
                             @Nullable String translationText) {
    }

    /** Adds the line's main and background lines to {@code lines}. */
    private static LineParse parseItem(JSONObject item, @Nullable JSONArray songPartsJson,
                                       List<LyricsLine> lines) {
        long lineTime = item.optLong("time", LyricsLine.NO_TIME);
        long lineDuration = item.optLong("duration", 0);
        long lineEndTime = (lineTime != LyricsLine.NO_TIME && lineDuration > 0)
                ? lineTime + lineDuration : LyricsLine.NO_TIME;
        String rawLineText = item.optString("text", "");

        JSONObject element = item.optJSONObject("element");
        String agentId = element == null ? null : element.optString("singer", "");
        if (agentId != null && agentId.isEmpty()) agentId = null;
        String songPart = element == null ? null : songPartOf(element, songPartsJson);
        boolean isDuet = isAgentDuet(agentId);

        JSONArray syllabus = item.optJSONArray("syllabus");
        JSONObject transliteration = item.optJSONObject("transliteration");
        String transliterationText = transliteration == null
                ? null : transliteration.optString("text", null);

        List<LyricsLine> added = new ArrayList<>(2);
        if (syllabus != null && syllabus.length() > 0) {
            addSyllableLines(lines, added, syllabus, transliteration, lineTime, lineEndTime,
                    lineDuration, rawLineText, agentId, isDuet, songPart);
        } else {
            addPlainLine(lines, lineTime, lineEndTime, rawLineText, agentId, isDuet, songPart);
        }

        return new LineParse(added, transliterationText,
                item.optString("translation", null));
    }

    /** Adds the syllable lines of a word-synced entry, lead singers first. */
    private static void addSyllableLines(List<LyricsLine> lines, List<LyricsLine> added,
                                         JSONArray syllabus, @Nullable JSONObject transliteration,
                                         long lineTime, long lineEndTime, long lineDuration,
                                         String rawLineText, @Nullable String agentId,
                                         boolean isDuet, @Nullable String songPart) {
        JSONArray transSyls = transliteration != null
                ? transliteration.optJSONArray("syllabus") : null;
        List<WordCandidate> mainSyls = new ArrayList<>();
        List<WordCandidate> bgSyls = new ArrayList<>();
        for (int s = 0; s < syllabus.length(); s++) {
            JSONObject syl = syllabus.optJSONObject(s);
            if (syl == null) continue;
            WordCandidate cand = new WordCandidate(syl, romajiAt(transSyls, s));
            if (syl.optBoolean("isBackground", false)) {
                bgSyls.add(cand);
            } else {
                mainSyls.add(cand);
            }
        }

        addCandidateLine(lines, added, mainSyls, lineTime, lineEndTime, lineDuration,
                rawLineText, agentId, isDuet, false, songPart);
        addCandidateLine(lines, added, bgSyls, lineTime, lineEndTime, lineDuration,
                null, agentId, isDuet, true, songPart);
    }

    private static void addCandidateLine(List<LyricsLine> lines, List<LyricsLine> added,
                                         List<WordCandidate> candidates, long lineTime,
                                         long lineEndTime, long lineDuration,
                                         @Nullable String text, @Nullable String agentId,
                                         boolean isDuet, boolean isBg, @Nullable String songPart) {
        LyricsLine line = buildLineFromCandidates(
                candidates, lineTime, lineEndTime, lineDuration, text, agentId, isDuet, isBg, songPart);
        if (line != null) {
            lines.add(line);
            added.add(line);
        }
    }

    /** Adds an entry that is only line synced, with no word timing. */
    private static void addPlainLine(List<LyricsLine> lines, long lineTime, long lineEndTime,
                                     String rawLineText, @Nullable String agentId,
                                     boolean isDuet, @Nullable String songPart) {
        String text = rawLineText.trim();
        boolean isBg = isBracketed(text);
        if (isBg) {
            text = text.substring(1, text.length() - 1).trim();
        }
        if (text.isEmpty()) return;
        lines.add(new LyricsLine(lineTime, lineEndTime, text, List.of(), agentId, isDuet, isBg, songPart));
    }

    /** A parenthesised line is a background vocal. */
    private static boolean isBracketed(String text) {
        return (text.startsWith("(") && text.endsWith(")"))
                || (text.startsWith("（") && text.endsWith("）"));
    }

    @Nullable
    private static String romajiAt(@Nullable JSONArray transSyls, int index) {
        if (transSyls == null || index >= transSyls.length()) {
            return null;
        }
        JSONObject tSyl = transSyls.optJSONObject(index);
        if (tSyl == null) {
            return null;
        }
        String roma = tSyl.optString("text", null);
        return roma == null || roma.trim().isEmpty() ? null : roma;
    }

    @Nullable
    private static String songPartOf(JSONObject element, @Nullable JSONArray songPartsJson) {
        int songPartIndex = element.optInt("songPartIndex", -1);
        if (songPartsJson == null || songPartIndex < 0 || songPartIndex >= songPartsJson.length()) {
            return null;
        }
        JSONObject sp = songPartsJson.optJSONObject(songPartIndex);
        if (sp == null) {
            return null;
        }
        String name = sp.optString("name", null);
        return name == null || name.isEmpty() ? null : name;
    }

    /** The songwriters the metadata lists, or null when it lists none. */
    @Nullable
    private static List<String> songwritersOf(JSONObject metadata) {
        JSONArray sw = metadata.optJSONArray("songWriters");
        if (sw == null || sw.length() == 0) {
            return null;
        }
        List<String> songwriters = new ArrayList<>();
        for (int i = 0; i < sw.length(); i++) {
            String writer = sw.optString(i);
            if (!writer.isEmpty()) {
                songwriters.add(writer);
            }
        }
        return songwriters;
    }

    private static boolean hasText(@Nullable String value) {
        return value != null && !value.trim().isEmpty();
    }

    @Nullable
    private String resolveLyricsUrl(TrackInfo track) throws IOException, JSONException {
        if (track.title().isEmpty() || track.artist().isEmpty()) {
            return null;
        }

        StringBuilder url = new StringBuilder(LEGACY_BASE_URL);
        url.append("?track=").append(LyricsRequests.encode(track.title()));
        url.append("&artist=").append(LyricsRequests.encode(track.artist()));
        if (!track.album().isEmpty()) {
            url.append("&album=").append(LyricsRequests.encode(track.album()));
        }
        if (track.durationSeconds() > 0) {
            url.append("&duration=").append(track.durationSeconds());
        }

        HttpURLConnection connection = null;
        try {
            connection = LyricsRequests.openConnection(url.toString());
            final int responseCode = connection.getResponseCode();
            if (responseCode == 404) {
                connection.disconnect();
                return null;
            }
            if (responseCode != 200) {
                LyricsRequests.logFailure(name(), connection);
                return null;
            }
            JSONObject response = Requester.parseJSONObject(connection);
            JSONArray results = response.optJSONArray("results");
            if (results == null || results.length() == 0) {
                return null;
            }
            JSONObject best = results.optJSONObject(0);
            if (best == null) {
                return null;
            }
            return LyricsRequests.optString(best, "lyricsUrl");
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private record WordCandidate(JSONObject syl, @Nullable String romaji) {
    }
}
