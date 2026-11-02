package net.loserver.oreradar.forge.gametest;

import net.loserver.oreradar.gametest.ScanBenchmark;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * The GameTest mod on Forge: loaded only by the gameTestServer and benchServer runs. The tests
 * themselves are found through {@link ForgeRadarGameTests}' {@code @GameTestHolder}.
 */
@Mod("oreradar_gametest")
public final class OreRadarGameTestMod {
    public OreRadarGameTestMod() {
        if (ScanBenchmark.enabled()) {
            MinecraftForge.EVENT_BUS.addListener((ServerStartedEvent event) -> ScanBenchmark.run(event.getServer()));
        }
    }
}
