package net.loserver.oreradar.neoforge.gametest;

import net.loserver.oreradar.EnergySettings;
import net.loserver.oreradar.gametest.GameTestPlatform;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.Connection;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.energy.IEnergyStorage;
import net.neoforged.neoforge.network.registration.NetworkRegistry;

/** Forge Energy, through the capabilities another mod's cable or charger would use. */
public final class NeoForgeGameTestPlatform implements GameTestPlatform {
    @Override
    public String energyName() {
        return "FE";
    }

    @Override
    public int energyPerCharge() {
        return EnergySettings.current().fePerCharge();
    }

    @Override
    public long offerBlockEnergy(ServerLevel level, BlockPos pos, Direction side, long amount) {
        IEnergyStorage energy = level.getCapability(Capabilities.EnergyStorage.BLOCK, pos, side);
        return energy == null ? 0 : energy.receiveEnergy((int) amount, false);
    }

    @Override
    public ItemStack offerItemEnergy(ItemStack stack, long amount) {
        IEnergyStorage energy = stack.getCapability(Capabilities.EnergyStorage.ITEM);
        if (energy != null) energy.receiveEnergy((int) amount, false);
        return stack;
    }

    /** NeoForge's own set-up for GameTest connections: a NeoForge client with every channel negotiated. */
    @Override
    public void prepareConnection(Connection connection) {
        NetworkRegistry.configureMockConnection(connection);
    }
}
