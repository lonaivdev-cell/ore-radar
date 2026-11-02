package net.loserver.oreradar.forge.gametest;

import net.loserver.oreradar.EnergySettings;
import net.loserver.oreradar.gametest.GameTestPlatform;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraftforge.common.capabilities.ForgeCapabilities;

/** Forge Energy, through the capabilities another mod's cable or charger would use. */
public final class ForgeGameTestPlatform implements GameTestPlatform {
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
        BlockEntity blockEntity = level.getBlockEntity(pos);
        if (blockEntity == null) return 0;
        return blockEntity.getCapability(ForgeCapabilities.ENERGY, side)
                .map(energy -> energy.receiveEnergy((int) amount, false))
                .orElse(0);
    }

    @Override
    public ItemStack offerItemEnergy(ItemStack stack, long amount) {
        stack.getCapability(ForgeCapabilities.ENERGY).ifPresent(energy -> energy.receiveEnergy((int) amount, false));
        return stack;
    }
}
