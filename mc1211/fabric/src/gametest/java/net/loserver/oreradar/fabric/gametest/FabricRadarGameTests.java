package net.loserver.oreradar.fabric.gametest;

import java.util.Collection;

import net.loserver.oreradar.gametest.RadarGameTests;
import net.minecraft.gametest.framework.GameTestGenerator;
import net.minecraft.gametest.framework.TestFunction;

/** Registers the shared GameTests on Fabric (the {@code fabric-gametest} entrypoint). */
public final class FabricRadarGameTests {
    @GameTestGenerator
    public Collection<TestFunction> tests() {
        return RadarGameTests.all();
    }
}
