package net.loserver.oreradar.fabric.gametest;

import net.fabricmc.fabric.api.transfer.v1.context.ContainerItemContext;
import net.fabricmc.fabric.api.transfer.v1.item.InventoryStorage;
import net.fabricmc.fabric.api.transfer.v1.transaction.Transaction;
import net.loserver.oreradar.EnergySettings;
import net.loserver.oreradar.gametest.GameTestPlatform;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.Connection;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;
import team.reborn.energy.api.EnergyStorage;

/** Team Reborn Energy, through the lookups another mod's cable or charger would use. */
public final class FabricGameTestPlatform implements GameTestPlatform {
    @Override
    public String energyName() {
        return "TR Energy";
    }

    @Override
    public int energyPerCharge() {
        return EnergySettings.current().trEnergyPerCharge();
    }

    @Override
    public long offerBlockEnergy(ServerLevel level, BlockPos pos, Direction side, long amount) {
        EnergyStorage storage = EnergyStorage.SIDED.find(level, pos, side);
        if (storage == null) return 0;
        try (Transaction transaction = Transaction.openOuter()) {
            long inserted = storage.insert(amount, transaction);
            transaction.commit();
            return inserted;
        }
    }

    /** The radar sits in a one-slot container, as in a charger's slot; the transaction swaps it there. */
    @Override
    public ItemStack offerItemEnergy(ItemStack stack, long amount) {
        SimpleContainer slot = new SimpleContainer(stack);
        EnergyStorage storage = EnergyStorage.ITEM.find(stack, ContainerItemContext.ofSingleSlot(InventoryStorage.of(slot, null).getSlot(0)));
        if (storage == null) return stack;
        try (Transaction transaction = Transaction.openOuter()) {
            storage.insert(amount, transaction);
            transaction.commit();
        }
        return slot.getItem(0);
    }

    /** Fabric's network code takes a registered payload on any connection; nothing to set up. */
    @Override
    public void prepareConnection(Connection connection) {}
}
