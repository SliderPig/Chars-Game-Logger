package com.example.clogger;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import net.minecraftforge.fml.ModList;

/**
 * Works out which mod a class belongs to, by reading the class lists of every mod jar once
 * (package -> mod id). Everything else is sorted into base buckets (minecraft, forge, java, libraries).
 */
public final class ModMap {
    private final Map<String, String> packageToMod = new HashMap<>();
    private final Set<String> modIds = new HashSet<>();
    private final Map<String, String> cache = new ConcurrentHashMap<>();
    public final List<String> notes = new ArrayList<>();
    private volatile boolean ready;

    public boolean ready() { return ready; }

    public Set<String> modIds() { return modIds; }

    public void build() {
        try {
            ModList list = ModList.get();
            if (list != null) {
                for (var info : list.getModFiles()) {
                    try {
                        String id = info.getMods().isEmpty() ? null : info.getMods().get(0).getModId();
                        for (var m : info.getMods()) modIds.add(m.getModId());
                        if (id == null) continue;
                        Path p = info.getFile().getFilePath();
                        scan(p, id);
                    } catch (Throwable t) {
                        notes.add("could not read a mod file: " + t);
                    }
                }
            }
        } catch (Throwable t) {
            notes.add("mod list unavailable: " + t);
        }
        try { addBukkitPlugins(); } catch (Throwable ignored) { }
        ready = true;
    }

    private void scan(Path p, String id) throws Exception {
        if (p == null) return;
        if (Files.isDirectory(p)) {
            try (Stream<Path> s = Files.walk(p)) {
                s.filter(x -> x.toString().endsWith(".class")).forEach(x -> {
                    Path rel = p.relativize(x).getParent();
                    if (rel != null) packageToMod.putIfAbsent(rel.toString().replace(File.separatorChar, '.'), id);
                });
            }
        } else {
            try (ZipFile z = new ZipFile(p.toFile())) {
                var en = z.entries();
                while (en.hasMoreElements()) {
                    ZipEntry e = en.nextElement();
                    String n = e.getName();
                    if (!n.endsWith(".class") || n.startsWith("META-INF/")) continue;
                    int slash = n.lastIndexOf('/');
                    if (slash > 0) packageToMod.putIfAbsent(n.substring(0, slash).replace('/', '.'), id);
                }
            }
        }
    }

    private void addBukkitPlugins() throws Exception {
        Class<?> bukkit = Class.forName("org.bukkit.Bukkit");
        Object pm = bukkit.getMethod("getPluginManager").invoke(null);
        if (pm == null) return;
        Object[] plugins = (Object[]) pm.getClass().getMethod("getPlugins").invoke(pm);
        for (Object pl : plugins) {
            try {
                Object desc = pl.getClass().getMethod("getDescription").invoke(pl);
                String name = String.valueOf(desc.getClass().getMethod("getName").invoke(desc));
                var loc = pl.getClass().getProtectionDomain().getCodeSource().getLocation();
                scan(Path.of(loc.toURI()), "plugin:" + name);
            } catch (Throwable ignored) { }
        }
    }

    /** Mod bucket for a class name, plus the method name (for mixin handlers). */
    public String classify(String cls, String method) {
        String base = cache.get(cls);
        if (base == null) {
            base = classifyClass(cls);
            cache.put(cls, base);
        }
        if (method != null && method.indexOf('$') > 0 && !ready_isModBucket(base)) {
            // mixin handlers are called handler$abc000$modid$name
            String[] parts = method.split("\\$");
            for (int i = 1; i < parts.length; i++) {
                String p = parts[i].toLowerCase(Locale.ROOT);
                if (p.length() > 2 && modIds.contains(p)) return p;
            }
        }
        return base;
    }

    private boolean ready_isModBucket(String b) { return modIds.contains(b) || b.startsWith("plugin:"); }

    private String classifyClass(String cls) {
        if (cls.startsWith("java.") || cls.startsWith("javax.") || cls.startsWith("jdk.") || cls.startsWith("sun.") || cls.startsWith("com.sun.")) return "(java)";
        if (cls.startsWith("net.minecraftforge.") || cls.startsWith("cpw.mods.")) return "forge";
        if (cls.startsWith("net.minecraft.") || cls.startsWith("com.mojang.")) return "minecraft";
        if (cls.startsWith("io.izzel.arclight.")) return "arclight";
        if (cls.startsWith("org.bukkit.") || cls.startsWith("org.spigotmc.") || cls.startsWith("io.papermc.") || cls.startsWith("com.destroystokyo.") || cls.startsWith("org.craftbukkit.") || cls.startsWith("org.bukkit")) return "(bukkit)";
        String pkg = cls;
        while (true) {
            int dot = pkg.lastIndexOf('.');
            if (dot < 0) break;
            pkg = pkg.substring(0, dot);
            String mod = packageToMod.get(pkg);
            if (mod != null) return mod;
        }
        for (String lib : LIBS) if (cls.startsWith(lib)) return "(libraries)";
        return "(unknown)";
    }

    private static final String[] LIBS = {"io.netty.", "it.unimi.", "com.google.", "org.apache.", "org.lwjgl.", "org.slf4j.", "org.objectweb.", "org.spongepowered.", "com.electronwill.",
            "org.joml.", "com.ibm.", "oshi.", "org.jline.", "com.github.", "org.checkerframework.", "org.antlr.", "org.yaml.", "org.xml.", "org.w3c.", "com.sun.", "net.java.", "org.openjdk.", "org.codehaus.", "org.jetbrains.", "kotlin.", "scala.", "net.sf.", "com.typesafe.", "javassist.", "org.reflections.", "org.eclipse."};

    /** Short readable form of "pkg.Class#method" with the mod tagged on. */
    public String pretty(String frame) {
        int h = frame.indexOf('#');
        String cls = h < 0 ? frame : frame.substring(0, h);
        String method = h < 0 ? "" : frame.substring(h + 1);
        String simple = cls.substring(cls.lastIndexOf('.') + 1);
        String mod = classify(cls, method);
        String tag = mod.equals("minecraft") || mod.equals("(java)") || mod.equals("(libraries)") ? "" : "  [" + mod + "]";
        return simple + (method.isEmpty() ? "" : "." + Names.method(method)) + tag;
    }
}
