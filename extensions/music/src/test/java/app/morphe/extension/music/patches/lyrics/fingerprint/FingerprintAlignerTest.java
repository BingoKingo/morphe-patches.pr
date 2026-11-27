/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.extension.music.patches.lyrics.fingerprint;

import org.junit.Assert;
import org.junit.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

import app.morphe.extension.music.patches.lyrics.fingerprint.AudioFingerprinter.Frame;
import app.morphe.extension.music.patches.lyrics.fingerprint.AudioFingerprinter.Session;
import app.morphe.extension.music.patches.lyrics.fingerprint.FingerprintAligner.Result;

/** Verifies the fingerprint extractor and cross-correlation with deterministic synthetic data. */
public class FingerprintAlignerTest {

    private static byte[] pcm(int frames, int blockFrames) {
        ByteBuffer bb = ByteBuffer.allocate(frames * 4).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < frames; i++) {
            int block = i / blockFrames;
            double amp = (block % 2) == 0 ? 24000.0 : 0.0;
            short s = (short) (amp * Math.sin(2 * Math.PI * 220.0 * i / 48000.0));
            bb.putShort(s);
            bb.putShort(s);
        }
        return bb.array();
    }

    private static List<Frame> fingerprint(byte[] pcm) {
        Session s = new Session();
        s.feed(pcm, 0, pcm.length);
        return s.frames();
    }

    /**
     * A continuous song-like loudness envelope with two distinctive landmarks. Unlike a sparse
     * spike train, a smooth envelope cannot produce a spurious perfect correlation from a
     * two-sample window, which is what would otherwise defeat a real Pearson aligner.
     */
    private static List<Frame> landmarks(int count) {
        return landmarks(count, 0f);
    }

    private static List<Frame> landmarks(int count, float phase) {
        List<Frame> frames = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            float t = i / (float) count;
            // Smooth dynamics: two broad hills plus a gentle oscillation and slow drift.
            double hillA = 1.1 * Math.exp(-Math.pow((t - 0.25) / 0.06, 2));
            double hillB = 0.7 * Math.exp(-Math.pow((t - 0.75) / 0.05, 2));
            double osc = 0.15 * Math.sin(i * 0.7 + phase);
            double drift = 0.08 * Math.sin(i * 0.13);
            float rms = (float) Math.max(0.02, 0.35 + hillA + hillB + osc + drift);
            frames.add(new Frame(rms, new float[AudioFingerprinter.BAND_COUNT]));
        }
        return frames;
    }

    /** Same envelope scaled to a different loudness, as distinct masters would be. */
    private static List<Frame> scaled(List<Frame> pattern, float gain) {
        List<Frame> out = new ArrayList<>(pattern.size());
        for (Frame f : pattern) {
            out.add(new Frame(f.rms * gain, f.bands));
        }
        return out;
    }

    private static List<Frame> withLead(int leadFrames, List<Frame> pattern, int totalFrames) {
        List<Frame> out = new ArrayList<>(totalFrames);
        for (int i = 0; i < leadFrames; i++) {
            // A quiet, non-silent intro so it carries no correlation with the song content.
            out.add(new Frame(0.05f, new float[AudioFingerprinter.BAND_COUNT]));
        }
        out.addAll(pattern);
        while (out.size() < totalFrames) {
            out.add(new Frame(0.05f, new float[AudioFingerprinter.BAND_COUNT]));
        }
        return out;
    }

    @Test
    public void fftConstantSignal() {
        double[] re = new double[8], im = new double[8];
        java.util.Arrays.fill(re, 1.0);
        AudioFingerprinter.fft(re, im);
        Assert.assertTrue("DC=" + re[0], re[0] > 7.9 && re[0] < 8.1);
        for (int i = 1; i < 8; i++) {
            Assert.assertTrue("bin " + i + "=" + re[i], Math.abs(re[i]) < 0.1);
        }
    }

    @Test
    public void extractsFramesFromPcm() {
        byte[] pcm = pcm(48000, 9600);
        List<Frame> frames = fingerprint(pcm);
        Assert.assertTrue("expected >8 frames, got " + frames.size(), frames.size() > 8);
        for (Frame f : frames) {
            Assert.assertTrue(f.rms >= 0);
            Assert.assertEquals(AudioFingerprinter.BAND_COUNT, f.bands.length);
        }
    }

    @Test
    public void alignsLeadInOffset() {
        int leadFrames = 6; // ~500 ms at 85 ms per frame
        List<Frame> pattern = landmarks(120);
        Result r = FingerprintAligner.align(pattern, withLead(leadFrames, pattern, 130));
        Assert.assertNotNull(r);
        Assert.assertTrue("confidence=" + r.confidence + " margin=" + r.margin, r.reliable());
        Assert.assertEquals(leadFrames * AudioFingerprinter.Session.hopMs(), r.offsetMs);
    }

    @Test
    public void alignsLongIntroWithCommonWindow() {
        int leadFrames = 41; // ~3.5 s
        List<Frame> pattern = landmarks(120);
        Result r = FingerprintAligner.align(pattern, withLead(leadFrames, pattern, 161));
        Assert.assertNotNull(r);
        Assert.assertTrue("confidence=" + r.confidence + " margin=" + r.margin, r.reliable());
        Assert.assertEquals(leadFrames * AudioFingerprinter.Session.hopMs(), r.offsetMs);
    }

    @Test
    public void unequalLengthsUseCommonWindow() {
        // The shorter pass must not change the measured offset.
        int leadFrames = 12; // ~1 s
        List<Frame> pattern = landmarks(60);
        Result r = FingerprintAligner.align(withLead(0, pattern, 150),
                withLead(leadFrames, pattern, 90));
        Assert.assertNotNull(r);
        Assert.assertTrue("confidence=" + r.confidence + " margin=" + r.margin, r.reliable());
        Assert.assertEquals(leadFrames * AudioFingerprinter.Session.hopMs(), r.offsetMs);
    }

    @Test
    public void rejectsUnrelatedClips() {
        // Different phase => a different song; it must not pass the confidence/margin gate.
        Result r = FingerprintAligner.align(landmarks(120, 0f), landmarks(120, 1.7f));
        Assert.assertNotNull(r);
        Assert.assertFalse("should be unreliable: " + r.describe(), r.reliable());
    }

    @Test
    public void alignsLongLeadInWithLoudnessDifference() {
        // A 17 s lead-in with the video master at a different loudness must still align: this is
        // the case the old global-normalization code failed on (confidence collapsed with lead).
        int leadFrames = 200;
        List<Frame> pattern = landmarks(350);
        List<Frame> video = withLead(leadFrames, scaled(pattern, 0.6f), 350 + leadFrames);
        Result r = FingerprintAligner.align(pattern, video);
        Assert.assertNotNull(r);
        Assert.assertTrue(r.describe(), r.reliable());
        Assert.assertEquals(leadFrames * AudioFingerprinter.Session.hopMs(), r.offsetMs);
        Assert.assertTrue("overlap=" + r.overlapFrames, r.overlapFrames >= 60);
    }

    @Test
    public void alignsEitherPassOrder() {
        // Recording the video version first yields a negative shift; the aligner accepts it.
        int leadFrames = 100;
        List<Frame> pattern = landmarks(350);
        Result r = FingerprintAligner.align(withLead(leadFrames, pattern, 350 + leadFrames), pattern);
        Assert.assertNotNull(r);
        Assert.assertTrue(r.describe(), r.reliable());
        Assert.assertEquals(-leadFrames * AudioFingerprinter.Session.hopMs(), r.offsetMs);
    }

    @Test
    public void tooShortReturnsNull() {
        Assert.assertNull(FingerprintAligner.align(landmarks(4), landmarks(4)));
    }
}
