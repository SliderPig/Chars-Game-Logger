package com.example.clogger;

import java.util.ArrayList;
import java.util.List;

import com.example.clogger.model.Report;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.Entity;

/** Everything that touches client-only classes. Only called when running as a client. */
final class ClientBridge {
    private ClientBridge() {}

    static Thread renderThread() {
        Minecraft mc = Minecraft.getInstance();
        return mc.isSameThread() ? Thread.currentThread() : null;
    }

    static void attachNet(NetCount n) {
        ClientPacketListener cpl = Minecraft.getInstance().getConnection();
        if (cpl != null) n.attach(cpl.getConnection().channel());
    }

    static Session.ClientSnap scan() {
        Minecraft mc = Minecraft.getInstance();
        ClientLevel lvl = mc.level;
        if (lvl == null) return null;
        Report.Dim d = new Report.Dim();
        d.name = lvl.dimension().location().toString();
        d.loadedChunks = lvl.getChunkSource().getLoadedChunksCount();
        for (Entity e : lvl.entitiesForRendering()) {
            d.entities++;
            d.entityTypes.merge(String.valueOf(BuiltInRegistries.ENTITY_TYPE.getKey(e.getType())), 1, Integer::sum);
        }
        List<String> extra = new ArrayList<>();
        try {
            extra.add("Client view (like F3): chunks " + mc.levelRenderer.getChunkStatistics() + " | entities " + mc.levelRenderer.getEntityStatistics() + " | particles " + mc.particleEngine.countParticles());
        } catch (Throwable ignored) { }
        return new Session.ClientSnap(d, extra);
    }
}
