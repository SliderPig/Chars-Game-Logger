package com.example.clogger.model;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Turns the numbers into a short ranked list of plain-English problems. */
public final class Findings {
    private Findings() {}

    public record Finding(int score, String title, String detail, String fix) {
        public String label() { return score >= 60 ? "BIG" : score >= 30 ? "medium" : "small"; }
    }

    /** Buckets that are not a mod you can remove. */
    public static final Set<String> BASE = Set.of("minecraft", "forge", "arclight", "(java)", "(libraries)", "(bukkit)", "(unknown)");

    private static String f(String fmt, Object... a) { return String.format(Locale.ROOT, fmt, a); }

    public static List<Finding> compute(Report r) {
        List<Finding> out = new ArrayList<>();
        try { if (r.server != null) side(r, r.server, true, out); } catch (RuntimeException e) { out.add(new Finding(1, "Server analysis failed: " + e, "", "")); }
        try { if (r.client != null) side(r, r.client, false, out); } catch (RuntimeException e) { out.add(new Finding(1, "Client analysis failed: " + e, "", "")); }
        try { shared(r, out); } catch (RuntimeException e) { out.add(new Finding(1, "Memory/log analysis failed: " + e, "", "")); }
        out.sort(Comparator.comparingInt(Finding::score).reversed());
        return out;
    }

    private static long sum(Map<String, Long> m) { long s = 0; for (long v : m.values()) s += v; return s; }

    private static String top(Map<String, Long> m, boolean skipBase) {
        String best = null;
        long bv = 0;
        long all = sum(m);
        for (var e : m.entrySet()) if (e.getValue() > bv && !(skipBase && BASE.contains(e.getKey()))) { best = e.getKey(); bv = e.getValue(); }
        if (skipBase && bv * 100 < all * 8) return null; // a mod under 8% is not a culprit
        return best;
    }

    private static String fixFor(String mod) {
        return switch (mod) {
            case "kubejs" -> "Look at the KubeJS scripts (kubejs/server_scripts) for tick or playerTick handlers that loop over every entity or player; run them less often (every 20 ticks instead of every tick).";
            case "palladium", "arrzenhanced", "eop", "fsang", "gabeupheroes", "powerborne" -> "This is a superpower mod: its power ticks run for every player. Fewer players using heavy powers, or switching the heavy ability off, helps.";
            default -> "Check that mod's config for a tick or range option, update it, or remove it for a minute to confirm it is the cause.";
        };
    }

    private static void side(Report r, Report.Side s, boolean server, List<Finding> out) {
        String who = server ? "Server" : "Client";
        long tickSamples = s.tree == null ? 0 : s.tree.samples();
        if (server && s.tps.any() && s.tps.avg() < 19.5) {
            double lost = 20 - s.tps.avg();
            String culprit = top(s.blamedMod, true);
            String why = culprit == null ? "" : f(" The mod using most of it: %s (%.0f%% of tick time).", culprit, 100.0 * s.blamedMod.get(culprit) / Math.max(1, tickSamples));
            out.add(new Finding((int) Math.min(100, 30 + lost * 8), f("TPS averaged %.1f (it should be 20)", s.tps.avg()),
                    f("A tick took %.1f ms on average, %.1f ms at the 95th percentile, %.1f ms at worst (50 ms is the limit for 20 TPS).%s", s.mspt.avg(), s.mspt.p95(), s.mspt.max(), why),
                    "See the mod list and tick tree below to find what takes the time."));
        } else if (server && s.mspt.any() && s.mspt.p99() > 50) {
            out.add(new Finding(35, f("Some server ticks are slow (1 in 100 took more than %.0f ms)", s.mspt.p99()),
                    f("TPS looks fine on average (%.1f) but the slow ticks cause stutter.", s.tps.avg()), "Look at the lag spikes section."));
        }
        if (!server && s.fps.any() && (s.fps.avg() < 45 || s.fpsLow1 > 0 && 1000.0 / s.fpsLow1 < 25)) {
            String culprit = top(s.blamedMod, true);
            String gpu = "";
            String m = top(s.topMethods, false);
            if (m != null && s.samples > 0 && (m.toLowerCase(Locale.ROOT).contains("swapbuffers") || m.toLowerCase(Locale.ROOT).contains("glfw")) && s.topMethods.get(m) * 100 / Math.max(1, s.samples) >= 35)
                gpu = " Most of the render thread's time is spent waiting for the graphics card or vsync, so the GPU is the limit.";
            out.add(new Finding((int) Math.min(100, 25 + (60 - Math.min(60, s.fps.avg())) * 1.2), f("Client FPS averaged %.0f (the worst 1%% of frames ran at about %.0f FPS)", s.fps.avg(), s.fpsLow1 > 0 ? 1000.0 / s.fpsLow1 : 0),
                    f("Frames took %.1f ms on average, %.1f ms at the 99th percentile.", s.frameMs.avg(), s.frameMs.p99()) + gpu + (culprit == null ? "" : f(" Biggest mod on the render thread: %s.", culprit)),
                    "Lower render distance / simulation distance, turn off shaders or fancy graphics, and check the render-thread tree below."));
        }
        if (tickSamples >= 30) {
            for (var e : s.blamedMod.entrySet()) {
                String mod = e.getKey();
                if (BASE.contains(mod)) continue;
                double share = 100.0 * e.getValue() / tickSamples;
                if (share < 8) continue;
                double ms = server ? share / 100 * s.mspt.avg() : share / 100 * s.frameMs.avg();
                String unit = server ? "tick" : "frame";
                out.add(new Finding((int) Math.min(90, share * (server ? 1.1 : 0.8) + (ms > 10 ? 10 : 0)),
                        f("%s: mod %s used %.0f%% of %s time (about %.1f ms each %s)", who, mod, share, unit, ms, unit),
                        "Counted by where the CPU was while the " + (server ? "server tick" : "render frame") + " ran (" + tickSamples + " samples).", fixFor(mod)));
            }
            long ent = sum(s.entityCode);
            if (server && ent * 100.0 / tickSamples >= 15) {
                String t = top(s.entityCode, false);
                out.add(new Finding((int) Math.min(80, ent * 100.0 / tickSamples * 1.2), f("Entities took %.0f%% of tick time", ent * 100.0 / tickSamples),
                        "Most in code of: " + t + ". Entity count at the peak: " + s.peakEntities + ".", "Reduce mobs: kill piles, lower mob caps, use fewer or smaller farms, give animals room."));
            }
            long be = sum(s.blockEntityCode);
            if (server && be * 100.0 / tickSamples >= 10) {
                out.add(new Finding((int) Math.min(70, be * 100.0 / tickSamples * 1.2), f("Block entities (chests, hoppers, machines) took %.0f%% of tick time", be * 100.0 / tickSamples),
                        "Most in code of: " + top(s.blockEntityCode, false) + ".", "Remove long hopper chains, merge chests, turn off machines nobody uses."));
            }
        }
        if (s.spikeCount > 0) {
            Report.Spike worst = s.spikes.stream().max(Comparator.comparingDouble(Report.Spike::ms)).orElse(null);
            out.add(new Finding((int) Math.min(85, 15 + s.spikeCount * 2 + (worst == null ? 0 : worst.ms() / 40)),
                    f("%s had %d lag spikes over %.0f ms", who, s.spikeCount, s.spikeThresholdMs),
                    worst == null ? "" : f("The worst lasted %.0f ms at %.0f s into the run, mostly in: %s (%s).", worst.ms(), worst.sec(), worst.mods(), worst.where()),
                    "Open the lag spike section for each one."));
        }
        if (server) {
            if (!s.busiestChunks.isEmpty()) {
                Report.ChunkLoad c = s.busiestChunks.get(0);
                if (c.entities() >= 60)
                    out.add(new Finding(Math.min(75, 20 + c.entities() / 4), f("%d entities in one chunk (%s, around %d, %d)", c.entities(), c.dim(), c.x() * 16 + 8, c.z() * 16 + 8),
                            "Mostly: " + c.top() + ".", "A mob farm or a pile of animals/items there. Thin them out or make it lag less (fewer mobs, kill chamber)."));
                if (c.blockEntities() >= 400)
                    out.add(new Finding(Math.min(70, 15 + c.blockEntities() / 20), f("%d block entities in one chunk (%s, around %d, %d)", c.blockEntities(), c.dim(), c.x() * 16 + 8, c.z() * 16 + 8),
                            "Chests, hoppers or machines.", "Shorten hopper lines, replace many hoppers with other designs."));
            }
            for (Report.Dim d : s.dims)
                for (var e : d.entityTypes.entrySet())
                    if (e.getValue() >= 300)
                        out.add(new Finding(Math.min(60, 10 + e.getValue() / 20), f("%d %s in %s", e.getValue(), e.getKey(), d.name), "Very many of one kind at once.", "Clear or cull them (items: pick up or let them despawn; mobs: lower the mob cap)."));
        }
    }

    private static void shared(Report r, List<Finding> out) {
        if (r.heapMaxMb > 0 && r.heapUsedMb.any()) {
            double ratio = r.heapUsedMb.p95() / r.heapMaxMb;
            if (ratio > 0.9)
                out.add(new Finding((int) Math.min(85, 40 + (ratio - 0.9) * 400), f("Memory was nearly full (%.0f%% of %d MB at the 95th percentile)", ratio * 100, r.heapMaxMb),
                        "When the memory Java may use fills up, the game freezes while it cleans up.",
                        r.physicalMb > r.heapMaxMb + 3000 ? "Give the game more memory (the -Xmx setting); this computer has " + r.physicalMb + " MB." : "This computer only has " + r.physicalMb + " MB, so remove heavy mods or lower render distance instead of raising -Xmx."));
        }
        if (r.seconds > 20 && r.gcMillis > 0) {
            double pct = r.gcMillis / (r.seconds * 10.0);
            if (pct > 5 || r.gcMaxPauseMs > 500)
                out.add(new Finding((int) Math.min(80, 20 + pct * 4 + r.gcMaxPauseMs / 50.0), f("Garbage collection took %.1f%% of the time (longest freeze %d ms, %d collections)", pct, r.gcMaxPauseMs, r.gcCount),
                        "Each collection pauses the game.", "More memory, fewer mods or entities, or G1GC flags (-XX:+UseG1GC -XX:MaxGCPauseMillis=50)."));
        }
        if (r.physicalMb > 0 && r.physicalMb < 6500)
            out.add(new Finding(12, "This computer has little memory (" + r.physicalMb + " MB)", "Modded Minecraft likes 8 GB or more for the game alone.", "Close other programs while playing."));
        for (var e : r.threadCpu.entrySet()) {
            String n = e.getKey();
            if (n.startsWith("Server thread") || n.startsWith("Render thread") || n.startsWith("clogger")) continue;
            if (e.getValue() >= 60) {
                String hint = n.startsWith("Worker-Main") ? " This is world generation / chunk loading (Chunky pre-generation does this)." : "";
                out.add(new Finding((int) Math.min(50, e.getValue() / 3), f("Thread group \"%s\" used %.0f%% of a CPU core", n, e.getValue()), "A background thread kept a core busy." + hint, "If it is not expected, find the mod that owns that thread name."));
            }
            break; // only the biggest
        }
        for (Report.LogGroup g : r.logs) {
            if (g.total() >= 50 || g.errors + g.fatals >= 10) {
                out.add(new Finding((int) Math.min(55, 8 + g.total() / 25 + (g.errors + g.fatals) / 3), f("\"%s\" (mod %s) logged %d warnings and %d errors", g.logger, g.mod, g.warns, g.errors + g.fatals),
                        "First one: " + g.first, "Fix or silence the cause. Spammed logging also slows the game down."));
            }
        }
        if (!r.crashReports.isEmpty())
            out.add(new Finding(90, r.crashReports.size() + " crash report(s) were written during the run", String.join(", ", r.crashReports), "Open the crash report files named above."));
    }
}
