package net.loserver.oreradar.neoforge.gametest;

import net.loserver.oreradar.gametest.ScanBenchmark;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerStartedEvent;

/**
 * The GameTest mod on NeoForge: loaded only by the gameTestServer and benchServer runs. The tests
 * themselves are found through {@link NeoForgeRadarGameTests}' {@code @GameTestHolder}.
 */
@Mod("oreradar_gametest")
public final class OreRadarGameTestMod {
    public OreRadarGameTestMod() {
        if (ScanBenchmark.enabled()) {
            NeoForge.EVENT_BUS.addListener((ServerStartedEvent event) -> ScanBenchmark.run(event.getServer()));
        }
    }
}
