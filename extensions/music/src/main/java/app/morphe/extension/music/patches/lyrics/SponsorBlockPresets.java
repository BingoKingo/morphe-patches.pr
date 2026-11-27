/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.extension.music.patches.lyrics;

import java.util.ArrayList;
import java.util.List;

import app.morphe.extension.shared.sponsorblock.objects.SegmentCategory;
import app.morphe.extension.shared.sponsorblock.requests.SBRequester;
import app.morphe.extension.shared.sponsorblock.requests.SBRequester.SegmentSubmitAction;

/**
 * In-memory list of SponsorBlock segments the user has queued for submission to one video.
 *
 * <p>Empty by default: the fingerprint alignment fills it with the measured lead-in and the user
 * reviews every entry in the presets page before the batch is actually uploaded. Entries are
 * keyed by video id so switching between the audio and video versions does not mix them.
 */
public final class SponsorBlockPresets {

    /** One editable preset segment. */
    public static final class Entry {
        public final long startMs;
        public final long endMs;
        public final SegmentCategory category;

        public Entry(long startMs, long endMs, SegmentCategory category) {
            this.startMs = startMs;
            this.endMs = endMs;
            this.category = category;
        }
    }

    private static final List<Entry> ENTRIES = new ArrayList<>();
    private static String videoId = "";

    private SponsorBlockPresets() {
    }

    public static synchronized String getVideoId() {
        return videoId;
    }

    public static synchronized boolean hasFor(String id) {
        return id != null && !id.isEmpty() && id.equals(videoId) && !ENTRIES.isEmpty();
    }

    public static synchronized int sizeFor(String id) {
        return hasFor(id) ? ENTRIES.size() : 0;
    }

    public static synchronized List<Entry> forVideo(String id) {
        return hasFor(id) ? new ArrayList<>(ENTRIES) : List.of();
    }

    public static synchronized void replaceAll(String id, List<Entry> entries) {
        ENTRIES.clear();
        videoId = id == null ? "" : id;
        if (entries != null) {
            ENTRIES.addAll(entries);
        }
    }

    public static synchronized void add(String id, Entry entry) {
        if (id == null || id.isEmpty() || entry == null) {
            return;
        }
        if (!id.equals(videoId)) {
            ENTRIES.clear();
            videoId = id;
        }
        ENTRIES.add(entry);
    }

    public static synchronized void remove(String id, int index) {
        if (!hasFor(id) || index < 0 || index >= ENTRIES.size()) {
            return;
        }
        ENTRIES.remove(index);
    }

    public static synchronized void set(String id, int index, Entry entry) {
        if (!hasFor(id) || index < 0 || index >= ENTRIES.size() || entry == null) {
            return;
        }
        ENTRIES.set(index, entry);
    }

    public static synchronized void clear() {
        ENTRIES.clear();
        videoId = "";
    }

    /** First entry's span, or a zero-length span at zero when the video has no presets. */
    public static synchronized long[] firstSpan(String id) {
        if (!hasFor(id)) {
            return new long[]{0, 0};
        }
        Entry first = ENTRIES.get(0);
        return new long[]{first.startMs, first.endMs};
    }

    public static synchronized List<SBRequester.BatchSegment> toBatch(String id) {
        if (!hasFor(id)) {
            return List.of();
        }
        List<SBRequester.BatchSegment> out = new ArrayList<>(ENTRIES.size());
        for (Entry e : ENTRIES) {
            out.add(new SBRequester.BatchSegment(e.startMs, e.endMs, e.category,
                    SegmentSubmitAction.SKIP));
        }
        return out;
    }
}
