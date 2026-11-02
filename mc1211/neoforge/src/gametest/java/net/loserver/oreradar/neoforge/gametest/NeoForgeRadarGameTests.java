package net.loserver.oreradar.neoforge.gametest;

import java.util.Collection;

import net.loserver.oreradar.OreRadar;
import net.loserver.oreradar.gametest.RadarGameTests;
import net.minecraft.gametest.framework.GameTestGenerator;
import net.minecraft.gametest.framework.TestFunction;
import net.neoforged.neoforge.gametest.GameTestHolder;

/**
 * Registers the shared GameTests on NeoForge. Their structures are {@code oreradar:*}, so they run
 * when {@code neoforge.enabledGameTestNamespaces} includes {@code oreradar} (the build sets it).
 */
@GameTestHolder(OreRadar.MOD_ID)
public final class NeoForgeRadarGameTests {
    private NeoForgeRadarGameTests() {}

    @GameTestGenerator
    public static Collection<TestFunction> tests() {
        return RadarGameTests.all();
    }
}
