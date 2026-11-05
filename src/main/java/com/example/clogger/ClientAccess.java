package com.example.clogger;

import net.minecraft.client.Minecraft;
import net.minecraft.server.MinecraftServer;

/** Client-only helper kept apart so the dedicated server never loads Minecraft's client class. */
final class ClientAccess {
    private ClientAccess() {}

    static MinecraftServer singleplayerServer() {
        return Minecraft.getInstance().getSingleplayerServer();
    }
}
