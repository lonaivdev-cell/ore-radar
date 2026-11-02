package net.loserver.oreradar;

import net.loserver.oreradar.content.RadarContent;
import net.loserver.oreradar.platform.Services;
import net.minecraft.resources.ResourceLocation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Loader-independent entry point; each loader's entrypoint calls {@link #init()} once. */
public final class OreRadar {
    public static final String MOD_ID = "oreradar";
    public static final String MOD_NAME = "Ore Radar";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    private OreRadar() {
    }

    public static void init() {
        LOGGER.info("{} initialising on {}", MOD_NAME, Services.PLATFORM.getPlatformName());
        RadarContent.init();
    }

    public static ResourceLocation id(String path) {
        return new ResourceLocation(MOD_ID, path);
    }
}
