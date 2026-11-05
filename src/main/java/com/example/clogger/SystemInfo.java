package com.example.clogger;

import java.io.File;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.RuntimeMXBean;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Properties;
import java.util.function.Supplier;

import com.example.clogger.model.Report;

import net.minecraft.SharedConstants;
import net.minecraft.server.MinecraftServer;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.loading.FMLEnvironment;
import net.minecraftforge.fml.loading.FMLLoader;
import net.minecraftforge.fml.loading.FMLPaths;

/** Writes the "what is this computer / game / mod list" part of the report. Every piece can fail alone. */
final class SystemInfo {
    private SystemInfo() {}

    static void add(Report r, boolean client, MinecraftServer server) {
        block(r, "Game", () -> game(client, server));
        block(r, "Computer", SystemInfo::computer);
        block(r, "Java", SystemInfo::java);
        if (client) {
            block(r, "Graphics", ClientInfo::graphics);
            block(r, "Client settings", ClientInfo::settings);
            block(r, "Resource packs and shaders", ClientInfo::packs);
        }
        if (server != null) block(r, "Server settings", () -> serverSettings(server));
        block(r, "Bukkit / Arclight", () -> { try { return bukkit(); } catch (Exception e) { throw new RuntimeException(e); } });
        block(r, "Mods", SystemInfo::mods);
    }

    private static void block(Report r, String title, Supplier<List<String>> s) {
        try {
            List<String> l = s.get();
            if (l != null && !l.isEmpty()) r.system.add(new Report.Block(title, l));
        } catch (Throwable t) {
            r.system.add(new Report.Block(title, List.of("unavailable because " + t)));
        }
    }

    private static List<String> game(boolean client, MinecraftServer server) {
        List<String> l = new ArrayList<>();
        l.add("Minecraft " + SharedConstants.getCurrentVersion().getName());
        try { l.add("Forge " + FMLLoader.versionInfo().forgeVersion()); } catch (Throwable t) { l.add("Forge: unavailable because " + t); }
        l.add("Running as: " + (FMLEnvironment.dist.isClient() ? "client" + (server != null ? " with built-in server (singleplayer)" : "") : "dedicated server"));
        l.add("Game folder: " + FMLPaths.GAMEDIR.get().toAbsolutePath());
        try { l.add("Mods loaded: " + ModList.get().size()); } catch (Throwable ignored) { }
        if (server != null) {
            l.add("Server software: " + server.getServerModName() + " " + server.getServerVersion());
            l.add("Players online: " + server.getPlayerCount() + " of " + server.getMaxPlayers());
        }
        return l;
    }

    private static List<String> computer() {
        List<String> l = new ArrayList<>();
        l.add("OS: " + System.getProperty("os.name") + " " + System.getProperty("os.version") + " (" + System.getProperty("os.arch") + ")");
        l.add("CPU: " + cpuName() + ", " + Runtime.getRuntime().availableProcessors() + " logical cores");
        try {
            var os = (com.sun.management.OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
            l.add("Memory: " + os.getTotalMemorySize() / 1048576 + " MB total, " + os.getFreeMemorySize() / 1048576 + " MB free now");
            l.add("Swap/page file: " + os.getTotalSwapSpaceSize() / 1048576 + " MB total, " + os.getFreeSwapSpaceSize() / 1048576 + " MB free");
        } catch (Throwable t) { l.add("Memory: unavailable because " + t); }
        try { l.add("Disk free in game folder: " + FMLPaths.GAMEDIR.get().toFile().getUsableSpace() / 1048576 + " MB"); } catch (Throwable ignored) { }
        return l;
    }

    static String cpuName() {
        try {
            if (System.getProperty("os.name").toLowerCase().contains("win")) {
                Process p = new ProcessBuilder("reg", "query", "HKLM\\HARDWARE\\DESCRIPTION\\System\\CentralProcessor\\0", "/v", "ProcessorNameString").redirectErrorStream(true).start();
                String out = new String(p.getInputStream().readAllBytes());
                p.waitFor(3, java.util.concurrent.TimeUnit.SECONDS);
                int i = out.indexOf("REG_SZ");
                if (i > 0) return out.substring(i + 6).trim();
            }
        } catch (Throwable ignored) { }
        String env = System.getenv("PROCESSOR_IDENTIFIER");
        if (env != null) return env;
        try {
            Path p = Path.of("/proc/cpuinfo");
            if (Files.exists(p)) for (String line : Files.readAllLines(p)) if (line.startsWith("model name")) return line.substring(line.indexOf(':') + 1).trim();
        } catch (Throwable ignored) { }
        return "unknown";
    }

    private static List<String> java() {
        List<String> l = new ArrayList<>();
        RuntimeMXBean rt = ManagementFactory.getRuntimeMXBean();
        l.add("Java " + System.getProperty("java.version") + " (" + System.getProperty("java.vendor") + "), VM " + rt.getVmName());
        l.add("Max memory for the game (-Xmx): " + Runtime.getRuntime().maxMemory() / 1048576 + " MB");
        StringBuilder gcs = new StringBuilder();
        for (GarbageCollectorMXBean g : ManagementFactory.getGarbageCollectorMXBeans()) gcs.append(gcs.length() > 0 ? ", " : "").append(g.getName());
        l.add("Garbage collectors: " + gcs);
        l.add("Java flags:");
        for (String a : rt.getInputArguments()) if (!a.startsWith("-Dminecraft.launcher") && a.length() < 200) l.add("   " + a);
        return l;
    }

    private static List<String> serverSettings(MinecraftServer s) {
        List<String> l = new ArrayList<>();
        l.add("View distance: " + s.getPlayerList().getViewDistance() + ", simulation distance: " + s.getPlayerList().getSimulationDistance());
        l.add("Difficulty: " + s.getWorldData().getDifficulty() + ", hardcore: " + s.getWorldData().isHardcore());
        try {
            File f = new File("server.properties");
            if (f.exists()) {
                Properties p = new Properties();
                try (var in = Files.newInputStream(f.toPath())) { p.load(in); }
                for (String k : new String[] {"view-distance", "simulation-distance", "max-players", "network-compression-threshold", "max-tick-time", "sync-chunk-writes", "entity-broadcast-range-percentage", "level-name"})
                    if (p.getProperty(k) != null) l.add("server.properties " + k + " = " + p.getProperty(k));
            }
        } catch (Throwable ignored) { }
        return l;
    }

    private static List<String> bukkit() throws Exception {
        Class<?> bukkit;
        try { bukkit = Class.forName("org.bukkit.Bukkit"); } catch (ClassNotFoundException e) { return List.of("Not a Bukkit/Arclight server (no plugin system)."); }
        List<String> l = new ArrayList<>();
        Object srv = bukkit.getMethod("getServer").invoke(null);
        if (srv == null) return List.of("Bukkit classes exist but the Bukkit server is not up yet.");
        l.add("Server: " + srv.getClass().getMethod("getName").invoke(srv) + " " + srv.getClass().getMethod("getVersion").invoke(srv));
        Object pm = srv.getClass().getMethod("getPluginManager").invoke(srv);
        Object[] plugins = (Object[]) pm.getClass().getMethod("getPlugins").invoke(pm);
        l.add("Plugins (" + plugins.length + "):");
        for (Object pl : plugins) {
            Object d = pl.getClass().getMethod("getDescription").invoke(pl);
            boolean on = (boolean) pl.getClass().getMethod("isEnabled").invoke(pl);
            l.add("   " + d.getClass().getMethod("getName").invoke(d) + " " + d.getClass().getMethod("getVersion").invoke(d) + (on ? "" : "  (disabled)"));
        }
        return l;
    }

    private static List<String> mods() {
        List<String> l = new ArrayList<>();
        var infos = new ArrayList<>(ModList.get().getMods());
        infos.sort(Comparator.comparing(m -> m.getModId()));
        for (var m : infos)
            l.add(String.format("%-28s %-18s %s", m.getModId(), m.getVersion(), m.getOwningFile().getFile().getFileName()));
        return l;
    }
}
