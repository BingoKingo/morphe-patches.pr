/*
 * Copyright 2026 Morphe.
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.lyrics.ui;

import android.animation.TimeInterpolator;

import java.util.LinkedHashMap;
import java.util.Map;

public final class SpringEasing {

    public static final class Result implements TimeInterpolator {
        public final float[] samples;
        public final int durationMs;

        Result(float[] s, int d) {
            this.samples = s;
            this.durationMs = d;
        }

        /** spring curve calculation with overshoot peak */
        @Override
        public float getInterpolation(float input) {
            return interpolate(input);
        }

        public float interpolate(float input) {
            if (input <= 0f) return samples[0];
            if (input >= 1f) return samples[samples.length - 1];
            float x = input * (samples.length - 1);
            int i = (int) x;
            float f = x - i;
            return samples[i] + (samples[i + 1] - samples[i]) * f;
        }
    }

    private static final Map<String, Result> CACHE = new LinkedHashMap<String, Result>(64, 0.75f, false) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Result> e) {
            return size() > 64;
        }
    };

    static double cubicBezierY(double t, double x1, double y1, double x2, double y2) {
        double cx = 3 * x1, bx = 3 * (x2 - x1) - cx, ax = 1 - cx - bx;
        double cy = 3 * y1, by = 3 * (y2 - y1) - cy, ay = 1 - cy - by;
        double u = t;
        for (int i = 0; i < 8; i++) {
            double x = ((ax * u + bx) * u + cx) * u - t;
            double dx = (3 * ax * u + 2 * bx) * u + cx;
            if (Math.abs(dx) < 1e-6) break;
            u -= x / dx;
        }
        return ((ay * u + by) * u + cy) * u;
    }

    public static synchronized Result get(double peakTimeMs, double zeta, double settleTailMs) {
        peakTimeMs = Math.round(peakTimeMs / 10.0) * 10.0;
        settleTailMs = Math.round(settleTailMs / 10.0) * 10.0;
        String key = peakTimeMs + ":" + zeta + ":" + settleTailMs;
        Result hit = CACHE.get(key);
        if (hit != null) return hit;

        double peakS = peakTimeMs / 1000.0, tailS = settleTailMs / 1000.0;
        double omegaD = Math.PI / peakS;
        double omega0 = omegaD / Math.sqrt(1 - zeta * zeta);
        double decay = zeta * omega0;
        double dt = 1.0 / 120.0;

        double peakValue = 1;
        for (double t = 0; t <= peakS; t += dt) {
            peakValue = 1 - Math.exp(-decay * t) * (Math.cos(omegaD * t) + (decay / omegaD) * Math.sin(omegaD * t));
        }
        java.util.ArrayList<Float> pts = new java.util.ArrayList<>();
        for (double t = 0; t <= peakS; t += dt) {
            pts.add((float) (peakValue * cubicBezierY(t / peakS, 0.41, 0, 0.12, 0.99)));
        }
        for (double t = dt; t <= tailS; t += dt) {
            double u = t / tailS;
            pts.add((float) (1 + (peakValue - 1) * 0.5 * (1 + Math.cos(Math.PI * u))));
        }
        pts.add(1f);
        float[] arr = new float[pts.size()];
        for (int i = 0; i < arr.length; i++) arr[i] = Math.round(pts.get(i) * 10000f) / 10000f;
        Result r = new Result(arr, (int) Math.round((peakS + tailS) * 1000));
        CACHE.put(key, r);
        return r;
    }
}
