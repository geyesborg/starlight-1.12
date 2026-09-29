package com.github.starlight;

import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.event.FMLPreInitializationEvent;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Starlight lighting engine (Spottedleaf, from Moonrise; GPL-3.0) ported to
 * Minecraft 1.12.2 on Cleanroom.
 */
@Mod(modid = Starlight.MODID, name = Starlight.NAME, version = Starlight.VERSION)
public class Starlight {

    public static final String MODID = "starlight";
    public static final String NAME = "Starlight (1.12.2 port)";
    public static final String VERSION = "0.1.0";

    public static final Logger LOGGER = LogManager.getLogger(NAME);

    @Mod.EventHandler
    public void preInit(FMLPreInitializationEvent event) {
        LOGGER.info("{} {} loaded", NAME, VERSION);
    }
}
