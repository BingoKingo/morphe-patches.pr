/*
 * Copyright 2026 Morphe.
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.lyrics.ui;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.LinearInterpolator;
import android.widget.LinearLayout;
import android.widget.ScrollView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;

import app.morphe.extension.shared.ui.Dim;

/**
 * spring bounce animation engine when lyrics line change so it scroll bouncy like jelly instead of stiff linear move.
 */
public final class LyricsScrollEngine {

    public interface Callback {
        boolean isUserScrolling();
        void setProgrammaticScrolling(boolean isProgrammatic);
        void onRecomputeAndScroll(int referenceIndex);
    }

    private static final long IN_FLIGHT_LOCK_MS = 400L;
    private static final float NATIVE_SCROLL_DISTANCE_RATIO = 2.0f;
    private static final float SKIP_THRESHOLD_PX = 0.5f;

    private static final class RowSpec {
        final View view;
        final float startDelta;
        final long delayMs;
        final int durationMs;
        final SpringEasing.Result easing;

        RowSpec(View view, float startDelta, long delayMs, int durationMs, SpringEasing.Result easing) {
            this.view = view;
            this.startDelta = startDelta;
            this.delayMs = delayMs;
            this.durationMs = durationMs;
            this.easing = easing;
        }
    }

    private final ScrollView scrollView;
    private final LinearLayout linesContainer;
    private final View footerContainer;
    private final Callback callback;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private boolean isAnimating = false;
    private boolean hasPendingUpdate = false;
    private int pendingTargetIndex = -1;

    @Nullable
    private ValueAnimator currentAnimator = null;
    @Nullable
    private RowSpec[] currentSpecs = null;
    private int currentSpecCount = 0;

    private final Runnable unlockRunnable = new Runnable() {
        @Override
        public void run() {
            isAnimating = false;
            if (hasPendingUpdate) {
                hasPendingUpdate = false;
                final int targetIdx = pendingTargetIndex;
                pendingTargetIndex = -1;
                if (!callback.isUserScrolling()) {
                    callback.onRecomputeAndScroll(targetIdx);
                }
            }
        }
    };

    public LyricsScrollEngine(@NonNull ScrollView scrollView,
                              @NonNull LinearLayout linesContainer,
                              @NonNull View footerContainer,
                              @NonNull Callback callback) {
        this.scrollView = scrollView;
        this.linesContainer = linesContainer;
        this.footerContainer = footerContainer;
        this.callback = callback;
    }

    public boolean isAnimating() {
        return isAnimating;
    }

    /**
     * reset any running animation
     */
    public void reset() {
        cancelCascade();
        handler.removeCallbacks(unlockRunnable);
        isAnimating = false;
        hasPendingUpdate = false;
        pendingTargetIndex = -1;
    }

    /**
     * overload for compat
     */
    public void scrollTo(int targetY,
                         @Nullable View referenceView,
                         int referenceIndex,
                         long lookAheadMs,
                         boolean forceInstant) {
        scrollTo(targetY, referenceView, referenceIndex, referenceView, referenceIndex, lookAheadMs, forceInstant);
    }

    /**
     * scroll with spring cascade to new active line
     */
    public void scrollTo(int targetY,
                         @Nullable View targetView,
                         int targetIndex,
                         @Nullable View referenceView,
                         int referenceIndex,
                         long lookAheadMs,
                         boolean forceInstant) {
        if (scrollView.getHeight() <= 0 || linesContainer.getChildCount() <= 0) {
            return;
        }
        if (callback.isUserScrolling()) {
            return;
        }

        // lock animation for a bit so multiple scroll request dont clash, queue latest target
        if (isAnimating && !forceInstant) {
            pendingTargetIndex = targetIndex;
            hasPendingUpdate = true;
            return;
        }

        final int target = Math.max(0, targetY);
        final int currentScrollY = scrollView.getScrollY();
        final float requestedDelta = (float) (target - currentScrollY);

        // already at target so dont do anything
        if (Math.abs(requestedDelta) < 1.0f && !forceInstant) {
            return;
        }

        // user seek or track change just jump directly without bounce
        if (forceInstant) {
            reset();
            callback.setProgrammaticScrolling(true);
            scrollView.scrollTo(0, target);
            callback.setProgrammaticScrolling(false);
            return;
        }

        final int viewportHeight = scrollView.getHeight();

        // if jump distance too far use normal smooth scroll so it dont look crazy
        if (Math.abs(requestedDelta) > viewportHeight * NATIVE_SCROLL_DISTANCE_RATIO) {
            reset();
            scrollView.smoothScrollTo(0, target);
            return;
        }

        // cancel previous animation and snap rows to position
        cancelCascade();

        // lock scroll for 400ms so animation finish smoothly
        isAnimating = true;
        handler.removeCallbacks(unlockRunnable);
        handler.postDelayed(unlockRunnable, IN_FLIGHT_LOCK_MS);

        // snap scrollview to target and calculate delta
        final int beforeY = scrollView.getScrollY();
        callback.setProgrammaticScrolling(true);
        scrollView.scrollTo(0, target);
        final int afterY = scrollView.getScrollY();
        callback.setProgrammaticScrolling(false);

        final float appliedDelta = (float) (afterY - beforeY);
        if (Math.abs(appliedDelta) < SKIP_THRESHOLD_PX) {
            return;
        }

        // collect all visible lines
        final List<View> validRows = new ArrayList<>(linesContainer.getChildCount());
        final int childCount = linesContainer.getChildCount();
        for (int i = 0; i < childCount; i++) {
            final View child = linesContainer.getChildAt(i);
            if (child == footerContainer) {
                continue;
            }
            if (child.getVisibility() == View.GONE) {
                continue;
            }
            // skip gap lines with 0 height
            if (child instanceof GapLineView && child.getHeight() <= 0) {
                continue;
            }
            validRows.add(child);
        }

        final int totalRows = validRows.size();
        if (totalRows == 0) {
            return;
        }

        // find target line index
        int targetRowIdx = -1;
        if (targetView != null) {
            for (int i = 0; i < totalRows; i++) {
                final View row = validRows.get(i);
                if (row == targetView || (row instanceof ViewGroup && ((ViewGroup) row).indexOfChild(targetView) >= 0)) {
                    targetRowIdx = i;
                    break;
                }
            }
        }
        if (targetRowIdx < 0) {
            targetRowIdx = Math.max(0, Math.min(targetIndex, totalRows - 1));
        }

        // find previous active line index
        int refRowIdx = -1;
        if (referenceView != null) {
            for (int i = 0; i < totalRows; i++) {
                final View row = validRows.get(i);
                if (row == referenceView || (row instanceof ViewGroup && ((ViewGroup) row).indexOfChild(referenceView) >= 0)) {
                    refRowIdx = i;
                    break;
                }
            }
        }
        if (refRowIdx < 0) {
            refRowIdx = (referenceIndex >= 0) ? Math.max(0, Math.min(referenceIndex, totalRows - 1)) : targetRowIdx;
        }

        final int refIdx = refRowIdx;

        // calculate visible lines range
        final int minViewY = Math.min(beforeY, target);
        final int maxViewY = Math.max(beforeY, target) + viewportHeight;

        int visMin = refIdx;
        int visMax = refIdx;
        for (int i = 0; i < totalRows; i++) {
            final View row = validRows.get(i);
            if (row.getBottom() >= minViewY && row.getTop() <= maxViewY) {
                if (i < visMin) visMin = i;
                if (i > visMax) visMax = i;
            }
        }

        int start = Math.max(0, Math.min(Math.min(visMin, refIdx), targetRowIdx));
        final int reachBottom = target + viewportHeight + (int) Math.abs(appliedDelta) + Dim.dp(200);
        int end = Math.min(totalRows, Math.max(Math.max(visMax, refIdx), targetRowIdx) + 1);
        while (end < totalRows && validRows.get(end).getTop() < reachBottom) {
            end++;
        }
        end = Math.min(totalRows, end + 3);

        final int count = end - start;
        if (count <= 0) {
            return;
        }

        // spring constants and delay increment
        final double peakTimeMs = Math.min(450.0, Math.max(350.0, (double) lookAheadMs));
        final SpringEasing.Result easing = SpringEasing.get(peakTimeMs, 0.78, peakTimeMs * 1.5);
        final int durationMs = easing.durationMs;
        final float delayIncrement = (float) (peakTimeMs * 0.1);

        // cascade delay wave from old line to new line
        final boolean scrollingDown;
        if (targetRowIdx > refRowIdx) {
            scrollingDown = true;
        } else if (targetRowIdx < refRowIdx) {
            scrollingDown = false;
        } else {
            scrollingDown = (appliedDelta >= 0);
        }

        final RowSpec[] specs = new RowSpec[count];
        int specIdx = 0;

        if (scrollingDown) {
            int n = 0;
            for (int i = start; i < end; i++) {
                final View row = validRows.get(i);
                final boolean isGap = (row instanceof GapLineView);
                final long delay = (i >= refIdx) ? Math.round(n * delayIncrement) : 0L;
                if (i >= refIdx && !isGap) {
                    n++;
                }
                specs[specIdx++] = new RowSpec(row, appliedDelta, delay, durationMs, easing);
            }
        } else {
            int n = 0;
            for (int i = end - 1; i >= start; i--) {
                final View row = validRows.get(i);
                final boolean isGap = (row instanceof GapLineView);
                final long delay = (i <= refIdx) ? Math.round(n * delayIncrement) : 0L;
                if (i <= refIdx && !isGap) {
                    n++;
                }
                specs[specIdx++] = new RowSpec(row, appliedDelta, delay, durationMs, easing);
            }
        }

        // single animator driving all line translation together
        long maxTotalMs = 0L;
        for (int i = 0; i < specIdx; i++) {
            final RowSpec spec = specs[i];
            maxTotalMs = Math.max(maxTotalMs, spec.delayMs + spec.durationMs);
            spec.view.setTranslationY(spec.startDelta); // keep offset during delay
        }

        currentSpecs = specs;
        currentSpecCount = specIdx;

        final long totalDurationMs = maxTotalMs;
        final ValueAnimator animator = ValueAnimator.ofInt(0, (int) totalDurationMs);
        currentAnimator = animator;
        animator.setDuration(totalDurationMs);
        animator.setInterpolator(new LinearInterpolator());
        animator.addUpdateListener(animation -> {
            final long playTime = (long) (animation.getAnimatedFraction() * totalDurationMs);
            final RowSpec[] arr = currentSpecs;
            final int len = currentSpecCount;
            if (arr == null) {
                return;
            }
            for (int i = 0; i < len; i++) {
                final RowSpec spec = arr[i];
                final long t = playTime - spec.delayMs;
                if (t <= 0) {
                    spec.view.setTranslationY(spec.startDelta);
                } else if (t >= spec.durationMs) {
                    spec.view.setTranslationY(0f);
                } else {
                    final float p = (float) t / (float) spec.durationMs;
                    final float eased = spec.easing.interpolate(p);
                    spec.view.setTranslationY(spec.startDelta * (1f - eased));
                }
            }
        });
        animator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                finishCascade();
            }

            @Override
            public void onAnimationCancel(Animator animation) {
                finishCascade();
            }
        });
        animator.start();
    }

    private void finishCascade() {
        if (currentSpecs != null) {
            for (int i = 0; i < currentSpecCount; i++) {
                currentSpecs[i].view.setTranslationY(0f);
            }
            currentSpecs = null;
            currentSpecCount = 0;
        }
        currentAnimator = null;
    }

    public void cancelCascade() {
        if (currentAnimator != null) {
            currentAnimator.cancel();
            currentAnimator = null;
        }
        finishCascade();
    }
}
