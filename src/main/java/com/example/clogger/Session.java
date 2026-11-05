package com.example.clogger;

import java.io.IOException;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import java.util.stream.Stream;

import javax.management.NotificationEmitter;

import com.example.clogger.model.Report;
import com.example.clogger.model.ReportWriter;
import com.example.clogger.model.Stats;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.fml.loading.FMLPaths;

/** One logging run. Created by /clogger start, ended by /clogger stop (or the game closing). */
final class Session {
    static volatile Session current;

    record ClientSnap(Report.Dim dim, List<String> extra) {}

    static boolean isBase(String m) { return com.example.clogger.model.Findings.BASE.contains(m); }

    final String name;
    final boolean hasClient, hasServer;
    volatile MinecraftServer server;
    final long startNs = System.nanoTime(), startMs = System.currentTimeMillis();
    final String startedText = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
    final String baseName;
    final Path dir;
    final ModMap mods = new ModMap();
    final Target serverT = new Target("Server thread", true), clientT = new Target("Render thread", false);

    static final double SPIKE_MS = Double.parseDouble(System.getProperty("clogger.spikeMs", "100"));

    private final Stats.Buf msptBuf = new Stats.Buf(300_000), tpsBuf = new Stats.Buf(50_000), frameBuf = new Stats.Buf(300_000), fpsBuf = new Stats.Buf(50_000), ctickBuf = new Stats.Buf(100_000),
            heapBuf = new Stats.Buf(50_000), threadBuf = new Stats.Buf(50_000), procBuf = new Stats.Buf(50_000), sysBuf = new Stats.Buf(50_000),
            inBuf = new Stats.Buf(50_000), outBuf = new Stats.Buf(50_000);
    private final AtomicInteger serverTicksSec = new AtomicInteger(), framesSec = new AtomicInteger();
    private final AtomicLong serverTickNsSec = new AtomicLong(), hookNs = new AtomicLong(), serverTicks = new AtomicLong(), frames = new AtomicLong();
    private volatile long serverTickStart, lastFrameStart, clientTickStart;
    private int serverTickCounter;
    private final List<Report.Event> events = new ArrayList<>();
    private int droppedEvents;
    private final Map<String, LongAdder> counters = new ConcurrentHashMap<>();
    private final Map<String, LongAdder> spawns = new ConcurrentHashMap<>();
    private final Map<String, String> playersSeen = new LinkedHashMap<>();
    private final List<String> unavailable = new ArrayList<>();
    private final List<Report.Timeline> timeline = new ArrayList<>();
    private final List<String> longGc = new ArrayList<>();
    private long spikeCountServer, spikeCountClient;
    private final List<Report.Spike> spikesServer = new ArrayList<>(), spikesClient = new ArrayList<>();
    private WorldScan.Snap bestSnap, lastSnap;
    private Report.Dim clientDim;
    private final List<String> clientExtra = new ArrayList<>();
    private int clientPeakEntities, clientPeakChunks;

    private final NetCount netServer = new NetCount(), netClient = new NetCount();
    private LogTap logTap;
    private Thread thread;
    private volatile boolean running = true;
    private Thread hook;
    private long gcCount0, gcMs0, gcMaxPause;
    private final AtomicLong gcMaxPauseA = new AtomicLong();
    private Report systemOnly;
    private final ThreadMXBean tmx = ManagementFactory.getThreadMXBean();
    private final Map<Long, long[]> threadCpu = new HashMap<>(); // id -> {lastCpu, total}
    private final Map<Long, String> threadNames = new HashMap<>();
    private long lastPartialMs = System.currentTimeMillis();
    private volatile long samplerCpuNs;
    private long infoWallTotal;
    private double lastDimCheckSec;
    private String clientDimName = "";

    Session(String name, boolean client, boolean serverSide, MinecraftServer server) {
        this.name = name == null ? "" : name;
        this.hasClient = client;
        this.hasServer = serverSide;
        this.server = server;
        this.dir = FMLPaths.GAMEDIR.get().resolve("clogger");
        String stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss"));
        String safe = this.name.replaceAll("[^A-Za-z0-9_-]", "_");
        this.baseName = stamp + (safe.isEmpty() ? "" : "_" + safe);
    }

    // ---------------------------------------------------------------- start / stop

    static synchronized Session start(String name, boolean client, boolean serverSide, MinecraftServer server) throws IOException {
        if (current != null) return null;
        Session s = new Session(name, client, serverSide, server);
        Files.createDirectories(s.dir);
        s.begin();
        current = s;
        return s;
    }

    private void begin() {
        if (hasClient) clientT.thread = clientThreadOrNull();
        // slow one-time work in the background so the command comes back at once
        Thread prep = new Thread(() -> {
            try { mods.build(); } catch (Throwable t) { unavailable("mod map: " + t); }
        }, "clogger-prep");
        prep.setDaemon(true);
        prep.start();
        systemOnly = new Report();
        try { SystemInfo.add(systemOnly, hasClient, server); } catch (Throwable t) { unavailable("system info: " + t); }
        logTap = LogTap.attach(this);
        for (GarbageCollectorMXBean g : ManagementFactory.getGarbageCollectorMXBeans()) {
            gcCount0 += Math.max(0, g.getCollectionCount());
            gcMs0 += Math.max(0, g.getCollectionTime());
            try {
                if (g instanceof NotificationEmitter ne) ne.addNotificationListener((n, hb) -> onGc(n), null, null);
            } catch (Throwable t) { unavailable("GC notifications: " + t); }
        }
        try { tmx.setThreadCpuTimeEnabled(true); } catch (Throwable t) { unavailable("thread CPU time: " + t); }
        try {
            for (long id : tmx.getAllThreadIds()) {
                long c = tmx.getThreadCpuTime(id);
                if (c >= 0) threadCpu.put(id, new long[] {c, 0});
            }
        } catch (Throwable ignored) { }
        hook = new Thread(() -> {
            Session s = current;
            if (s == this) s.finish(true, true);
        }, "clogger-shutdown");
        Runtime.getRuntime().addShutdownHook(hook);
        thread = new Thread(this::loop, "clogger");
        thread.setDaemon(true);
        thread.start();
        event("Logging started" + (name.isEmpty() ? "" : " (" + name + ")") + ": " + (hasClient && hasServer ? "client and server" : hasServer ? "server" : "client"));
        netAttachExisting();
    }

    private static Thread clientThreadOrNull() {
        try { return ClientBridge.renderThread(); } catch (Throwable t) { return null; }
    }

    void netAttachExisting() {
        try {
            if (hasServer && server != null) for (ServerPlayer p : server.getPlayerList().getPlayers()) attachPlayer(p);
        } catch (Throwable t) { unavailable("network counting (server): " + t); }
        try { if (hasClient) ClientBridge.attachNet(netClient); } catch (Throwable t) { unavailable("network counting (client): " + t); }
    }

    void attachPlayer(ServerPlayer p) {
        try { netServer.attach(p.connection.connection.channel()); } catch (Throwable t) { unavailable("network counting (server): " + t); }
    }

    void attachClientNet() {
        try { if (hasClient) ClientBridge.attachNet(netClient); } catch (Throwable t) { unavailable("network counting (client): " + t); }
    }

    /** Ends the run and writes the files. Returns the report path. */
    static synchronized Path stop() {
        Session s = current;
        if (s == null) return null;
        return s.finish(false, false);
    }

    private synchronized Path finish(boolean fromHook, boolean quiet) {
        if (!running && current != this) return null;
        running = false;
        current = null;
        try { if (thread != null && !fromHook) thread.join(1500); } catch (InterruptedException ignored) { }
        if (logTap != null) logTap.detach();
        netServer.detachAll();
        netClient.detachAll();
        if (!fromHook) {
            try { Runtime.getRuntime().removeShutdownHook(hook); } catch (Throwable ignored) { }
        }
        try {
            second(true);
        } catch (Throwable ignored) { }
        Path txt = dir.resolve(baseName + ".txt");
        try {
            Report r = build(false);
            ReportWriter w = new ReportWriter(r, mods::pretty);
            Files.writeString(txt, w.text(), StandardCharsets.UTF_8);
            Files.writeString(dir.resolve(baseName + ".json"), w.json(), StandardCharsets.UTF_8);
            Files.deleteIfExists(dir.resolve(baseName + ".partial.txt"));
        } catch (Throwable t) {
            try { Files.writeString(dir.resolve(baseName + ".error.txt"), "Could not write the report: " + t, StandardCharsets.UTF_8); } catch (Throwable ignored) { }
            CloggerMod.LOG.error("clogger could not write its report", t);
        }
        return txt;
    }

    Path partialPath() { return dir.resolve(baseName + ".partial.txt"); }

    // ---------------------------------------------------------------- shared helpers

    void unavailable(String why) {
        synchronized (unavailable) { if (!unavailable.contains(why) && unavailable.size() < 40) unavailable.add(why); }
    }

    double sec() { return (System.nanoTime() - startNs) / 1e9; }

    private final Map<String, Integer> eventKinds = new HashMap<>();

    /** Like event(), but at most `limit` of this kind are written; the rest are only counted. */
    void event(String kind, int limit, String text) {
        int n;
        synchronized (eventKinds) { n = eventKinds.merge(kind, 1, Integer::sum); }
        if (n <= limit) event(text);
        else if (n == limit + 1) event("(more " + kind + " not listed, see the counts below)");
    }

    void event(String text) {
        synchronized (events) {
            if (events.size() >= 3000) { droppedEvents++; return; }
            events.add(new Report.Event(sec(), text));
        }
    }

    void count(String key) { counters.computeIfAbsent(key, k -> new LongAdder()).increment(); }

    void spawned(String type) { if (spawns.size() < 300 || spawns.containsKey(type)) spawns.computeIfAbsent(type, k -> new LongAdder()).increment(); }

    private void onGc(javax.management.Notification n) {
        try {
            if (!"com.sun.management.gc.notification".equals(n.getType())) return;
            var info = com.sun.management.GarbageCollectionNotificationInfo.from((javax.management.openmbean.CompositeData) n.getUserData());
            long ms = info.getGcInfo().getDuration();
            gcMaxPauseA.accumulateAndGet(ms, Math::max);
            if (ms >= 100) {
                String t = String.format("%.1fs: %s (%s) took %d ms, cause: %s", sec(), info.getGcName(), info.getGcAction(), ms, info.getGcCause());
                synchronized (longGc) { if (longGc.size() < 50) longGc.add(t); }
                event("Memory cleanup froze the game for " + ms + " ms (" + info.getGcName() + ")");
            }
        } catch (Throwable ignored) { }
    }

    // ---------------------------------------------------------------- hooks called by the game events

    void serverTick(boolean start) {
        long t0 = System.nanoTime();
        if (start) {
            serverTickStart = t0;
            if (serverT.thread == null) serverT.thread = Thread.currentThread();
            serverT.active = true;
            return;
        }
        serverT.active = false;
        long dt = t0 - serverTickStart;
        if (serverTickStart == 0) return;
        double ms = dt / 1e6;
        msptBuf.add(ms);
        serverTicks.incrementAndGet();
        serverTicksSec.incrementAndGet();
        serverTickNsSec.addAndGet(dt);
        if (ms >= SPIKE_MS) spike(serverT, serverTickStart, t0, ms, spikesServer, true);
        if (++serverTickCounter % 200 == 20 && server != null) {
            try {
                WorldScan.Snap sn = WorldScan.scan(server, serverT, this);
                synchronized (this) {
                    lastSnap = sn;
                    if (bestSnap == null || sn.entities() + sn.blockEntities() >= bestSnap.entities() + bestSnap.blockEntities()) bestSnap = sn;
                }
                var players = server.getPlayerList().getPlayers();
                synchronized (playersSeen) {
                    for (ServerPlayer p : players) playersSeen.put(p.getGameProfile().getName(), p.latency + " ms ping, in " + p.level().dimension().location() + ", gamemode " + p.gameMode.getGameModeForPlayer().getName());
                }
            } catch (Throwable t) { unavailable("world scan: " + t); }
        }
        hookNs.addAndGet(System.nanoTime() - t0);
    }

    void renderFrame() {
        long now = System.nanoTime();
        if (clientT.thread == null) clientT.thread = Thread.currentThread();
        long prev = lastFrameStart;
        lastFrameStart = now;
        if (prev == 0) return;
        double ms = (now - prev) / 1e6;
        frameBuf.add(ms);
        frames.incrementAndGet();
        framesSec.incrementAndGet();
        if (ms >= SPIKE_MS) spike(clientT, prev, now, ms, spikesClient, false);
    }

    void clientTick(boolean start) {
        long now = System.nanoTime();
        if (start) { clientTickStart = now; return; }
        if (clientTickStart != 0) ctickBuf.add((now - clientTickStart) / 1e6);
        if (hasClient && ++clientTickCounter % 100 == 10) {
            long t0 = System.nanoTime();
            try {
                ClientSnap sn = ClientBridge.scan();
                if (sn != null) {
                    synchronized (this) {
                        clientDim = sn.dim();
                        clientExtra.clear();
                        clientExtra.addAll(sn.extra());
                        clientPeakEntities = Math.max(clientPeakEntities, sn.dim().entities);
                        clientPeakChunks = Math.max(clientPeakChunks, sn.dim().loadedChunks);
                    }
                    if (!sn.dim().name.equals(clientDimName)) {
                        if (!clientDimName.isEmpty()) event("You moved to dimension " + sn.dim().name + " (client view)");
                        clientDimName = sn.dim().name;
                    }
                }
            } catch (Throwable t) { unavailable("client scan: " + t); }
            hookNs.addAndGet(System.nanoTime() - t0);
        }
    }

    private int clientTickCounter;

    private void spike(Target t, long fromNs, long toNs, double ms, List<Report.Spike> list, boolean server) {
        synchronized (list) {
            if (server) spikeCountServer++; else spikeCountClient++;
            String mods = "(no profiler sample landed inside it)", where = "";
            List<Target.Sample> ss = t.between(fromNs, toNs);
            if (!ss.isEmpty()) {
                Map<String, Integer> by = new HashMap<>(), at = new HashMap<>();
                for (Target.Sample s : ss) { by.merge(s.blame(), 1, Integer::sum); at.merge(s.where(), 1, Integer::sum); }
                StringBuilder b = new StringBuilder();
                by.entrySet().stream().sorted(Map.Entry.<String, Integer>comparingByValue().reversed()).limit(3)
                        .forEach(e -> b.append(b.length() > 0 ? ", " : "").append(e.getKey()).append(' ').append(e.getValue() * 100 / ss.size()).append('%'));
                mods = b.toString();
                where = this.mods.pretty(at.entrySet().stream().max(Map.Entry.comparingByValue()).get().getKey()) + " (" + ss.size() + " samples)";
            }
            Report.Spike sp = new Report.Spike((fromNs - startNs) / 1e9, ms, t.label, mods, where);
            if (list.size() < 40) list.add(sp);
            else {
                int worstIdx = 0;
                for (int i = 1; i < list.size(); i++) if (list.get(i).ms() < list.get(worstIdx).ms()) worstIdx = i;
                if (list.get(worstIdx).ms() < ms) list.set(worstIdx, sp);
            }
        }
        if (ms >= 500 || (server ? spikeCountServer : spikeCountClient) <= 20) event(String.format("Lag spike: %s took %.0f ms", server ? "a server tick" : "a frame", ms));
    }

    // ---------------------------------------------------------------- the logger thread: stack samples and per-second numbers

    private volatile long infoWallNs;
    private long passes;

    private void loop() {
        long nextSecond = System.nanoTime() + 1_000_000_000L;
        long intervalNs = 20_000_000L;
        while (running) {
            long t0 = System.nanoTime();
            long c0 = tmx.getCurrentThreadCpuTime();
            try { sample(t0); } catch (Throwable t) { unavailable("profiler: " + t); }
            long cpuCost = Math.max(0, tmx.getCurrentThreadCpuTime() - c0);
            passes++;
            // aim for about 1% of a core, counting the time the JVM needs to stop the threads
            intervalNs = Math.max(20_000_000L, Math.min(250_000_000L, Math.max(cpuCost, infoWallNs / 2) * 100));
            if (t0 >= nextSecond) {
                try { second(false); } catch (Throwable t) { unavailable("per-second numbers: " + t); }
                nextSecond = t0 + 1_000_000_000L;
            }
            long sleep = intervalNs - (System.nanoTime() - t0);
            if (sleep > 0) {
                try { Thread.sleep(sleep / 1_000_000L, (int) (sleep % 1_000_000L)); } catch (InterruptedException e) { break; }
            }
        }
        try { samplerCpuNs = tmx.getCurrentThreadCpuTime(); } catch (Throwable ignored) { }
    }

    private void sample(long now) {
        Target[] ts = {serverT, clientT};
        List<Long> ids = new ArrayList<>(2);
        List<Target> used = new ArrayList<>(2);
        for (Target t : ts) {
            Thread th = t.thread;
            if (th == null || !th.isAlive()) continue;
            if ((t == serverT && !hasServer) || (t == clientT && !hasClient)) continue;
            ids.add(th.getId());
            used.add(t);
        }
        if (ids.isEmpty()) return;
        long[] arr = ids.stream().mapToLong(Long::longValue).toArray();
        long w0 = System.nanoTime();
        ThreadInfo[] infos = tmx.getThreadInfo(arr, 90);
        infoWallNs = System.nanoTime() - w0;
        infoWallTotal += infoWallNs;
        for (int i = 0; i < infos.length; i++) {
            ThreadInfo ti = infos[i];
            if (ti == null) continue;
            used.get(i).take(ti.getStackTrace(), ti.getThreadState(), mods, now);
        }
    }

    private long prevSecNs = System.nanoTime();
    private long gcCountPrev, gcMsPrev;
    private long prevIn, prevOut;
    private long ticksAtPrev;
    private int secondsDone;

    private void second(boolean last) {
        long now = System.nanoTime();
        double dt = Math.max(0.05, (now - prevSecNs) / 1e9);
        prevSecNs = now;
        if (last && dt < 0.2) return;
        double tps = 0, mspt = 0, fps = 0;
        if (hasServer && serverT.thread != null) {
            int ticks = serverTicksSec.getAndSet(0);
            long ns = serverTickNsSec.getAndSet(0);
            tps = Math.min(20.0, ticks / dt);
            mspt = ticks == 0 ? 0 : ns / 1e6 / ticks;
            if (ticks > 0 || serverT.active) tpsBuf.add(tps);
        }
        if (hasClient && clientT.thread != null) {
            fps = framesSec.getAndSet(0) / dt;
            fpsBuf.add(fps);
        }
        Runtime rt = Runtime.getRuntime();
        double heap = (rt.totalMemory() - rt.freeMemory()) / 1048576.0;
        heapBuf.add(heap);
        threadBuf.add(tmx.getThreadCount());
        try {
            var os = (com.sun.management.OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
            double pc = os.getProcessCpuLoad(), sc = os.getCpuLoad();
            if (pc >= 0) procBuf.add(pc * 100);
            if (sc >= 0) sysBuf.add(sc * 100);
        } catch (Throwable t) { unavailable("CPU load: " + t); }
        long in = netServer.totalIn.sum() + netClient.totalIn.sum(), out = netServer.totalOut.sum() + netClient.totalOut.sum();
        if (netServer.connectionsEver() + netClient.connectionsEver() > 0) {
            inBuf.add((in - prevIn) / dt);
            outBuf.add((out - prevOut) / dt);
        }
        prevIn = in;
        prevOut = out;
        int ents = 0, chunks = 0;
        synchronized (this) {
            if (hasServer && lastSnap != null) { ents = lastSnap.entities(); chunks = lastSnap.loadedChunks(); }
            else if (clientDim != null) { ents = clientDim.entities; chunks = clientDim.loadedChunks; }
        }
        synchronized (timeline) { timeline.add(new Report.Timeline(sec(), tps, mspt, fps, heap, ents, chunks)); }
        if (++secondsDone % 5 == 0) threadCpuTick();
        if (System.currentTimeMillis() - lastPartialMs > 60_000 && !last) {
            lastPartialMs = System.currentTimeMillis();
            try {
                Report r = build(true);
                Path tmp = dir.resolve(baseName + ".partial.tmp");
                Files.writeString(tmp, new ReportWriter(r, mods::pretty).text(), StandardCharsets.UTF_8);
                Files.move(tmp, partialPath(), StandardCopyOption.REPLACE_EXISTING);
            } catch (Throwable t) { unavailable("partial file: " + t); }
        }
    }

    private void threadCpuTick() {
        try {
            long[] ids = tmx.getAllThreadIds();
            ThreadInfo[] infos = tmx.getThreadInfo(ids, 0);
            for (int i = 0; i < ids.length; i++) {
                if (infos[i] == null) continue;
                long cpu = tmx.getThreadCpuTime(ids[i]);
                if (cpu < 0) continue;
                long[] rec = threadCpu.get(ids[i]);
                if (rec == null) { rec = new long[] {0, 0}; threadCpu.put(ids[i], rec); }
                rec[1] += cpu - rec[0];
                rec[0] = cpu;
                threadNames.put(ids[i], infos[i].getThreadName());
            }
        } catch (Throwable t) { unavailable("thread CPU: " + t); }
    }

    static String groupName(String n) {
        String g = n.replaceAll("[-_ #]*\\d+", "-N").replaceAll("(-N)+", "-N");
        return g.endsWith("-N") ? g.substring(0, g.length() - 2) + " (several)" : g;
    }

    // ---------------------------------------------------------------- build the report

    Report build(boolean partial) {
        Report r = new Report();
        r.name = name;
        r.partial = partial;
        r.startedAt = startedText;
        r.endedAt = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        r.seconds = sec();
        r.hasClient = hasClient;
        r.hasServer = hasServer;
        if (systemOnly != null) r.system.addAll(systemOnly.system);
        synchronized (unavailable) { r.unavailable.addAll(unavailable); }
        if (!mods.notes.isEmpty()) r.unavailable.addAll(mods.notes);
        if (hasServer) {
            synchronized (serverT) {
                Report.Side s = new Report.Side();
                s.label = "server";
                s.threadName = "Server thread";
                fillTarget(s, serverT);
                s.tps = Stats.summarize(tpsBuf.toArray());
                s.mspt = Stats.summarize(msptBuf.toArray());
                s.ticks = serverTicks.get();
                s.spikeThresholdMs = SPIKE_MS;
                synchronized (spikesServer) { s.spikes.addAll(spikesServer); s.spikeCount = spikeCountServer; }
                s.spikes.sort(Comparator.comparingDouble(Report.Spike::ms).reversed());
                synchronized (this) {
                    if (bestSnap != null) {
                        s.dims.addAll(bestSnap.dims());
                        s.busiestChunks.addAll(bestSnap.chunks());
                        s.peakEntities = bestSnap.entities();
                        s.peakChunks = bestSnap.loadedChunks();
                    } else s.extra.add("World scan: no snapshot yet (it runs 1 s after start, then every 10 s).");
                }
                synchronized (playersSeen) { playersSeen.forEach((k, v) -> s.players.add(k + ": " + v)); }
                spawns.forEach((k, v) -> s.spawns.put(k, v.sum()));
                netFill(s, netServer, "server connections (players)");
                r.server = s;
            }
        }
        if (hasClient) {
            synchronized (clientT) {
                Report.Side s = new Report.Side();
                s.label = "client";
                s.threadName = "Render thread";
                fillTarget(s, clientT);
                s.fps = Stats.summarize(fpsBuf.toArray());
                double[] ft = frameBuf.toArray();
                s.frameMs = Stats.summarize(ft);
                s.fpsLow1 = ft.length == 0 ? 0 : Stats.worstAverage(ft, 0.01);
                s.clientTickMs = Stats.summarize(ctickBuf.toArray());
                s.frames = frames.get();
                s.spikeThresholdMs = SPIKE_MS;
                synchronized (spikesClient) { s.spikes.addAll(spikesClient); s.spikeCount = spikeCountClient; }
                s.spikes.sort(Comparator.comparingDouble(Report.Spike::ms).reversed());
                synchronized (this) {
                    if (clientDim != null) {
                        s.dims.add(clientDim);
                        s.peakEntities = clientPeakEntities;
                        s.peakChunks = clientPeakChunks;
                    }
                    s.extra.addAll(clientExtra);
                }
                netFill(s, netClient, "client connection");
                r.client = s;
            }
        }
        r.heapUsedMb = Stats.summarize(heapBuf.toArray());
        r.heapMaxMb = Runtime.getRuntime().maxMemory() / 1048576;
        try { r.physicalMb = ((com.sun.management.OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean()).getTotalMemorySize() / 1048576; } catch (Throwable ignored) { }
        r.threads = Stats.summarize(threadBuf.toArray());
        r.processCpu = Stats.summarize(procBuf.toArray());
        r.systemCpu = Stats.summarize(sysBuf.toArray());
        long cnt = 0, ms = 0;
        for (GarbageCollectorMXBean g : ManagementFactory.getGarbageCollectorMXBeans()) {
            cnt += Math.max(0, g.getCollectionCount());
            ms += Math.max(0, g.getCollectionTime());
        }
        r.gcCount = cnt - gcCount0;
        r.gcMillis = ms - gcMs0;
        r.gcMaxPauseMs = gcMaxPauseA.get();
        r.gcPerMinute = r.seconds > 0 ? r.gcCount * 60.0 / r.seconds : 0;
        synchronized (longGc) { r.longGcPauses.addAll(longGc); }
        // thread cpu, grouped by name
        Map<String, Double> groups = new HashMap<>();
        synchronized (threadCpu) {
            for (var e : threadCpu.entrySet()) {
                String n = threadNames.get(e.getKey());
                if (n == null || r.seconds < 1) continue;
                groups.merge(groupName(n), e.getValue()[1] / 1e9 / r.seconds * 100.0, Double::sum);
            }
        }
        groups.entrySet().stream().sorted(Map.Entry.<String, Double>comparingByValue().reversed()).limit(25).forEach(e -> r.threadCpu.put(e.getKey(), e.getValue()));
        synchronized (events) { r.events.addAll(events); if (droppedEvents > 0) r.events.add(new Report.Event(r.seconds, "(" + droppedEvents + " more events were not kept)")); }
        counters.forEach((k, v) -> r.counters.put(k, v.sum()));
        if (logTap != null) logTap.fill(r);
        synchronized (timeline) { r.timeline.addAll(timeline); }
        crashReports(r);
        // overhead
        long samplerCpu = samplerCpuNs;
        try { if (samplerCpu == 0 && thread != null) samplerCpu = Math.max(0, tmx.getThreadCpuTime(thread.getId())); } catch (Throwable ignored) { }
        double wall = Math.max(1e9, System.nanoTime() - startNs);
        r.overheadPercentOfCore = (samplerCpu + hookNs.get()) * 100.0 / wall;
        r.overheadNote = String.format("Logger thread CPU %.2f s + event hooks/world scans %.2f s over %.0f s. %d stack samples; getting a stack snapshot blocked the sampled threads for %.1f ms in total (%.3f%% of the run, per thread).",
                samplerCpu / 1e9, hookNs.get() / 1e9, wall / 1e9, passes, infoWallTotal / 1e6, infoWallTotal * 100.0 / wall);
        return r;
    }

    private void fillTarget(Report.Side s, Target t) {
        s.tree = t.tree;
        s.samples = t.samples;
        s.idleSamples = t.idle;
        s.betweenSamples = t.between;
        s.blamedMod.putAll(t.blamed);
        s.topMethods.putAll(t.topMethods);
        s.blamedMethods.putAll(t.blamedMethods);
        s.entityCode.putAll(t.entityCode);
        s.blockEntityCode.putAll(t.beCode);
    }

    private void netFill(Report.Side s, NetCount n, String what) {
        n.in.forEach((k, v) -> s.packetsIn.put(k, v.sum()));
        n.out.forEach((k, v) -> s.packetsOut.put(k, v.sum()));
        if (n.connectionsEver() == 0) {
            s.networkNote = "No " + what + " were counted (nobody connected while logging, or the connection was not reachable).";
            return;
        }
        s.networkNote = "Counted packets on " + n.connectionsEver() + " " + what + " (packet counts only, not bytes).";
        s.packetsInPerSec = Stats.summarize(inBuf.toArray());
        s.packetsOutPerSec = Stats.summarize(outBuf.toArray());
    }

    private void crashReports(Report r) {
        try {
            Path cr = FMLPaths.GAMEDIR.get().resolve("crash-reports");
            if (Files.isDirectory(cr)) {
                try (Stream<Path> s = Files.list(cr)) {
                    s.filter(p -> p.toFile().lastModified() >= startMs - 2000).forEach(p -> r.crashReports.add("crash-reports/" + p.getFileName()));
                }
            }
            try (Stream<Path> s = Files.list(FMLPaths.GAMEDIR.get())) {
                s.filter(p -> p.getFileName().toString().startsWith("hs_err_pid") && p.toFile().lastModified() >= startMs - 2000).forEach(p -> r.crashReports.add(p.getFileName().toString()));
            }
        } catch (Throwable t) { r.unavailable.add("crash report list: " + t); }
    }

    // ---------------------------------------------------------------- status text

    String status() {
        return String.format("Logging%s for %.0f s (%s). Server ticks: %d, frames: %d. Report goes to %s", name.isEmpty() ? "" : " \"" + name + "\"", sec(),
                hasClient && hasServer ? "client + server" : hasServer ? "server" : "client", serverTicks.get(), frames.get(), dir.resolve(baseName + ".txt"));
    }
}
