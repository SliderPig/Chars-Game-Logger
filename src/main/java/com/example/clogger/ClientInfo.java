package com.example.clogger;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

import com.mojang.blaze3d.platform.GlUtil;

import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.server.packs.repository.Pack;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.loading.FMLPaths;

/** Client-only parts of the system info. Only loaded on the client. */
final class ClientInfo {
    private ClientInfo() {}

    static List<String> graphics() {
        List<String> l = new ArrayList<>();
        Minecraft mc = Minecraft.getInstance();
        try {
            l.add("GPU: " + GlUtil.getRenderer());
            l.add("GPU vendor: " + GlUtil.getVendor());
            l.add("OpenGL / driver: " + GlUtil.getOpenGLVersion());
        } catch (Throwable t) { l.add("GPU: unavailable because " + t + " (needs the render thread)"); }
        try { l.add("CPU as the game sees it: " + GlUtil.getCpuInfo()); } catch (Throwable ignored) { }
        l.add("Window: " + mc.getWindow().getWidth() + "x" + mc.getWindow().getHeight() + " pixels, GUI scale " + mc.getWindow().getGuiScale());
        for (String id : new String[] {"embeddium", "rubidium", "sodium", "oculus", "iris", "optifine", "canvas"})
            if (ModList.get().isLoaded(id)) l.add("Render mod present: " + id);
        return l;
    }

    static List<String> settings() {
        Options o = Minecraft.getInstance().options;
        List<String> l = new ArrayList<>();
        l.add("Render distance: " + o.renderDistance().get() + " chunks, simulation distance: " + o.simulationDistance().get());
        l.add("Max FPS: " + o.framerateLimit().get() + (o.enableVsync().get() ? ", VSync on" : ", VSync off"));
        l.add("Graphics: " + o.graphicsMode().get() + ", particles: " + o.particles().get() + ", clouds: " + o.cloudStatus().get() + ", smooth lighting: " + o.ambientOcclusion().get());
        l.add("Biome blend: " + o.biomeBlendRadius().get() + ", entity distance: " + o.entityDistanceScaling().get() + ", mipmaps: " + o.mipmapLevels().get() + ", FOV: " + o.fov().get());
        return l;
    }

    static List<String> packs() {
        List<String> l = new ArrayList<>();
        Minecraft mc = Minecraft.getInstance();
        l.add("Resource packs, bottom to top:");
        for (Pack p : mc.getResourcePackRepository().getSelectedPacks()) l.add("   " + p.getId());
        for (String f : new String[] {"iris.properties", "oculus.properties"}) {
            try {
                Path p = FMLPaths.CONFIGDIR.get().resolve(f);
                if (Files.exists(p)) {
                    Properties pr = new Properties();
                    try (var in = Files.newInputStream(p)) { pr.load(in); }
                    l.add("Shader pack (" + f + "): " + pr.getProperty("shaderPack", "(none)") + ", enabled: " + pr.getProperty("enableShaders", "?"));
                }
            } catch (Throwable ignored) { }
        }
        return l;
    }
}
