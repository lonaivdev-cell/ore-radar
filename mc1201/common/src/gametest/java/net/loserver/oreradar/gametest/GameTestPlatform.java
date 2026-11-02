package net.loserver.oreradar.gametest;

import net.loserver.oreradar.platform.Services;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;

/** The loader-specific parts of the GameTests: each energy API is reached its own way. */
public interface GameTestPlatform {
    GameTestPlatform INSTANCE = Services.load(GameTestPlatform.class);

    /** "FE" or "TR Energy", for messages. */
    String energyName();

    /** This loader's energy per unit of charge (fePerCharge or trEnergyPerCharge). */
    int energyPerCharge();

    /** Offers energy to the block's energy API on that side, as a cable would, and returns what it took. */
    long offerBlockEnergy(ServerLevel level, BlockPos pos, Direction side, long amount);

    /**
     * Offers energy to an item's energy API, as another mod's charger would. Returns the stack
     * afterwards: on Fabric the transaction swaps it for a new one.
     */
    ItemStack offerItemEnergy(ItemStack stack, long amount);
}
