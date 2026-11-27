/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/3575
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.lyrics.ui;

import static app.morphe.extension.shared.StringRef.str;

import android.annotation.SuppressLint;
import android.app.Dialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.function.IntConsumer;

import app.morphe.extension.music.patches.lyrics.LyricsManager;
import app.morphe.extension.music.patches.lyrics.SkipSegments;
import app.morphe.extension.music.patches.lyrics.SponsorBlockPresets;
import app.morphe.extension.music.settings.Settings;
import app.morphe.extension.music.shared.VideoInformation;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.settings.preference.CustomDialogListPreference;
import app.morphe.extension.shared.sponsorblock.objects.SegmentCategory;
import app.morphe.extension.shared.sponsorblock.requests.SBRequester;
import app.morphe.extension.shared.sponsorblock.requests.SBRequester.SegmentSubmitAction;
import app.morphe.extension.shared.theme.ThemeUtils;
import app.morphe.extension.shared.ui.CustomDialog;
import app.morphe.extension.shared.ui.Dim;
import app.morphe.extension.shared.ui.SheetBottomDialog;
import app.morphe.extension.shared.ui.SheetBottomDialog.SlideDialog;

/**
 * YouTube Music SponsorBlock sheet with three bottom tabs:
 *
 * <ul>
 *   <li><b>New</b> - one segment, edited against the playing position. Saving it only
 *       stashes it in {@link SponsorBlockPresets}; nothing is uploaded from here.</li>
 *   <li><b>Presets</b> - every segment queued for the current video. Only this tab uploads,
 *       as a single batch request, after the user has reviewed the list.</li>
 *   <li><b>History</b> - the segments the server already has, read-only. Tapping one loads it
 *       into New so it can be adjusted and saved as a preset.</li>
 * </ul>
 *
 * <p>While the sheet is open the current span is previewed: the lyrics skip it as if it were
 * already stored. The tab strip mirrors the share-card mode selector.
 */
final class SponsorBlockMusicSubmitDialog {

    /** Highlights are a single point in time and cannot shift lyrics. */
    private static final SegmentCategory[] CATEGORIES =
            SegmentCategory.categoriesWithoutHighlights();
    private static final int DEFAULT_CATEGORY_INDEX =
            Arrays.asList(CATEGORIES).indexOf(SegmentCategory.MUSIC_OFFTOPIC);

    /** How long the server takes before a new segment is returned by segment requests. */
    private static final long REFRESH_DELAY_MS = 10_000;

    private static final int SLIDE_ANIMATION_MILLIS = 160;
    private static final int SEGMENT_TRACK_COLOR = 0xFF222229;

    static final int TAB_NEW = 0;
    static final int TAB_PRESETS = 1;
    static final int TAB_HISTORY = 2;

    private SponsorBlockMusicSubmitDialog() {
    }

    static String formatTimeMs(long ms) {
        final long total = Math.max(0, ms);
        return String.format(Locale.US, "%02d:%02d.%03d",
                total / 60000, (total % 60000) / 1000, total % 1000);
    }

    private static String formatSeconds(long ms) {
        return String.format(Locale.US, "%.3f", ms / 1000f);
    }

    static String formatOffsetMs(long offsetMs) {
        return (offsetMs >= 0 ? "+" : "") + offsetMs + "ms";
    }

    /** Opens on New with a span recorded from the offset ruler. */
    static void showRecorded(Context context, String videoId, long startMs, long endMs,
                             IntConsumer onOffsetSync, Runnable onExit) {
        // Duplicate guard: when the server already has segments for this video, show History
        // first so the just-recorded span is reviewed against what is already stored.
        final int landing = hasServerSegments(videoId, VideoInformation.getVideoLength())
                ? TAB_HISTORY : TAB_NEW;
        show(context, videoId, startMs, endMs, onOffsetSync, onExit, landing);
    }

    /** Opens on Presets with the queued fingerprint segments; falls back when there are none. */
    static void showPresets(Context context, String videoId, IntConsumer onOffsetSync,
                            Runnable onExit) {
        final long[] span = SponsorBlockPresets.firstSpan(videoId);
        show(context, videoId, span[0], span[1], onOffsetSync, onExit, TAB_PRESETS);
    }

    private static void show(Context context, String videoId, long startMs, long endMs,
                             IntConsumer onOffsetSync, Runnable onExit, int requestedTab) {
        try {
            new Sheet(context, videoId, startMs, endMs, onOffsetSync, onExit, requestedTab).show();
        } catch (Exception ex) {
            Logger.printException(() -> "Could not show the SB submit sheet", ex);
            onExit.run();
        }
    }

    private static boolean hasServerSegments(String videoId, long videoLength) {
        final List<SkipSegments.Seg> segs = SkipSegments.peek(videoId, videoLength);
        return segs != null && !segs.isEmpty();
    }

    private static final class Sheet {

        private final Context context;
        private final String videoId;
        private final long videoLength;
        private final IntConsumer onOffsetSync;
        private final Runnable onExit;
        private final LyricsManager manager = LyricsManager.getInstance();

        private final long[] times;
        private final int[] slots = {0, 0};
        private final int[] selected = {DEFAULT_CATEGORY_INDEX};
        private final boolean[] submitted = {false};
        private final boolean[] cleaned = {false};

        private final TextView[] tabs = new TextView[3];
        private final LinearLayout[] pages = new LinearLayout[3];
        private final Button copyButton;
        private final Button actionButton;
        private final TextView startView;
        private final TextView endView;
        private final Button categoryButton;
        private final OffsetSliderView offsetSlider;
        private final SlideDialog dialog;

        private int currentTab = TAB_NEW;
        /** Preset row currently loaded in New, or -1 when it is a fresh span. */
        private int editingIndex = -1;

        Sheet(Context context, String videoId, long startMs, long endMs,
              IntConsumer onOffsetSync, Runnable onExit, int requestedTab) {
            this.context = context;
            this.videoId = videoId;
            this.videoLength = VideoInformation.getVideoLength();
            this.onOffsetSync = onOffsetSync;
            this.onExit = onExit;
            this.times = new long[]{
                    clampTime(Math.min(startMs, endMs), videoLength),
                    clampTime(Math.max(startMs, endMs), videoLength)};

            manager.clearDialogSegment();

            // --- New content, built once so its state survives tab switches. ---
            startView = createTimeView(context);
            endView = createTimeView(context);
            final TextView separator = new TextView(context);
            separator.setText(" - ");
            separator.setTextSize(22);
            separator.setTypeface(Typeface.DEFAULT_BOLD);
            separator.setTextColor(ThemeUtils.getAppForegroundColor());

            offsetSlider = new OffsetSliderView(context);
            offsetSlider.setValue(manager.getTemporaryOffsetMs());
            offsetSlider.listener = ms -> {
                manager.setTemporaryOffsetMs(ms);
                manager.persistTemporaryOffsetMs();
                onOffsetSync.accept(ms);
            };

            bindTimeView(startView, 0);
            bindTimeView(endView, 1);

            final LinearLayout timesRow = new LinearLayout(context);
            timesRow.setOrientation(LinearLayout.HORIZONTAL);
            timesRow.setGravity(Gravity.CENTER);
            timesRow.addView(startView);
            timesRow.addView(separator);
            timesRow.addView(endView);

            final Button offsetButtonA = createCircleButton(context, "A");
            final Button offsetButtonB = createCircleButton(context, "B");
            offsetButtonA.setOnClickListener(v -> applyOffsetSlot(0));
            offsetButtonB.setOnClickListener(v -> applyOffsetSlot(1));

            final LinearLayout offsetRow = new LinearLayout(context);
            offsetRow.setOrientation(LinearLayout.HORIZONTAL);
            offsetRow.setGravity(Gravity.CENTER_VERTICAL);
            offsetRow.addView(offsetButtonA, new LinearLayout.LayoutParams(Dim.dp36, Dim.dp36));
            final LinearLayout.LayoutParams sliderParams =
                    new LinearLayout.LayoutParams(0, Dim.dp36, 1f);
            sliderParams.leftMargin = Dim.dp8;
            sliderParams.rightMargin = Dim.dp8;
            offsetRow.addView(offsetSlider, sliderParams);
            offsetRow.addView(offsetButtonB, new LinearLayout.LayoutParams(Dim.dp36, Dim.dp36));

            final String[] categoryTitles = new String[CATEGORIES.length];
            final String[] categoryKeys = new String[CATEGORIES.length];
            for (int i = 0; i < CATEGORIES.length; i++) {
                categoryTitles[i] = CATEGORIES[i].title.toString();
                categoryKeys[i] = CATEGORIES[i].keyValue;
            }

            categoryButton = CustomDialog.createButton(context, null,
                    categoryTitles[selected[0]], null, false, false);
            categoryButton.setOnClickListener(v -> {
                final Dialog picker = CustomDialogListPreference.createListDialog(
                        context, str("morphe_sb_new_segment_choose_category"),
                        categoryTitles, categoryKeys, categoryKeys[selected[0]],
                        index -> {
                            selected[0] = index;
                            categoryButton.setText(categoryTitles[index]);
                        });
                picker.show();
            });

            final LinearLayout newPageContent = new LinearLayout(context);
            newPageContent.setOrientation(LinearLayout.VERTICAL);
            newPageContent.addView(timesRow, matchWrap());
            newPageContent.addView(offsetRow, blockParams(Dim.dp28));
            newPageContent.addView(categoryButton, blockParams(Dim.dp16));

            // --- Shell. ---
            final LinearLayout sheet = SheetBottomDialog.createMainLayout(
                    context, LyricsSheetChrome.BACKGROUND_COLOR);
            dialog = SheetBottomDialog.createSlideDialog(context, sheet, SLIDE_ANIMATION_MILLIS);
            dialog.setOnCancelListener(d -> cleanup());
            dialog.setOnDismissListener(d -> cleanup());

            final LinearLayout sheetRoot = new LinearLayout(context);
            sheetRoot.setOrientation(LinearLayout.VERTICAL);
            final LinearLayout topBar = LyricsSheetChrome.createTopBar(context, "", () -> {
                cleanup();
                dialog.dismissImmediately();
            });
            // The tabs share the back-button row; there is no separate title row.
            if (topBar.getChildCount() > 1) {
                topBar.removeViewAt(1);
            }
            sheetRoot.addView(topBar, matchWrap());

            // One ScrollView child hosts all three pages: ScrollView accepts only one direct
            // child, and toggling visibility keeps the New page's state intact.
            final LinearLayout pageHost = new LinearLayout(context);
            pageHost.setOrientation(LinearLayout.VERTICAL);
            for (int i = 0; i < pages.length; i++) {
                pages[i] = new LinearLayout(context);
                pages[i].setOrientation(LinearLayout.VERTICAL);
                pages[i].setVisibility(i == TAB_NEW ? View.VISIBLE : View.GONE);
                pageHost.addView(pages[i], matchWrap());
            }
            pages[TAB_NEW].addView(newPageContent, matchWrap());

            final ScrollView scroll = SheetBottomDialog.createCappedScrollView(context, 70);
            scroll.addView(pageHost, matchWrap());
            final LinearLayout contentHost = LyricsSheetChrome.createContentHost(context);
            contentHost.addView(scroll, matchWrap());
            // Weighted so the content shrinks before the dock is pushed off a short screen.
            sheetRoot.addView(contentHost, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

            // --- Tabs on the back-button row; dock only holds the actions. ---
            final LinearLayout dock = LyricsSheetChrome.createBottomDock(context);

            final LinearLayout tabTrack = new LinearLayout(context);
            tabTrack.setOrientation(LinearLayout.HORIZONTAL);
            tabTrack.setGravity(Gravity.CENTER_VERTICAL);
            tabTrack.setPadding(Dim.dp4, Dim.dp4, Dim.dp4, Dim.dp4);
            final GradientDrawable trackBg = new GradientDrawable();
            trackBg.setShape(GradientDrawable.RECTANGLE);
            trackBg.setCornerRadius(Dim.dp24);
            trackBg.setColor(SEGMENT_TRACK_COLOR);
            tabTrack.setBackground(trackBg);
            final LinearLayout.LayoutParams trackParams = new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            trackParams.leftMargin = Dim.dp8;
            topBar.addView(tabTrack, trackParams);

            final String[] tabTitles = {
                    str("morphe_music_lyrics_sb_tab_new"),
                    str("morphe_music_lyrics_sb_tab_presets"),
                    str("morphe_music_lyrics_sb_tab_history")
            };
            for (int i = 0; i < tabs.length; i++) {
                final int index = i;
                tabs[i] = createSegmentTab(context, tabTitles[i]);
                tabs[i].setOnClickListener(v -> switchTab(index));
                tabTrack.addView(tabs[i], segmentTabParams());
            }

            final LinearLayout buttonRow = new LinearLayout(context);
            buttonRow.setOrientation(LinearLayout.HORIZONTAL);
            copyButton = CustomDialog.createButton(context, dialog,
                    str("morphe_sb_settings_copy"), () -> copySpan(), false, false);
            actionButton = CustomDialog.createButton(context, dialog,
                    str("morphe_music_lyrics_sb_add_to_presets"), this::onAction, true, false);
            final LinearLayout.LayoutParams halfParams =
                    new LinearLayout.LayoutParams(0, Dim.dp36, 1f);
            halfParams.setMargins(Dim.dp4, 0, Dim.dp4, 0);
            buttonRow.addView(copyButton, halfParams);
            buttonRow.addView(actionButton, new LinearLayout.LayoutParams(0, Dim.dp36, 1f));
            dock.addView(buttonRow, matchWrap());
            sheetRoot.addView(dock, matchWrap());

            sheet.addView(sheetRoot, matchWrap());

            refreshTimes();

            // Presets for another video are not this video's; the presets page stays empty.
            int landing = requestedTab;
            if (landing == TAB_PRESETS && !SponsorBlockPresets.hasFor(videoId)) {
                landing = hasServerSegments(videoId, videoLength) ? TAB_HISTORY : TAB_NEW;
            }
            if (landing == TAB_PRESETS) {
                // New already shows the first preset, so saving must replace it.
                editingIndex = 0;
            }
            if (landing == TAB_HISTORY) {
                Settings.LYRICS_SB_HISTORY_SEEN.save(true);
            }
            switchTab(landing);
        }

        void show() {
            try {
                dialog.show();
            } catch (WindowManager.BadTokenException ex) {
                Logger.printException(() -> "Could not show the submit dialog", ex);
                manager.clearDialogSegment();
            }
        }

        // --- Tabs ---

        private void switchTab(int tab) {
            currentTab = tab;
            for (int i = 0; i < pages.length; i++) {
                final boolean active = i == tab;
                pages[i].setVisibility(active ? View.VISIBLE : View.GONE);
                styleSegmentTab(tabs[i], active);
            }
            if (tab == TAB_PRESETS) {
                buildPresetsPage();
            } else if (tab == TAB_HISTORY) {
                buildHistoryPage();
            }
            updateActionButton();
        }

        private void updateActionButton() {
            if (currentTab == TAB_NEW) {
                copyButton.setVisibility(View.VISIBLE);
                actionButton.setVisibility(View.VISIBLE);
                actionButton.setText(str("morphe_music_lyrics_sb_add_to_presets"));
                actionButton.setEnabled(true);
            } else if (currentTab == TAB_PRESETS) {
                copyButton.setVisibility(View.GONE);
                actionButton.setVisibility(View.VISIBLE);
                final int count = SponsorBlockPresets.sizeFor(videoId);
                actionButton.setText(str("morphe_music_lyrics_sb_submit_all", count));
                actionButton.setEnabled(count > 0);
            } else {
                copyButton.setVisibility(View.GONE);
                actionButton.setVisibility(View.GONE);
            }
        }

        // --- New ---

        private void refreshTimes() {
            startView.setText(formatTimeMs(times[0]));
            endView.setText(formatTimeMs(times[1]));
            // The candidate skips the lyrics the moment it exists and on every resample,
            // so what the sheet shows is what playback already does.
            manager.setDialogSegment(videoId, times[0], times[1]);
            offsetSlider.setValue(manager.getTemporaryOffsetMs());
        }

        private void onAction() {
            if (currentTab == TAB_NEW) {
                saveToPresets();
            } else if (currentTab == TAB_PRESETS) {
                submitBatch();
            }
        }

        private void saveToPresets() {
            if (times[0] >= times[1]) {
                Utils.showToastShort(str("morphe_sb_new_segment_start_is_before_end"));
                return;
            }
            final SponsorBlockPresets.Entry entry = new SponsorBlockPresets.Entry(
                    times[0], times[1], CATEGORIES[selected[0]]);
            int index = editingIndex;
            if (index >= 0 && index < SponsorBlockPresets.sizeFor(videoId)) {
                SponsorBlockPresets.set(videoId, index, entry);
            } else {
                SponsorBlockPresets.add(videoId, entry);
                index = SponsorBlockPresets.sizeFor(videoId) - 1;
            }
            editingIndex = index;
            Utils.showToastShort(str("morphe_music_lyrics_sb_saved_to_presets"));
            switchTab(TAB_PRESETS);
        }

        /** Loads a preset or history row into New for adjustment. */
        private void loadSegment(long startMs, long endMs, @Nullable String categoryKey,
                                 int presetIndex) {
            times[0] = clampTime(Math.min(startMs, endMs), videoLength);
            times[1] = clampTime(Math.max(startMs, endMs), videoLength);
            slots[0] = 0;
            slots[1] = 0;
            selected[0] = categoryIndex(categoryKey);
            categoryButton.setText(CATEGORIES[selected[0]].title.toString());
            editingIndex = presetIndex;
            refreshTimes();
            switchTab(TAB_NEW);
        }

        private int categoryIndex(@Nullable String key) {
            if (key != null) {
                for (int i = 0; i < CATEGORIES.length; i++) {
                    if (key.equals(CATEGORIES[i].keyValue)) {
                        return i;
                    }
                }
            }
            return DEFAULT_CATEGORY_INDEX;
        }

        // --- Presets ---

        private void buildPresetsPage() {
            final LinearLayout page = pages[TAB_PRESETS];
            page.removeAllViews();
            final List<SponsorBlockPresets.Entry> entries = SponsorBlockPresets.forVideo(videoId);
            if (entries.isEmpty()) {
                addMessage(page, str("morphe_music_lyrics_sb_no_presets"));
                return;
            }
            addHint(page, str("morphe_music_lyrics_sb_presets_hint"));
            for (int i = 0; i < entries.size(); i++) {
                final int index = i;
                final SponsorBlockPresets.Entry entry = entries.get(i);
                final TextView row = createRow(context, (i + 1) + ". "
                        + formatTimeMs(entry.startMs) + " - " + formatTimeMs(entry.endMs)
                        + "  [" + entry.category.title + "]");
                row.setOnClickListener(v ->
                        loadSegment(entry.startMs, entry.endMs, entry.category.keyValue, index));
                row.setOnLongClickListener(v -> {
                    SponsorBlockPresets.remove(videoId, index);
                    if (index == editingIndex) {
                        editingIndex = -1;
                    } else if (index < editingIndex) {
                        editingIndex--;
                    }
                    buildPresetsPage();
                    updateActionButton();
                    return true;
                });
                page.addView(row, rowParams());
            }
        }

        private void submitBatch() {
            final List<SBRequester.BatchSegment> batch = SponsorBlockPresets.toBatch(videoId);
            if (batch.isEmpty()) {
                return;
            }
            actionButton.setEnabled(false);
            // The sheet may have been opened right after a version switch, before the player
            // reported a length. Use the freshest value available when actually submitting.
            final long freshLength = VideoInformation.getVideoLength();
            final long length = freshLength > 0 ? freshLength : videoLength;
            Utils.runOnBackgroundThread(() -> {
                final boolean stored = SBRequester.submitSegmentsBatch(videoId, batch, length);
                Utils.runOnMainThread(() -> {
                    if (!stored) {
                        // Keep the reviewed presets so the user can retry.
                        actionButton.setEnabled(true);
                        updateActionButton();
                        return;
                    }
                    submitted[0] = true;
                    SponsorBlockPresets.clear();
                    cleanup();
                    dialog.dismissImmediately();
                    Utils.runOnMainThreadDelayed(() -> {
                        if (videoId.equals(manager.skipSegmentsVideoId())) {
                            SkipSegments.invalidate(videoId);
                            SkipSegments.prefetch(videoId);
                        }
                    }, REFRESH_DELAY_MS);
                });
            });
        }

        // --- History ---

        private void buildHistoryPage() {
            final LinearLayout page = pages[TAB_HISTORY];
            page.removeAllViews();
            addMessage(page, str("morphe_music_lyrics_sb_history_loading"));
            Utils.runOnBackgroundThread(() -> {
                final List<SkipSegments.Seg> segs = SkipSegments.await(videoId, videoLength);
                Utils.runOnMainThread(() -> {
                    page.removeAllViews();
                    if (segs == null) {
                        addMessage(page, str("morphe_music_lyrics_sb_history_unavailable"));
                        return;
                    }
                    if (segs.isEmpty()) {
                        addMessage(page, str("morphe_music_lyrics_sb_history_empty"));
                        return;
                    }
                    addHint(page, str("morphe_music_lyrics_sb_history_hint"));
                    for (SkipSegments.Seg seg : segs) {
                        final TextView row = createRow(context,
                                formatTimeMs(seg.startMs()) + " - " + formatTimeMs(seg.endMs())
                                        + "  [" + seg.category() + "]");
                        row.setOnClickListener(v ->
                                loadSegment(seg.startMs(), seg.endMs(), seg.category(), -1));
                        page.addView(row, rowParams());
                    }
                });
            });
        }

        // --- Shared helpers ---

        private void cleanup() {
            if (cleaned[0]) {
                return;
            }
            cleaned[0] = true;
            if (!submitted[0]) {
                manager.clearDialogSegment();
            }
            onExit.run();
        }

        private void copySpan() {
            final long offsetMs = manager.getTemporaryOffsetMs();
            final String text = videoId + " [" + formatSeconds(times[0]) + ", "
                    + formatSeconds(times[1]) + "] " + (offsetMs > 0 ? "+" : "") + offsetMs;
            final ClipboardManager clipboard = (ClipboardManager)
                    context.getSystemService(Context.CLIPBOARD_SERVICE);
            if (clipboard != null) {
                clipboard.setPrimaryClip(ClipData.newPlainText("sponsorblock", text));
                Utils.showToastShort(str("morphe_music_lyrics_copied"));
            }
        }

        @SuppressLint("ClickableViewAccessibility")
        private void bindTimeView(TextView view, int index) {
            final long[] downSample = new long[1];
            view.setOnTouchListener((v, event) -> {
                if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                    final long rawVideoTime = VideoInformation.getVideoTime();
                    // A real seek to the start is authoritative. The smoothed clock can still
                    // hold the old position for a moment after the rewind, which made it
                    // impossible to set A to 00:00.000.
                    downSample[0] = rawVideoTime == 0 ? 0 : manager.getVideoPositionMs();
                }
                return false;
            });
            view.setOnLongClickListener(v -> {
                // A long press re-anchors this side: drop any offset previously applied to it.
                slots[index] = 0;
                times[index] = clampTime(downSample[0], videoLength);
                if (times[0] > times[1]) {
                    swapSides();
                }
                refreshTimes();
                return true;
            });
            view.setOnClickListener(v -> VideoInformation.seekTo(times[index]));
        }

        private void swapSides() {
            final long swapTime = times[0];
            times[0] = times[1];
            times[1] = swapTime;
            final int swapSlot = slots[0];
            slots[0] = slots[1];
            slots[1] = swapSlot;
        }

        private void applyOffsetSlot(int index) {
            final int delta = manager.getTemporaryOffsetMs();
            final long target = clampTime(times[index] + delta, videoLength);
            final long applied = target - times[index];
            if (applied != 0) {
                slots[index] += (int) applied;
                times[index] += applied;
                if (times[0] > times[1]) {
                    swapSides();
                }
                refreshTimes();
            }
            offsetSlider.apply(delta - (int) applied);
        }
    }

    // --- Static view helpers ---

    private static void addMessage(LinearLayout page, String text) {
        final TextView message = new TextView(page.getContext());
        message.setText(text);
        message.setTextColor(ThemeUtils.getAppForegroundColor());
        message.setGravity(Gravity.CENTER);
        message.setPadding(0, Dim.dp24, 0, Dim.dp24);
        page.addView(message, matchWrap());
    }

    private static void addHint(LinearLayout page, String text) {
        final TextView hint = new TextView(page.getContext());
        hint.setText(text);
        hint.setTextSize(12);
        hint.setTextColor(ThemeUtils.getAppForegroundColor());
        hint.setAlpha(0.7f);
        hint.setPadding(0, 0, 0, Dim.dp8);
        page.addView(hint, matchWrap());
    }

    private static TextView createRow(Context context, String text) {
        final TextView row = new TextView(context);
        row.setText(text);
        row.setTextSize(15);
        row.setTextColor(ThemeUtils.getAppForegroundColor());
        row.setPadding(Dim.dp8, Dim.dp12, Dim.dp8, Dim.dp12);
        row.setBackground(CustomDialog.createRoundedBackground(12,
                ThemeUtils.getCancelOrNeutralButtonBackgroundColor()));
        return row;
    }

    private static LinearLayout.LayoutParams rowParams() {
        final LinearLayout.LayoutParams params = matchWrap();
        params.bottomMargin = Dim.dp8;
        return params;
    }

    private static TextView createTimeView(Context context) {
        final TextView view = new TextView(context);
        view.setTextSize(22);
        view.setTypeface(Typeface.DEFAULT_BOLD);
        view.setTextColor(ThemeUtils.getAppForegroundColor());
        view.setGravity(Gravity.CENTER);
        return view;
    }

    private static LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
    }

    private static LinearLayout.LayoutParams blockParams(int topMargin) {
        final LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, Dim.dp36);
        params.topMargin = topMargin;
        return params;
    }

    private static long clampTime(long value, long videoLength) {
        if (value < 0) {
            return 0;
        }
        return videoLength > 0 && value > videoLength ? videoLength : value;
    }

    private static Button createCircleButton(Context context, String text) {
        final Button button = CustomDialog.createButton(context, null, text, null, false, false);
        final GradientDrawable circle = new GradientDrawable();
        circle.setShape(GradientDrawable.OVAL);
        circle.setColor(ThemeUtils.getCancelOrNeutralButtonBackgroundColor());
        button.setBackground(circle);
        return button;
    }

    private static TextView createSegmentTab(Context context, String label) {
        final TextView tab = new TextView(context);
        tab.setText(label);
        tab.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
        tab.setTypeface(null, Typeface.BOLD);
        tab.setSingleLine(true);
        tab.setGravity(Gravity.CENTER);
        tab.setMinimumHeight(Dim.dp40);
        tab.setFocusable(true);
        return tab;
    }

    private static LinearLayout.LayoutParams segmentTabParams() {
        return new LinearLayout.LayoutParams(0, Dim.dp40, 1f);
    }

    private static void styleSegmentTab(TextView tab, boolean active) {
        final GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.RECTANGLE);
        bg.setCornerRadius(Dim.dp20);
        if (active) {
            bg.setColor(Color.WHITE);
            tab.setTextColor(0xFF141418);
        } else {
            bg.setColor(Color.TRANSPARENT);
            tab.setTextColor(0xFFD2D2DC);
        }
        LyricsSheetChrome.attachInteractiveBackground(tab, bg, Dim.dp20,
                active ? 0x22000000 : 0x2AFFFFFF);
    }

    /**
     * The offset block: a rounded rectangle the size of the category picker that is dragged as
     * a whole to move the temporary offset through the same range and step as the panel's ruler.
     * Holding it for a second resets the offset to zero.
     */
    private static final class OffsetSliderView extends View {
        private static final int RANGE_MS = 30000;
        private static final int STEP_MS = 10;
        private static final float DRAG_DIVISOR = 10f;
        private static final long LONG_PRESS_MS = 1000;

        private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final int touchSlop;

        private int offsetMs;
        private float downX;
        private int downValue;
        private boolean dragging;

        @Nullable
        private IntConsumer listener;

        private final Runnable resetRunnable = () -> {
            downValue = 0;
            apply(0);
        };

        OffsetSliderView(Context context) {
            super(context);
            textPaint.setTextAlign(Paint.Align.CENTER);
            textPaint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
            textPaint.setColor(ThemeUtils.getAppForegroundColor());
            touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
            setBackground(CustomDialog.createRoundedBackground(20,
                    ThemeUtils.getCancelOrNeutralButtonBackgroundColor()));
        }

        private static int clamp(int ms) {
            return Math.max(-RANGE_MS, Math.min(RANGE_MS, ms));
        }

        void setValue(int ms) {
            offsetMs = clamp(ms);
            invalidate();
        }

        void apply(int ms) {
            final int stepped = clamp(Math.round(ms / (float) STEP_MS) * STEP_MS);
            if (stepped == offsetMs) {
                return;
            }
            offsetMs = stepped;
            invalidate();
            if (listener != null) {
                listener.accept(stepped);
            }
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            final int w = MeasureSpec.getSize(widthMeasureSpec);
            final int h = (int) (36 * getResources().getDisplayMetrics().density);
            setMeasuredDimension(w, h);
        }

        @SuppressLint("ClickableViewAccessibility")
        @Override
        public boolean onTouchEvent(@NonNull MotionEvent event) {
            final float x = event.getX();
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    setValue(LyricsManager.getInstance().getTemporaryOffsetMs());
                    downX = x;
                    downValue = offsetMs;
                    dragging = false;
                    postDelayed(resetRunnable, LONG_PRESS_MS);
                    return true;
                case MotionEvent.ACTION_MOVE:
                    if (!dragging && Math.abs(x - downX) > touchSlop) {
                        dragging = true;
                        removeCallbacks(resetRunnable);
                    }
                    if (dragging) {
                        final int delta = Math.round(
                                (x - downX) / Math.max(1, getWidth())
                                        * (2f * RANGE_MS) / DRAG_DIVISOR);
                        apply(downValue - delta);
                    }
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    removeCallbacks(resetRunnable);
                    dragging = false;
                    return true;
                default:
                    return super.onTouchEvent(event);
            }
        }

        @Override
        protected void onDraw(@NonNull Canvas canvas) {
            textPaint.setTextSize(TypedValue.applyDimension(
                    TypedValue.COMPLEX_UNIT_SP, 16, getResources().getDisplayMetrics()));
            final Paint.FontMetrics metrics = textPaint.getFontMetrics();
            canvas.drawText(formatOffsetMs(offsetMs),
                    getWidth() / 2f,
                    getHeight() / 2f - (metrics.ascent + metrics.descent) / 2f, textPaint);
        }
    }
}
