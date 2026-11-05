package com.example.clogger.model;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class StatsTest {
    @Test void percentilesInterpolate() {
        double[] v = {1, 2, 3, 4, 5};
        assertEquals(3, Stats.percentile(v, 50), 1e-9);
        assertEquals(1, Stats.percentile(v, 0), 1e-9);
        assertEquals(5, Stats.percentile(v, 100), 1e-9);
        assertEquals(4.6, Stats.percentile(v, 90), 1e-9);
    }

    @Test void summaryOfUnsortedValues() {
        Stats.Summary s = Stats.summarize(new double[] {10, 1, 5, 3});
        assertEquals(4, s.n());
        assertEquals(4.75, s.avg(), 1e-9);
        assertEquals(1, s.min());
        assertEquals(10, s.max());
        assertFalse(Stats.summarize(new double[0]).any());
    }

    @Test void worstAverage() {
        double[] v = new double[100];
        for (int i = 0; i < 100; i++) v[i] = i + 1;
        assertEquals(100, Stats.worstAverage(v, 0.01), 1e-9);
        assertEquals(99.5, Stats.worstAverage(v, 0.02), 1e-9);
    }

    @Test void bufStaysSmall() {
        Stats.Buf b = new Stats.Buf(1000);
        for (int i = 0; i < 100_000; i++) b.add(i);
        assertTrue(b.size() <= 1000);
        assertTrue(b.size() > 400);
        double[] a = b.toArray();
        assertEquals(0, a[0]);
        assertTrue(a[a.length - 1] > 90_000);
    }
}
