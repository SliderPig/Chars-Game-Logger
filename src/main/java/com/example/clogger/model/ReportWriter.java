package com.example.clogger.model;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;

/** Turns a Report into the text document and the small json. Pure: no game classes. */
public final class ReportWriter {
    private final Report r;
    private final Function<String, String> pretty;

    public ReportWriter(Report r, Function<String, String> prettyFrame) {
        this.r = r;
        this.pretty = prettyFrame == null ? s -> s : prettyFrame;
    }

    private static String f(String fmt, Object... a) { return String.format(Locale.ROOT, fmt, a); }

    private static void head(StringBuilder sb, String title) {
        sb.append('\n').append("=".repeat(78)).append('\n').append(title).append('\n').append("=".repeat(78)).append('\n');
    }

    private static String dur(double sec) {
        long s = (long) sec;
        return s >= 3600 ? f("%dh %02dm %02ds", s / 3600, s / 60 % 60, s % 60) : s >= 60 ? f("%dm %02ds", s / 60, s % 60) : f("%.1fs", sec);
    }

    private static String ms(Stats.Summary s) {
        return s.any() ? f("avg %.1f | median %.1f | 95%% %.1f | 99%% %.1f | max %.1f", s.avg(), s.p50(), s.p95(), s.p99(), s.max()) : "no data";
    }

    public String text() {
        StringBuilder sb = new StringBuilder(32_000);
        sb.append("CHAR'S GAME LOGGER REPORT").append(r.partial ? "   (PARTIAL: the game was still logging or stopped suddenly)" : "").append('\n');
        sb.append("Name:      ").append(r.name.isEmpty() ? "(none)" : r.name).append('\n');
        sb.append("Started:   ").append(r.startedAt).append('\n');
        sb.append("Ended:     ").append(r.endedAt).append("   (").append(dur(r.seconds)).append(")\n");
        sb.append("Measured:  ").append(r.hasServer && r.hasClient ? "client AND server (singleplayer)" : r.hasServer ? "server" : "client").append('\n');

        head(sb, "WHAT'S WRONG / WHAT'S SLOW  (biggest first)");
        List<Findings.Finding> fs = Findings.compute(r);
        if (fs.isEmpty()) sb.append("Nothing stood out. The game ran fine during this log.\n");
        int i = 0;
        for (Findings.Finding x : fs) {
            if (++i > 10) { sb.append("... and ").append(fs.size() - 10).append(" smaller things.\n"); break; }
            sb.append('\n').append(i).append(". [").append(x.label()).append("] ").append(x.title()).append('\n');
            if (!x.detail().isEmpty()) sb.append("   ").append(x.detail()).append('\n');
            if (!x.fix().isEmpty()) sb.append("   What to try: ").append(x.fix()).append('\n');
        }

        head(sb, "THE RUN AT A GLANCE");
        if (r.server != null) {
            Report.Side s = r.server;
            sb.append("Server TPS:         ").append(s.tps.any() ? f("avg %.2f | lowest second %.2f", s.tps.avg(), s.tps.min()) : "no data").append('\n');
            sb.append("Server tick (ms):   ").append(ms(s.mspt)).append("   [").append(s.ticks).append(" ticks]\n");
        }
        if (r.client != null) {
            Report.Side s = r.client;
            sb.append("Client FPS:         ").append(s.fps.any() ? f("avg %.0f | lowest second %.0f | 1%% lows %.0f", s.fps.avg(), s.fps.min(), s.fpsLow1 > 0 ? 1000 / s.fpsLow1 : 0) : "no data").append('\n');
            sb.append("Frame time (ms):    ").append(ms(s.frameMs)).append("   [").append(s.frames).append(" frames]\n");
            sb.append("Client tick (ms):   ").append(ms(s.clientTickMs)).append('\n');
        }
        sb.append("Memory used (MB):   ").append(ms(r.heapUsedMb)).append("   of max ").append(r.heapMaxMb).append('\n');
        sb.append("GC:                 ").append(r.gcCount).append(" collections, ").append(r.gcMillis).append(" ms total, longest ").append(r.gcMaxPauseMs).append(" ms\n");
        sb.append("Threads:            ").append(ms(r.threads)).append('\n');
        sb.append("Process CPU (%):    ").append(ms(r.processCpu)).append('\n');
        sb.append("System CPU (%):     ").append(ms(r.systemCpu)).append('\n');

        if (r.server != null) side(sb, r.server, true);
        if (r.client != null) side(sb, r.client, false);

        head(sb, "THREADS: who used the CPU (percent of ONE core, over the whole run)");
        int n = 0;
        for (var e : r.threadCpu.entrySet()) {
            if (++n > 15 || e.getValue() < 0.5) break;
            sb.append(f("  %6.1f%%  %s%n", e.getValue(), e.getKey()));
        }
        if (n == 0) sb.append("  (no thread CPU data)\n");

        if (r.server != null) world(sb, r.server);
        network(sb);

        head(sb, "TIMELINE (about 15 points through the run)");
        if (r.timeline.isEmpty()) sb.append("  no data\n");
        else {
            sb.append("     time    TPS   tick ms    FPS   heap MB  entities  chunks\n");
            int step = Math.max(1, r.timeline.size() / 15);
            for (int k = 0; k < r.timeline.size(); k += step) {
                Report.Timeline t = r.timeline.get(k);
                sb.append(f("  %7s  %5s  %8s  %5s  %8.0f  %8d  %6d%n", dur(t.sec()), t.tps() > 0 ? f("%.1f", t.tps()) : "-", t.msptAvg() > 0 ? f("%.1f", t.msptAvg()) : "-", t.fps() > 0 ? f("%.0f", t.fps()) : "-", t.heapMb(), t.entities(), t.chunks()));
            }
        }

        head(sb, "EVENTS (in order)");
        if (r.events.isEmpty()) sb.append("  none\n");
        int shown = 0;
        for (Report.Event e : r.events) {
            if (++shown > 400) { sb.append("  ... ").append(r.events.size() - 400).append(" more not shown\n"); break; }
            sb.append(f("  %8s  %s%n", dur(e.sec()), e.text()));
        }
        if (!r.counters.isEmpty()) {
            sb.append("\nCounts during the run:\n");
            r.counters.forEach((k, v) -> sb.append("  ").append(k).append(": ").append(v).append('\n'));
        }

        head(sb, "ERRORS AND WARNINGS from the game's log (" + r.logLinesSeen + " lines seen)");
        List<Report.LogGroup> gs = new ArrayList<>(r.logs);
        gs.sort(Comparator.comparingLong(Report.LogGroup::total).reversed());
        if (gs.isEmpty()) sb.append("  none\n");
        int g = 0;
        for (Report.LogGroup x : gs) {
            if (++g > 25) { sb.append("  ... ").append(gs.size() - 25).append(" more loggers\n"); break; }
            sb.append(f("  %-40s mod %-14s %d warn, %d error%s%n", x.logger, x.mod, x.warns, x.errors, x.fatals > 0 ? ", " + x.fatals + " FATAL" : ""));
            sb.append("      first: ").append(x.first).append('\n');
            if (!x.firstThrowable.isEmpty()) sb.append("      cause: ").append(x.firstThrowable).append('\n');
            x.messages.entrySet().stream().sorted(Map.Entry.<String, Long>comparingByValue().reversed()).limit(3).filter(m -> m.getValue() > 1)
                    .forEach(m -> sb.append("      ").append(m.getValue()).append("x  ").append(m.getKey()).append('\n'));
        }

        head(sb, "CRASH REPORTS written during the run");
        if (r.crashReports.isEmpty()) sb.append("  none\n");
        r.crashReports.forEach(c -> sb.append("  ").append(c).append('\n'));

        head(sb, "SYSTEM, GAME AND MODS");
        for (Report.Block b : r.system) {
            sb.append('\n').append("-- ").append(b.title()).append(" --\n");
            for (String l : b.lines()) sb.append("  ").append(l).append('\n');
        }

        head(sb, "THIS LOGGER'S OWN COST");
        sb.append(f("  About %.2f%% of one CPU core. %s%n", r.overheadPercentOfCore, r.overheadNote));
        if (!r.unavailable.isEmpty()) {
            sb.append("\nNot available:\n");
            r.unavailable.forEach(u -> sb.append("  - ").append(u).append('\n'));
        }
        return sb.toString();
    }

    private void side(StringBuilder sb, Report.Side s, boolean server) {
        head(sb, (server ? "SERVER" : "CLIENT") + " DETAILS" + (s.label.isEmpty() ? "" : " (" + s.label + ")"));
        if (server) {
            sb.append("Tick time (ms):  ").append(ms(s.mspt)).append('\n');
            sb.append("TPS per second:  ").append(ms(s.tps)).append('\n');
        } else {
            sb.append("Frame time (ms): ").append(ms(s.frameMs)).append('\n');
            sb.append("FPS per second:  ").append(ms(s.fps)).append('\n');
            sb.append("Client tick ms:  ").append(ms(s.clientTickMs)).append('\n');
        }
        s.extra.forEach(e -> sb.append(e).append('\n'));
        long total = s.samples;
        sb.append(f("%nProfiler: %d samples of thread \"%s\" (%d inside the %s, %d between, %d idle/waiting).%n", total, s.threadName, s.tree == null ? 0 : s.tree.samples(), server ? "tick" : "frame", s.betweenSamples, s.idleSamples));
        long inTick = s.tree == null ? 0 : s.tree.samples();
        if (inTick > 0) {
            sb.append("\nWho was running (each sample counted for the deepest mod in the call stack):\n");
            table(sb, s.blamedMod, inTick, 15, true);
            sb.append("\nHottest methods (where the CPU actually was):\n");
            table(sb, s.topMethods, inTick, 15, false);
            sb.append("\nMod entry points (the mod's own method that was running):\n");
            table(sb, s.blamedMethods, inTick, 12, false);
            if (!s.entityCode.isEmpty()) {
                sb.append("\nEntity code (declaring class of the entity method running; shared base classes show as themselves):\n");
                table(sb, s.entityCode, inTick, 12, false);
            }
            if (!s.blockEntityCode.isEmpty()) {
                sb.append("\nBlock entity code:\n");
                table(sb, s.blockEntityCode, inTick, 12, false);
            }
            sb.append("\n").append(server ? "Tick-time tree" : "Render-thread tree").append(" (percent of ").append(server ? "tick" : "frame").append(" samples; small branches folded):\n");
            sb.append(s.tree.render(1.5, 120, pretty));
        } else sb.append("No profiler samples were collected.\n");

        sb.append("\nLag spikes (").append(server ? "ticks" : "frames").append(" over ").append(f("%.0f", s.spikeThresholdMs)).append(" ms): ").append(s.spikeCount).append('\n');
        int k = 0;
        for (Report.Spike sp : s.spikes) {
            if (++k > 15) break;
            sb.append(f("  at %8s  %7.0f ms  %s: mods [%s]  %s%n", dur(sp.sec()), sp.ms(), sp.thread(), sp.mods(), sp.where()));
        }
        if (s.spikeCount > s.spikes.size()) sb.append("  (only the worst ").append(s.spikes.size()).append(" are listed)\n");
    }

    private void table(StringBuilder sb, Map<String, Long> m, long all, int limit, boolean pct) {
        int n = 0;
        for (var e : m.entrySet().stream().sorted(Map.Entry.<String, Long>comparingByValue().reversed()).toList()) {
            if (++n > limit) break;
            String key = pct ? e.getKey() : pretty.apply(e.getKey());
            sb.append(f("  %5.1f%%  %s%n", e.getValue() * 100.0 / Math.max(1, all), key));
        }
    }

    private void world(StringBuilder sb, Report.Side s) {
        head(sb, "WORLD: entities, chunks, block entities (snapshot at the busiest moment)");
        if (s.dims.isEmpty()) sb.append("  no data\n");
        for (Report.Dim d : s.dims) {
            sb.append(f("%n%s: %d loaded chunks, %d entities, %d block entities%n", d.name, d.loadedChunks, d.entities, d.blockEntities));
            sb.append("  Entities by type: ").append(topList(d.entityTypes, 12)).append('\n');
            sb.append("  Block entities by type: ").append(topList(d.blockEntityTypes, 12)).append('\n');
        }
        if (!s.busiestChunks.isEmpty()) {
            sb.append("\nBusiest chunks (block coordinates are the chunk's middle):\n");
            for (Report.ChunkLoad c : s.busiestChunks)
                sb.append(f("  %-24s x=%6d z=%6d   %4d entities, %4d block entities   mostly %s%n", c.dim(), c.x() * 16 + 8, c.z() * 16 + 8, c.entities(), c.blockEntities(), c.top()));
        }
        if (!s.spawns.isEmpty()) sb.append("\nThings that appeared during the run: ").append(topList(s.spawns, 12)).append('\n');
        if (!s.players.isEmpty()) {
            sb.append("\nPlayers seen:\n");
            s.players.forEach(p -> sb.append("  ").append(p).append('\n'));
        }
    }

    private static String topList(Map<String, ? extends Number> m, int limit) {
        if (m.isEmpty()) return "none";
        StringBuilder b = new StringBuilder();
        int n = 0;
        for (var e : m.entrySet().stream().sorted((a, c) -> Double.compare(c.getValue().doubleValue(), a.getValue().doubleValue())).toList()) {
            if (++n > limit) break;
            if (n > 1) b.append(", ");
            b.append(e.getKey()).append(' ').append(e.getValue().longValue());
        }
        return b.toString();
    }

    private void network(StringBuilder sb) {
        head(sb, "NETWORK (packets the game sent and received)");
        boolean any = false;
        for (Report.Side s : new Report.Side[] {r.server, r.client}) {
            if (s == null) continue;
            any = true;
            sb.append(s.label.isEmpty() ? "" : s.label + ": ").append(s.networkNote).append('\n');
            if (s.packetsInPerSec.any()) sb.append("  Received per second: ").append(ms(s.packetsInPerSec)).append('\n');
            if (s.packetsOutPerSec.any()) sb.append("  Sent per second:     ").append(ms(s.packetsOutPerSec)).append('\n');
            if (!s.packetsIn.isEmpty()) sb.append("  Most received: ").append(topList(s.packetsIn, 8)).append('\n');
            if (!s.packetsOut.isEmpty()) sb.append("  Most sent:     ").append(topList(s.packetsOut, 8)).append('\n');
        }
        if (!any) sb.append("  none\n");
    }

    /** A small json for comparing two runs. */
    public String json() {
        StringBuilder b = new StringBuilder("{\n");
        kv(b, "name", r.name);
        kv(b, "started", r.startedAt);
        num(b, "seconds", r.seconds);
        num(b, "serverTpsAvg", r.server == null ? 0 : r.server.tps.avg());
        num(b, "serverMsptAvg", r.server == null ? 0 : r.server.mspt.avg());
        num(b, "serverMsptP95", r.server == null ? 0 : r.server.mspt.p95());
        num(b, "serverMsptP99", r.server == null ? 0 : r.server.mspt.p99());
        num(b, "serverMsptMax", r.server == null ? 0 : r.server.mspt.max());
        num(b, "clientFpsAvg", r.client == null ? 0 : r.client.fps.avg());
        num(b, "clientFrameMsP99", r.client == null ? 0 : r.client.frameMs.p99());
        num(b, "heapUsedMbAvg", r.heapUsedMb.avg());
        num(b, "heapMaxMb", r.heapMaxMb);
        num(b, "gcMillis", r.gcMillis);
        num(b, "gcMaxPauseMs", r.gcMaxPauseMs);
        num(b, "serverLagSpikes", r.server == null ? 0 : r.server.spikeCount);
        num(b, "clientLagSpikes", r.client == null ? 0 : r.client.spikeCount);
        num(b, "overheadPercentOfCore", r.overheadPercentOfCore);
        b.append("  \"problems\": [");
        List<Findings.Finding> fs = Findings.compute(r);
        for (int i = 0; i < Math.min(8, fs.size()); i++) {
            if (i > 0) b.append(", ");
            b.append('"').append(esc(fs.get(i).title())).append('"');
        }
        b.append("],\n  \"mods\": {");
        boolean first = true;
        for (Report.Side s : new Report.Side[] {r.server, r.client}) {
            if (s == null || s.tree == null || s.tree.samples() == 0) continue;
            if (!first) b.append(", ");
            first = false;
            b.append('"').append(s == r.server ? "server" : "client").append("\": {");
            int n = 0;
            for (var e : s.blamedMod.entrySet().stream().sorted(Map.Entry.<String, Long>comparingByValue().reversed()).limit(12).toList()) {
                if (n++ > 0) b.append(", ");
                b.append('"').append(esc(e.getKey())).append("\": ").append(f("%.1f", e.getValue() * 100.0 / s.tree.samples()));
            }
            b.append('}');
        }
        b.append("}\n}\n");
        return b.toString();
    }

    private static void kv(StringBuilder b, String k, String v) { b.append("  \"").append(k).append("\": \"").append(esc(v)).append("\",\n"); }

    private static void num(StringBuilder b, String k, double v) { b.append("  \"").append(k).append("\": ").append(Double.isFinite(v) ? f("%.3f", v) : "0").append(",\n"); }

    static String esc(String s) {
        StringBuilder b = new StringBuilder();
        for (char c : s.toCharArray()) {
            if (c == '"' || c == '\\') b.append('\\').append(c);
            else if (c < 32) b.append(' ');
            else b.append(c);
        }
        return b.toString();
    }
}
