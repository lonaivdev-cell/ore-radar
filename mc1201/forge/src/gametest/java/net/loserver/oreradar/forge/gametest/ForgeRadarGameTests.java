package net.loserver.oreradar.forge.gametest;

import java.util.Collection;

import net.loserver.oreradar.OreRadar;
import net.loserver.oreradar.gametest.RadarGameTests;
import net.minecraft.gametest.framework.GameTestGenerator;
import net.minecraft.gametest.framework.TestFunction;
import net.minecraftforge.gametest.GameTestHolder;

/**
 * Registers the shared GameTests on Forge. Their structures are {@code oreradar:*}, so they run
 * when {@code forge.enabledGameTestNamespaces} includes {@code oreradar} (the build sets it).
 */
@GameTestHolder(OreRadar.MOD_ID)
public final class ForgeRadarGameTests {
    private ForgeRadarGameTests() {}

    @GameTestGenerator
    public static Collection<TestFunction> tests() {
        return RadarGameTests.all();
    }
}
