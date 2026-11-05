package com.example.clogger;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.LongAdder;

import io.netty.channel.Channel;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundBundlePacket;
import net.minecraft.network.protocol.game.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.game.ServerboundCustomPayloadPacket;

/**
 * Counts packets (not bytes) going through connections, by packet type. A passive netty handler put in front of the
 * connection's own handler; it never changes or delays a packet.
 */
final class NetCount {
    static final String NAME = "clogger_count";

    final Map<String, LongAdder> in = new ConcurrentHashMap<>(), out = new ConcurrentHashMap<>();
    final LongAdder totalIn = new LongAdder(), totalOut = new LongAdder();
    private final List<Channel> channels = new CopyOnWriteArrayList<>();

    private final class Handler extends ChannelDuplexHandler {
        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
            try { count(msg, in, totalIn); } catch (Throwable ignored) { }
            super.channelRead(ctx, msg);
        }

        @Override
        public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
            try { count(msg, out, totalOut); } catch (Throwable ignored) { }
            super.write(ctx, msg, promise);
        }
    }

    private void count(Object msg, Map<String, LongAdder> m, LongAdder total) {
        if (msg instanceof ClientboundBundlePacket b) {
            for (Packet<?> p : b.subPackets()) count(p, m, total);
            return;
        }
        if (!(msg instanceof Packet<?> p)) return;
        total.increment();
        String name;
        if (p instanceof ClientboundCustomPayloadPacket c) name = "mod message " + c.getIdentifier();
        else if (p instanceof ServerboundCustomPayloadPacket c) name = "mod message " + c.getIdentifier();
        else name = p.getClass().getSimpleName();
        if (m.size() < 400 || m.containsKey(name)) m.computeIfAbsent(name, k -> new LongAdder()).increment();
    }

    void attach(Channel ch) {
        if (ch == null || channels.contains(ch)) return;
        channels.add(ch);
        ch.eventLoop().execute(() -> {
            try {
                if (ch.pipeline().get(NAME) == null && ch.pipeline().get("packet_handler") != null) ch.pipeline().addBefore("packet_handler", NAME, new Handler());
            } catch (Throwable ignored) { }
        });
    }

    void detachAll() {
        for (Channel ch : channels) {
            try {
                ch.eventLoop().execute(() -> {
                    try { if (ch.pipeline().get(NAME) != null) ch.pipeline().remove(NAME); } catch (Throwable ignored) { }
                });
            } catch (Throwable ignored) { }
        }
        channels.clear();
    }

    int connections() { return channels.size(); }
}
