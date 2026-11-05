package com.example.clogger.model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Everything a logging run found. Plain data: the collectors fill it, Findings and ReportWriter read it. */
public final class Report {
    public String name = "";
    public String startedAt = "", endedAt = "";
    public double seconds;
    public boolean partial;
    public boolean hasClient, hasServer;

    /** System/mod info already written as text lines, one titled block each. */
    public final List<Block> system = new ArrayList<>();
    public Side server, client;

    public Stats.Summary heapUsedMb = Stats.Summary.EMPTY, threads = Stats.Summary.EMPTY, processCpu = Stats.Summary.EMPTY, systemCpu = Stats.Summary.EMPTY;
    public long heapMaxMb, physicalMb, gcCount, gcMillis, gcMaxPauseMs;
    public double gcPerMinute;
    public final List<String> longGcPauses = new ArrayList<>();
    /** Thread name group -> percent of one CPU core used over the run, biggest first. */
    public final Map<String, Double> threadCpu = new LinkedHashMap<>();

    public final List<Event> events = new ArrayList<>();
    public final Map<String, Long> counters = new LinkedHashMap<>();
    public final List<LogGroup> logs = new ArrayList<>();
    public long logLinesSeen;
    public final List<String> crashReports = new ArrayList<>();
    public final List<Timeline> timeline = new ArrayList<>();

    public double overheadPercentOfCore;
    public String overheadNote = "";
    public final List<String> unavailable = new ArrayList<>();

    public record Block(String title, List<String> lines) {}
    public record Event(double sec, String text) {}
    public record Timeline(double sec, double tps, double msptAvg, double fps, double heapMb, int entities, int chunks) {}

    /** One logger's warnings/errors, grouped. */
    public static final class LogGroup {
        public String logger, mod = "?", level = "";
        public long warns, errors, fatals;
        public String first = "", firstThrowable = "";
        public final Map<String, Long> messages = new LinkedHashMap<>();
        public long total() { return warns + errors + fatals; }
    }

    public record Spike(double sec, double ms, String thread, String mods, String where) {}
    public record ChunkLoad(String dim, int x, int z, int entities, int blockEntities, String top) {}

    /** The numbers for one side (server or client). */
    public static final class Side {
        public String label = "";
        public Stats.Summary tps = Stats.Summary.EMPTY, mspt = Stats.Summary.EMPTY, fps = Stats.Summary.EMPTY, frameMs = Stats.Summary.EMPTY, clientTickMs = Stats.Summary.EMPTY;
        public double fpsLow1;
        public long ticks, frames;
        public final List<Spike> spikes = new ArrayList<>();
        public long spikeCount;
        public double spikeThresholdMs;

        public StackTree tree;
        public String threadName = "";
        public long samples, idleSamples, betweenSamples;
        /** mod -> samples blamed on it (deepest mod frame in the stack). */
        public final Map<String, Long> blamedMod = new LinkedHashMap<>();
        public final Map<String, Long> selfMod = new LinkedHashMap<>();
        public final Map<String, Long> topMethods = new LinkedHashMap<>();
        public final Map<String, Long> blamedMethods = new LinkedHashMap<>();
        public final Map<String, Long> entityCode = new LinkedHashMap<>();
        public final Map<String, Long> blockEntityCode = new LinkedHashMap<>();

        public final List<Dim> dims = new ArrayList<>();
        public final List<ChunkLoad> busiestChunks = new ArrayList<>();
        public final List<String> players = new ArrayList<>();
        public final Map<String, Long> spawns = new LinkedHashMap<>();
        public int peakEntities, peakChunks;

        public final Map<String, Long> packetsIn = new LinkedHashMap<>(), packetsOut = new LinkedHashMap<>();
        public Stats.Summary packetsInPerSec = Stats.Summary.EMPTY, packetsOutPerSec = Stats.Summary.EMPTY;
        public String networkNote = "";
        public final List<String> extra = new ArrayList<>();
    }

    public static final class Dim {
        public String name = "";
        public int loadedChunks, entities, blockEntities;
        public final Map<String, Integer> entityTypes = new LinkedHashMap<>(), blockEntityTypes = new LinkedHashMap<>();
    }
}
