/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.extension.music.patches.lyrics.fingerprint;

import static app.morphe.extension.shared.StringRef.str;

import android.media.AudioTrack;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import app.morphe.extension.music.patches.lyrics.LyricsManager;
import app.morphe.extension.music.patches.lyrics.SkipSegments;
import app.morphe.extension.music.patches.lyrics.SponsorBlockPresets;
import app.morphe.extension.music.patches.lyrics.fingerprint.AudioFingerprinter.Frame;
import app.morphe.extension.music.patches.lyrics.fingerprint.FingerprintAligner.Result;
import app.morphe.extension.music.shared.VideoInformation;
import app.morphe.extension.shared.FingerprintCaptureState;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.sponsorblock.objects.SegmentCategory;

/**
 * Coordinates the two-pass "snapshot" fingerprint capture.
 *
 * <p>First pass: the audio-only track plays from the start and its PCM is fingerprinted.
 * When the user then switches to the music-video version the same track starts at zero and the
 * second pass is triggered automatically by {@link VideoInformation}'s video-id listener; the two
 * fingerprints are cross-correlated to measure the video's lead-in. The resulting segment is
 * placed in {@link SponsorBlockPresets} for the user to review.
 *
 * <p>PCM is tapped from the AudioTrack hook in {@code SonosCastPatch.writeAudio}: no microphone
 * and no extra permission. That hook only exists when the Sonos Cast patch is applied, so a
 * capture that receives no PCM within {@link #HOOK_PROBE_MS} is cancelled with an explanatory
 * toast instead of silently failing.
 */
public final class FingerprintCapture {

    /** Upper bound for a pass. The first pass may be shorter, the second matches it exactly. */
    public static final long CAPTURE_DURATION_MS = 60_000;
    /** Minimum captured audio accepted as a fingerprint (10 s). */
    static final long MIN_CAPTURE_MS = 10_000;
    /** If no PCM arrives within this long, check whether the hook fired at all. */
    static final long HOOK_PROBE_MS = 5_000;
    /** Upper bound for waiting out a buffering delay once the hook is known to be present. */
    private static final long HOOK_BUFFER_WAIT_MS = 30_000;
    /** 48 kHz stereo 16-bit: 192 bytes per millisecond. */
    private static final int BYTES_PER_MS = 48_000 * 4 / 1000;
    /** The progress hook is ~1s coarse; the first second is still the start of the track. */
    private static final long START_TOLERANCE_MS = 1_000;
    /**
     * Drop the first part of both passes. It absorbs start jitter, the player's pre-roll, and any
     * audio the AudioTrack still had buffered from before the rewind. 1.5 s comfortably covers the
     * ExoPlayer + AudioTrack buffer on a cold rewind.
     */
    private static final long PREROLL_MS = 1_500;
    /**
     * A pass only arms after a seek-to-zero was actually dispatched. {@link VideoInformation}
     * optimistically writes {@code videoTime = 0} on seek, so a position near zero by itself does
     * not prove the rewind landed; requiring a recent confirmed seek keeps buffered pre-roll audio
     * out of the fingerprint. The window is generous so a slow buffer does not strand a pass.
     */
    private static final long SEEK_CONFIRM_WINDOW_MS = 30_000;
    private static volatile long seekZeroAtUptimeMs = -1;

    private enum Phase {
        IDLE,
        /** First pass: audio-only version. */
        BASE,
        /** Waiting for the user to switch to the other version. */
        BASE_READY,
        /** Second pass: music-video version. */
        ALIGN
    }

    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "MorpheFingerprint");
        t.setDaemon(true);
        return t;
    });
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private static volatile Phase phase = Phase.IDLE;
    private static volatile AudioFingerprinter.Session session;
    private static volatile long capturedBytes;
    private static volatile long reportedSeconds = -1;
    private static volatile long lastHookAtUptimeMs = -1;
    /** False until the player actually reaches the start, so buffered/old audio is ignored. */
    private static volatile boolean armed;
    /** Early finish requested: keep what was captured, then finish this pass. */
    private static volatile boolean stopping;
    /** Length of the first pass; the second pass stops at exactly the same duration. */
    private static volatile long targetBytes;
    private static volatile long skipBytes;
    /** Raw media time when this pass was armed; used to compensate start jitter. */
    private static volatile long armVideoTimeMs = -1;
    private static volatile long baseArmMs = -1;
    private static volatile int generation;
    private static volatile String captureVideoId = "";
    private static volatile List<Frame> baseFrames;
    private static volatile String baseVideoId = "";
    private static volatile Callback callback;

    static {
        VideoInformation.addVideoIdListener(FingerprintCapture::onVideoIdChanged);
    }

    private FingerprintCapture() {
    }

    public interface Callback {
        /** Main thread. Seconds captured so far in the current pass. */
        void onCaptureProgress(int seconds);

        /** Main thread. The first pass finished; the user should switch to the other version. */
        void onBaseCaptured();

        /** Main thread. Both passes finished and the presets were filled. */
        void onAligned(Result result, String baseId, String targetId);

        /** Main thread. A toast already explained the failure; just refresh the UI. */
        void onFailed();
    }

    public static boolean isCapturing() {
        final Phase current = phase;
        return !stopping && (current == Phase.BASE || current == Phase.ALIGN);
    }

    public static boolean isBaseReady() {
        return phase == Phase.BASE_READY;
    }

    public static int progressSeconds() {
        return (int) (capturedBytes / BYTES_PER_MS / 1000);
    }

    public static String baseVideoId() {
        return baseVideoId;
    }

    public static void setCallback(Callback cb) {
        callback = cb;
    }

    public static void clearCallback() {
        callback = null;
    }

    /** Starts the first pass. Caller should have seeked to 0 already. */
    public static void startBase(String videoId) {
        if (videoId == null || videoId.isEmpty()) {
            return;
        }
        baseFrames = null;
        baseVideoId = "";
        beginCapture(videoId, Phase.BASE);
        seekToStart(0);
    }

    /** Starts the second pass manually, for when the automatic trigger did not run. */
    public static void startAlign(String videoId) {
        if (baseFrames == null || videoId == null || videoId.isEmpty()) {
            return;
        }
        beginCapture(videoId, Phase.ALIGN);
        seekToStart(0);
    }

    /** User cancelled, or the track changed mid-capture; drop in-flight state without a result. */
    public static void cancel() {
        phase = Phase.IDLE;
        session = null;
        baseFrames = null;
        armed = false;
        stopping = false;
        FingerprintCaptureState.setActive(false);
    }

    /**
     * Called when the user taps the snapshot button while recording: finish this pass with the
     * audio captured so far. The first pass' length becomes the second pass' target length.
     */
    public static void stop() {
        final Phase current = phase;
        if (current != Phase.BASE && current != Phase.ALIGN) {
            return;
        }
        stopping = true;
        generation++; // invalidate a pending hook probe
        FingerprintCaptureState.setActive(false);
        EXECUTOR.execute(() -> finish(current));
    }

    private static void beginCapture(String videoId, Phase next) {
        capturedBytes = 0;
        reportedSeconds = -1;
        armed = false;
        stopping = false;
        seekZeroAtUptimeMs = -1;
        if (next == Phase.BASE) {
            targetBytes = 0;
        }
        skipBytes = PREROLL_MS * BYTES_PER_MS;
        armVideoTimeMs = -1;
        captureVideoId = videoId;
        session = new AudioFingerprinter.Session();
        phase = next;
        FingerprintCaptureState.setActive(true);
        final int gen = ++generation;
        scheduleHookProbe(next, gen, SystemClock.uptimeMillis());
    }

    private static void onVideoIdChanged(String videoId) {
        if (videoId == null || videoId.isEmpty()) {
            return;
        }
        final Phase current = phase;
        if (current == Phase.BASE || current == Phase.ALIGN) {
            // A track change ends the pass: continuing would mix two songs into one fingerprint.
            if (!videoId.equals(captureVideoId)) {
                Logger.printDebug(() -> "Fingerprint capture cancelled by track change to: " + videoId);
                cancel();
                final Callback cb = callback;
                if (cb != null) MAIN.post(cb::onFailed);
            }
            return;
        }
        if (current != Phase.BASE_READY || videoId.equals(baseVideoId)) {
            return;
        }
        // The other version started: capture it from zero, no dialog interaction needed.
        Utils.runOnMainThread(() -> {
            if (phase != Phase.BASE_READY) {
                return;
            }
            beginCapture(videoId, Phase.ALIGN);
            seekToStart(0);
        });
    }

    private static void seekToStart(int attempt) {
        if (phase != Phase.BASE && phase != Phase.ALIGN) {
            return;
        }
        if (VideoInformation.seekTo(0) || attempt >= 8) {
            if (attempt < 8) {
                seekZeroAtUptimeMs = SystemClock.uptimeMillis();
            }
            return;
        }
        MAIN.postDelayed(() -> seekToStart(attempt + 1), 250);
    }

    /** Called from the AudioTrack hook on every write, even before any capture starts. */
    public static void markHookSeen() {
        lastHookAtUptimeMs = SystemClock.uptimeMillis();
    }

    private static void scheduleHookProbe(Phase expected, int gen, long startedAtUptimeMs) {
        // Must not run on EXECUTOR: the sleep would queue ahead of every PCM feed and the
        // probe would fire with capturedBytes still zero even while audio is flowing.
        MAIN.postDelayed(() -> {
            if (generation != gen || phase != expected || capturedBytes > 0) {
                return;
            }
            // Once this hook has fired at least once the Sonos patch is present; silence right
            // now just means the player is still buffering.
            final boolean hookPresent = lastHookAtUptimeMs >= 0;
            if (hookPresent
                    && SystemClock.uptimeMillis() - startedAtUptimeMs < HOOK_BUFFER_WAIT_MS) {
                scheduleHookProbe(expected, gen, startedAtUptimeMs);
                return;
            }
            cancel();
            fail(str(hookPresent
                    ? "morphe_music_lyrics_snapshot_no_audio"
                    : "morphe_music_lyrics_snapshot_unavailable"));
        }, HOOK_PROBE_MS);
    }

    /** Feeds a chunk of 48 kHz stereo 16-bit PCM. Called from the AudioTrack hook. */
    public static void onPcm(AudioTrack track, ByteBuffer audio) {
        final Phase current = phase;
        if (stopping || (current != Phase.BASE && current != Phase.ALIGN) || audio == null) {
            return;
        }
        final AudioFingerprinter.Session active = session;
        if (active == null) {
            return;
        }
        final int position = audio.position();
        final int length = audio.remaining();
        if (length <= 0) {
            return;
        }
        final byte[] chunk = new byte[length];
        audio.get(chunk);
        audio.position(position);
        EXECUTOR.execute(() -> feed(active, chunk, current));
    }

    private static void feed(AudioFingerprinter.Session active, byte[] chunk, Phase expected) {
        // A cancelled or restarted capture leaves stale chunks queued; never let them feed
        // another pass's session or inflate its byte count.
        if (phase != expected || session != active || stopping) {
            return;
        }
        if (!armed) {
            // Skip everything written before the rewind actually landed. AudioTrack may still be
            // flushing the old position, and VideoInformation optimistically reports 0 right after
            // a seek, so require both a recent confirmed seek-to-zero and a near-zero position.
            final long videoTime = VideoInformation.getVideoTime();
            final boolean seekLanded = seekZeroAtUptimeMs >= 0
                    && SystemClock.uptimeMillis() - seekZeroAtUptimeMs <= SEEK_CONFIRM_WINDOW_MS;
            if (!seekLanded || videoTime < 0 || videoTime > START_TOLERANCE_MS) {
                return;
            }
            armVideoTimeMs = videoTime;
            armed = true;
        }
        int offset = 0;
        if (skipBytes > 0) {
            offset = (int) Math.min(skipBytes, chunk.length);
            skipBytes -= offset;
        }
        if (offset >= chunk.length) {
            return;
        }
        active.feed(chunk, offset, chunk.length - offset);
        capturedBytes += chunk.length - offset;

        final long seconds = capturedBytes / BYTES_PER_MS / 1000;
        if (seconds != reportedSeconds) {
            reportedSeconds = seconds;
            final Callback cb = callback;
            if (cb != null) {
                final int value = (int) seconds;
                MAIN.post(() -> cb.onCaptureProgress(value));
            }
        }
        final long limitMs = targetBytes > 0
                ? targetBytes / BYTES_PER_MS : CAPTURE_DURATION_MS;
        if (capturedBytes / BYTES_PER_MS >= limitMs) {
            finish(expected);
        }
    }

    /** Avoid producing a preset that the server already has (it would come back as 409). */
    private static boolean alreadyOnServer(String videoId, long offsetMs) {
        final List<SkipSegments.Seg> segs =
                SkipSegments.peek(videoId, VideoInformation.getVideoLength());
        if (segs == null) {
            return false;
        }
        for (SkipSegments.Seg seg : segs) {
            if ("music_offtopic".equals(seg.category())
                    && Math.abs(seg.startMs()) <= 1000
                    && Math.abs(seg.endMs() - offsetMs) <= 1000) {
                return true;
            }
        }
        return false;
    }

    private static void finish(Phase expected) {
        if (phase != expected) {
            return;
        }
        stopping = false;
        final AudioFingerprinter.Session active = session;
        if (active == null) {
            return;
        }
        session = null;
        final long elapsedMs = capturedBytes / BYTES_PER_MS;
        final String videoId = captureVideoId;
        final List<Frame> frames = active.frames();
        FingerprintCaptureState.setActive(false);

        if (elapsedMs < MIN_CAPTURE_MS || active.frameCount() < 8) {
            phase = Phase.IDLE;
            fail(str("morphe_music_lyrics_snapshot_too_short"));
            return;
        }

        if (expected == Phase.BASE) {
            baseFrames = frames;
            baseVideoId = videoId;
            baseArmMs = armVideoTimeMs;
            // The second pass ends at exactly this length.
            targetBytes = capturedBytes;
            phase = Phase.BASE_READY;
            final Callback cb = callback;
            if (cb != null) {
                MAIN.post(cb::onBaseCaptured);
            }
            return;
        }

        final List<Frame> base = baseFrames;
        baseFrames = null;
        phase = Phase.IDLE;
        final Result result = FingerprintAligner.align(base, frames);
        if (result == null) {
            Logger.printDebug(() -> "Fingerprint align: no common window "
                    + "(base frames=" + (base == null ? 0 : base.size())
                    + ", align frames=" + frames.size() + ")");
            fail(str("morphe_music_lyrics_snapshot_align_failed"));
            return;
        }
        Logger.printDebug(() -> "Fingerprint align: " + result.describe()
                + " (reliable=" + result.reliable() + ")");
        if (!result.reliable()) {
            fail(str("morphe_music_lyrics_snapshot_align_failed"));
            return;
        }

        // If one pass was armed later than the other, the raw shift includes that difference.
        long offsetMs = result.offsetMs;
        if (baseArmMs >= 0 && armVideoTimeMs >= 0) {
            offsetMs += armVideoTimeMs - baseArmMs;
        }
        // The two passes may be recorded in either order (audio-first or video-first); either way
        // the magnitude is the lead-in the longer version has over the shorter one, which is the
        // span to skip. The user reviews it before submission.
        final long leadInMs = Math.abs(offsetMs);
        final long videoLength = VideoInformation.getVideoLength();
        if (leadInMs < 1000 || leadInMs > 60_000
                || (videoLength > 0 && leadInMs > videoLength - MIN_CAPTURE_MS)) {
            Logger.printDebug(() -> "Fingerprint align: lead-in " + leadInMs
                    + "ms out of range (videoLength=" + videoLength + ")");
            fail(str("morphe_music_lyrics_snapshot_align_failed"));
            return;
        }

        if (alreadyOnServer(videoId, leadInMs)) {
            SponsorBlockPresets.clear();
            toast(str("morphe_sb_submit_failed_duplicate"));
        } else {
            // The lead-in is the span SponsorBlock should skip at the start of the video.
            SponsorBlockPresets.replaceAll(videoId, List.of(
                    new SponsorBlockPresets.Entry(0, leadInMs, SegmentCategory.MUSIC_OFFTOPIC)));
            final String delta = "+" + (leadInMs / 1000.0) + "s";
            toast(str("morphe_music_lyrics_snapshot_aligned", delta));
        }
        MAIN.post(() -> LyricsManager.getInstance().notifySnapshotAligned());
        final Callback cb = callback;
        if (cb != null) {
            MAIN.post(() -> cb.onAligned(result, baseVideoId, videoId));
        }
    }

    private static void fail(String reason) {
        Logger.printDebug(() -> reason);
        toast(reason);
        final Callback cb = callback;
        if (cb != null) {
            MAIN.post(cb::onFailed);
        }
    }

    private static void toast(String message) {
        MAIN.post(() -> {
            try {
                Utils.showToastShort(message);
            } catch (Exception ex) {
                Logger.printException(() -> "fingerprint toast failed", ex);
            }
        });
    }
}
