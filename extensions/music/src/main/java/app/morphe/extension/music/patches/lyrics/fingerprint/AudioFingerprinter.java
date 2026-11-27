/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.extension.music.patches.lyrics.fingerprint;

import java.util.ArrayList;
import java.util.List;

/**
 * Extracts a compact landmark fingerprint from raw PCM audio so two versions of the same song
 * can be aligned without network access or native libraries.
 *
 * <p>The input is 48 kHz stereo 16-bit little-endian PCM, the format {@code PcmConverter} emits.
 * It is downmixed to mono, decimated to {@link #WORK_RATE_HZ}, split into overlapping frames and
 * reduced to a small feature vector per frame (RMS energy plus the energy share of a handful of
 * frequency bands). The resulting {@link Frame} list is a few kilobytes per minute and is what
 * {@link FingerprintAligner} cross-correlates.
 */
public final class AudioFingerprinter {

    /** Decimation target. 48 kHz / 4 = 12 kHz, ample for band energy comparison. */
    static final int WORK_RATE_HZ = 12000;
    /** Analysis window: 2048 samples at 12 kHz = ~170 ms. */
    static final int FRAME_SIZE = 2048;
    /** Hop between frames: half a window (50% overlap). */
    static final int FRAME_HOP = FRAME_SIZE / 2;
    /** Number of frequency bands kept per frame. */
    static final int BAND_COUNT = 8;

    private AudioFingerprinter() {
    }

    /** One frame feature: loudness plus per-band energy ratios. */
    public static final class Frame {
        public final float rms;
        public final float[] bands;

        Frame(float rms, float[] bands) {
            this.rms = rms;
            this.bands = bands;
        }
    }

    /** Streaming fingerprinter. Feed PCM buffers via {@link #feed}; frames accumulate internally. */
    public static final class Session {
        private final short[] window = new short[FRAME_SIZE];
        private int windowFilled;
        private final Decimator decimator = new Decimator();
        private final double[] real = new double[FRAME_SIZE];
        private final double[] imag = new double[FRAME_SIZE];
        private final List<Frame> frames = new ArrayList<>();

        /** Feeds a chunk of 48 kHz stereo 16-bit LE PCM. */
        public void feed(byte[] pcm, int offset, int length) {
            int end = offset + length;
            int i = offset;
            while (i + 4 <= end) {
                int left = (pcm[i] & 0xFF) | ((pcm[i + 1] & 0xFF) << 8);
                int right = (pcm[i + 2] & 0xFF) | ((pcm[i + 3] & 0xFF) << 8);
                short mono = (short) (((left << 16) + (right << 16)) >> 17);
                i += 4;
                if (decimator.accept(mono)) {
                    pushSample(decimator.value);
                }
            }
        }

        private void pushSample(short sample) {
            window[windowFilled++] = sample;
            if (windowFilled == FRAME_SIZE) {
                frames.add(analyze());
                System.arraycopy(window, FRAME_HOP, window, 0, FRAME_HOP);
                windowFilled = FRAME_HOP;
            }
        }

        private Frame analyze() {
            double sumSq = 0;
            for (int i = 0; i < FRAME_SIZE; i++) {
                double v = window[i] / 32768.0;
                sumSq += v * v;
                real[i] = v * WINDOW[i];
                imag[i] = 0;
            }
            float rms = (float) Math.sqrt(sumSq / FRAME_SIZE);
            fft(real, imag);

            float[] bands = new float[BAND_COUNT];
            float total = 0;
            int usableBins = FRAME_SIZE / 2;
            for (int b = 0; b < BAND_COUNT; b++) {
                int from = bandEdge(b, usableBins);
                int to = bandEdge(b + 1, usableBins);
                double energy = 0;
                for (int k = from; k < to; k++) {
                    energy += real[k] * real[k] + imag[k] * imag[k];
                }
                bands[b] = (float) energy;
                total += bands[b];
            }
            if (total > 0) {
                for (int b = 0; b < BAND_COUNT; b++) {
                    bands[b] /= total;
                }
            }
            return new Frame(rms, bands);
        }

        private static int bandEdge(int index, int usableBins) {
            if (index <= 0) return 1;
            if (index >= BAND_COUNT) return usableBins;
            int edge = (int) Math.round(Math.pow(usableBins, (double) index / BAND_COUNT));
            return Math.min(usableBins, Math.max(index + 1, edge));
        }

        public List<Frame> frames() { return frames; }
        public int frameCount() { return frames.size(); }
        public static long hopMs() { return (long) (1000.0 * FRAME_HOP / WORK_RATE_HZ); }
        public static long msPerFrameWindow() { return (long) (1000.0 * FRAME_SIZE / WORK_RATE_HZ); }
    }

    private static final class Decimator {
        private int phase;
        short value;
        boolean accept(short sample) {
            boolean take = (phase++ % 4) == 0;
            if (take) value = sample;
            return take;
        }
    }

    private static final double[] WINDOW = new double[FRAME_SIZE];
    static {
        for (int i = 0; i < FRAME_SIZE; i++) {
            WINDOW[i] = 0.5 * (1 - Math.cos(2 * Math.PI * i / (FRAME_SIZE - 1)));
        }
    }

    /** Iterative in-place radix-2 Cooley-Tukey FFT. n must be a power of two. */
    static void fft(double[] re, double[] im) {
        final int n = re.length;
        for (int i = 1, j = 0; i < n; i++) {
            int bit = n >> 1;
            for (; j >= bit; bit >>= 1) j -= bit;
            j += bit;
            if (i < j) {
                double tr = re[i]; re[i] = re[j]; re[j] = tr;
                double ti = im[i]; im[i] = im[j]; im[j] = ti;
            }
        }
        for (int len = 2; len <= n; len <<= 1) {
            double ang = -2 * Math.PI / len;
            double wLenRe = Math.cos(ang), wLenIm = Math.sin(ang);
            for (int i = 0; i < n; i += len) {
                double wRe = 1, wIm = 0;
                for (int k = 0; k < len / 2; k++) {
                    int u = i + k, v = u + len / 2;
                    double tRe = re[v] * wRe - im[v] * wIm;
                    double tIm = re[v] * wIm + im[v] * wRe;
                    re[v] = re[u] - tRe; im[v] = im[u] - tIm;
                    re[u] += tRe; im[u] += tIm;
                    double nextRe = wRe * wLenRe - wIm * wLenIm;
                    wIm = wRe * wLenIm + wIm * wLenRe;
                    wRe = nextRe;
                }
            }
        }
    }
}
