/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.extension.music.patches.lyrics.fingerprint;

import java.util.List;

import app.morphe.extension.music.patches.lyrics.fingerprint.AudioFingerprinter.Frame;

/**
 * Aligns two fingerprints of the same song recorded from different versions (e.g. the released
 * audio and a music video with a long intro) by cross-correlating their loudness envelopes.
 *
 * <p>Only the RMS envelope is correlated: band ratios are robust to lossy re-encoding but change
 * between distinct masters, whereas the rhythm of loud and quiet passages identifies "the same
 * performance shifted in time".
 *
 * <p>The score for every candidate shift is a <b>Pearson correlation computed over that shift's
 * actual overlap</b>, not a dot product against the whole clip. Normalizing by the overlap length
 * is what lets a long video intro be measured: with a global normalization the score dropped as
 * the intro grew and almost any real clip failed the confidence floor. The minimum overlap is a
 * floor plus a fraction of the shorter clip, so a lead-in up to roughly {@code 1 - minRatio} of
 * the capture can be recovered.
 */
public final class FingerprintAligner {

    /** Below this Pearson correlation the two clips are treated as different recordings. */
    static final float MIN_CONFIDENCE = 0.5f;
    /** Search range in frames. 700 frames * ~85 ms hop = ~60 s of leading offset. */
    static final int MAX_SHIFT_FRAMES = 700;
    /** Minimum overlap as a fraction of the (equalized) clip. */
    static final float MIN_OVERLAP_RATIO = 0.30f;
    /** Absolute floor in frames (~5 s at 85 ms/frame): never require fewer than this. */
    static final int MIN_OVERLAP_FRAMES_FLOOR = 60;
    /** The best peak must beat distant rivals by this fraction of its own score. */
    static final float MIN_PEAK_MARGIN_RATIO = 0.05f;
    /** Shifts this close to the best one are the same peak and do not count as rivals. */
    private static final int PEAK_NEIGHBORHOOD = 2;
    /** Ignore frames quieter than this fraction of the clip's peak (silence carries no signal). */
    private static final float SILENCE_FLOOR = 0.08f;

    private FingerprintAligner() {
    }

    public static final class Result {
        /** How much later version B starts than version A, in ms (positive = B has a lead-in). */
        public final long offsetMs;
        /** Normalized correlation strength at the chosen shift (-1..1). */
        public final float confidence;
        /** How much the chosen peak beats the best distant rival. */
        public final float margin;
        /** Frames actually overlapped at the chosen shift (for diagnostics). */
        public final int overlapFrames;
        /** Common window length in frames (for diagnostics). */
        public final int windowFrames;
        /** Raw shift in frames at the chosen peak (for diagnostics). */
        public final int shiftFrames;

        Result(long offsetMs, float confidence, float margin, int overlapFrames,
               int windowFrames, int shiftFrames) {
            this.offsetMs = offsetMs;
            this.confidence = confidence;
            this.margin = margin;
            this.overlapFrames = overlapFrames;
            this.windowFrames = windowFrames;
            this.shiftFrames = shiftFrames;
        }

        public boolean reliable() {
            return confidence >= MIN_CONFIDENCE
                    && margin >= MIN_PEAK_MARGIN_RATIO * confidence;
        }

        /** Compact single-line summary for logs / toasts. */
        public String describe() {
            return String.format("shift=%dms conf=%.3f margin=%.3f overlap=%d/%d frames",
                    offsetMs, confidence, margin, overlapFrames, windowFrames);
        }
    }

    /**
     * Finds the shift k such that A[i] aligns with B[i + k], i.e. B's content is delayed by
     * {@code k} frames relative to A — the exact case of a video with a longer intro. Negative
     * shifts (B starts earlier) are accepted and reported, so recording the two passes in either
     * order does not silently fail.
     *
     * @return a result whose {@code reliable()} must be checked before use, or {@code null} when
     *         either clip has too little signal to compare.
     */
    public static Result align(List<Frame> a, List<Frame> b) {
        if (a == null || b == null) return null;
        // A shorter pass must not change the comparison window: use the common prefix.
        final int n = Math.min(a.size(), b.size());
        if (n < MIN_OVERLAP_FRAMES_FLOOR) return null;

        final float[] envA = envelope(a.subList(0, n));
        final float[] envB = envelope(b.subList(0, n));

        final int maxShift = Math.min(MAX_SHIFT_FRAMES, n - 4);
        final int minOverlap = Math.max(MIN_OVERLAP_FRAMES_FLOOR, (int) (n * MIN_OVERLAP_RATIO));

        float best = Float.NEGATIVE_INFINITY;
        int bestShift = 0;
        int bestOverlap = 0;
        float bestRival = Float.NEGATIVE_INFINITY;

        for (int k = -maxShift; k <= maxShift; k++) {
            final int overlapStart = Math.max(0, -k);
            final int overlapEnd = Math.min(n, n - k);
            final int count = overlapEnd - overlapStart;
            if (count < minOverlap) continue;

            final float corr = pearson(envA, envB, overlapStart, overlapEnd, k);
            if (corr > best) {
                // The previous best becomes a rival candidate if it is far from the new peak.
                if (best != Float.NEGATIVE_INFINITY && Math.abs(k - bestShift) > PEAK_NEIGHBORHOOD) {
                    bestRival = Math.max(bestRival, best);
                }
                best = corr;
                bestShift = k;
                bestOverlap = count;
            } else if (Math.abs(k - bestShift) > PEAK_NEIGHBORHOOD) {
                bestRival = Math.max(bestRival, corr);
            }
        }

        if (best == Float.NEGATIVE_INFINITY) return null;
        final float confidence = Math.max(-1f, Math.min(1f, best));
        final float margin = bestRival == Float.NEGATIVE_INFINITY
                ? 1f : Math.max(0f, best - bestRival);
        final long offsetMs = (long) bestShift * AudioFingerprinter.Session.hopMs();
        return new Result(offsetMs, confidence, margin, bestOverlap, n, bestShift);
    }

    /** Pearson correlation of envA[s..e) with envB[s+k..e+k). */
    private static float pearson(float[] a, float[] b, int s, int e, int k) {
        final int count = e - s;
        if (count <= 1) return 0f;
        double meanA = 0, meanB = 0;
        for (int i = s; i < e; i++) {
            meanA += a[i];
            meanB += b[i + k];
        }
        meanA /= count;
        meanB /= count;
        double num = 0, varA = 0, varB = 0;
        for (int i = s; i < e; i++) {
            final double da = a[i] - meanA;
            final double db = b[i + k] - meanB;
            num += da * db;
            varA += da * da;
            varB += db * db;
        }
        final double denom = Math.sqrt(varA * varB);
        if (denom == 0) return 0f;
        return (float) (num / denom);
    }

    private static float[] envelope(List<Frame> frames) {
        float peak = 0;
        for (Frame f : frames) peak = Math.max(peak, f.rms);
        final float floor = peak * SILENCE_FLOOR;
        final float[] env = new float[frames.size()];
        for (int i = 0; i < frames.size(); i++) {
            env[i] = Math.max(0f, frames.get(i).rms - floor);
        }
        return env;
    }
}
