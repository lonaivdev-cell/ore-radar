package net.loserver.oreradar.fabric.gametest;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.loserver.oreradar.gametest.ScanBenchmark;

/** The GameTest mod on Fabric: loaded only by the gametest and benchServer runs. */
public final class OreRadarFabricGameTestMod implements ModInitializer {
    @Override
    public void onInitialize() {
        if (ScanBenchmark.enabled()) {
            ServerLifecycleEvents.SERVER_STARTED.register(ScanBenchmark::run);
        }
    }
}
