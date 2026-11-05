package com.example.clogger;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.event.CommandEvent;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.ServerChatEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.entity.player.AdvancementEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.level.BlockEvent;
import net.minecraftforge.event.level.ExplosionEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

/** Game events that work on both the client (singleplayer) and the dedicated server. Every handler does nothing when no run is active. */
public final class Hooks {

    @SubscribeEvent
    public void commands(RegisterCommandsEvent e) {
        try { Commands.registerServer(e.getDispatcher(), e.getCommandSelection() == net.minecraft.commands.Commands.CommandSelection.DEDICATED); } catch (Throwable t) { CloggerMod.LOG.error("could not register /clogger", t); }
    }

    @SubscribeEvent
    public void serverTick(TickEvent.ServerTickEvent e) {
        Session s = Session.current;
        if (s == null || !s.hasServer) return;
        try { s.serverTick(e.phase == TickEvent.Phase.START); } catch (Throwable t) { s.unavailable("server tick hook: " + t); }
    }

    @SubscribeEvent
    public void login(PlayerEvent.PlayerLoggedInEvent e) {
        Session s = Session.current;
        if (s == null || !s.hasServer || !(e.getEntity() instanceof ServerPlayer p)) return;
        try {
            s.event("Joined: " + p.getGameProfile().getName() + " (" + p.level().dimension().location() + ")");
            s.attachPlayer(p);
        } catch (Throwable t) { s.unavailable("login hook: " + t); }
    }

    @SubscribeEvent
    public void logout(PlayerEvent.PlayerLoggedOutEvent e) {
        Session s = Session.current;
        if (s == null || !s.hasServer || !(e.getEntity() instanceof ServerPlayer p)) return;
        s.event("Left: " + p.getGameProfile().getName());
    }

    @SubscribeEvent
    public void dimension(PlayerEvent.PlayerChangedDimensionEvent e) {
        Session s = Session.current;
        if (s == null || !s.hasServer) return;
        s.event(e.getEntity().getGameProfile().getName() + " went " + e.getFrom().location() + " -> " + e.getTo().location());
    }

    @SubscribeEvent
    public void death(LivingDeathEvent e) {
        Session s = Session.current;
        if (s == null || !s.hasServer || e.getEntity().level().isClientSide) return;
        if (e.getEntity() instanceof Player p) s.event("Death: " + p.getGameProfile().getName() + " (" + e.getSource().getMsgId() + ") at " + p.blockPosition().toShortString());
        else s.count("mobs that died");
    }

    @SubscribeEvent
    public void command(CommandEvent e) {
        Session s = Session.current;
        if (s == null || !s.hasServer) return;
        try {
            String line = e.getParseResults().getReader().getString();
            if (line.startsWith("clogger") || line.startsWith("cloggerserver")) return;
            var src = e.getParseResults().getContext().getSource();
            s.count("commands run");
            if (src.getEntity() instanceof Player || src.getTextName().equals("Server")) // command blocks and functions are only counted
                s.event("commands", 300, "Command by " + src.getTextName() + ": /" + (line.length() > 120 ? line.substring(0, 120) + "..." : line));
        } catch (Throwable ignored) { }
    }

    @SubscribeEvent
    public void chat(ServerChatEvent e) {
        Session s = Session.current;
        if (s == null || !s.hasServer) return;
        String t = e.getRawText();
        s.count("chat messages");
        s.event("chat", 300, "Chat: <" + e.getUsername() + "> " + (t.length() > 100 ? t.substring(0, 100) + "..." : t));
    }

    @SubscribeEvent
    public void advancement(AdvancementEvent.AdvancementEarnEvent e) {
        Session s = Session.current;
        if (s == null || !s.hasServer) return;
        String id = e.getAdvancement().getId().toString();
        if (id.contains("/root") || id.startsWith("minecraft:recipes/")) return;
        s.count("advancements earned");
        s.event("advancements", 200, "Advancement: " + e.getEntity().getGameProfile().getName() + " got " + id);
    }

    @SubscribeEvent
    public void blockBreak(BlockEvent.BreakEvent e) {
        Session s = Session.current;
        if (s != null && s.hasServer) s.count("blocks broken by players");
    }

    @SubscribeEvent
    public void pickup(PlayerEvent.ItemPickupEvent e) {
        Session s = Session.current;
        if (s != null && s.hasServer && !e.getEntity().level().isClientSide) s.count("items picked up by players");
    }

    @SubscribeEvent
    public void joined(EntityJoinLevelEvent e) {
        Session s = Session.current;
        if (s == null || !s.hasServer) return;
        Entity en = e.getEntity();
        if (e.getLevel().isClientSide) return;
        s.spawned(String.valueOf(net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getKey(en.getType())));
    }

    @SubscribeEvent
    public void explosion(ExplosionEvent.Start e) {
        Session s = Session.current;
        if (s != null && s.hasServer && !e.getLevel().isClientSide) s.count("explosions");
    }

    @SubscribeEvent
    public void stopping(ServerStoppingEvent e) {
        Session s = Session.current;
        if (s == null || !s.hasServer) return;
        s.event("The server is stopping");
        if (!s.hasClient) { // dedicated server: write the report before the process goes
            java.nio.file.Path p = Session.stop();
            CloggerMod.LOG.info("clogger report written: {}", p);
        }
    }
}
