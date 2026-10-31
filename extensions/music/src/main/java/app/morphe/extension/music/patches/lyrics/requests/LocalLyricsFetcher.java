/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/2625
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.lyrics.requests;

import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.Context;
import android.database.Cursor;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.provider.MediaStore;

import androidx.annotation.Nullable;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.IntPredicate;

import app.morphe.extension.music.patches.lyrics.model.Lyrics;
import app.morphe.extension.music.patches.lyrics.model.LyricsLine;
import app.morphe.extension.music.patches.lyrics.parsers.CharactersConverter;
import app.morphe.extension.music.patches.lyrics.parsers.LRCParser;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;

public final class LocalLyricsFetcher {

    /**
     * Value of {@code MediaMetadataRetriever.METADATA_KEY_LYRICS}. Kept as a literal because the
     * music modules compile SDK does not expose the constant symbol. The key is only honored by
     * the framework on API 30+, so it is used only as a best-effort fallback after direct parsing.
     */
    private static final int METADATA_KEY_LYRICS = 0x104;
    private static final int MAX_TAG_BYTES = 16 * 1024 * 1024;
    private static final int M4A_TAIL_BYTES = 4 * 1024 * 1024;
    private static final int MAX_M4A_DEPTH = 32;

    /** M4A atoms whose children are read for lyrics. */
    private static final Set<String> M4A_CONTAINERS = Set.of(
            "meta", "ilst", "udta", "moov", "trak", "mdia", "minf", "stbl");

    private LocalLyricsFetcher() {
    }

    @Nullable
    public static Lyrics fetch(@Nullable Uri mediaUri) {
        if (mediaUri == null) {
            return null;
        }

        try {
            return readEmbeddedLyrics(mediaUri);
        } catch (Throwable ignored) {
            return null;
        }
    }

    @Nullable
    private static Lyrics readEmbeddedLyrics(Uri mediaUri) {
        Context context = Utils.getContext();
        if (context == null) {
            return null;
        }

        byte[] header = readBytes(context, mediaUri, 10);
        byte[] data;
        if (header != null && header.length >= 10 && startsWith(header, "ID3")) {
            final int tagSize = syncsafe(header[6], header[7], header[8], header[9]);
            final int totalRead = 10 + tagSize;
            data = readBytes(context, mediaUri,
                    Math.min(totalRead, MAX_TAG_BYTES));
        } else {
            // Not ID3v2 — read default limit for FLAC/OGG/M4A tail detection.
            data = readBytes(context, mediaUri, MAX_TAG_BYTES);
        }

        Lyrics parsed = parseData(data);
        if (parsed != null) {
            return parsed;
        }
        if (data != null && isLikelyM4a(data)) {
            Lyrics tailParsed = parseData(readBytesTail(context, mediaUri));
            if (tailParsed != null) {
                return tailParsed;
            }
        }

        return fallbackViaMediaMetadataRetriever(context, mediaUri);
    }

    /** The lyrics embedded in a block of file bytes, or null when it holds none. */
    @Nullable
    private static Lyrics parseData(@Nullable byte[] data) {
        return data == null ? null : parse(extractLyrics(data));
    }

    @Nullable
    public static Uri resolveMediaStoreUri(String title, String artist, int durationSeconds,
                                           @Nullable String rawTitle, @Nullable String rawArtist) {
        String primaryTitle = (title != null && !title.trim().isEmpty()) ? title : rawTitle;
        String primaryArtist = (artist != null && !artist.trim().isEmpty()) ? artist : rawArtist;
        if (primaryTitle == null || primaryTitle.trim().isEmpty()) {
            return null;
        }
        Context context = Utils.getContext();
        if (context == null) {
            return null;
        }
        ContentResolver resolver = context.getContentResolver();
        if (resolver == null) {
            return null;
        }

        Uri base = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI;
        String[] projection = {
                MediaStore.Audio.Media._ID,
                MediaStore.Audio.Media.ARTIST,
                MediaStore.Audio.Media.DURATION
        };

        // The raw title widens the net when the title was normalized; the artist is scored per row.
        List<String> titleCandidates = new ArrayList<>(2);
        titleCandidates.add(title);
        if (rawTitle != null && !rawTitle.equals(title)) {
            titleCandidates.add(rawTitle);
        }

        String wantArtist = normalize(primaryArtist);

        MediaStoreSearch search = new MediaStoreSearch(resolver, base, projection,
                wantArtist, durationSeconds);
        try {
            for (String t : titleCandidates) {
                if (isBlank(t)) {
                    continue;
                }
                search.queryTitle(t);
            }
        } catch (Exception ex) {
            Logger.printDebug(() -> "Could not query MediaStore for candidate URI", ex);
            return null;
        }
        return search.best;
    }

    private static boolean isBlank(@Nullable String value) {
        return value == null || value.trim().isEmpty();
    }

    /** One MediaStore lookup, keeping the closest row seen across every title tried. */
    private static final class MediaStoreSearch {
        private final ContentResolver resolver;
        private final Uri base;
        private final String[] projection;
        private final String wantArtist;
        private final int durationSeconds;

        @Nullable
        private Uri best;
        private long bestScore = Long.MAX_VALUE;

        MediaStoreSearch(ContentResolver resolver, Uri base, String[] projection,
                         String wantArtist, int durationSeconds) {
            this.resolver = resolver;
            this.base = base;
            this.projection = projection;
            this.wantArtist = wantArtist;
            this.durationSeconds = durationSeconds;
        }

        void queryTitle(String title) {
            String selection = MediaStore.Audio.Media.TITLE + " LIKE ? ESCAPE '\\'";
            Cursor cursor = resolver.query(base, projection, selection,
                    new String[]{likeEscape(title)}, null);
            if (cursor != null && cursor.getCount() == 0) {
                cursor.close();
                cursor = resolver.query(base, projection, selection,
                        new String[]{"%" + likeEscape(title) + "%"}, null);
            }
            if (cursor == null) {
                return;
            }
            try {
                final int idCol = cursor.getColumnIndex(MediaStore.Audio.Media._ID);
                final int artistCol = cursor.getColumnIndex(MediaStore.Audio.Media.ARTIST);
                final int durCol = cursor.getColumnIndex(MediaStore.Audio.Media.DURATION);
                if (idCol < 0) {
                    return;
                }
                while (cursor.moveToNext()) {
                    consider(cursor.getLong(idCol),
                            artistCol >= 0 ? cursor.getString(artistCol) : null,
                            durCol >= 0 ? cursor.getLong(durCol) : 0);
                }
            } finally {
                cursor.close();
            }
        }

        /** Keeps the row when its distance score is no worse than the best so far. */
        private void consider(long id, @Nullable String storedArtist, long durMs) {
            long score = artistPenalty(storedArtist);
            if (durationSeconds > 0 && durMs > 0) {
                score += Math.abs(durMs - (long) durationSeconds * 1000L);
            }
            if (score < bestScore) {
                bestScore = score;
                best = ContentUris.withAppendedId(base, id);
            }
        }

        /**
         * How far the stored artist is from the wanted one: a row with no artist is a poor match,
         * a different artist a worse one, and a either-way substring match none at all.
         */
        private long artistPenalty(@Nullable String storedArtist) {
            if (wantArtist.isEmpty()) {
                return 0;
            }
            String normStored = normalize(storedArtist);
            if (normStored.isEmpty()) {
                return 1_000_000L;
            }
            if (normStored.contains(wantArtist) || wantArtist.contains(normStored)) {
                return 0;
            }
            return 500_000L;
        }
    }

    @Nullable
    private static byte[] readBytes(Context context, Uri uri, int limit) {
        ContentResolver resolver = context.getContentResolver();
        if (resolver == null) {
            return null;
        }
        try (InputStream in = resolver.openInputStream(uri)) {
            if (in == null) {
                return null;
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream(Math.min(limit, 1 << 16));
            byte[] buf = new byte[1 << 16];
            int total = 0;
            int n;
            while ((n = in.read(buf)) != -1) {
                if (total + n > limit) {
                    final int remaining = limit - total;
                    if (remaining > 0) {
                        out.write(buf, 0, remaining);
                    }
                    break;
                }
                out.write(buf, 0, n);
                total += n;
            }
            return out.toByteArray();
        } catch (IOException | SecurityException | NullPointerException ex) {
            Logger.printDebug(() -> "Could not read bytes from URI", ex);
            return null;
        }
    }

    @Nullable
    private static byte[] readBytesTail(Context context, Uri uri) {
        ContentResolver resolver = context.getContentResolver();
        if (resolver == null) {
            return null;
        }
        try (InputStream in = resolver.openInputStream(uri)) {
            if (in == null) {
                return null;
            }
            TailRing ring = new TailRing();
            byte[] buf = new byte[1 << 16];
            int n;
            while ((n = in.read(buf)) != -1) {
                ring.append(buf, n);
            }
            return ring.toByteArray();
        } catch (IOException | SecurityException | NullPointerException ex) {
            Logger.printDebug(() -> "Could not read tail bytes from URI", ex);
            return null;
        }
    }

    /** Keeps the last {@link #M4A_TAIL_BYTES} of a stream, wrapping to the start when it fills. */
    private static final class TailRing {
        private final byte[] ring = new byte[M4A_TAIL_BYTES];
        private int pos;
        private int len;

        /** Appends up to {@code count} bytes, dropping the oldest when the ring is full. */
        void append(byte[] src, int count) {
            int remaining = count;
            int srcOff = 0;
            while (remaining > 0) {
                final int space = M4A_TAIL_BYTES - len;
                final int take = Math.min(space, remaining);
                System.arraycopy(src, srcOff, ring, pos, take);
                pos = (pos + take) % M4A_TAIL_BYTES;
                len += take;
                srcOff += take;
                remaining -= take;
            }
        }

        /** The bytes kept so far in order, or null when nothing was read. */
        @Nullable
        byte[] toByteArray() {
            if (len == 0) {
                return null;
            }
            byte[] result = new byte[len];
            if (len < M4A_TAIL_BYTES) {
                System.arraycopy(ring, 0, result, 0, len);
                return result;
            }
            System.arraycopy(ring, pos, result, 0, M4A_TAIL_BYTES - pos);
            System.arraycopy(ring, 0, result, M4A_TAIL_BYTES - pos, pos);
            return result;
        }
    }

    private static boolean isLikelyM4a(byte[] d) {
        return d.length >= 8
                && d[4] == 'f' && d[5] == 't' && d[6] == 'y' && d[7] == 'p';
    }

    @Nullable
    private static String extractLyrics(byte[] d) {
        if (startsWith(d, "ID3")) {
            String s = parseId3v2(d);
            if (s != null) {
                return s;
            }
        }
        if (startsWith(d, "fLaC")) {
            String s = parseFlac(d);
            if (s != null) {
                return s;
            }
        }
        if (startsWith(d, "OggS")) {
            String s = parseOgg(d);
            if (s != null) {
                return s;
            }
        }
        if (isLikelyM4a(d)) {
            return walkM4a(d, 0, d.length, 0);
        }
        return null;
    }

    /** True when the data carries the ASCII signature starting at {@code offset}. */
    private static boolean startsWith(byte[] d, int offset, String signature) {
        if (d.length < offset + signature.length()) {
            return false;
        }
        for (int i = 0; i < signature.length(); i++) {
            if (d[offset + i] != signature.charAt(i)) {
                return false;
            }
        }
        return true;
    }

    /** True when the data opens with the given ASCII signature. */
    private static boolean startsWith(byte[] d, String signature) {
        return startsWith(d, 0, signature);
    }

    private static boolean isOutOfBounds(byte[] d, int off, int len) {
        return off < 0 || len <= 0 || off + (long) len > d.length;
    }

    @Nullable
    private static String parseId3v2(byte[] d) {
        if (d.length < 10) {
            return null;
        }
        final int major = d[3] & 0xFF;
        final int tagSize = syncsafe(d[6], d[7], d[8], d[9]);
        int pos = frameStart(d, major);
        if (pos < 0) {
            return null;
        }
        final int end = Math.min(pos + tagSize, d.length);
        final int idLen = major == 2 ? 3 : 4;
        final int headerLen = major == 2 ? 6 : 10;
        final boolean syncsafeSize = major == 4;
        while (pos + headerLen <= end) {
            String id = new String(d, pos, idLen, StandardCharsets.ISO_8859_1);
            final int size = frameSize(d, pos, headerLen, syncsafeSize);
            final int bodyOff = pos + headerLen;
            if (size < 0 || bodyOff + size > d.length) {
                break;
            }
            String lyrics = frameLyrics(d, id, bodyOff, size);
            if (lyrics != null) {
                return lyrics;
            }
            // Null padding detection: all bytes of frame ID are 0 -> padding reached.
            if (isNullPadding(d, pos, idLen)) {
                break;
            }
            pos = bodyOff + size;
        }
        return null;
    }

    /**
     * Where the first ID3v2 frame starts, skipping the extended header when the tag has one.
     *
     * @return The frame start, or -1 when the tag is too short or its extended header is invalid.
     */
    private static int frameStart(byte[] d, int major) {
        if ((major != 3 && major != 4) || (d[5] & 0x40) == 0) {
            return 10;
        }
        if (d.length < 14) {
            return -1;
        }
        final int extSize = readInt32BE(d, 10);
        if (extSize < 0 || extSize > d.length - 10) {
            return -1;
        }
        return 10 + extSize;
    }

    /** The size field of the frame at {@code pos}, in whichever encoding the tag version uses. */
    private static int frameSize(byte[] d, int pos, int headerLen, boolean syncsafeSize) {
        if (headerLen == 6) {
            return ((d[pos + 3] & 0xFF) << 16) | ((d[pos + 4] & 0xFF) << 8) | (d[pos + 5] & 0xFF);
        }
        return syncsafeSize
                ? syncsafe(d[pos + 4], d[pos + 5], d[pos + 6], d[pos + 7])
                : readInt32BE(d, pos + 4);
    }

    /** The lyrics an ID3v2 frame carries, or null when the frame holds none. */
    @Nullable
    private static String frameLyrics(byte[] d, String id, int off, int len) {
        switch (id) {
            case "USLT", "ULT": {
                return nonBlank(parseTextFrame(d, off, len));
            }
            case "SYLT", "SLT": {
                return nonBlank(parseSylt(d, off, len));
            }
            case "TXXX": {
                if (!namesLyrics(parseTxxxDescription(d, off, len))) {
                    return null;
                }
                return nonBlank(parseTxxxValue(d, off, len));
            }
            case "COMM": {
                if (!namesLyrics(parseCommDescription(d, off, len))) {
                    return null;
                }
                return nonBlank(parseCommBody(d, off, len));
            }
            default:
                return null;
        }
    }

    /** True for a frame description that names lyrics, which TXXX and COMM frames both use. */
    private static boolean namesLyrics(@Nullable String desc) {
        return desc != null && desc.length() >= 5
                && (desc.regionMatches(true, 0, "LYRICS", 0, 6)
                || desc.regionMatches(true, 0, "LYRIC", 0, 5));
    }

    /** The text itself when it has any non-blank content, or null. */
    @Nullable
    private static String nonBlank(@Nullable String text) {
        return text != null && !text.trim().isEmpty() ? text : null;
    }

    private static boolean isNullPadding(byte[] d, int pos, int idLen) {
        for (int k = 0; k < idLen; k++) {
            if (d[pos + k] != 0) {
                return false;
            }
        }
        return true;
    }

    @Nullable
    private static String parseTxxxDescription(byte[] d, int off, int len) {
        if (isOutOfBounds(d, off, len) || len <= 1) return null;
        final int enc = d[off] & 0xFF;
        final int frameEnd = off + len;
        final int descEnd = indexOfNullTerm(d, off + 1, frameEnd, enc);
        if (descEnd < 0 || descEnd >= frameEnd) return null;
        return decodeAutoDetect(copy(d, off + 1, descEnd - off - 1), enc).trim();
    }

    @Nullable
    private static String parseTxxxValue(byte[] d, int off, int len) {
        if (isOutOfBounds(d, off, len) || len <= 1) return null;
        final int enc = d[off] & 0xFF;
        final int frameEnd = off + len;
        final int descEnd = indexOfNullTerm(d, off + 1, frameEnd, enc);
        if (descEnd < 0 || descEnd >= frameEnd) return null;
        return frameText(d, descEnd, frameEnd, enc);
    }

    @Nullable
    private static String parseCommDescription(byte[] d, int off, int len) {
        if (isOutOfBounds(d, off, len) || len <= 4) return null;
        final int enc = d[off] & 0xFF;
        final int frameEnd = off + len;
        final int descStart = off + 1 + 3;
        final int descEnd = indexOfNullTerm(d, descStart, frameEnd, enc);
        if (descEnd < 0 || descEnd >= frameEnd) return null;
        return decodeAutoDetect(copy(d, descStart, descEnd - descStart), enc).trim();
    }

    @Nullable
    private static String parseCommBody(byte[] d, int off, int len) {
        if (isOutOfBounds(d, off, len) || len <= 4) return null;
        final int enc = d[off] & 0xFF;
        final int frameEnd = off + len;
        final int descStart = off + 1 + 3;
        final int descEnd = indexOfNullTerm(d, descStart, frameEnd, enc);
        if (descEnd < 0 || descEnd >= frameEnd) return null;
        return frameText(d, descEnd, frameEnd, enc);
    }

    @Nullable
    private static String parseTextFrame(byte[] d, int off, int len) {
        if (isOutOfBounds(d, off, len) || len <= 0) {
            return null;
        }
        final int enc = d[off] & 0xFF;
        final int frameEnd = off + len;

        int descStart = off + 1 + 3;
        if (descStart > frameEnd) {
            descStart = off + 1;
        }

        int lyricsStart = indexOfNullTerm(d, descStart, frameEnd, enc);
        if (lyricsStart < 0 || lyricsStart >= frameEnd) {
            return null;
        }

        return frameText(d, lyricsStart, frameEnd, enc);
    }

    /**
     * The text of a frame that continues from {@code textStart} to the end of the frame.
     *
     * <p>UTF-16 bodies carry no terminator of their own and run to the frame end; every other
     * encoding ends at its first terminator, or at the frame end when there is none. A byte order
     * mark at the start of the text is skipped, and text of no length yields nothing.
     */
    @Nullable
    private static String frameText(byte[] d, int textStart, int frameEnd, int enc) {
        final int start = skipBomOrBidiMarker(d, textStart, frameEnd, enc);
        int end = frameEnd;
        if (enc != 1 && enc != 2) {
            final int term = indexOfNullTerm(d, start, frameEnd, enc);
            if (term >= 0) {
                end = term;
            }
        }
        final int textLen = end - start;
        if (textLen <= 0) {
            return null;
        }
        return decodeAutoDetect(copy(d, start, textLen), enc).trim();
    }

    @Nullable
    private static String parseSylt(byte[] d, int off, int len) {
        if (isOutOfBounds(d, off, len) || len < 5) {
            return null;
        }
        final int enc = d[off] & 0xFF;
        final int tsFormat = d[off + 4] & 0xFF;
        final int tsBytes = (tsFormat == 2) ? 4 : 2;
        final int frameEnd = off + len;
        int p = off + 1 + 3 + 1 + 1;
        if (p + 1 > frameEnd) {
            return null;
        }
        final int textStart = indexOfNullTerm(d, p, frameEnd, enc);
        if (textStart < 0) {
            return null;
        }
        final int term = termLength(enc);
        StringBuilder sb = new StringBuilder();
        int q = textStart;
        while (q < frameEnd) {
            final int tEnd = indexOfNullTerm(d, q, frameEnd, enc);
            final int segLen = ((tEnd < 0) ? frameEnd : tEnd) - q;
            if (segLen <= 0) {
                break;
            }
            appendSyltSegment(sb, d, q, segLen, enc, syltTimestamp(d, tEnd, tsBytes, frameEnd));
            if (tEnd < 0) {
                break;
            }
            q = tEnd + term + tsBytes;
        }
        String result = sb.toString().trim();
        return result.isEmpty() ? null : result;
    }

    /** The timestamp stored after a SYLT segment, or -1 when it does not fit in the frame. */
    private static long syltTimestamp(byte[] d, int tEnd, int tsBytes, int frameEnd) {
        if (tEnd < 0 || tEnd + tsBytes > frameEnd) {
            return -1;
        }
        return (tsBytes == 4)
                ? readInt32BE(d, tEnd) & 0xFFFFFFFFL
                : ((d[tEnd] & 0xFF) << 8) | (d[tEnd + 1] & 0xFF);
    }

    /** Adds one SYLT segment to {@code sb}, prefixed by its timestamp when it has one. */
    private static void appendSyltSegment(StringBuilder sb, byte[] d, int start, int segLen,
                                          int enc, long tsMs) {
        String seg = decodeAutoDetect(copy(d, start, segLen), enc).trim();
        if (seg.isEmpty()) {
            return;
        }
        if (tsMs >= 0) {
            sb.append('[')
                    .append(LRCParser.formatCentiseconds(tsMs))
                    .append(']');
        }
        sb.append(seg).append('\n');
    }

    @Nullable
    private static String parseFlac(byte[] d) {
        if (!startsWith(d, "fLaC")) {
            return null;
        }
        int pos = 4;
        while (pos + 4 <= d.length) {
            final int header = d[pos] & 0xFF;
            final boolean last = (header & 0x80) != 0;
            final int type = header & 0x7F;
            final int size = readU24BE(d, pos + 1);
            final int body = pos + 4;
            if (body + size > d.length) {
                break;
            }
            if (type == 4) {
                String s = parseVorbisComment(d, body, size);
                if (s != null) {
                    return s;
                }
            }
            pos = body + size;
            if (last) {
                break;
            }
        }
        return null;
    }

    /** The lyric keys a Vorbis comment may carry, longest prefix first so no shorter key matches one. */
    private static final String[] VORBIS_LYRIC_KEYS = {
            "UNSYNCEDLYRICS=", "SYNCEDLYRICS=", "LYRICS=", "LYRIC="
    };

    /**
     * The value of a Vorbis comment that names lyrics, or null when it names something else.
     * The key match is case-insensitive, as the tags themselves are.
     */
    @Nullable
    private static String vorbisLyricValue(String comment) {
        for (String key : VORBIS_LYRIC_KEYS) {
            if (comment.regionMatches(true, 0, key, 0, key.length())) {
                return comment.substring(key.length());
            }
        }
        return null;
    }

    @Nullable
    private static String parseVorbisComment(byte[] d, int start, int size) {
        if (isOutOfBounds(d, start, size)) {
            return null;
        }
        final int end = start + size;
        int p = start;
        if (p + 4 > end) {
            return null;
        }
        final int vendorLen = readInt32LE(d, p);
        if (vendorLen < 0 || vendorLen > end - (p + 4)) {
            return null;
        }
        p += 4 + vendorLen;
        if (p + 4 > end) {
            return null;
        }
        final int count = readInt32LE(d, p);
        p += 4;
        for (int i = 0; i < count && p + 4 <= end; i++) {
            final int len = readInt32LE(d, p);
            p += 4;
            if (len < 0 || p + (long) len > end) {
                break;
            }
            String comment = new String(d, p, len, StandardCharsets.UTF_8);
            p += len;
            String value = vorbisLyricValue(comment);
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    @Nullable
    private static String parseOgg(byte[] d) {
        int pos = 0;
        StringBuilder packet = new StringBuilder();
        int segmentsLeft = 0;
        while (pos + 27 <= d.length) {
            if (!startsWith(d, pos, "OggS")) {
                break;
            }
            final int numSegments = d[pos + 26] & 0xFF;
            final int tableEnd = pos + 27 + numSegments;
            if (tableEnd > d.length) {
                break;
            }
            int dataPos = tableEnd;
            for (int i = 0; i < numSegments; i++) {
                final int segLen = d[pos + 27 + i] & 0xFF;
                if (dataPos + segLen > d.length) {
                    return null;
                }
                packet.append(new String(d, dataPos, segLen, StandardCharsets.ISO_8859_1));
                dataPos += segLen;
            }
            final boolean continues =
                    numSegments > 0 && (d[pos + 27 + numSegments - 1] & 0xFF) == 255;
            if (continues) {
                segmentsLeft++;
            } else if (segmentsLeft > 0) {
                segmentsLeft--;
            } else {
                String s = completePacketLyrics(packet.toString());
                if (s != null) {
                    return s;
                }
                packet.setLength(0);
            }
            pos = dataPos;
        }
        return null;
    }

    /** The lyrics of one complete Ogg packet, or null when it holds no Vorbis comment. */
    @Nullable
    private static String completePacketLyrics(String payload) {
        final int marker = payload.indexOf("\003vorbis");
        if (marker < 0) {
            return null;
        }
        return parseVorbisCommentFromPacket(payload, marker + 7);
    }

    @Nullable
    private static String parseVorbisCommentFromPacket(String payload, int off) {
        final int len = payload.length();
        if (off < 0 || off + 4 > len) {
            return null;
        }
        int p = off;
        final int vendorLen = ((payload.charAt(p) & 0xFF))
                | ((payload.charAt(p + 1) & 0xFF) << 8)
                | ((payload.charAt(p + 2) & 0xFF) << 16)
                | ((payload.charAt(p + 3) & 0xFF) << 24);
        if (vendorLen < 0 || vendorLen > len - (p + 4)) {
            return null;
        }
        p += 4 + vendorLen;
        if (p + 4 > len) {
            return null;
        }
        final int count = ((payload.charAt(p) & 0xFF))
                | ((payload.charAt(p + 1) & 0xFF) << 8)
                | ((payload.charAt(p + 2) & 0xFF) << 16)
                | ((payload.charAt(p + 3) & 0xFF) << 24);
        p += 4;
        for (int i = 0; i < count && p + 4 <= len; i++) {
            final int entryLen = ((payload.charAt(p) & 0xFF))
                    | ((payload.charAt(p + 1) & 0xFF) << 8)
                    | ((payload.charAt(p + 2) & 0xFF) << 16)
                    | ((payload.charAt(p + 3) & 0xFF) << 24);
            p += 4;
            if (entryLen < 0 || p + (long) entryLen > len) {
                break;
            }
            String comment = payload.substring(p, p + entryLen);
            p += entryLen;
            String value = vorbisLyricValue(comment);
            if (value != null) {
                return decodeAutoDetect(value.getBytes(StandardCharsets.ISO_8859_1), 3);
            }
        }
        return null;
    }

    @Nullable
    private static String walkM4a(byte[] d, int start, int end, int depth) {
        if (depth > MAX_M4A_DEPTH) {
            return null;
        }
        int pos = start;
        while (pos + 8 <= end) {
            final int size = readInt32BE(d, pos);
            if (size < 8 || pos + size > end) {
                break;
            }
            String found = m4aAtomLyrics(d, pos, pos + size, depth);
            if (found != null) {
                return found;
            }
            pos += size;
        }
        return null;
    }

    /** The lyrics an M4A atom carries, reading its payload or descending into its children. */
    @Nullable
    private static String m4aAtomLyrics(byte[] d, int pos, int atomEnd, int depth) {
        final int c0 = d[pos + 4] & 0xFF;
        final int c1 = d[pos + 5] & 0xFF;
        final int c2 = d[pos + 6] & 0xFF;
        final int c3 = d[pos + 7] & 0xFF;
        final boolean isLyrics = (c0 == 0xA9 && c1 == 'l' && c2 == 'y' && c3 == 'r')
                || (c0 == 'l' && c1 == 'y' && c2 == 'r' && c3 == ' ')
                || (c0 == 'L' && c1 == 'Y' && c2 == 'R' && c3 == ' ');
        if (isLyrics) {
            return nonBlank(readM4aData(d, pos + 8, atomEnd));
        }
        String type = new String(d, pos + 4, 4, StandardCharsets.ISO_8859_1);
        if (!M4A_CONTAINERS.contains(type)) {
            return null;
        }
        int child = type.equals("meta") ? pos + 12 : pos + 8;
        return walkM4a(d, child, atomEnd, depth + 1);
    }

    @Nullable
    private static String readM4aData(byte[] d, int start, int end) {
        int p = start;
        while (p + 8 <= end) {
            final int size = readInt32BE(d, p);
            if (size < 8 || p + size > end) {
                break;
            }
            final int c0 = d[p + 4] & 0xFF;
            final int c1 = d[p + 5] & 0xFF;
            final int c2 = d[p + 6] & 0xFF;
            final int c3 = d[p + 7] & 0xFF;
            if (c0 == 'd' && c1 == 'a' && c2 == 't' && c3 == 'a') {
                final int contentStart = p + 16;
                final int contentEnd = p + size;
                if (contentEnd <= contentStart) {
                    return null;
                }
                byte[] content = copy(d, contentStart, contentEnd - contentStart);
                String trimmed = decodeAutoDetect(content, 3)
                        .replace("\uFEFF", "").replace("\0", " ").trim();
                return trimmed.isEmpty() ? null : trimmed;
            }
            p += size;
        }
        return null;
    }

    @Nullable
    private static Lyrics fallbackViaMediaMetadataRetriever(Context context, Uri uri) {
        try (MediaMetadataRetriever retriever = new MediaMetadataRetriever()) {
            retriever.setDataSource(context, uri);
            String raw = retriever.extractMetadata(METADATA_KEY_LYRICS);
            if (raw == null || raw.trim().isEmpty()) {
                return null;
            }
            return parse(raw);
        } catch (Exception ex) {
            Logger.printDebug(() -> "Could not extract lyrics via MediaMetadataRetriever", ex);
            return null;
        }
    }

    @Nullable
    private static Lyrics parse(@Nullable String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return null;
        }

        LRCParser.LrcParseResult result = LRCParser.parseSyncedWithCreditLines(raw);
        if (!result.lines.isEmpty()) {
            return new Lyrics(result.lines, "Local", result.lines.stream().anyMatch(LyricsLine::hasWords),
                    null, null, null,
                    result.creditLines.isEmpty() ? null : result.creditLines,
                    raw, "lrc", null);
        }

        List<LyricsLine> plain = LRCParser.parsePlain(raw);
        if (!plain.isEmpty()) {
            return new Lyrics(plain, "Local", false, null, null, null, null, raw, "lrc", null);
        }

        return null;
    }

    private static int syncsafe(int b0, int b1, int b2, int b3) {
        return ((b0 & 0x7F) << 21) | ((b1 & 0x7F) << 14) | ((b2 & 0x7F) << 7) | (b3 & 0x7F);
    }

    private static int readInt32BE(byte[] d, int off) {
        return ((d[off] & 0xFF) << 24) | ((d[off + 1] & 0xFF) << 16)
                | ((d[off + 2] & 0xFF) << 8) | (d[off + 3] & 0xFF);
    }

    private static int readInt32LE(byte[] d, int off) {
        return ((d[off] & 0xFF)) | ((d[off + 1] & 0xFF) << 8)
                | ((d[off + 2] & 0xFF) << 16) | ((d[off + 3] & 0xFF) << 24);
    }

    private static int skipBomOrBidiMarker(byte[] d, int start, int end, int enc) {
        int pos = start;
        if (enc == 1 || enc == 2) {
            for (int i = pos; i + 1 < end; i++) {
                if ((d[i] & 0xFF) == 0xFF && (d[i + 1] & 0xFF) == 0xFE) {
                    pos = i;
                    break;
                }
                if ((d[i] & 0xFF) == 0xFE && (d[i + 1] & 0xFF) == 0xFF) {
                    pos = i;
                    break;
                }
            }
            for (int i = pos; i + 2 < end; i++) {
                if ((d[i] & 0xFF) == 0xEF
                        && (d[i + 1] & 0xFF) == 0xBB
                        && (d[i + 2] & 0xFF) == 0xBF) {
                    pos = i;
                    break;
                }
            }
        }
        return pos;
    }

    private static int readU24BE(byte[] d, int off) {
        return ((d[off] & 0xFF) << 16) | ((d[off + 1] & 0xFF) << 8) | (d[off + 2] & 0xFF);
    }

    private static int termLength(int enc) {
        return (enc == 1 || enc == 2) ? 2 : 1;
    }

    private static int indexOfNullTerm(byte[] d, int start, int end, int enc) {
        final int term = termLength(enc);
        final int limit = end - term + 1;
        for (int i = start; i < limit; i++) {
            if (term == 1) {
                if ((d[i] & 0xFF) == 0) {
                    return i + 1;
                }
            } else {
                if ((d[i] & 0xFF) == 0 && (d[i + 1] & 0xFF) == 0) {
                    return i + 2;
                }
            }
        }
        return -1;
    }

    private static String decodeAutoDetect(byte[] b, int declaredEnc) {
        if (b == null || b.length == 0) {
            return "";
        }
        if (startsWith(b, 0xEF, 0xBB, 0xBF)) {
            return new String(b, 3, b.length - 3, StandardCharsets.UTF_8);
        }
        if (startsWith(b, 0xFF, 0xFE)) {
            return new String(b, StandardCharsets.UTF_16);
        }
        if (startsWith(b, 0xFE, 0xFF)) {
            return new String(b, StandardCharsets.UTF_16BE);
        }
        if (declaredEnc == 1 && b.length >= 2) {
            return new String(b, StandardCharsets.UTF_16LE);
        }
        if (declaredEnc == 2 && b.length >= 2) {
            return new String(b, StandardCharsets.UTF_16BE);
        }
        if (isValidUtf8(b)) {
            return new String(b, StandardCharsets.UTF_8);
        }
        String guessed = guessDoubleByte(b);
        if (guessed != null) {
            return guessed;
        }
        return switch (declaredEnc) {
            case 1 -> new String(b, StandardCharsets.UTF_16);
            case 2 -> new String(b, StandardCharsets.UTF_16BE);
            case 3 -> new String(b, StandardCharsets.UTF_8);
            default -> new String(b, StandardCharsets.ISO_8859_1);
        };
    }

    /** True when the data opens with the given byte values. */
    private static boolean startsWith(byte[] b, int... prefix) {
        if (b.length < prefix.length) {
            return false;
        }
        for (int i = 0; i < prefix.length; i++) {
            if ((b[i] & 0xFF) != prefix[i]) {
                return false;
            }
        }
        return true;
    }

    /**
     * Decodes the data with the first of the double-byte charsets it looks like, or null when it
     * looks like none of them at all.
     */
    @Nullable
    private static String guessDoubleByte(byte[] b) {
        if (isLikelyDoubleByte(b, c -> c >= 0x81 && c <= 0xFE,
                c -> c >= 0x40 && c <= 0xFE && c != 0x7F)) {
            return decodeWith(b, "GBK", "GBK");
        }
        if (isLikelyDoubleByte(b, c -> c >= 0x81 && c <= 0xFE,
                c -> (c >= 0x40 && c <= 0x7E) || (c >= 0xA1 && c <= 0xFE))) {
            return decodeWith(b, "Big5", "Big5");
        }
        if (isLikelyDoubleByte(b, c -> (c >= 0x81 && c <= 0x9F) || (c >= 0xE0 && c <= 0xEF),
                c -> (c >= 0x40 && c <= 0x7E) || (c >= 0x80 && c <= 0xFC))) {
            return decodeWith(b, "Shift_JIS", "Shift_JIS");
        }
        if (isLikelyDoubleByte(b, c -> c >= 0x81 && c <= 0xFE,
                c -> c >= 0x41 && c <= 0xFE)) {
            return decodeWith(b, "EUC-KR", "EUC-KR");
        }
        if (isLikelyWindows1252(b)) {
            return decodeWith(b, "Windows-1252", "Windows-1252");
        }
        return null;
    }

    /** The data decoded with {@code charset}, or null when the platform does not support it. */
    @Nullable
    private static String decodeWith(byte[] b, String charset, String name) {
        try {
            return new String(b, charset);
        } catch (Exception ex) {
            Logger.printDebug(() -> "Could not decode " + name + " text", ex);
            return null;
        }
    }

    private static boolean isValidUtf8(byte[] b) {
        int i = 0;
        while (i < b.length) {
            int b0 = b[i] & 0xFF;
            int expectedCont;
            if (b0 <= 0x7F) {
                i++;
                continue;
            } else if (b0 >= 0xC2 && b0 <= 0xDF) {
                expectedCont = 1;
            } else if (b0 >= 0xE0 && b0 <= 0xEF) {
                expectedCont = 2;
            } else if (b0 >= 0xF0 && b0 <= 0xF4) {
                expectedCont = 3;
            } else {
                return false; // Illegal start byte (0x80-0xBF, 0xC0-0xC1, 0xF5-0xFF)
            }
            i++;
            for (int j = 0; j < expectedCont; j++) {
                if (i >= b.length || (b[i] & 0xC0) != 0x80) {
                    return false;
                }
                i++;
            }
        }
        return true;
    }

    /**
     * Whether every non-ASCII byte belongs to a two-byte character of the encoding described by
     * its lead and trail byte ranges, and at least one does. A lead byte with nothing usable
     * after it means the text is not in this encoding, which is what tells the next one its turn.
     */
    private static boolean isLikelyDoubleByte(byte[] b, IntPredicate lead, IntPredicate trail) {
        int i = 0;
        int pairs = 0;
        while (i < b.length) {
            int b0 = b[i] & 0xFF;
            if (b0 <= 0x7F) {
                i++;
                continue;
            }
            if (lead.test(b0) && i + 1 < b.length) {
                int b1 = b[i + 1] & 0xFF;
                if (trail.test(b1)) {
                    pairs++;
                    i += 2;
                    continue;
                }
            }
            return false;
        }
        return pairs > 0;
    }

    private static boolean isLikelyWindows1252(byte[] b) {
        for (byte value : b) {
            int b0 = value & 0xFF;
            if (b0 >= 0x80 && b0 <= 0x9F) {
                return true;
            }
        }
        return false;
    }

    private static byte[] copy(byte[] src, int off, int len) {
        if (off < 0 || len <= 0 || off > src.length) {
            return new byte[0];
        }
        if (off + len > src.length) {
            len = src.length - off;
        }
        byte[] r = new byte[len];
        System.arraycopy(src, off, r, 0, len);
        return r;
    }

    private static String normalize(@Nullable String s) {
        if (s == null) {
            return "";
        }
        return CharactersConverter.normalize(s);
    }

    /** Escapes SQLite LIKE wildcards so the pattern matches the literal string. */
    private static String likeEscape(String s) {
        return s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
}
