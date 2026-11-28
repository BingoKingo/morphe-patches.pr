/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.lyrics.romanizers;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.fastfilter.utils.Hash;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

import app.morphe.extension.music.patches.lyrics.LyricsDirectories;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;

/** Downloadable multilingual proper-noun filter (ParaNames + Wikidata Gazetteer). */
public final class ProperNounStore {

    private static final String FILE = "proper-nouns.fuse16";
    private static final String NAME = "Proper nouns";
    private static final String URL =
            "https://gist.githubusercontent.com/BingoKingo/d1f4ca24a088335553844931c24138d2/raw/"
                    + "3e1efef5ebea102f5f43a135343063e02c0fd273/" + FILE;

    private static final int HEADER_BYTES = 20;

    private static volatile ByteBuffer filter;
    private static volatile long seed;
    private static volatile int segmentLength;
    private static volatile int segmentCountLength;
    private static volatile int arrayLength;

    private ProperNounStore() {
    }

    @Nullable
    public static File file() {
        File dir = LyricsDirectories.directory(LyricsDirectories.WORD_DICT);
        return dir == null ? null : new File(dir, FILE);
    }

    public static boolean isReady() {
        return open() != null;
    }

    public static void ensure(@NonNull RomanizationDictionary.Listener listener) {
        if (open() != null) {
            listener.onDownload(NAME, 100);
            listener.onReady(keyCount());
            return;
        }
        Utils.runOnBackgroundThread(() -> {
            try {
                File dir = LyricsDirectories.directory(LyricsDirectories.WORD_DICT);
                if (dir == null) {
                    throw new IOException("no dictionary directory");
                }
                File target = new File(dir, FILE);
                if (!target.exists()) {
                    listener.onDownload(NAME, 0);
                    RomanizationDictionary.download(URL, target, NAME, listener);
                } else {
                    listener.onDownload(NAME, 100);
                }
                if (open() == null) {
                    throw new IOException("could not load proper-noun filter");
                }
                listener.onReady(keyCount());
            } catch (Throwable ex) {
                Logger.printDebug(() -> "Could not load proper-noun filter",
                        ex instanceof Exception e ? e : new RuntimeException(ex));
                listener.onFailed();
            }
        });
    }

    /**
     * Maps a filter that is already on disk instead of deserializing its ~34 million fingerprints.
     * Opening is then instant, and queries read only three shorts from the mapped file. This never
     * starts a download: a user who has not selected the dictionary keeps the custom list only.
     */
    @Nullable
    private static synchronized ByteBuffer open() {
        ByteBuffer local = filter;
        if (local != null) {
            return local;
        }
        File target = file();
        if (target == null || !target.exists()) {
            return null;
        }
        try (RandomAccessFile file = new RandomAccessFile(target, "r");
             FileChannel channel = file.getChannel()) {
            long size = channel.size();
            if (size < HEADER_BYTES || size > Integer.MAX_VALUE) {
                throw new IOException("bad proper-noun filter size");
            }
            MappedByteBuffer mapped = channel.map(FileChannel.MapMode.READ_ONLY, 0, size);
            mapped.order(ByteOrder.BIG_ENDIAN);
            int segments = mapped.getInt(0);
            int countLength = mapped.getInt(4);
            long filterSeed = mapped.getLong(8);
            int fingerprints = mapped.getInt(16);
            if (segments <= 0 || Integer.bitCount(segments) != 1 || countLength <= 0
                    || fingerprints <= 0 || countLength % segments != 0
                    || size != HEADER_BYTES + (long) fingerprints * 2L) {
                throw new IOException("bad proper-noun filter header");
            }
            seed = filterSeed;
            segmentLength = segments;
            segmentCountLength = countLength;
            arrayLength = fingerprints;
            filter = mapped;
            return mapped;
        } catch (Throwable ex) {
            Logger.printDebug(() -> "Could not map proper-noun filter",
                    ex instanceof Exception e ? e : new RuntimeException(ex));
            return null;
        }
    }

    public static void delete() {
        filter = null;
        File f = file();
        if (f != null && f.exists()) {
            Utils.deleteQuietly(f);
        }
    }

    /** Case-insensitive membership test against the downloaded filter. */
    public static boolean mightContain(@Nullable String token) {
        ByteBuffer local = open();
        if (local == null || token == null) {
            return false;
        }
        String lower = token.toLowerCase(Locale.ROOT).trim();
        if (lower.isEmpty()) {
            return false;
        }
        long hash = Hash.hash64(fnv1a(lower), seed);
        int fingerprint = (short) hash;
        int h0 = Hash.reduce((int) (hash >>> 32), segmentCountLength);
        int h1 = h0 + segmentLength;
        int h2 = h1 + segmentLength;
        int mask = segmentLength - 1;
        h1 ^= (int) ((hash >> 18) & mask);
        h2 ^= (int) (hash & mask);
        if (h0 < 0 || h1 < 0 || h2 < 0 || h2 >= arrayLength) {
            return false;
        }
        fingerprint ^= local.getShort(HEADER_BYTES + h0 * 2)
                ^ local.getShort(HEADER_BYTES + h1 * 2)
                ^ local.getShort(HEADER_BYTES + h2 * 2);
        return (fingerprint & 0xffff) == 0;
    }

    public static int keyCount() {
        return open() == null ? 0 : arrayLength;
    }

    /** FNV-1a over UTF-8 bytes, shared with the offline generator. */
    private static long fnv1a(String value) {
        long hash = 0xcbf29ce484222325L;
        for (byte b : value.getBytes(StandardCharsets.UTF_8)) {
            hash ^= (b & 0xFFL);
            hash *= 0x100000001b3L;
        }
        return hash;
    }
}
