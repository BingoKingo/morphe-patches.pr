/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.extension.shared;

/**
 * Cross-module flag: true while the lyrics fingerprint "snapshot" capture is recording PCM.
 *
 * <p>SponsorBlock's automatic skip consults this so it does not cut the video's intro while the
 * capture is measuring it. It lives in the shared module because both the music extension (which
 * sets it) and the shared-youtube SponsorBlock controller (which reads it) depend on shared.
 */
public final class FingerprintCaptureState {

    private static volatile boolean active = false;

    private FingerprintCaptureState() {
    }

    public static boolean isActive() {
        return active;
    }

    public static void setActive(boolean value) {
        active = value;
    }
}
