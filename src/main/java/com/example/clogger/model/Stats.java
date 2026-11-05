package com.example.clogger.model;

import java.util.Arrays;

/** Small number helpers: percentiles and a summary of a list of values. */
public final class Stats {
    private Stats() {}

    /** Summary of a list of numbers (n = 0 means there was nothing to measure). */
    public record Summary(int n, double avg, double min, double p50, double p95, double p99, double max) {
        public static final Summary EMPTY = new Summary(0, 0, 0, 0, 0, 0, 0);
        public boolean any() { return n > 0; }
    }

    /** Percentile (0..100) of an already sorted array, linear between neighbours. */
    public static double percentile(double[] sorted, double p) {
        if (sorted.length == 0) return 0;
        if (sorted.length == 1) return sorted[0];
        double pos = Math.max(0, Math.min(100, p)) / 100.0 * (sorted.length - 1);
        int lo = (int) Math.floor(pos);
        int hi = Math.min(sorted.length - 1, lo + 1);
        return sorted[lo] + (sorted[hi] - sorted[lo]) * (pos - lo);
    }

    public static Summary summarize(double[] values) {
        if (values == null || values.length == 0) return Summary.EMPTY;
        double[] s = values.clone();
        Arrays.sort(s);
        double sum = 0;
        for (double v : s) sum += v;
        return new Summary(s.length, sum / s.length, s[0], percentile(s, 50), percentile(s, 95), percentile(s, 99), s[s.length - 1]);
    }

    /** Average of the worst `fraction` (e.g. 0.01 = "1% lows") of values when bigger is worse. */
    public static double worstAverage(double[] values, double fraction) {
        if (values.length == 0) return 0;
        double[] s = values.clone();
        Arrays.sort(s);
        int k = Math.max(1, (int) Math.ceil(s.length * fraction));
        double sum = 0;
        for (int i = s.length - k; i < s.length; i++) sum += s[i];
        return sum / k;
    }

    /** A growing list of doubles that stops growing at a cap (then keeps every Nth value so long runs stay small). */
    public static final class Buf {
        private double[] data = new double[256];
        private int size;
        private final int cap;
        private int stride = 1, skip;

        public Buf(int cap) { this.cap = Math.max(16, cap); }

        public synchronized void add(double v) {
            if (++skip < stride) return;
            skip = 0;
            if (size == data.length) {
                if (size >= cap) { // halve: keep every second value, then take every second new one
                    for (int i = 0; i < size / 2; i++) data[i] = data[i * 2];
                    size /= 2;
                    stride *= 2;
                } else data = Arrays.copyOf(data, Math.min(cap, data.length * 2));
            }
            data[size++] = v;
        }

        public synchronized double[] toArray() { return Arrays.copyOf(data, size); }
        public synchronized int size() { return size; }
    }
}
