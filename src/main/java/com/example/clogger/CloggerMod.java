package com.example.clogger;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.loading.FMLEnvironment;

/**
 * Char's Game Logger: /clogger start, /clogger stop writes a report about how the game ran.
 * Does nothing at all until you start it.
 */
@Mod(CloggerMod.MODID)
public class CloggerMod {
    public static final String MODID = "clogger";
    public static final Logger LOG = LogManager.getLogger("clogger");

    public CloggerMod() {
        MinecraftForge.EVENT_BUS.register(new Hooks());
        if (FMLEnvironment.dist == Dist.CLIENT) ClientHooks.init();
    }
}
