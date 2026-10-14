/*
 * Copyright 2026 Morphe.
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.lyrics.ui;

import static app.morphe.extension.music.patches.lyrics.ui.LyricsPanelView.TRANSITION_INTERPOLATOR;
import static app.morphe.extension.music.patches.lyrics.ui.LyricsPanelView.lineTextColor;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.LinearInterpolator;
import android.view.animation.PathInterpolator;

import androidx.annotation.Nullable;

import app.morphe.extension.music.patches.lyrics.LyricsManager;
import app.morphe.extension.shared.ui.Dim;

class GapLineView extends View {
    public record GapInterval(long startMs, long endMs, long nextStartMs, boolean isDuet, int beforeLineIndex) {}

    static final long GAP_EXIT_DURATION_MS = 600L;
    private static final float INTERVAL_DOT_RADIUS_DP = 4.0f;
    private static final float INTERVAL_DOT_GAP_DP = 7.0f;
    private static final int INTERVAL_SECONDARY_ALPHA = 0x55;
    private static final int INTERVAL_PRIMARY_ALPHA = 0xFF;

    private final GapInterval interval;
    private boolean isActive = false;
    private float heightFraction = 0f;
    private float groupScale = 0f;
    private float groupAlpha = 0f;
    private ValueAnimator expandAnimator;
    private ValueAnimator exitAnimator;
    private final Paint dotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private static final float TARGET_HEIGHT_DP = 38f;

    public GapLineView(Context context, GapInterval interval) {
        super(context);
        this.interval = interval;
        dotPaint.setStyle(Paint.Style.FILL);
        setVisibility(View.GONE);
    }

    public GapInterval getInterval() {
        return interval;
    }

    public boolean isGapActive() {
        return isActive || (exitAnimator != null && exitAnimator.isRunning());
    }

    public void updateState(long currentPos, boolean active) {
        if (active) {
            if (exitAnimator != null && exitAnimator.isRunning()) {
                exitAnimator.cancel();
            }
            if (!isActive) {
                isActive = true;
                setVisibility(View.VISIBLE);
                startExpandAnimation();
            }
            invalidate();
        } else {
            if (isActive) {
                isActive = false;
                if (expandAnimator != null && expandAnimator.isRunning()) {
                    expandAnimator.cancel();
                }
                if (currentPos >= interval.endMs() && currentPos < interval.nextStartMs()) {
                    startExitAnimation();
                } else {
                    resetImmediate();
                }
            } else if (exitAnimator != null && exitAnimator.isRunning()) {
                if (currentPos < interval.startMs() || currentPos >= interval.nextStartMs()) {
                    resetImmediate();
                }
            } else if (currentPos >= interval.endMs() && currentPos < interval.nextStartMs() && getVisibility() == View.VISIBLE) {
                startExitAnimation();
            }
        }
    }

    private void startExpandAnimation() {
        if (expandAnimator != null) expandAnimator.cancel();
        if (exitAnimator != null) exitAnimator.cancel();

        final float startHeight = heightFraction;
        final float startScale = groupScale;
        final float startAlpha = groupAlpha;

        expandAnimator = ValueAnimator.ofFloat(0.0f, 1.0f);
        expandAnimator.setDuration(300);
        expandAnimator.setInterpolator(new PathInterpolator(0.25f, 0.1f, 0.25f, 1.0f));
        expandAnimator.addUpdateListener(anim -> {
            float f = (float) anim.getAnimatedValue();
            heightFraction = startHeight + (1.0f - startHeight) * f;
            groupScale = startScale + (1.0f - startScale) * f;
            groupAlpha = startAlpha + (1.0f - startAlpha) * f;
            updateViewHeight();
            invalidate();
        });
        expandAnimator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                heightFraction = 1.0f;
                groupScale = 1.0f;
                groupAlpha = 1.0f;
                updateViewHeight();
                invalidate();
            }
        });
        expandAnimator.start();
    }

    private void startExitAnimation() {
        if (expandAnimator != null) expandAnimator.cancel();
        if (exitAnimator != null) exitAnimator.cancel();

        final long remaining = Math.max(100L, interval.nextStartMs() - LyricsManager.getInstance().getPositionMs());
        final long duration = Math.min(GAP_EXIT_DURATION_MS, remaining);

        exitAnimator = ValueAnimator.ofFloat(0.0f, 1.0f);
        exitAnimator.setDuration(duration);
        exitAnimator.setInterpolator(new LinearInterpolator());
        exitAnimator.addUpdateListener(anim -> {
            float p = (float) anim.getAnimatedValue();

            if (p <= 0.35f) {
                float k = p / 0.35f;
                groupScale = 1.0f + 0.2f * (float) Math.sin(k * Math.PI / 2.0);
            } else {
                float k = (p - 0.35f) / 0.65f;
                groupScale = Math.max(0f, 1.2f * (1.0f - (float) Math.sin(k * Math.PI / 2.0)));
            }

            // collapse height and fade dots when interlude end
            if (p < 0.625f) {
                heightFraction = 1.0f;
                groupAlpha = 1.0f;
            } else {
                float k = (p - 0.625f) / 0.375f;
                float collapseEase = (float) Math.sin(k * Math.PI / 2.0);
                heightFraction = Math.max(0f, 1.0f - collapseEase);
                groupAlpha = Math.max(0f, 1.0f - collapseEase);
                updateViewHeight();
            }

            invalidate();
        });
        exitAnimator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                resetImmediate();
            }
        });
        exitAnimator.start();
    }

    private void resetImmediate() {
        if (expandAnimator != null) expandAnimator.cancel();
        if (exitAnimator != null) exitAnimator.cancel();
        isActive = false;
        heightFraction = 0f;
        groupScale = 0f;
        groupAlpha = 0f;
        updateViewHeight();
        setVisibility(View.GONE);
        invalidate();
    }

    private void updateViewHeight() {
        int newHeight = Math.round(Dim.dp(TARGET_HEIGHT_DP) * heightFraction);
        ViewGroup.LayoutParams lp = getLayoutParams();
        if (lp != null && lp.height != newHeight) {
            lp.height = newHeight;
            setLayoutParams(lp);
        }
    }

    public void destroy() {
        if (expandAnimator != null) expandAnimator.cancel();
        if (exitAnimator != null) exitAnimator.cancel();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int width = MeasureSpec.getSize(widthMeasureSpec);
        int height = Math.round(Dim.dp(TARGET_HEIGHT_DP) * heightFraction);
        setMeasuredDimension(width, height);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (getHeight() <= 0 || groupAlpha <= 0f || groupScale <= 0f) {
            return;
        }

        long currentTime = LyricsManager.getInstance().getPositionMs();
        long gapDuration = interval.endMs() - interval.startMs();
        if (gapDuration <= 0) return;

        float segmentDurationMs = (float) gapDuration / 3f;
        float syllableDuration = segmentDurationMs * 0.7f;
        float gapPadding = segmentDurationMs * 0.3f;

        float currentScale = groupScale;
        if (isActive) {
            long elapsedSinceStart = Math.max(0L, currentTime - interval.startMs());
            float cyclePhase = (float) ((elapsedSinceStart % 8000L) / 8000.0);
            float breathe = 1.0f + 0.1f * (float) Math.sin(2.0 * Math.PI * cyclePhase);
            currentScale = groupScale * breathe;
        }

        final float r = Dim.dp(INTERVAL_DOT_RADIUS_DP);
        final float gapPx = Dim.dp(INTERVAL_DOT_GAP_DP);
        final float step = 2f * r + gapPx;
        final float totalWidth = 2f * step + 2f * r;

        final float textMargin = Dim.dp16;
        final float startX = interval.isDuet()
                ? (getWidth() - totalWidth - textMargin)
                : textMargin;
        final float groupCenterX = startX + totalWidth / 2f;
        final float cy = getHeight() / 2f;

        int baseColor = lineTextColor();
        int red = Color.red(baseColor);
        int green = Color.green(baseColor);
        int blue = Color.blue(baseColor);

        canvas.save();
        canvas.scale(currentScale, currentScale, groupCenterX, cy);

        for (int i = 0; i < 3; i++) {
            float sylStart = interval.startMs() + (i * segmentDurationMs) + gapPadding;
            float sylEnd = sylStart + syllableDuration;

            float dotFillAlpha;
            if (currentTime < sylStart) {
                dotFillAlpha = (float) INTERVAL_SECONDARY_ALPHA;
            } else if (currentTime <= sylEnd) {
                float t = (float) (currentTime - sylStart) / syllableDuration;
                t = Math.max(0f, Math.min(1f, t));
                float interpolated = TRANSITION_INTERPOLATOR.getInterpolation(t);
                dotFillAlpha = INTERVAL_SECONDARY_ALPHA + (INTERVAL_PRIMARY_ALPHA - INTERVAL_SECONDARY_ALPHA) * interpolated;
            } else {
                dotFillAlpha = (float) INTERVAL_PRIMARY_ALPHA;
            }

            int finalAlpha = Math.max(0, Math.min(255, Math.round(dotFillAlpha * groupAlpha)));
            dotPaint.setColor(Color.argb(finalAlpha, red, green, blue));

            float cx = startX + r + i * step;
            canvas.drawCircle(cx, cy, r, dotPaint);
        }

        canvas.restore();

        if ((isActive || (exitAnimator != null && exitAnimator.isRunning())) && LyricsManager.getInstance().isPlaying()) {
            postInvalidateOnAnimation();
        }
    }
}
