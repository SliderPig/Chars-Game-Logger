package com.example.clogger;

import java.nio.file.Path;
import java.util.function.Supplier;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;

import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.MinecraftServer;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.loading.FMLEnvironment;

/** /clogger start [name], stop, status. The client version measures your game; the server version (op) measures the server. */
final class Commands {
    private Commands() {}

    /** The server command: on a dedicated server it is /clogger, everywhere it is also /cloggerserver. */
    static void registerServer(CommandDispatcher<CommandSourceStack> d, boolean dedicated) {
        if (dedicated) d.register(tree("clogger", false));
        d.register(tree("cloggerserver", false));
    }

    static void registerClient(CommandDispatcher<CommandSourceStack> d) {
        d.register(tree("clogger", true));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> tree(String root, boolean client) {
        LiteralArgumentBuilder<CommandSourceStack> b = net.minecraft.commands.Commands.literal(root);
        if (!client) b.requires(s -> s.hasPermission(2));
        return b.executes(c -> status(c, client))
                .then(net.minecraft.commands.Commands.literal("start").executes(c -> start(c, "", client))
                        .then(net.minecraft.commands.Commands.argument("name", StringArgumentType.greedyString()).executes(c -> start(c, StringArgumentType.getString(c, "name"), client))))
                .then(net.minecraft.commands.Commands.literal("stop").executes(c -> stop(c, client)))
                .then(net.minecraft.commands.Commands.literal("status").executes(c -> status(c, client)));
    }

    private static void say(CommandContext<CommandSourceStack> c, Component m) {
        Supplier<Component> sup = () -> m;
        c.getSource().sendSuccess(sup, false);
    }

    /** The command word the player actually typed, so the hints match. */
    private static String root(CommandContext<CommandSourceStack> c, boolean client) {
        String in = c.getInput();
        return client || in.startsWith("clogger ") || in.equals("clogger") ? "clogger" : "cloggerserver";
    }

    private static int start(CommandContext<CommandSourceStack> c, String name, boolean client) {
        try {
            MinecraftServer server = client ? ClientAccess.singleplayerServer() : c.getSource().getServer();
            Session s = Session.start(name.trim(), client, server != null, server);
            if (s == null) {
                say(c, Component.literal("Already logging. Use /clogger stop first.").withStyle(ChatFormatting.YELLOW));
                return 0;
            }
            String what = client ? (server != null ? "your game and the built-in server" : "your game (client)") : "the server";
            say(c, Component.literal("Logging " + what + " now. Run /" + root(c, client) + " stop when you are done.").withStyle(ChatFormatting.LIGHT_PURPLE));
            return 1;
        } catch (Throwable t) {
            CloggerMod.LOG.error("clogger start failed", t);
            say(c, Component.literal("Could not start: " + t).withStyle(ChatFormatting.RED));
            return 0;
        }
    }

    private static int stop(CommandContext<CommandSourceStack> c, boolean client) {
        try {
            Path p = Session.stop();
            if (p == null) {
                say(c, Component.literal("Nothing is being logged.").withStyle(ChatFormatting.YELLOW));
                return 0;
            }
            String abs = p.toAbsolutePath().toString();
            MutableComponent link = Component.literal(abs).withStyle(st -> st.withColor(ChatFormatting.AQUA).withUnderlined(true)
                    .withClickEvent(new ClickEvent(FMLEnvironment.dist == Dist.CLIENT ? ClickEvent.Action.OPEN_FILE : ClickEvent.Action.COPY_TO_CLIPBOARD, abs)));
            say(c, Component.literal("Report written: ").withStyle(ChatFormatting.LIGHT_PURPLE).append(link));
            return 1;
        } catch (Throwable t) {
            CloggerMod.LOG.error("clogger stop failed", t);
            say(c, Component.literal("Could not stop cleanly: " + t).withStyle(ChatFormatting.RED));
            return 0;
        }
    }

    private static int status(CommandContext<CommandSourceStack> c, boolean client) {
        Session s = Session.current;
        say(c, Component.literal(s == null ? "Not logging. /" + root(c, client) + " start [name] begins a run." : s.status()).withStyle(ChatFormatting.LIGHT_PURPLE));
        return 1;
    }
}
