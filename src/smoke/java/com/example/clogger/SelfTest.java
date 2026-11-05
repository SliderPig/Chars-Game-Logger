package com.example.clogger;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.EventPriority;

/**
 * Only compiled with -Psmoke. Makes a flat world, runs /clogger start, fills the world with mobs and hoppers, makes one slow
 * tick on purpose, runs /clogger stop and quits. Used by tools/run_real_client.py.
 */
public final class SelfTest {
    private static int ticks = 1, inWorld = -1, spawnLeft = 120;
    private static final int RUN_TICKS = 20 * 60;
    private static boolean slowDone;

    public static void init() {
        MinecraftForge.EVENT_BUS.addListener((TickEvent.ClientTickEvent e) -> {
            if (e.phase != TickEvent.Phase.END) return;
            try { tick(); } catch (Throwable t) { CloggerMod.LOG.error("[SelfTest] failed", t); Minecraft.getInstance().stop(); }
        });
        // runs after the logger's own START hook, so the sleep counts as part of the tick
        MinecraftForge.EVENT_BUS.addListener(EventPriority.LOWEST, (TickEvent.ServerTickEvent e) -> {
            if (e.phase == TickEvent.Phase.START && inWorld == 700 && !slowDone) {
                slowDone = true;
                slowTick();
            }
        });
    }

    private static void slowTick() {
        try { Thread.sleep(400); } catch (InterruptedException ignored) { }
    }

    private static void cmd(String c) {
        Minecraft.getInstance().player.connection.sendCommand(c);
    }

    private static void tick() {
        Minecraft mc = Minecraft.getInstance();
        ticks++;
        if (mc.level == null || mc.player == null) {
            inWorld = -1;
            if (mc.screen instanceof TitleScreen && ticks > 200 && mc.getSingleplayerServer() == null && ticks % 100 == 0) {
                mc.createWorldOpenFlows().createFreshLevel("smoke", new LevelSettings("smoke", GameType.CREATIVE, false, Difficulty.NORMAL, true, new GameRules(), WorldDataConfiguration.DEFAULT),
                        new WorldOptions(1L, false, false), access -> access.registryOrThrow(Registries.WORLD_PRESET).getHolderOrThrow(WorldPresets.FLAT).value().createWorldDimensions());
            }
            if (ticks > 3000) { CloggerMod.LOG.error("[SelfTest] never reached a world"); mc.stop(); }
            return;
        }
        inWorld++;
        if (inWorld == 100) cmd("gamerule doMobSpawning false");
        if (inWorld == 120) cmd("clogger start selftest");
        if (inWorld == 130) cmd("clogger status");
        if (inWorld > 140 && spawnLeft > 0 && inWorld % 2 == 0) { cmd("summon minecraft:cow ~" + (spawnLeft % 6) + " ~ ~" + (spawnLeft % 5)); spawnLeft--; }
        if (inWorld == 420) cmd("fill ~2 ~ ~2 ~14 ~ ~4 minecraft:hopper");
        if (inWorld == 440) mc.player.connection.sendChat("hello from the self test");
        if (inWorld == 460) cmd("tp @s ~ ~ ~40");
        if (inWorld == 480) cmd("tp @s ~ ~ ~-40");
        if (inWorld == 500) cmd("say " + "a log line");
        if (inWorld == 520) {
            var lg = org.apache.logging.log4j.LogManager.getLogger("SelfTestLogger");
            for (int i = 0; i < 60; i++) lg.warn("spammy warning number {}", i);
            lg.error("selftest error", new RuntimeException("boom"));
        }
        if (inWorld == 120 + 900) CloggerMod.LOG.info("[SelfTest] rolling partial file exists: {}", Session.current != null && Session.current.partialExists());
        if (inWorld == 120 + RUN_TICKS) cmd("clogger stop");
        if (inWorld == 120 + RUN_TICKS + 60) { CloggerMod.LOG.info("[SelfTest] done"); mc.stop(); }
    }
}
