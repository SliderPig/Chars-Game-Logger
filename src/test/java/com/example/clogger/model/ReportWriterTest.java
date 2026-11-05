package com.example.clogger.model;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;

class ReportWriterTest {
    static Report slowServer() {
        Report r = new Report();
        r.name = "t";
        r.hasServer = true;
        r.seconds = 120;
        Report.Side s = new Report.Side();
        s.tps = Stats.summarize(new double[] {12, 12, 13, 11});
        s.mspt = Stats.summarize(new double[] {70, 80, 90, 60, 55});
        s.tree = new StackTree(50);
        for (int i = 0; i < 100; i++) s.tree.add(List.of("net.minecraft.server.MinecraftServer#tick", "dev.latvian.kubejs.Script#run"));
        s.blamedMod.put("kubejs", 34L);
        s.blamedMod.put("minecraft", 66L);
        s.samples = 100;
        s.spikeCount = 3;
        s.spikeThresholdMs = 150;
        s.spikes.add(new Report.Spike(30, 400, "Server thread", "kubejs", "Script#run"));
        s.busiestChunks.add(new Report.ChunkLoad("minecraft:overworld", 3, 4, 150, 2, "minecraft:zombie 140"));
        r.server = s;
        r.heapMaxMb = 4096;
        r.heapUsedMb = Stats.summarize(new double[] {3900, 3950, 4000});
        r.physicalMb = 8000;
        Report.LogGroup g = new Report.LogGroup();
        g.logger = "KubeJS"; g.mod = "kubejs"; g.errors = 60; g.first = "boom";
        r.logs.add(g);
        return r;
    }

    @Test void findingsRankTheProblems() {
        List<Findings.Finding> f = Findings.compute(slowServer());
        assertFalse(f.isEmpty());
        assertTrue(f.get(0).title().contains("TPS averaged 12"), f.get(0).title());
        assertTrue(f.stream().anyMatch(x -> x.title().contains("kubejs used 34%")));
        assertTrue(f.stream().anyMatch(x -> x.title().contains("150 entities in one chunk")));
        assertTrue(f.stream().anyMatch(x -> x.title().contains("Memory was nearly full")));
        for (int i = 1; i < f.size(); i++) assertTrue(f.get(i - 1).score() >= f.get(i).score());
    }

    @Test void textHasSummaryFirstThenDetails() {
        String t = new ReportWriter(slowServer(), x -> x).text();
        int a = t.indexOf("WHAT'S WRONG"), b = t.indexOf("SERVER DETAILS"), c = t.indexOf("SYSTEM, GAME AND MODS");
        assertTrue(a > 0 && a < b && b < c, t);
        assertTrue(t.contains("TPS averaged 12"));
    }

    @Test void aTinyModIsNotCalledTheCulprit() {
        Report r = slowServer();
        r.server.blamedMod.clear();
        r.server.blamedMod.put("minecraft", 97L);
        r.server.blamedMod.put("clogger", 3L);
        List<Findings.Finding> f = Findings.compute(r);
        assertFalse(f.stream().anyMatch(x -> x.detail().contains("clogger")), f.toString());
        assertTrue(f.get(0).title().contains("TPS averaged 12"));
    }

    @Test void healthyRunSaysSo() {
        Report r = new Report();
        r.hasServer = true;
        r.server = new Report.Side();
        r.server.tps = Stats.summarize(new double[] {20, 20});
        r.server.mspt = Stats.summarize(new double[] {10, 12});
        assertTrue(new ReportWriter(r, null).text().contains("Nothing stood out"));
    }

    @Test void jsonIsEscapedAndBalanced() {
        Report r = slowServer();
        r.name = "a\"b";
        String j = new ReportWriter(r, null).json();
        assertTrue(j.contains("a\\\"b"));
        assertEquals(j.chars().filter(c -> c == '{').count(), j.chars().filter(c -> c == '}').count());
    }
}
