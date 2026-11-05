package com.example.clogger;

import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.client.event.RegisterClientCommandsEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;

/** Client-only event wiring (frames, client ticks, client commands). */
final class ClientHooks {
    private ClientHooks() {}

    static void init() {
        MinecraftForge.EVENT_BUS.addListener((RegisterClientCommandsEvent e) -> {
            try { Commands.registerClient(e.getDispatcher()); } catch (Throwable t) { CloggerMod.LOG.error("could not register client /clogger", t); }
        });
        MinecraftForge.EVENT_BUS.addListener((TickEvent.RenderTickEvent e) -> {
            Session s = Session.current;
            if (s == null || !s.hasClient || e.phase != TickEvent.Phase.START) return;
            try { s.renderFrame(); } catch (Throwable t) { s.unavailable("frame hook: " + t); }
        });
        MinecraftForge.EVENT_BUS.addListener((TickEvent.ClientTickEvent e) -> {
            Session s = Session.current;
            if (s == null || !s.hasClient) return;
            try { s.clientTick(e.phase == TickEvent.Phase.START); } catch (Throwable t) { s.unavailable("client tick hook: " + t); }
        });
        MinecraftForge.EVENT_BUS.addListener((ClientPlayerNetworkEvent.LoggingIn e) -> {
            Session s = Session.current;
            if (s == null || !s.hasClient) return;
            s.event("You joined a world or server");
            s.attachClientNet();
        });
        MinecraftForge.EVENT_BUS.addListener((ClientPlayerNetworkEvent.LoggingOut e) -> {
            Session s = Session.current;
            if (s != null && s.hasClient) s.event("You left the world or server");
        });
    }
}
