/*
 * Copyright 2026 Morphe.
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.lyrics.ui;

import static app.morphe.extension.music.patches.lyrics.ui.LyricsPanelView.TRANSLATION_RELATIVE_SIZE;
import static app.morphe.extension.music.patches.lyrics.ui.LyricsPanelView.containsKanji;
import static app.morphe.extension.music.patches.lyrics.ui.LyricsPanelView.TRANSITION_INTERPOLATOR;
import static app.morphe.extension.music.patches.lyrics.ui.LyricsPanelView.auxiliaryTextColor;
import static app.morphe.extension.music.patches.lyrics.ui.LyricsPanelView.lineTextColor;
import static app.morphe.extension.music.patches.lyrics.ui.LyricsPanelView.unsungWordColor;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ArgbEvaluator;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RenderEffect;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.os.Build;
import android.os.SystemClock;
import android.text.Layout;
import android.text.Spannable;
import android.text.SpannableString;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.style.ForegroundColorSpan;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.Interpolator;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import android.text.Spanned;
import android.view.Gravity;
import android.view.MotionEvent;
import java.util.Arrays;
import app.morphe.extension.music.patches.lyrics.LyricsManager;
import app.morphe.extension.music.patches.lyrics.derive.LyricsRomanizer;
import app.morphe.extension.music.patches.lyrics.model.LyricsLine;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.ui.Dim;

class LyricsLineView extends TextView {
    public static record WordTiming(int start, int end, long startMs, long endMs,
            @Nullable String romaji) {
    }

    public static final class WordUnit {
        public final String kanji;
        public String romaji;
        public long startMs;
        public long endMs;
        public boolean endsWithSpace;
        public float kanjiWidth;
        public float romajiWidth;
        public float unitLeft;
        public float unitRight;
        public float kanjiX;
        public float romajiX;
        public float kanjiY;
        public float romajiY;
        public float lineTop;
        public float lineBottom;
        public int lineIndex;

        public float[] charWidths;
        public float[] charOffsets;
        public float[] romajiCharWidths;
        public float[] romajiCharOffsets;

        public long wordStartMs = LyricsLine.NO_TIME;
        public long wordEndMs = LyricsLine.NO_TIME;
        public float wordCenterX;
        public float wordCenterY;
        public float kanjiCenterX;
        public float kanjiCenterY;
        public float romajiCenterX;
        public float romajiCenterY;

        public WordUnit(String kanji, String romaji, long startMs, long endMs, boolean endsWithSpace) {
            this.kanji = kanji != null ? kanji : "";
            this.romaji = romaji != null ? romaji : "";
            this.startMs = startMs;
            this.endMs = endMs;
            this.endsWithSpace = endsWithSpace;
            this.wordStartMs = startMs;
            this.wordEndMs = endMs;
        }

        public WordUnit(String kanji, String romaji, long startMs, long endMs) {
            this(kanji, romaji, startMs, endMs, true);
        }
    }

    @Nullable
    private List<WordUnit> wordUnits = null;
    @Nullable
    private String translationText = null;
    private final TextPaint transPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private StaticLayout transLayout = null;
    private int transLayoutWidth = -1;

    private final TextPaint romajiPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint unsungKanjiPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint unsungRomajiPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint sungKanjiPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint sungRomajiPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint glowPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private int cachedLayoutWidth = -1;
    private int totalMeasuredHeight = 0;

    private List<WordTiming> wordTimings = Collections.emptyList();
    private long positionMs = Long.MIN_VALUE;
    private boolean allSung = false;
    private int unsungColor;
    private int sungColor;
    private int originalTextStart;
    private float[] lineMaxSungX;
    private float currentScale = 0.95f;
    private boolean isLineActive = false;
    private boolean cachedHasWordTimings = false;

    private boolean computeHasWordTimings() {
        if (wordUnits != null && !wordUnits.isEmpty()) {
            for (int i = 0; i < wordUnits.size(); i++) {
                if (wordUnits.get(i).startMs != LyricsLine.NO_TIME) {
                    return true;
                }
            }
            return false;
        }
        return wordTimings != null && !wordTimings.isEmpty();
    }

    public boolean hasWordTimings() {
        return cachedHasWordTimings;
    }

    public void setLineActive(boolean active) {
        if (this.isLineActive != active) {
            this.isLineActive = active;
            postInvalidateOnAnimation();
        }
    }

    private boolean isSecondary = false;
    private int parentLineIndex = -1;

    public void setSecondary(boolean secondary) {
        this.isSecondary = secondary;
    }

    public boolean isSecondary() {
        return isSecondary;
    }

    public void setParentLineIndex(int idx) {
        this.parentLineIndex = idx;
    }

    public int getParentLineIndex() {
        return parentLineIndex;
    }

    private float secondaryExpandFraction = 1f;
    @Nullable
    private ValueAnimator secondaryExpandAnimator;

    public void setSecondaryExpandFraction(float fraction) {
        float clamped = Math.max(0f, Math.min(1f, fraction));
        if (Math.abs(this.secondaryExpandFraction - clamped) > 0.001f) {
            this.secondaryExpandFraction = clamped;
            requestLayout();
            invalidate();
        }
    }

    public float getSecondaryExpandFraction() {
        return secondaryExpandFraction;
    }

    public void animateSecondaryExpansion(boolean expand) {
        float target = expand ? 1f : 0f;
        if (secondaryExpandAnimator != null) {
            secondaryExpandAnimator.cancel();
            secondaryExpandAnimator = null;
        }
        if (Math.abs(secondaryExpandFraction - target) < 0.001f) {
            if (!expand && getVisibility() != View.GONE) {
                setVisibility(View.GONE);
            } else if (expand && getVisibility() != View.VISIBLE) {
                setVisibility(View.VISIBLE);
            }
            return;
        }
        if (expand && getVisibility() != View.VISIBLE) {
            setVisibility(View.VISIBLE);
        }
        long duration = expand ? 450L : 350L;
        secondaryExpandAnimator = ValueAnimator.ofFloat(secondaryExpandFraction, target);
        secondaryExpandAnimator.setDuration(duration);
        secondaryExpandAnimator.setInterpolator(TRANSITION_INTERPOLATOR);
        secondaryExpandAnimator.addUpdateListener(animation -> {
            float val = (Float) animation.getAnimatedValue();
            setSecondaryExpandFraction(val);
            setAlpha(val * 0.90f);
        });
        secondaryExpandAnimator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                secondaryExpandAnimator = null;
                if (!expand) {
                    setVisibility(View.GONE);
                }
            }
        });
        secondaryExpandAnimator.start();
    }

    private boolean isGravityRightAligned() {
        int absGravity = Gravity.getAbsoluteGravity(getGravity(), getLayoutDirection());
        return (absGravity & Gravity.HORIZONTAL_GRAVITY_MASK) == Gravity.RIGHT;
    }

    private RenderEffect currentRenderEffect;

    public void setRenderEffectCompat(@Nullable RenderEffect effect) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (this.currentRenderEffect == effect) {
                return;
            }
            this.currentRenderEffect = effect;
            super.setRenderEffect(effect);
        }
    }

    private Layout cachedLayout;
    /** Where the word starts and ends being sung, in the direction its script runs. */
    private float[] cachedWordLeadX;
    private float[] cachedWordTrailX;
    private int[] cachedWordLine;
    private int[] cachedWordWrappedLine;
    private int cachedWordCount;
    private int cachedLineCount;
    private int[] cachedLineTop;
    private int[] cachedLineBottom;
    private float[] cachedLineLeft;
    private float[] cachedLineRight;
    private int cachedOrigStart;

    private CharSequence cachedText;
    private String cachedTextStr;

    private Layout sungLayout;
    private CharSequence sungLayoutText;
    private int sungLayoutWidth = -1;
    private int sungLayoutColor;
    private int sungLayoutOrigStart = -1;

    private int transStart = -1;
    private int transEnd = -1;
    private int romaStart = -1;
    private int romaEnd = -1;
    float lastTouchY;

    LyricsLineView(Context context) {
        super(context);
    }

    void setWordUnits(@Nullable List<WordUnit> units) {
        this.wordUnits = (units != null && !units.isEmpty()) ? units : null;
        this.cachedHasWordTimings = computeHasWordTimings();
        this.cachedLayoutWidth = -1;
        requestLayout();
        invalidate();
    }

    void setTranslationText(@Nullable String text) {
        this.translationText = (text != null && !text.trim().isEmpty()) ? text.trim() : null;
        this.transLayout = null;
        this.cachedLayoutWidth = -1;
        requestLayout();
        invalidate();
    }

    public boolean hasWordUnits() {
        return wordUnits != null && !wordUnits.isEmpty();
    }

    private void layoutWordUnits(int availableWidth) {
        if (wordUnits == null || wordUnits.isEmpty() || availableWidth <= 0) {
            return;
        }
        if (availableWidth == cachedLayoutWidth) {
            return;
        }
        cachedLayoutWidth = availableWidth;

        Paint mainP = getPaint();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            mainP.setFontFeatureSettings("\"liga\" 0, \"clig\" 0, \"dlig\" 0");
        }
        romajiPaint.setTextSize(mainP.getTextSize() * 0.54f);
        romajiPaint.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));

        unsungKanjiPaint.set(mainP);
        unsungKanjiPaint.clearShadowLayer();
        unsungKanjiPaint.setFakeBoldText(false);
        sungKanjiPaint.set(mainP);
        sungKanjiPaint.clearShadowLayer();
        sungKanjiPaint.setFakeBoldText(false);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            unsungKanjiPaint.setFontFeatureSettings("\"liga\" 0, \"clig\" 0, \"dlig\" 0");
            sungKanjiPaint.setFontFeatureSettings("\"liga\" 0, \"clig\" 0, \"dlig\" 0");
        }

        unsungRomajiPaint.set(romajiPaint);
        unsungRomajiPaint.clearShadowLayer();
        unsungRomajiPaint.setFakeBoldText(false);
        sungRomajiPaint.set(romajiPaint);
        sungRomajiPaint.clearShadowLayer();
        sungRomajiPaint.setFakeBoldText(false);

        Paint.FontMetrics kanjiFm = mainP.getFontMetrics();
        float kanjiHeight = kanjiFm.descent - kanjiFm.ascent;
        float kanjiBaseline = -kanjiFm.ascent;

        Paint.FontMetrics romajiFm = romajiPaint.getFontMetrics();
        float romajiHeight = romajiFm.descent - romajiFm.ascent;
        float romajiBaseline = -romajiFm.ascent;

        boolean hasRomaji = false;
        for (int i = 0; i < wordUnits.size(); i++) {
            if (!wordUnits.get(i).romaji.isEmpty()) {
                hasRomaji = true;
                break;
            }
        }

        float romajiMargin = hasRomaji ? Dim.dp(4f) : 0f;
        float effectiveRomajiHeight = hasRomaji ? romajiHeight : 0f;
        float singleLineHeight = kanjiHeight + romajiMargin + effectiveRomajiHeight;
        float lineSpacing = hasRomaji
                ? (singleLineHeight + Dim.dp(6f))
                : (singleLineHeight * 1.15f + Dim.dp(4f));
        float baseSpaceWidth = mainP.measureText(" ");

        // measure all word width first
        for (int i = 0; i < wordUnits.size(); i++) {
            WordUnit u = wordUnits.get(i);
            u.kanjiWidth = mainP.measureText(u.kanji);
            u.romajiWidth = (!u.romaji.isEmpty()) ? romajiPaint.measureText(u.romaji) : 0f;
            int kLen = u.kanji.length();
            if (kLen > 0) {
                u.charWidths = new float[kLen];
                u.charOffsets = new float[kLen];
                mainP.getTextWidths(u.kanji, u.charWidths);
                boolean hasLigature = false;
                for (int c = 0; c < kLen; c++) {
                    if (u.charWidths[c] <= 0f) {
                        hasLigature = true;
                        break;
                    }
                }
                if (hasLigature) {
                    for (int c = 0; c < kLen; ) {
                        int cp = u.kanji.codePointAt(c);
                        int cc = Character.charCount(cp);
                        float cw = mainP.measureText(u.kanji, c, c + cc);
                        for (int k = 0; k < cc; k++) {
                            u.charWidths[c + k] = (k == 0) ? cw : 0f;
                        }
                        c += cc;
                    }
                }
                float off = 0f;
                for (int c = 0; c < kLen; c++) {
                    u.charOffsets[c] = off;
                    off += u.charWidths[c];
                }
                u.kanjiWidth = Math.max(u.kanjiWidth, off);
            } else {
                u.charWidths = null;
                u.charOffsets = null;
            }

            int rLen = u.romaji.length();
            if (rLen > 0) {
                u.romajiCharWidths = new float[rLen];
                u.romajiCharOffsets = new float[rLen];
                romajiPaint.getTextWidths(u.romaji, u.romajiCharWidths);
                boolean hasLigature = false;
                for (int c = 0; c < rLen; c++) {
                    if (u.romajiCharWidths[c] <= 0f) {
                        hasLigature = true;
                        break;
                    }
                }
                if (hasLigature) {
                    for (int c = 0; c < rLen; ) {
                        int cp = u.romaji.codePointAt(c);
                        int cc = Character.charCount(cp);
                        float cw = romajiPaint.measureText(u.romaji, c, c + cc);
                        for (int k = 0; k < cc; k++) {
                            u.romajiCharWidths[c + k] = (k == 0) ? cw : 0f;
                        }
                        c += cc;
                    }
                }
                float rOff = 0f;
                for (int c = 0; c < rLen; c++) {
                    u.romajiCharOffsets[c] = rOff;
                    rOff += u.romajiCharWidths[c];
                }
                u.romajiWidth = Math.max(u.romajiWidth, rOff);
            } else {
                u.romajiCharWidths = null;
                u.romajiCharOffsets = null;
            }
        }

        boolean isRtlLine = false;
        for (int i = 0; i < wordUnits.size(); i++) {
            WordUnit u = wordUnits.get(i);
            if (LyricsRomanizer.containsArabic(u.kanji) || LyricsRomanizer.containsHebrew(u.kanji)) {
                isRtlLine = true;
                break;
            }
        }

        if (isRtlLine) {
            layoutRtlWordUnits(availableWidth, mainP, kanjiHeight, kanjiBaseline,
                    romajiHeight, romajiBaseline, baseSpaceWidth, hasRomaji);
            return;
        }

        float curX = 0f;
        float curY = 0f;
        int currentLine = 0;
        float lastRomajiEndX = 0f;

        int i = 0;
        while (i < wordUnits.size()) {
            WordUnit firstInGroup = wordUnits.get(i);
            boolean isContinuousScript = LyricsRomanizer.containsJapanese(firstInGroup.kanji)
                    || containsKanji(firstInGroup.kanji)
                    || LyricsRomanizer.containsHanzi(firstInGroup.kanji)
                    || LyricsRomanizer.isSongJapanese();

            if (isContinuousScript) {
                WordUnit u = firstInGroup;
                float pillarW = Math.max(u.kanjiWidth, u.romajiWidth);
                if (curX > 0f && curX + pillarW > availableWidth) {
                    curX = 0f;
                    currentLine++;
                    curY += lineSpacing;
                    lastRomajiEndX = 0f;
                }
                u.unitLeft = curX;
                u.unitRight = curX + pillarW;
                u.lineIndex = currentLine;
                u.lineTop = curY;
                u.lineBottom = curY + singleLineHeight;

                u.kanjiX = curX;
                u.romajiX = curX;
                u.kanjiY = curY + kanjiBaseline;
                u.romajiY = curY + kanjiHeight + romajiMargin + romajiBaseline;

                if (!u.romaji.isEmpty()) {
                    lastRomajiEndX = u.romajiX + u.romajiWidth;
                }
                float spacing = u.endsWithSpace ? (baseSpaceWidth * 0.6f) : 0f;
                curX += pillarW + spacing;

                u.wordStartMs = u.startMs;
                u.wordEndMs = u.endMs;
                u.wordCenterX = (u.unitLeft + u.unitRight) / 2f;
                u.wordCenterY = curY + singleLineHeight / 2f;
                u.kanjiCenterX = u.kanjiX + u.kanjiWidth / 2f;
                u.kanjiCenterY = u.wordCenterY;
                u.romajiCenterX = u.romajiX + u.romajiWidth / 2f;
                u.romajiCenterY = u.wordCenterY;

                i++;
            } else {
                // non cjk text
                // group syllable in same word
                int groupEnd = i;
                float wordTotalW = 0f;
                while (groupEnd < wordUnits.size()) {
                    WordUnit wu = wordUnits.get(groupEnd);
                    wordTotalW += Math.max(wu.kanjiWidth, wu.romajiWidth);
                    if (wu.endsWithSpace || groupEnd == wordUnits.size() - 1) {
                        break;
                    }
                    groupEnd++;
                }

                // wrap line if word too wide
                if (curX > 0f && curX + wordTotalW > availableWidth) {
                    curX = 0f;
                    currentLine++;
                    curY += lineSpacing;
                    lastRomajiEndX = 0f;
                }

                int wordStartIdx = i;
                float wordGroupLeft = curX;
                long wordStartMs = Long.MAX_VALUE;
                long wordEndMs = Long.MIN_VALUE;

                for (int wi = i; wi <= groupEnd; wi++) {
                    WordUnit u = wordUnits.get(wi);
                    float pillarW = Math.max(u.kanjiWidth, u.romajiWidth);

                    // fallback if one word wider than screen
                    if (curX > 0f && curX + pillarW > availableWidth) {
                        curX = 0f;
                        currentLine++;
                        curY += lineSpacing;
                        lastRomajiEndX = 0f;
                    }

                    final float spacing;
                    if (LyricsRomanizer.containsHangul(u.kanji)) {
                        spacing = u.endsWithSpace ? baseSpaceWidth : 0f;
                    } else if (LyricsRomanizer.containsCyrillic(u.kanji) || LyricsRomanizer.containsArabic(u.kanji)
                            || LyricsRomanizer.containsHebrew(u.kanji) || LyricsRomanizer.containsGreek(u.kanji)
                            || LyricsRomanizer.containsDevanagari(u.kanji) || LyricsRomanizer.containsThai(u.kanji)) {
                        spacing = u.endsWithSpace ? (hasRomaji ? (baseSpaceWidth * 0.75f) : baseSpaceWidth) : 0f;
                    } else {
                        spacing = u.endsWithSpace ? baseSpaceWidth : 0f;
                    }

                    u.unitLeft = curX;
                    u.unitRight = curX + pillarW;
                    u.lineIndex = currentLine;
                    u.lineTop = curY;
                    u.lineBottom = curY + singleLineHeight;

                    u.kanjiX = curX;
                    u.romajiX = curX;
                    u.kanjiY = curY + kanjiBaseline;
                    u.romajiY = curY + kanjiHeight + romajiMargin + romajiBaseline;

                    u.kanjiCenterX = curX + u.kanjiWidth / 2f;
                    u.kanjiCenterY = curY + singleLineHeight / 2f;
                    u.romajiCenterX = curX + u.romajiWidth / 2f;
                    u.romajiCenterY = curY + singleLineHeight / 2f;

                    if (!u.romaji.isEmpty()) {
                        lastRomajiEndX = u.romajiX + u.romajiWidth;
                    }
                    curX += pillarW + spacing;

                    if (u.startMs != LyricsLine.NO_TIME) {
                        wordStartMs = Math.min(wordStartMs, u.startMs);
                    }
                    if (u.endMs != LyricsLine.NO_TIME) {
                        wordEndMs = Math.max(wordEndMs, u.endMs);
                    }
                }

                float wordGroupRight = wordUnits.get(groupEnd).unitRight;
                float wordCenterX = (wordGroupLeft + wordGroupRight) / 2f;
                float wordCenterY = (wordUnits.get(wordStartIdx).lineTop + wordUnits.get(wordStartIdx).lineBottom) / 2f;
                if (wordStartMs == Long.MAX_VALUE) wordStartMs = LyricsLine.NO_TIME;
                if (wordEndMs == Long.MIN_VALUE) wordEndMs = LyricsLine.NO_TIME;

                for (int wi = i; wi <= groupEnd; wi++) {
                    WordUnit u = wordUnits.get(wi);
                    u.wordStartMs = wordStartMs;
                    u.wordEndMs = wordEndMs;
                    u.wordCenterX = wordCenterX;
                    u.wordCenterY = wordCenterY;
                }

                i = groupEnd + 1;
            }
        }

        final boolean isRightAligned = isGravityRightAligned();
        if (isRightAligned && currentLine >= 0) {
            float[] lineWidths = new float[currentLine + 1];
            for (int j = 0; j < wordUnits.size(); j++) {
                WordUnit u = wordUnits.get(j);
                if (u.lineIndex <= currentLine) {
                    lineWidths[u.lineIndex] = Math.max(lineWidths[u.lineIndex], u.unitRight);
                }
            }
            for (int j = 0; j < wordUnits.size(); j++) {
                WordUnit u = wordUnits.get(j);
                float xShift = Math.max(0f, availableWidth - lineWidths[u.lineIndex]);
                u.unitLeft += xShift;
                u.unitRight += xShift;
                u.kanjiX += xShift;
                u.romajiX += xShift;
                u.wordCenterX += xShift;
                u.kanjiCenterX += xShift;
                u.romajiCenterX += xShift;
            }
        }

        if (translationText != null && !translationText.isEmpty()) {
            transPaint.setTextSize(mainP.getTextSize() * TRANSLATION_RELATIVE_SIZE);
            transPaint.setColor(auxiliaryTextColor());
            transPaint.setAlpha(Math.round(255 * 0.58f));
            transPaint.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));

            Layout.Alignment align = isRightAligned
                    ? Layout.Alignment.ALIGN_OPPOSITE
                    : Layout.Alignment.ALIGN_NORMAL;

            StaticLayout.Builder tb = StaticLayout.Builder
                    .obtain(translationText, 0, translationText.length(), transPaint, availableWidth)
                    .setAlignment(align)
                    .setLineSpacing(Dim.dp(3), 1.15f)
                    .setIncludePad(false);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                tb.setUseLineSpacingFromFallbacks(true);
            }
            transLayout = tb.build();
            transLayoutWidth = availableWidth;

            totalMeasuredHeight = Math.round(curY + singleLineHeight + Dim.dp(4f) + transLayout.getHeight());
        } else {
            transLayout = null;
            totalMeasuredHeight = Math.round(curY + singleLineHeight);
        }
    }

    private void layoutRtlWordUnits(int availableWidth, Paint mainP,
                                    float kanjiHeight, float kanjiBaseline,
                                    float romajiHeight, float romajiBaseline,
                                    float baseSpaceWidth, boolean hasRomaji) {
        final boolean isRightAligned = isGravityRightAligned();

        // wrap arabic words
        float arabicLineSpacing = kanjiHeight * 1.25f;
        List<List<WordUnit>> arabicLines = new ArrayList<>();
        List<WordUnit> curArabicLine = new ArrayList<>();
        float curLineWidth = 0f;

        for (int i = 0; i < wordUnits.size(); i++) {
            WordUnit u = wordUnits.get(i);
            float wordW = u.kanjiWidth;
            float spaceW = u.endsWithSpace ? baseSpaceWidth : 0f;

            if (!curArabicLine.isEmpty() && curLineWidth + wordW > availableWidth) {
                arabicLines.add(curArabicLine);
                curArabicLine = new ArrayList<>();
                curLineWidth = 0f;
            }
            curArabicLine.add(u);
            curLineWidth += wordW + spaceW;
        }
        if (!curArabicLine.isEmpty()) {
            arabicLines.add(curArabicLine);
        }

        // rtl word position right to left
        for (int ln = 0; ln < arabicLines.size(); ln++) {
            List<WordUnit> lineWords = arabicLines.get(ln);
            float lineY = ln * arabicLineSpacing;
            float kanjiY = lineY + kanjiBaseline;

            float totalW = 0f;
            for (int w = 0; w < lineWords.size(); w++) {
                WordUnit u = lineWords.get(w);
                totalW += u.kanjiWidth;
                if (w < lineWords.size() - 1 && u.endsWithSpace) {
                    totalW += baseSpaceWidth;
                }
            }

            float currentRight = isRightAligned ? availableWidth : Math.min(availableWidth, totalW);

            for (int w = 0; w < lineWords.size(); w++) {
                WordUnit u = lineWords.get(w);
                u.kanjiX = currentRight - u.kanjiWidth;
                u.kanjiY = kanjiY;
                u.kanjiCenterX = u.kanjiX + u.kanjiWidth / 2f;
                u.kanjiCenterY = lineY + kanjiHeight / 2f;
                u.wordCenterX = u.kanjiCenterX;
                u.wordCenterY = u.kanjiCenterY;
                u.unitLeft = u.kanjiX;
                u.unitRight = currentRight;
                u.lineIndex = ln;
                u.lineTop = lineY;
                u.lineBottom = lineY + kanjiHeight;

                float spaceW = u.endsWithSpace ? baseSpaceWidth : 0f;
                currentRight = u.kanjiX - spaceW;
            }
        }

        float arabicSectionHeight = arabicLines.size() * arabicLineSpacing;
        float bottomOfLyrics = arabicSectionHeight;

        // wrap romaji text
        if (hasRomaji) {
            float romajiMargin = Dim.dp(6f);
            float romajiSectionTop = arabicSectionHeight + romajiMargin;
            float romajiLineSpacing = romajiHeight * 1.2f;

            List<List<WordUnit>> romajiLines = new ArrayList<>();
            List<WordUnit> curRomajiLine = new ArrayList<>();
            float curRLineWidth = 0f;

            for (int i = 0; i < wordUnits.size(); i++) {
                WordUnit u = wordUnits.get(i);
                if (u.romaji.isEmpty()) continue;
                float wordW = u.romajiWidth;
                float spaceW = u.endsWithSpace ? baseSpaceWidth : 0f;

                if (!curRomajiLine.isEmpty() && curRLineWidth + wordW > availableWidth) {
                    romajiLines.add(curRomajiLine);
                    curRomajiLine = new ArrayList<>();
                    curRLineWidth = 0f;
                }
                curRomajiLine.add(u);
                curRLineWidth += wordW + spaceW;
            }
            if (!curRomajiLine.isEmpty()) {
                romajiLines.add(curRomajiLine);
            }

            // ltr word position left to right
            for (int rln = 0; rln < romajiLines.size(); rln++) {
                List<WordUnit> rWords = romajiLines.get(rln);
                float rLineY = romajiSectionTop + rln * romajiLineSpacing;
                float romajiY = rLineY + romajiBaseline;

                float totalRW = 0f;
                for (int w = 0; w < rWords.size(); w++) {
                    WordUnit u = rWords.get(w);
                    totalRW += u.romajiWidth;
                    if (w < rWords.size() - 1 && u.endsWithSpace) {
                        totalRW += baseSpaceWidth;
                    }
                }

                float currentLeft = isRightAligned ? Math.max(0f, availableWidth - totalRW) : 0f;

                for (int w = 0; w < rWords.size(); w++) {
                    WordUnit u = rWords.get(w);
                    u.romajiX = currentLeft;
                    u.romajiY = romajiY;
                    u.romajiCenterX = currentLeft + u.romajiWidth / 2f;
                    u.romajiCenterY = rLineY + romajiHeight / 2f;

                    float spaceW = u.endsWithSpace ? baseSpaceWidth : 0f;
                    currentLeft += u.romajiWidth + spaceW;
                }
            }

            if (!romajiLines.isEmpty()) {
                bottomOfLyrics = romajiSectionTop + romajiLines.size() * romajiLineSpacing;
            }
        }

        // translation text layout
        if (translationText != null && !translationText.isEmpty()) {
            transPaint.setTextSize(mainP.getTextSize() * TRANSLATION_RELATIVE_SIZE);
            transPaint.setColor(auxiliaryTextColor());
            transPaint.setAlpha(Math.round(255 * 0.58f));
            transPaint.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));

            Layout.Alignment align = isRightAligned
                    ? Layout.Alignment.ALIGN_OPPOSITE
                    : Layout.Alignment.ALIGN_NORMAL;

            StaticLayout.Builder tb = StaticLayout.Builder
                    .obtain(translationText, 0, translationText.length(), transPaint, availableWidth)
                    .setAlignment(align)
                    .setLineSpacing(Dim.dp(3), 1.15f)
                    .setIncludePad(false);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                tb.setUseLineSpacingFromFallbacks(true);
            }
            transLayout = tb.build();
            transLayoutWidth = availableWidth;

            totalMeasuredHeight = Math.round(bottomOfLyrics + Dim.dp(6f) + transLayout.getHeight());
        } else {
            transLayout = null;
            totalMeasuredHeight = Math.round(bottomOfLyrics);
        }
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        if (wordUnits != null && !wordUnits.isEmpty()) {
            int width = MeasureSpec.getSize(widthMeasureSpec);
            int padH = getCompoundPaddingLeft() + getCompoundPaddingRight();
            int availableW = Math.max(0, width - padH);
            if (availableW > 0) {
                layoutWordUnits(availableW);
            }
            int padV = getExtendedPaddingTop() + getExtendedPaddingBottom();
            int measuredH = Math.max(totalMeasuredHeight + padV, getSuggestedMinimumHeight());
            if (isSecondary) {
                measuredH = Math.round(measuredH * secondaryExpandFraction);
            }
            setMeasuredDimension(width, measuredH);
            return;
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
        if (isSecondary) {
            int measuredH = Math.round(getMeasuredHeight() * secondaryExpandFraction);
            setMeasuredDimension(getMeasuredWidth(), measuredH);
        }
    }

    private long lastDrawnClockPos = Long.MIN_VALUE;

    private long getLiveClockPositionMs() {
        if (positionMs == Long.MIN_VALUE) {
            lastDrawnClockPos = Long.MIN_VALUE;
            return Long.MIN_VALUE;
        }
        long rawPos = LyricsManager.getInstance().getPositionMs();
        if (LyricsManager.getInstance().isPlaying() && lastDrawnClockPos != Long.MIN_VALUE) {
            if (rawPos >= lastDrawnClockPos - 1000L) {
                rawPos = Math.max(lastDrawnClockPos, rawPos);
            }
        }
        lastDrawnClockPos = rawPos;
        return rawPos;
    }

    private void onDrawWordUnits(Canvas canvas) {
        if (wordUnits == null || wordUnits.isEmpty()) {
            return;
        }
        if (isSecondary && secondaryExpandFraction <= 0.001f) {
            return;
        }

        final boolean active = isLineActive || (positionMs != Long.MIN_VALUE && !allSung);
        float target = active ? 1.0f : 0.95f;
        if (Math.abs(currentScale - target) > 0.001f) {
            currentScale += (target - currentScale) * 0.15f;
            postInvalidateOnAnimation();
        } else {
            currentScale = target;
        }

        canvas.save();
        if (isSecondary) {
            canvas.clipRect(0, 0, getWidth(), getHeight());
        }
        float pivotX = isGravityRightAligned()
                ? (getWidth() - getCompoundPaddingRight())
                : getCompoundPaddingLeft();
        float pivotY = getHeight() / 2f;
        canvas.scale(currentScale, currentScale, pivotX, pivotY);
        canvas.translate(getCompoundPaddingLeft(), getExtendedPaddingTop());

        TextPaint mainP = getPaint();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            mainP.setFontFeatureSettings("\"liga\" 0, \"clig\" 0, \"dlig\" 0");
            unsungKanjiPaint.setFontFeatureSettings("\"liga\" 0, \"clig\" 0, \"dlig\" 0");
            sungKanjiPaint.setFontFeatureSettings("\"liga\" 0, \"clig\" 0, \"dlig\" 0");
            glowPaint.setFontFeatureSettings("\"liga\" 0, \"clig\" 0, \"dlig\" 0");
        }

        final boolean lineHasTimings = hasWordTimings();

        // setup colors
        int baseSung = (sungColor != 0) ? sungColor : lineTextColor();
        sungKanjiPaint.setColor(baseSung);
        sungKanjiPaint.setFakeBoldText(false);

        final int sungRomajiColor = Color.argb(
                Math.round(255 * 0.85f),
                Color.red(baseSung),
                Color.green(baseSung),
                Color.blue(baseSung));
        sungRomajiPaint.setColor(sungRomajiColor);
        sungRomajiPaint.setFakeBoldText(false);

        glowPaint.set(sungKanjiPaint);
        glowPaint.setShader(null);
        glowPaint.clearShadowLayer();

        int baseUnsung = (active && lineHasTimings)
                ? ((unsungColor != 0) ? unsungColor : unsungWordColor())
                : baseSung;
        unsungKanjiPaint.setColor(baseUnsung);
        unsungKanjiPaint.setFakeBoldText(false);

        int rAlpha = Color.alpha(baseUnsung);
        final int unsungRomajiColor = Color.argb(
                Math.round(rAlpha * 0.85f),
                Color.red(baseUnsung),
                Color.green(baseUnsung),
                Color.blue(baseUnsung));
        unsungRomajiPaint.setColor(unsungRomajiColor);
        unsungRomajiPaint.setFakeBoldText(false);

        final int wordCount = wordUnits.size();

        // layer 1 base text
        final long baseLivePos = (active && lineHasTimings && !allSung) ? getLiveClockPositionMs() : Long.MIN_VALUE;
        final float restingLift = 0f;

        for (int i = 0; i < wordCount; i++) {
            WordUnit u = wordUnits.get(i);
            final boolean timed = baseLivePos != Long.MIN_VALUE && u.startMs != LyricsLine.NO_TIME;
            // past words stay solid white
            if (timed && baseLivePos >= u.endMs) {
                continue;
            }
            final boolean isKanjiRtl = LyricsRomanizer.containsArabic(u.kanji) || LyricsRomanizer.containsHebrew(u.kanji);
            int numChars = u.kanji.length();

            if (!isKanjiRtl && numChars > 0 && u.charWidths != null && u.charOffsets != null) {
                for (int c = 0; c < numChars; ) {
                    int cp = u.kanji.codePointAt(c);
                    int cc = Character.charCount(cp);
                    EmphasisState state = timed
                            ? computeCharEmphasisState(u, c, numChars, baseLivePos, restingLift, mainP.getTextSize(), isSecondary)
                            : null;
                    float lift = state != null ? state.lift : 0f;
                    float scale = state != null ? state.scale : 1f;
                    float chX = u.kanjiX + u.charOffsets[c];
                    float chW = u.charWidths[c];
                    float chCenterX = chX + chW / 2f;
                    String chStr = u.kanji.substring(c, c + cc);

                    canvas.save();
                    if (lift > 0f) {
                        canvas.translate(0f, -lift);
                    }
                    if (scale != 1f) {
                        canvas.scale(scale, scale, chCenterX, u.kanjiCenterY);
                    }
                    canvas.drawText(chStr, chX, u.kanjiY, unsungKanjiPaint);
                    canvas.restore();
                    c += cc;
                }
            } else {
                EmphasisState state = timed ? computeEmphasisState(u, baseLivePos, restingLift, mainP.getTextSize(), isSecondary) : null;
                float lift = state != null ? state.lift : 0f;
                float scale = state != null ? state.scale : 1f;

                canvas.save();
                if (lift > 0f) {
                    canvas.translate(0f, -lift);
                }
                if (scale != 1f) {
                    canvas.scale(scale, scale, u.wordCenterX, u.wordCenterY);
                }
                canvas.drawText(u.kanji, u.kanjiX, u.kanjiY, unsungKanjiPaint);
                canvas.restore();
            }

            if (!u.romaji.isEmpty() && u.romajiWidth > 0f) {
                int rLen = u.romaji.length();
                if (rLen > 0 && u.romajiCharWidths != null && u.romajiCharOffsets != null) {
                    for (int c = 0; c < rLen; ) {
                        int cp = u.romaji.codePointAt(c);
                        int cc = Character.charCount(cp);
                        EmphasisState state = timed
                                ? computeCharEmphasisState(u, c, rLen, baseLivePos, restingLift, romajiPaint.getTextSize(), isSecondary)
                                : null;
                        float lift = state != null ? state.lift : 0f;
                        float scale = state != null ? state.scale : 1f;
                        float chX = u.romajiX + u.romajiCharOffsets[c];
                        float chW = u.romajiCharWidths[c];
                        float chCenterX = chX + chW / 2f;
                        String chStr = u.romaji.substring(c, c + cc);

                        canvas.save();
                        if (lift > 0f) canvas.translate(0f, -lift);
                        if (scale != 1f) canvas.scale(scale, scale, chCenterX, u.romajiCenterY);
                        canvas.drawText(chStr, chX, u.romajiY, unsungRomajiPaint);
                        canvas.restore();
                        c += cc;
                    }
                } else {
                    canvas.save();
                    EmphasisState state = timed ? computeEmphasisState(u, baseLivePos, restingLift, romajiPaint.getTextSize(), isSecondary) : null;
                    float lift = state != null ? state.lift : 0f;
                    float scale = state != null ? state.scale : 1f;
                    if (lift > 0f) {
                        canvas.translate(0f, -lift);
                    }
                    if (scale != 1f) {
                        canvas.scale(scale, scale, u.romajiCenterX, u.romajiCenterY);
                    }
                    canvas.drawText(u.romaji, u.romajiX, u.romajiY, unsungRomajiPaint);
                    canvas.restore();
                }
            }
        }

        // inactive line only need base layer
        if (!active) {
            drawTranslationLayout(canvas);
            canvas.restore();
            return;
        }

        // layer 2 active text
        if (allSung || !lineHasTimings) {
            for (int i = 0; i < wordCount; i++) {
                WordUnit u = wordUnits.get(i);
                canvas.drawText(u.kanji, u.kanjiX, u.kanjiY, sungKanjiPaint);
                if (!u.romaji.isEmpty() && u.romajiWidth > 0f) {
                    canvas.drawText(u.romaji, u.romajiX, u.romajiY, sungRomajiPaint);
                }
            }
        } else {
            final long livePosMs = getLiveClockPositionMs();

            // find syllable currently playing
            int activeWordIndex = -1;
            for (int i = 0; i < wordCount; i++) {
                WordUnit u = wordUnits.get(i);
                if (u.startMs != LyricsLine.NO_TIME && livePosMs < u.endMs) {
                    if (livePosMs >= u.startMs) {
                        activeWordIndex = i;
                    }
                    break;
                }
            }

            for (int i = 0; i < wordCount; i++) {
                WordUnit u = wordUnits.get(i);
                if (u.startMs == LyricsLine.NO_TIME) {
                    continue;
                }
                if (activeWordIndex != -1 && i > activeWordIndex) {
                    continue;
                }
                if (activeWordIndex == -1 && livePosMs < u.startMs) {
                    continue;
                }

                final boolean isKanjiRtl = LyricsRomanizer.containsArabic(u.kanji) || LyricsRomanizer.containsHebrew(u.kanji);
                int numChars = u.kanji.length();

                if (i == activeWordIndex) {
                    final long dur = Math.max(1L, u.endMs - u.startMs);
                    final float progress = Math.max(0f, Math.min(1f, (float) (livePosMs - u.startMs) / (float) dur));

                    if (!isKanjiRtl && numChars > 0 && u.charWidths != null && u.charOffsets != null) {
                        final float wiperX = progress * u.kanjiWidth;
                        for (int c = 0; c < numChars; ) {
                            int cp = u.kanji.codePointAt(c);
                            int cc = Character.charCount(cp);
                            float chLeft = u.charOffsets[c];
                            float chW = u.charWidths[c];
                            float chRight = chLeft + chW;

                            EmphasisState state = computeCharEmphasisState(u, c, numChars, livePosMs, restingLift, mainP.getTextSize(), isSecondary);
                            float lift = state.lift;
                            float scale = state.scale;
                            float glowAlpha = state.glowAlpha;

                            float chX = u.kanjiX + chLeft;
                            float chCenterX = chX + chW / 2f;
                            String chStr = u.kanji.substring(c, c + cc);

                            canvas.save();
                            if (lift > 0f) {
                                canvas.translate(0f, -lift);
                            }
                            if (scale != 1f) {
                                canvas.scale(scale, scale, chCenterX, u.kanjiCenterY);
                            }

                            if (wiperX >= chRight) {
                                if (glowAlpha > 1f) {
                                    float bloomRadius = Math.max(2f, 0.28f * mainP.getTextSize());
                                    int glowColor = Color.argb(Math.round(glowAlpha), 255, 255, 255);
                                    glowPaint.set(sungKanjiPaint);
                                    glowPaint.setShader(null);
                                    glowPaint.setShadowLayer(bloomRadius, 0f, 0f, glowColor);
                                    glowPaint.setColor(glowColor);
                                    glowPaint.setAlpha(Math.round(glowAlpha * 0.40f));
                                    canvas.drawText(chStr, chX, u.kanjiY, glowPaint);
                                    glowPaint.clearShadowLayer();
                                }
                                canvas.drawText(chStr, chX, u.kanjiY, sungKanjiPaint);
                            } else if (wiperX > chLeft) {
                                float chProgress = (wiperX - chLeft) / Math.max(0.001f, chW);
                                if (glowAlpha > 1f) {
                                    float bloomRadius = Math.max(2f, 0.28f * mainP.getTextSize());
                                    int glowColor = Color.argb(Math.round(glowAlpha * chProgress), 255, 255, 255);
                                    glowPaint.set(sungKanjiPaint);
                                    glowPaint.setShader(null);
                                    glowPaint.setShadowLayer(bloomRadius, 0f, 0f, glowColor);
                                    glowPaint.setColor(glowColor);
                                    glowPaint.setAlpha(Math.round(glowAlpha * chProgress * 0.40f));
                                    canvas.drawText(chStr, chX, u.kanjiY, glowPaint);
                                    glowPaint.clearShadowLayer();
                                }
                                drawFeatheredFill(canvas, chStr, chX, u.kanjiY, chW,
                                        chProgress, sungKanjiPaint, sungKanjiPaint.getColor(), false);
                            }
                            canvas.restore();
                            c += cc;
                        }
                    } else {
                        EmphasisState state = computeEmphasisState(u, livePosMs, restingLift, mainP.getTextSize(), isSecondary);
                        float lift = state.lift;
                        float scale = state.scale;
                        float glowAlpha = state.glowAlpha;

                        if (glowAlpha > 1f && progress > 0f) {
                            float bloomRadius = Math.max(2f, 0.28f * mainP.getTextSize());
                            int glowColor = Color.argb(Math.round(glowAlpha * progress), 255, 255, 255);
                            glowPaint.set(sungKanjiPaint);
                            glowPaint.setShader(null);
                            glowPaint.setShadowLayer(bloomRadius, 0f, 0f, glowColor);
                            glowPaint.setColor(glowColor);
                            glowPaint.setAlpha(Math.round(glowAlpha * progress * 0.40f));
                            canvas.save();
                            if (lift > 0f) canvas.translate(0f, -lift);
                            if (scale != 1f) canvas.scale(scale, scale, u.wordCenterX, u.wordCenterY);
                            canvas.drawText(u.kanji, u.kanjiX, u.kanjiY, glowPaint);
                            canvas.restore();
                            glowPaint.clearShadowLayer();
                        }

                        canvas.save();
                        if (lift > 0f) {
                            canvas.translate(0f, -lift);
                        }
                        if (scale != 1f) {
                            canvas.scale(scale, scale, u.wordCenterX, u.wordCenterY);
                        }
                        drawFeatheredFill(canvas, u.kanji, u.kanjiX, u.kanjiY, u.kanjiWidth,
                                progress, sungKanjiPaint, sungKanjiPaint.getColor(), isKanjiRtl);
                        canvas.restore();
                    }

                    // romaji text
                    if (!u.romaji.isEmpty() && u.romajiWidth > 0f) {
                        int rNumChars = u.romaji.length();
                        if (rNumChars > 0 && u.romajiCharWidths != null && u.romajiCharOffsets != null) {
                            final float wiperX = progress * u.romajiWidth;
                            for (int c = 0; c < rNumChars; ) {
                                int cp = u.romaji.codePointAt(c);
                                int cc = Character.charCount(cp);
                                float chLeft = u.romajiCharOffsets[c];
                                float chW = u.romajiCharWidths[c];
                                float chRight = chLeft + chW;

                                EmphasisState state = computeCharEmphasisState(u, c, rNumChars, livePosMs, restingLift, romajiPaint.getTextSize(), isSecondary);
                                float lift = state.lift;
                                float scale = state.scale;
                                float glowAlpha = state.glowAlpha;

                                float chX = u.romajiX + chLeft;
                                float chCenterX = chX + chW / 2f;
                                String chStr = u.romaji.substring(c, c + cc);

                                canvas.save();
                                if (lift > 0f) {
                                    canvas.translate(0f, -lift);
                                }
                                if (scale != 1f) {
                                    canvas.scale(scale, scale, chCenterX, u.romajiCenterY);
                                }

                                if (wiperX >= chRight) {
                                    if (glowAlpha > 1f) {
                                        float bloomRadius = Math.max(2f, 0.28f * romajiPaint.getTextSize());
                                        int glowColor = Color.argb(Math.round(glowAlpha), 255, 255, 255);
                                        glowPaint.set(sungRomajiPaint);
                                        glowPaint.setShader(null);
                                        glowPaint.setShadowLayer(bloomRadius, 0f, 0f, glowColor);
                                        glowPaint.setColor(glowColor);
                                        glowPaint.setAlpha(Math.round(glowAlpha * 0.40f));
                                        canvas.drawText(chStr, chX, u.romajiY, glowPaint);
                                        glowPaint.clearShadowLayer();
                                    }
                                    canvas.drawText(chStr, chX, u.romajiY, sungRomajiPaint);
                                } else if (wiperX > chLeft) {
                                    float chProgress = (wiperX - chLeft) / Math.max(0.001f, chW);
                                    if (glowAlpha > 1f) {
                                        float bloomRadius = Math.max(2f, 0.28f * romajiPaint.getTextSize());
                                        int glowColor = Color.argb(Math.round(glowAlpha * chProgress), 255, 255, 255);
                                        glowPaint.set(sungRomajiPaint);
                                        glowPaint.setShader(null);
                                        glowPaint.setShadowLayer(bloomRadius, 0f, 0f, glowColor);
                                        glowPaint.setColor(glowColor);
                                        glowPaint.setAlpha(Math.round(glowAlpha * chProgress * 0.40f));
                                        canvas.drawText(chStr, chX, u.romajiY, glowPaint);
                                        glowPaint.clearShadowLayer();
                                    }
                                    drawFeatheredFill(canvas, chStr, chX, u.romajiY, chW,
                                            chProgress, sungRomajiPaint, sungRomajiPaint.getColor(), false);
                                }
                                canvas.restore();
                                c += cc;
                            }
                        } else {
                            EmphasisState state = computeEmphasisState(u, livePosMs, restingLift, romajiPaint.getTextSize(), isSecondary);
                            float lift = state.lift;
                            float scale = state.scale;
                            float glowAlpha = state.glowAlpha;
                            if (glowAlpha > 1f && progress > 0f) {
                                float bloomRadius = Math.max(2f, 0.28f * romajiPaint.getTextSize());
                                int glowColor = Color.argb(Math.round(glowAlpha * progress), 255, 255, 255);
                                glowPaint.set(sungRomajiPaint);
                                glowPaint.setShader(null);
                                glowPaint.setShadowLayer(bloomRadius, 0f, 0f, glowColor);
                                glowPaint.setColor(glowColor);
                                glowPaint.setAlpha(Math.round(glowAlpha * progress * 0.40f));
                                canvas.save();
                                if (lift > 0f) canvas.translate(0f, -lift);
                                if (scale != 1f) canvas.scale(scale, scale, u.romajiCenterX, u.romajiCenterY);
                                canvas.drawText(u.romaji, u.romajiX, u.romajiY, glowPaint);
                                canvas.restore();
                                glowPaint.clearShadowLayer();
                            }
                            canvas.save();
                            if (lift > 0f) {
                                canvas.translate(0f, -lift);
                            }
                            if (scale != 1f) {
                                canvas.scale(scale, scale, u.romajiCenterX, u.romajiCenterY);
                            }
                            drawFeatheredFill(canvas, u.romaji, u.romajiX, u.romajiY, u.romajiWidth,
                                    progress, sungRomajiPaint, sungRomajiPaint.getColor(), false);
                            canvas.restore();
                        }
                    }
                } else {
                    // already finished words
                    if (!isKanjiRtl && numChars > 0 && u.charWidths != null && u.charOffsets != null) {
                        for (int c = 0; c < numChars; ) {
                            int cp = u.kanji.codePointAt(c);
                            int cc = Character.charCount(cp);
                            EmphasisState state = computeCharEmphasisState(u, c, numChars, livePosMs, restingLift, mainP.getTextSize(), isSecondary);
                            float lift = state.lift;
                            float scale = state.scale;
                            float glowAlpha = state.glowAlpha;

                            float chX = u.kanjiX + u.charOffsets[c];
                            float chW = u.charWidths[c];
                            float chCenterX = chX + chW / 2f;
                            String chStr = u.kanji.substring(c, c + cc);

                            canvas.save();
                            if (lift > 0f) {
                                canvas.translate(0f, -lift);
                            }
                            if (scale != 1f) {
                                canvas.scale(scale, scale, chCenterX, u.kanjiCenterY);
                            }

                            if (glowAlpha > 1f) {
                                float bloomRadius = Math.max(2f, 0.28f * mainP.getTextSize());
                                int glowColor = Color.argb(Math.round(glowAlpha), 255, 255, 255);
                                glowPaint.set(sungKanjiPaint);
                                glowPaint.setShader(null);
                                glowPaint.setShadowLayer(bloomRadius, 0f, 0f, glowColor);
                                glowPaint.setColor(glowColor);
                                glowPaint.setAlpha(Math.round(glowAlpha * 0.40f));
                                canvas.drawText(chStr, chX, u.kanjiY, glowPaint);
                                glowPaint.clearShadowLayer();
                            }

                            canvas.drawText(chStr, chX, u.kanjiY, sungKanjiPaint);
                            canvas.restore();
                            c += cc;
                        }
                    } else {
                        EmphasisState state = computeEmphasisState(u, livePosMs, restingLift, mainP.getTextSize(), isSecondary);
                        float lift = state.lift;
                        float scale = state.scale;
                        float glowAlpha = state.glowAlpha;

                        if (glowAlpha > 1f) {
                            float bloomRadius = Math.max(2f, 0.28f * mainP.getTextSize());
                            int glowColor = Color.argb(Math.round(glowAlpha), 255, 255, 255);
                            glowPaint.set(sungKanjiPaint);
                            glowPaint.setShader(null);
                            glowPaint.setShadowLayer(bloomRadius, 0f, 0f, glowColor);
                            glowPaint.setColor(glowColor);
                            glowPaint.setAlpha(Math.round(glowAlpha * 0.40f));
                            canvas.save();
                            if (lift > 0f) canvas.translate(0f, -lift);
                            if (scale != 1f) canvas.scale(scale, scale, u.wordCenterX, u.wordCenterY);
                            canvas.drawText(u.kanji, u.kanjiX, u.kanjiY, glowPaint);
                            canvas.restore();
                            glowPaint.clearShadowLayer();
                        }

                        canvas.save();
                        if (lift > 0f) {
                            canvas.translate(0f, -lift);
                        }
                        if (scale != 1f) {
                            canvas.scale(scale, scale, u.wordCenterX, u.wordCenterY);
                        }
                        canvas.drawText(u.kanji, u.kanjiX, u.kanjiY, sungKanjiPaint);
                        canvas.restore();
                    }

                    if (!u.romaji.isEmpty() && u.romajiWidth > 0f) {
                        int rNumChars = u.romaji.length();
                        if (rNumChars > 0 && u.romajiCharWidths != null && u.romajiCharOffsets != null) {
                            for (int c = 0; c < rNumChars; ) {
                                int cp = u.romaji.codePointAt(c);
                                int cc = Character.charCount(cp);
                                EmphasisState state = computeCharEmphasisState(u, c, rNumChars, livePosMs, restingLift, romajiPaint.getTextSize(), isSecondary);
                                float lift = state.lift;
                                float scale = state.scale;
                                float glowAlpha = state.glowAlpha;

                                float chX = u.romajiX + u.romajiCharOffsets[c];
                                float chW = u.romajiCharWidths[c];
                                float chCenterX = chX + chW / 2f;
                                String chStr = u.romaji.substring(c, c + cc);

                                canvas.save();
                                if (lift > 0f) canvas.translate(0f, -lift);
                                if (scale != 1f) canvas.scale(scale, scale, chCenterX, u.romajiCenterY);

                                if (glowAlpha > 1f) {
                                    float bloomRadius = Math.max(2f, 0.28f * romajiPaint.getTextSize());
                                    int glowColor = Color.argb(Math.round(glowAlpha), 255, 255, 255);
                                    glowPaint.set(sungRomajiPaint);
                                    glowPaint.setShader(null);
                                    glowPaint.setShadowLayer(bloomRadius, 0f, 0f, glowColor);
                                    glowPaint.setColor(glowColor);
                                    glowPaint.setAlpha(Math.round(glowAlpha * 0.40f));
                                    canvas.drawText(chStr, chX, u.romajiY, glowPaint);
                                    glowPaint.clearShadowLayer();
                                }

                                canvas.drawText(chStr, chX, u.romajiY, sungRomajiPaint);
                                canvas.restore();
                                c += cc;
                            }
                        } else {
                            EmphasisState state = computeEmphasisState(u, livePosMs, restingLift, romajiPaint.getTextSize(), isSecondary);
                            float lift = state.lift;
                            float scale = state.scale;
                            canvas.save();
                            if (lift > 0f) {
                                canvas.translate(0f, -lift);
                            }
                            if (scale != 1f) {
                                canvas.scale(scale, scale, u.romajiCenterX, u.romajiCenterY);
                            }
                            canvas.drawText(u.romaji, u.romajiX, u.romajiY, sungRomajiPaint);
                            canvas.restore();
                        }
                    }
                }
            }
        }

        drawTranslationLayout(canvas);
        canvas.restore();

        if (active && lineHasTimings && LyricsManager.getInstance().isPlaying()) {
            postInvalidateOnAnimation();
        }
    }

    private static class EmphasisState {
        final float scale;
        final float lift;
        final float glowAlpha;

        EmphasisState(float scale, float lift, float glowAlpha) {
            this.scale = scale;
            this.lift = lift;
            this.glowAlpha = glowAlpha;
        }
    }

    private static EmphasisState computeCharEmphasisState(
            WordUnit u, int charIndex, int numChars, long livePosMs,
            float restingLift, float textSize, boolean isSecondary) {

        long refStart = (u.wordStartMs != LyricsLine.NO_TIME) ? u.wordStartMs : u.startMs;
        long refEnd = (u.wordEndMs != LyricsLine.NO_TIME) ? u.wordEndMs : u.endMs;
        if (refStart == LyricsLine.NO_TIME) {
            return new EmphasisState(1f, 0f, 0f);
        }

        final float sungLift = Math.max(2.5f, textSize * 0.045f);
        final boolean growable = isWordGrowable(u, isSecondary);

        if (growable) {
            long finalDuration = Math.max(1L, refEnd - refStart);
            float maxTotalDelay = finalDuration * 0.60f;
            float baseDelayPerChar = Math.min(finalDuration * 0.09f, numChars > 1 ? maxTotalDelay / (numChars - 1) : 0f);
            long charDelayMs = (long) (charIndex * baseDelayPerChar);
            long charStartMs = refStart + charDelayMs;

            if (livePosMs < charStartMs) {
                return new EmphasisState(1f, 0f, 0f);
            }

            long growDurationMs = Math.max(500L, Math.min(4500L, (long) (finalDuration * 1.5f)));
            float elapsedMs = (float) (livePosMs - charStartMs);

            // calculate bounce and lift for sung words
            long minDuration = 1000L;
            long maxDuration = 5000L;
            float progress = Math.min(1f, Math.max(0f, (float) (finalDuration - minDuration) / (float) (maxDuration - minDuration)));
            float easedProgress = progress * progress * progress;

            float penaltyFactor = 1.0f;
            float maxDecayRate = 0f;
            boolean isLongWord = numChars > 5;
            boolean isShortDuration = finalDuration < 1500L;
            if (isLongWord || isShortDuration) {
                float decayStrength = 0f;
                if (isLongWord) decayStrength += Math.min((numChars - 5) / 3f, 1.0f) * 0.4f;
                if (isShortDuration) decayStrength += Math.max(0f, 1.0f - (finalDuration - 1000f) / 500f) * 0.4f;
                maxDecayRate = Math.min(decayStrength, 0.85f);
            }

            float positionInWord = numChars > 1 ? (float) charIndex / (float) (numChars - 1) : 0f;
            float decayFactor = 1.0f - positionInWord * maxDecayRate;
            float charProgress = easedProgress * penaltyFactor * decayFactor;

            float baseGrowth = numChars <= 3 ? 0.07f : 0.05f;
            float charMaxScale = 1.0f + baseGrowth + charProgress * 0.10f;
            float charShadowIntensity = 0.40f + charProgress * 0.40f;
            float normalizedGrowth = Math.min(1.0f, Math.max(0f, (charMaxScale - 1.0f) / 0.13f));

            // lift up letter higher if singer hold long note
            float charPeakLift = Math.max(5.5f, (textSize * 0.080f) + (normalizedGrowth * textSize * 0.045f));

            // go up to peak then hold a bit then go back down to resting height
            float t = elapsedMs / (float) Math.max(1L, growDurationMs);
            if (t <= 0f) {
                return new EmphasisState(1f, 0f, 0f);
            } else if (t < 0.25f) {
                float phase = t / 0.25f;
                float easeUp = 0.5f * (1.0f - (float) Math.cos(phase * Math.PI));
                float scale = 1.0f + (charMaxScale - 1.0f) * easeUp;
                float lift = charPeakLift * easeUp;
                float glowAlpha = 255f * charShadowIntensity * easeUp;
                return new EmphasisState(scale, lift, glowAlpha);
            } else if (t <= 0.30f) {
                float scale = charMaxScale;
                float lift = charPeakLift;
                float glowAlpha = 255f * charShadowIntensity;
                return new EmphasisState(scale, lift, glowAlpha);
            } else if (t <= 1.0f) {
                float phase = (t - 0.30f) / 0.70f;
                float easeDown = 0.5f * (1.0f - (float) Math.cos(phase * Math.PI));
                float scale = charMaxScale + (1.0f - charMaxScale) * easeDown;
                float lift = charPeakLift - (charPeakLift - sungLift) * easeDown;
                float glowAlpha = 255f * charShadowIntensity * (1.0f - easeDown);
                return new EmphasisState(scale, lift, glowAlpha);
            } else {
                return new EmphasisState(1.0f, sungLift, 0f);
            }
        } else {
            // normal lyrics glide up slightly and stay elevated
            long sylStart = (u.startMs != LyricsLine.NO_TIME) ? u.startMs : refStart;
            long sylEnd = (u.endMs != LyricsLine.NO_TIME) ? u.endMs : refEnd;
            long sylDuration = Math.max(1L, sylEnd - sylStart);

            // timing when each letter get wiped
            float charRatio;
            if (numChars == u.romaji.length() && !u.romaji.isEmpty() && u.romajiCharOffsets != null && u.romajiWidth > 0f) {
                charRatio = charIndex < u.romajiCharOffsets.length ? (u.romajiCharOffsets[charIndex] / u.romajiWidth) : 0f;
            } else if (u.charOffsets != null && u.kanjiWidth > 0f && charIndex < u.charOffsets.length) {
                charRatio = u.charOffsets[charIndex] / u.kanjiWidth;
            } else {
                charRatio = numChars > 1 ? ((float) charIndex / (float) numChars) : 0f;
            }

            long charStartMs = sylStart + (long) (charRatio * sylDuration);
            if (livePosMs < charStartMs) {
                return new EmphasisState(1f, 0f, 0f);
            }

            long charAnimDur = Math.max(350L, Math.min(800L, (long) (sylDuration * 1.2f)));
            float elapsed = (float) (livePosMs - charStartMs);
            float t = Math.min(1.0f, Math.max(0f, elapsed / (float) charAnimDur));

            // smooth glide up to sung lift using smoothstep
            float easeUp = t * t * (3.0f - 2.0f * t);
            float lift = sungLift * easeUp;
            return new EmphasisState(1.0f, lift, 0f);
        }
    }

    private static EmphasisState computeEmphasisState(WordUnit u, long livePosMs, float restingLift, float textSize, boolean isSecondary) {
        long refStart = (u.wordStartMs != LyricsLine.NO_TIME) ? u.wordStartMs : u.startMs;
        long refEnd = (u.wordEndMs != LyricsLine.NO_TIME) ? u.wordEndMs : u.endMs;
        if (refStart == LyricsLine.NO_TIME || livePosMs < refStart) {
            return new EmphasisState(1f, 0f, 0f);
        }

        final float sungLift = Math.max(2.5f, textSize * 0.045f);
        final boolean growable = isWordGrowable(u, isSecondary);
        if (growable) {
            long finalDuration = Math.max(1L, refEnd - refStart);
            long growDurationMs = (long) (finalDuration * 1.5f);
            float elapsedMs = (float) (livePosMs - refStart);
            float t = elapsedMs / (float) Math.max(1L, growDurationMs);

            float wordMaxScale = 1.06f;
            float charPeakLift = Math.max(5.5f, textSize * 0.080f);
            float maxGlowAlpha = 255f * 0.50f;

            if (t <= 0f) {
                return new EmphasisState(1f, 0f, 0f);
            } else if (t < 0.25f) {
                float phase = t / 0.25f;
                float easeUp = 0.5f * (1.0f - (float) Math.cos(phase * Math.PI));
                return new EmphasisState(1.0f + (wordMaxScale - 1.0f) * easeUp, charPeakLift * easeUp, maxGlowAlpha * easeUp);
            } else if (t <= 0.30f) {
                return new EmphasisState(wordMaxScale, charPeakLift, maxGlowAlpha);
            } else if (t <= 1.0f) {
                float phase = (t - 0.30f) / 0.70f;
                float easeDown = 0.5f * (1.0f - (float) Math.cos(phase * Math.PI));
                return new EmphasisState(wordMaxScale + (1.0f - wordMaxScale) * easeDown,
                        charPeakLift - (charPeakLift - sungLift) * easeDown,
                        maxGlowAlpha * (1.0f - easeDown));
            } else {
                return new EmphasisState(1.0f, sungLift, 0f);
            }
        } else {
            long sylStart = (u.startMs != LyricsLine.NO_TIME) ? u.startMs : refStart;
            long sylEnd = (u.endMs != LyricsLine.NO_TIME) ? u.endMs : refEnd;
            long sylDuration = Math.max(1L, sylEnd - sylStart);
            if (livePosMs < sylStart) {
                return new EmphasisState(1f, 0f, 0f);
            }

            long wordAnimDur = Math.max(350L, Math.min(800L, (long) (sylDuration * 1.2f)));
            float elapsed = (float) (livePosMs - sylStart);
            float t = Math.min(1.0f, Math.max(0f, elapsed / (float) wordAnimDur));
            float easeUp = t * t * (3.0f - 2.0f * t);
            float lift = sungLift * easeUp;
            return new EmphasisState(1.0f, lift, 0f);
        }
    }

    private static boolean isWordGrowable(WordUnit u, boolean isSecondary) {
        if (isSecondary) return false;
        long dur = (u.wordEndMs != LyricsLine.NO_TIME && u.wordStartMs != LyricsLine.NO_TIME)
                ? (u.wordEndMs - u.wordStartMs)
                : (u.endMs - u.startMs);
        if (dur < 1000L) return false;
        String text = u.kanji.trim();
        if (text.length() > 7) return false;
        if (LyricsRomanizer.containsArabic(text) || LyricsRomanizer.containsHebrew(text)) return false;
        return true;
    }

    private static final float WORD_FEATHER_PX = 8f;

    /**
     * Draws text with a horizontal LinearGradient: solid inside the filled region, soft feathered
     * drop-off to transparent in the unfilled region.
     */
    private void drawFeatheredFill(Canvas canvas, String text, float x, float y, float width,
                                   float progress, TextPaint paint, int color, boolean isRtl) {
        if (width <= 0f || progress <= 0f) {
            return;
        }
        final int solid = color | 0xFF000000;
        if (progress >= 1f) {
            paint.setColor(solid);
            canvas.drawText(text, x, y, paint);
            return;
        }
        final float p = Math.max(0f, Math.min(1f, progress));
        final float feather = Math.min(WORD_FEATHER_PX, width / 2f);
        final int clear = color & 0x00FFFFFF;

        final float wiperX;
        final int[] colors;
        final float[] stops;

        if (isRtl) {
            wiperX = (1f - p) * width;
            final float curFeather = Math.min(feather, Math.min(wiperX, width - wiperX));
            final float solidEnd = Math.max(0f, Math.min(1f, (wiperX - curFeather) / width));
            final float fadeEnd = Math.max(solidEnd, Math.min(1f, (wiperX + curFeather) / width));
            colors = new int[]{clear, clear, solid, solid};
            stops = new float[]{0f, solidEnd, fadeEnd, 1f};
        } else {
            wiperX = p * width;
            final float curFeather = Math.min(feather, Math.min(wiperX, width - wiperX));
            final float solidEnd = Math.max(0f, Math.min(1f, (wiperX - curFeather) / width));
            final float fadeEnd = Math.max(solidEnd, Math.min(1f, (wiperX + curFeather) / width));
            colors = new int[]{solid, solid, clear, clear};
            stops = new float[]{0f, solidEnd, fadeEnd, 1f};
        }

        final LinearGradient shader = new LinearGradient(x, 0f, x + width, 0f,
                colors,
                stops,
                Shader.TileMode.CLAMP);
        paint.setShader(shader);
        canvas.drawText(text, x, y, paint);
        paint.setShader(null);
    }

    private void drawTranslationLayout(Canvas canvas) {
        if (transLayout != null) {
            float transTop = totalMeasuredHeight - transLayout.getHeight();
            canvas.save();
            canvas.translate(0, transTop);
            transLayout.draw(canvas);
            canvas.restore();
        }
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        if (isGravityRightAligned()) {
            setPivotX(w);
        } else {
            setPivotX(0f);
        }
        setPivotY(h / 2f);
    }

    void setTranslationBounds(int start, int end) {
        transStart = start;
        transEnd = end;
    }

    void setRomanizationBounds(int start, int end) {
        romaStart = start;
        romaEnd = end;
    }

    @Nullable
    String getCopyTextForTouch(float touchY) {
        Layout layout = getLayout();
        if (layout == null) {
            return null;
        }
        int lineCount = layout.getLineCount();
        if (lineCount <= 1) {
            return null;
        }
        int touchedLine = layout.getLineForVertical((int) touchY);
        CharSequence text = getText();
        if (text == null) {
            return null;
        }
        for (int i = 0; i < lineCount; i++) {
            if (i != touchedLine) continue;
            int lineStart = layout.getLineStart(i);
            int lineEnd = layout.getLineEnd(i);
            if (transStart >= 0 && lineStart < transEnd && lineEnd > transStart) {
                return text.subSequence(
                        Math.max(lineStart, transStart),
                        Math.min(lineEnd, transEnd)).toString().trim();
            }
            if (romaStart >= 0 && lineStart < romaEnd && lineEnd > romaStart) {
                return text.subSequence(
                        Math.max(lineStart, romaStart),
                        Math.min(lineEnd, romaEnd)).toString().trim();
            }
        }
        return null;
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
            lastTouchY = event.getY();
        }
        return super.onTouchEvent(event);
    }

    void setHighlight(List<WordTiming> timings, long posMs, boolean sung,
                      int unsungCol, int sungCol, int origStart) {
        final boolean activeChanged = (this.positionMs == Long.MIN_VALUE) != (posMs == Long.MIN_VALUE);
        final boolean sungChanged = (this.allSung != sung);
        final boolean colorChanged = (this.unsungColor != unsungCol || this.sungColor != sungCol);
        final boolean timingChanged = (this.wordTimings != timings || this.originalTextStart != origStart);

        this.wordTimings = timings != null ? timings : Collections.emptyList();
        this.cachedHasWordTimings = computeHasWordTimings();
        this.positionMs = posMs;
        if (posMs == Long.MIN_VALUE) {
            this.lastDrawnClockPos = Long.MIN_VALUE;
        }
        this.allSung = sung;
        this.unsungColor = unsungCol;
        this.sungColor = sungCol;
        this.originalTextStart = origStart;

        if (timingChanged || colorChanged) {
            cachedLayout = null;
            sungLayout = null;
        }


        if (activeChanged) {
            postInvalidateOnAnimation();
        } else if (wordUnits == null || wordUnits.isEmpty()) {
            invalidate();
        } else if (sungChanged || colorChanged || (posMs != Long.MIN_VALUE)) {
            invalidate();
        }
    }

    private void ensureWordCache(Layout layout, CharSequence text, Paint paint,
                                 List<WordTiming> timings, int origStart) {
        if (layout == cachedLayout && timings.size() == cachedWordCount
                && origStart == cachedOrigStart) {
            return;
        }
        cachedLayout = layout;
        cachedWordCount = timings.size();
        cachedOrigStart = origStart;
        cachedWordLeadX = new float[cachedWordCount];
        cachedWordTrailX = new float[cachedWordCount];
        cachedWordLine = new int[cachedWordCount];
        cachedWordWrappedLine = new int[cachedWordCount];
        for (int i = 0; i < cachedWordCount; i++) {
            final WordTiming timing = timings.get(i);
            final int s = timing.start() + origStart;
            final int e = Math.min(timing.end() + origStart, text.length());
            if (s >= text.length() || s >= e) {
                cachedWordWrappedLine[i] = -1;
                continue;
            }
            final int line = layout.getLineForOffset(s);
            final float lead = layout.getPrimaryHorizontal(s);
            float trail = layout.getPrimaryHorizontal(e);
            final int endLine = layout.getLineForOffset(e);
            if (trail == lead || endLine != line) {
                final float width = paint.measureText(text, s, e);
                trail = layout.isRtlCharAt(s) ? lead - width : lead + width;
            }
            cachedWordLeadX[i] = lead;
            cachedWordTrailX[i] = trail;
            cachedWordLine[i] = line;
            cachedWordWrappedLine[i] = endLine != line ? endLine : -1;
        }
        final int lineCount = layout.getLineCount();
        if (cachedLineCount != lineCount) {
            cachedLineCount = lineCount;
            cachedLineTop = new int[lineCount];
            cachedLineBottom = new int[lineCount];
            cachedLineLeft = new float[lineCount];
            cachedLineRight = new float[lineCount];
        }
        for (int ln = 0; ln < lineCount; ln++) {
            cachedLineTop[ln] = layout.getLineTop(ln);
            cachedLineBottom[ln] = layout.getLineBottom(ln);
            cachedLineLeft[ln] = layout.getLineLeft(ln);
            cachedLineRight[ln] = layout.getLineRight(ln);
        }
    }

    private Layout ensureSungLayout(Layout base, CharSequence text, int origStart) {
        if (sungLayout != null && sungLayoutText == text
                && sungLayoutWidth == base.getWidth()
                && sungLayoutColor == sungColor
                && sungLayoutOrigStart == origStart) {
            return sungLayout;
        }

        SpannableString copy = new SpannableString(text);
        final int end = originalEnd(copy, origStart);
        if (origStart < end) {
            for (ForegroundColorSpan span : copy.getSpans(origStart, end, ForegroundColorSpan.class)) {
                copy.removeSpan(span);
            }
            copy.setSpan(new ForegroundColorSpan(sungColor), origStart, end,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }

        StaticLayout.Builder b = StaticLayout.Builder
                .obtain(copy, 0, copy.length(), getPaint(), base.getWidth())
                .setAlignment(base.getAlignment())
                .setLineSpacing(getLineSpacingExtra(), getLineSpacingMultiplier())
                .setIncludePad(getIncludeFontPadding())
                .setBreakStrategy(getBreakStrategy())
                .setHyphenationFrequency(getHyphenationFrequency());
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            b.setUseLineSpacingFromFallbacks(true);
        }
        sungLayout = b.build();
        sungLayoutText = text;
        sungLayoutWidth = base.getWidth();
        sungLayoutColor = sungColor;
        sungLayoutOrigStart = origStart;
        return sungLayout;
    }

    /** End of the lyric line itself, before any translation or romanization below it. */
    private int originalEnd(CharSequence text, int origStart) {
        if (text != cachedText) {
            cachedText = text;
            cachedTextStr = text.toString();
        }
        final int newline = cachedTextStr.indexOf('\n', origStart);
        return newline >= 0 ? newline : cachedTextStr.length();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (isSecondary && secondaryExpandFraction <= 0.001f) {
            return;
        }
        if (wordUnits != null && !wordUnits.isEmpty()) {
            onDrawWordUnits(canvas);
            return;
        }

        final boolean active = isLineActive || (positionMs != Long.MIN_VALUE && !allSung);
        float target = active ? 1.0f : 0.95f;
        if (Math.abs(currentScale - target) > 0.001f) {
            currentScale += (target - currentScale) * 0.15f;
            postInvalidateOnAnimation();
        } else {
            currentScale = target;
        }

        canvas.save();
        if (isSecondary) {
            canvas.clipRect(0, 0, getWidth(), getHeight());
        }
        float pivotX = isGravityRightAligned()
                ? (getWidth() - getCompoundPaddingRight())
                : getCompoundPaddingLeft();
        float pivotY = getHeight() / 2f;
        canvas.scale(currentScale, currentScale, pivotX, pivotY);

        super.onDraw(canvas);
        if (unsungColor == 0 || !hasWordTimings()) {
            canvas.restore();
            return;
        }
        Layout layout = getLayout();
        if (layout == null) {
            canvas.restore();
            return;
        }
        CharSequence text = getText();
        Paint tp = getPaint();
        final int origStart = originalTextStart;
        final int paddingLeft = getCompoundPaddingLeft();
        final int paddingTop = getExtendedPaddingTop();

        ensureWordCache(layout, text, tp, wordTimings, origStart);

        final int lineCount = cachedLineCount;
        if (lineMaxSungX == null || lineMaxSungX.length != lineCount) {
            lineMaxSungX = new float[lineCount];
        }
        Arrays.fill(lineMaxSungX, 0, lineCount, Float.NaN);
        int firstSungLine = -1;

        for (int i = 0; i < cachedWordCount; i++) {
            final WordTiming timing = wordTimings.get(i);
            final int s = timing.start() + origStart;
            final int e = Math.min(timing.end() + origStart, text.length());
            if (s >= text.length() || s >= e) {
                continue;
            }
            final long livePosMs = (positionMs != Long.MIN_VALUE) ? getLiveClockPositionMs() : positionMs;
            float progress;
            if (allSung) {
                progress = 1f;
            } else if (livePosMs >= timing.endMs()) {
                progress = 1f;
            } else if (livePosMs <= timing.startMs()) {
                progress = 0f;
            } else {
                float raw = (float) (livePosMs - timing.startMs())
                        / (float) Math.max(1L, timing.endMs() - timing.startMs());
                progress = Math.max(0f, Math.min(1f, raw));
            }
            if (progress <= 0f) {
                continue;
            }
            final int lineNum = cachedWordLine[i];
            if (firstSungLine < 0) {
                firstSungLine = lineNum;
            }
            final float lead = cachedWordLeadX[i];
            final float edge = lead + (cachedWordTrailX[i] - lead) * progress;
            lineMaxSungX[lineNum] = extendSung(lineMaxSungX[lineNum], edge,
                    isRightToLeftLine(layout, lineNum));
            final int wrappedLine = cachedWordWrappedLine[i];
            if (wrappedLine >= 0 && wrappedLine < lineCount) {
                final int charsOnLine0 = layout.getLineEnd(lineNum) - s;
                final int charsOnLine1 = e - layout.getLineEnd(lineNum);
                if (charsOnLine0 + charsOnLine1 > 0) {
                    final float line1Progress = Math.max(0f,
                            (progress * (charsOnLine0 + charsOnLine1) - charsOnLine0)
                                    / (float) charsOnLine1);
                    if (line1Progress > 0f) {
                        final float line1Edge = layout.getLineLeft(wrappedLine)
                                + (layout.getLineRight(wrappedLine)
                                        - layout.getLineLeft(wrappedLine))
                                        * line1Progress;
                        lineMaxSungX[wrappedLine] = extendSung(
                                lineMaxSungX[wrappedLine], line1Edge,
                                isRightToLeftLine(layout, wrappedLine));
                    }
                }
            }
        }

        if (allSung && firstSungLine < 0 && origStart < text.length()) {
            final int lastChar = originalEnd(text, origStart) - 1;
            final int firstLine = layout.getLineForOffset(origStart);
            final int lastLine = layout.getLineForOffset(Math.max(lastChar, origStart));
            for (int ln = firstLine; ln <= lastLine && ln < lineCount; ln++) {
                lineMaxSungX[ln] = isRightToLeftLine(layout, ln)
                        ? cachedLineLeft[ln] : cachedLineRight[ln];
            }
        }

        Layout sung = ensureSungLayout(layout, text, origStart);

        canvas.save();
        canvas.translate(paddingLeft, paddingTop);
        for (int ln = 0; ln < lineCount; ln++) {
            final float edge = lineMaxSungX[ln];
            if (Float.isNaN(edge)) {
                continue;
            }
            final float left = cachedLineLeft[ln];
            final float right = cachedLineRight[ln];
            final float top = cachedLineTop[ln];
            final float bottom = cachedLineBottom[ln];
            final boolean rtl = isRightToLeftLine(layout, ln);

            if (rtl) {
                if (allSung || edge <= left) {
                    canvas.save();
                    canvas.clipRect(left, top, right, bottom);
                    sung.draw(canvas);
                    canvas.restore();
                } else if (edge < right) {
                    canvas.save();
                    canvas.clipRect(edge, top, right, bottom);
                    sung.draw(canvas);
                    canvas.restore();
                }
            } else {
                if (allSung || edge >= right) {
                    canvas.save();
                    canvas.clipRect(left, top, right, bottom);
                    sung.draw(canvas);
                    canvas.restore();
                } else if (edge > left) {
                    canvas.save();
                    canvas.clipRect(left, top, edge, bottom);
                    sung.draw(canvas);
                    canvas.restore();
                }
            }
        }
        canvas.restore(); // restore translate
        canvas.restore(); // restore scale

        if (firstSungLine >= 0 && !allSung && LyricsManager.getInstance().isPlaying()) {
            postInvalidateOnAnimation();
        }
    }

    private static boolean isRightToLeftLine(Layout layout, int line) {
        return layout.getParagraphDirection(line) == Layout.DIR_RIGHT_TO_LEFT;
    }

    /** Right to left lines fill leftwards, so their furthest point is the smallest one. */
    private static float extendSung(float current, float edge, boolean rightToLeft) {
        if (Float.isNaN(current)) {
            return edge;
        }
        return rightToLeft ? Math.min(current, edge) : Math.max(current, edge);
    }
}
