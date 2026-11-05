package com.example.clogger;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.example.clogger.model.Report;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;

/** Counts entities, block entities and chunks of every dimension. Must run on the server thread. */
final class WorldScan {
    private WorldScan() {}

    record Snap(List<Report.Dim> dims, List<Report.ChunkLoad> chunks, int entities, int blockEntities, int loadedChunks) {}

    private static final class Ck {
        int ent, be;
        final Map<String, Integer> types = new HashMap<>();
    }

    private static Method chunksMethod;
    private static boolean tried;

    @SuppressWarnings("unchecked")
    private static Iterable<ChunkHolder> holders(ChunkMap cm) throws Exception {
        if (!tried) {
            tried = true;
            for (Method m : ChunkMap.class.getDeclaredMethods()) {
                if (m.getParameterCount() == 0 && Iterable.class.isAssignableFrom(m.getReturnType()) && m.getGenericReturnType().getTypeName().contains("ChunkHolder")) {
                    m.setAccessible(true);
                    chunksMethod = m;
                    break;
                }
            }
        }
        if (chunksMethod == null) throw new IllegalStateException("the chunk list method of ChunkMap was not found");
        return (Iterable<ChunkHolder>) chunksMethod.invoke(cm);
    }

    static Snap scan(MinecraftServer server, Target t, Session s) {
        List<Report.Dim> dims = new ArrayList<>();
        List<Report.ChunkLoad> all = new ArrayList<>();
        int ents = 0, bes = 0, chunks = 0;
        for (ServerLevel lvl : server.getAllLevels()) {
            Report.Dim d = new Report.Dim();
            d.name = lvl.dimension().location().toString();
            d.loadedChunks = lvl.getChunkSource().getLoadedChunksCount();
            Map<Long, Ck> per = new HashMap<>();
            for (Entity e : lvl.getAllEntities()) {
                String type = String.valueOf(BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()));
                d.entities++;
                d.entityTypes.merge(type, 1, Integer::sum);
                t.entityClasses.putIfAbsent(e.getClass().getName(), type);
                Ck c = per.computeIfAbsent(ChunkPos.asLong(e.blockPosition()), k -> new Ck());
                c.ent++;
                c.types.merge(type, 1, Integer::sum);
            }
            try {
                for (ChunkHolder h : holders(lvl.getChunkSource().chunkMap)) {
                    LevelChunk lc = h.getTickingChunk();
                    if (lc == null) continue;
                    Map<?, BlockEntity> m = lc.getBlockEntities();
                    if (m.isEmpty()) continue;
                    Ck c = per.computeIfAbsent(lc.getPos().toLong(), k -> new Ck());
                    for (BlockEntity be : m.values()) {
                        String type = String.valueOf(BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(be.getType()));
                        d.blockEntities++;
                        c.be++;
                        d.blockEntityTypes.merge(type, 1, Integer::sum);
                        c.types.merge(type, 1, Integer::sum);
                        t.beClasses.putIfAbsent(be.getClass().getName(), type);
                    }
                }
            } catch (Throwable ex) {
                s.unavailable("block entity counts: " + ex);
            }
            for (var e : per.entrySet()) {
                Ck c = e.getValue();
                if (c.ent + c.be < 20) continue;
                String top = c.types.entrySet().stream().max(Map.Entry.comparingByValue()).map(x -> x.getKey() + " " + x.getValue()).orElse("?");
                long k = e.getKey();
                all.add(new Report.ChunkLoad(d.name, (int) k, (int) (k >> 32), c.ent, c.be, top));
            }
            dims.add(d);
            ents += d.entities;
            bes += d.blockEntities;
            chunks += d.loadedChunks;
        }
        all.sort(Comparator.comparingInt((Report.ChunkLoad c) -> c.entities() + c.blockEntities()).reversed());
        if (all.size() > 10) all = new ArrayList<>(all.subList(0, 10));
        return new Snap(dims, all, ents, bes, chunks);
    }
}
