package com.example.clogger;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.example.clogger.model.Findings;
import com.example.clogger.model.StackTree;

/** One thread being stack-sampled (the server thread or the render thread) and what the samples showed. */
final class Target {
    static final int RING = 4096;

    record Sample(long t, String blame, String where) {}

    final String label;
    final boolean server;
    volatile Thread thread;
    /** true while a server tick runs (set by the tick event); the render thread is always "in frame". */
    volatile boolean active;
    final StackTree tree = new StackTree(70);
    long samples, idle, between;
    final Map<String, Long> blamed = new HashMap<>(), topMethods = new HashMap<>(), blamedMethods = new HashMap<>(), entityCode = new HashMap<>(), beCode = new HashMap<>();
    /** class name -> type id, filled from world snapshots so entity cost can be named. */
    final Map<String, String> entityClasses = new ConcurrentHashMap<>(), beClasses = new ConcurrentHashMap<>();

    private final Sample[] ring = new Sample[RING];
    private int head;

    Target(String label, boolean server) {
        this.label = label;
        this.server = server;
    }

    synchronized void remember(Sample s) {
        ring[head] = s;
        head = (head + 1) % RING;
    }

    /** Samples between two nanoTime stamps. */
    synchronized List<Sample> between(long fromNs, long toNs) {
        List<Sample> out = new ArrayList<>();
        for (Sample s : ring) if (s != null && s.t >= fromNs && s.t <= toNs) out.add(s);
        return out;
    }

    private static void inc(Map<String, Long> m, String k) { m.merge(k, 1L, Long::sum); }

    /** Called from the sampler thread only. */
    synchronized void take(StackTraceElement[] st, Thread.State state, ModMap mods, long nowNs) {
        samples++;
        boolean waiting = state == Thread.State.WAITING || state == Thread.State.TIMED_WAITING;
        if (server && !active) {
            if (waiting) idle++;
            else between++;
            return;
        }
        if (!server && waiting) { idle++; return; }
        int n = Math.min(st.length, 90);
        List<String> frames = new ArrayList<>(n);
        String[] cls = new String[n];
        String[] mod = new String[n];
        for (int i = n - 1; i >= 0; i--) { // root first
            StackTraceElement e = st[i];
            String c = clean(e.getClassName());
            cls[i] = c;
            mod[i] = mods.classify(c, e.getMethodName());
            frames.add(c + "#" + e.getMethodName());
        }
        tree.add(frames);
        // leaf is st[0]; frames list is root-first, so frames.get(n-1-i) is st[i]
        String blame = null;
        int blameAt = -1;
        for (int i = 0; i < n; i++) {
            if (!Findings.BASE.contains(mod[i])) { blame = mod[i]; blameAt = i; break; }
        }
        if (blame == null) {
            for (int i = 0; i < n; i++) if (!mod[i].equals("(java)") && !mod[i].equals("(libraries)")) { blame = mod[i]; break; }
            if (blame == null) blame = "(java)";
        }
        inc(blamed, blame);
        String leaf = frames.get(n - 1);
        inc(topMethods, leaf);
        String where = leaf;
        if (blameAt >= 0) {
            where = frames.get(n - 1 - blameAt);
            inc(blamedMethods, where);
        }
        if (server) {
            for (int i = n - 1; i >= 0; i--) { // shallowest first
                String t = entityClasses.get(cls[i]);
                if (t != null || cls[i].startsWith("net.minecraft.world.entity.")) {
                    inc(entityCode, t != null ? t + " (" + simple(cls[i]) + ")" : simple(cls[i]) + " (shared base class)");
                    break;
                }
            }
            for (int i = n - 1; i >= 0; i--) {
                String t = beClasses.get(cls[i]);
                if (t != null || cls[i].startsWith("net.minecraft.world.level.block.entity.")) {
                    inc(beCode, t != null ? t + " (" + simple(cls[i]) + ")" : simple(cls[i]) + " (shared base class)");
                    break;
                }
            }
        }
        remember(new Sample(nowNs, blame, where));
    }

    /** Lambda classes get a new name every run (Foo$$Lambda$8386/0x0000...); keep just Foo$$Lambda. */
    static String clean(String c) {
        int i = c.indexOf("$$Lambda");
        return i < 0 ? c : c.substring(0, i + 8);
    }

    static String simple(String c) { return c.substring(c.lastIndexOf('.') + 1); }
}
