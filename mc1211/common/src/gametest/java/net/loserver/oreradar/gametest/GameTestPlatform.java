package net.loserver.oreradar.gametest;

import net.loserver.oreradar.platform.Services;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.Connection;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;

/** The loader-specific parts of the GameTests: each energy API is reached its own way, and so is a stand-in's channel set. */
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

    /**
     * Sets up a stand-in player's connection as a modded client's would be once it has joined: on
     * NeoForge, with every channel negotiated, without which its network code refuses our payloads.
     */
    void prepareConnection(Connection connection);
}
