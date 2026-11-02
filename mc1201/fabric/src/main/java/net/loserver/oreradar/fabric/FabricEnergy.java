package net.loserver.oreradar.fabric;

import net.fabricmc.fabric.api.transfer.v1.context.ContainerItemContext;
import net.fabricmc.fabric.api.transfer.v1.item.ItemVariant;
import net.fabricmc.fabric.api.transfer.v1.storage.StoragePreconditions;
import net.fabricmc.fabric.api.transfer.v1.transaction.TransactionContext;
import net.loserver.oreradar.EnergySettings;
import net.loserver.oreradar.content.RadarContent;
import net.loserver.oreradar.core.energy.ChargeMath;
import net.loserver.oreradar.item.RadarItem;
import net.minecraft.world.item.ItemStack;
import team.reborn.energy.api.EnergyStorage;

/** Team Reborn Energy for the station (every side) and, with {@code acceptExternalChargers}, for the radar item. */
final class FabricEnergy {
    private FabricEnergy() {}

    static void register() {
        EnergyStorage.SIDED.registerForBlockEntity(
                (station, side) -> station instanceof FabricChargingStationBlockEntity fabric ? fabric.energy() : null,
                RadarContent.STATION_BLOCK_ENTITY.get());
        EnergyStorage.ITEM.registerForItems(
                (stack, context) -> EnergySettings.current().externalChargers() ? new RadarEnergy(context) : null,
                RadarContent.RADAR.get());
    }

    /**
     * The radar's charge as TR Energy, at {@code trEnergyPerCharge}. It only takes energy, in whole
     * units of charge, by swapping the stack in its container for one with the new charge, which
     * the transaction undoes if it aborts.
     */
    private record RadarEnergy(ContainerItemContext context) implements EnergyStorage {
        @Override
        public long insert(long maxAmount, TransactionContext transaction) {
            StoragePreconditions.notNegative(maxAmount);
            if (context.getAmount() != 1) return 0;
            EnergySettings energy = EnergySettings.current();
            ItemStack radar = context.getItemVariant().toStack();
            int charge = RadarItem.charge(radar);
            long accepted = ChargeMath.receive(maxAmount, energy.trEnergyPerCharge(), energy.capacity() - charge);
            if (accepted <= 0) return 0;
            RadarItem.setCharge(radar, charge + (int) (accepted / energy.trEnergyPerCharge()));
            return context.exchange(ItemVariant.of(radar), 1, transaction) == 1 ? accepted : 0;
        }

        @Override
        public boolean supportsExtraction() {
            return false;
        }

        @Override
        public long extract(long maxAmount, TransactionContext transaction) {
            return 0;
        }

        @Override
        public long getAmount() {
            ItemStack radar = context.getItemVariant().toStack();
            return ChargeMath.toEnergy(RadarItem.charge(radar), EnergySettings.current().trEnergyPerCharge()) * context.getAmount();
        }

        @Override
        public long getCapacity() {
            EnergySettings energy = EnergySettings.current();
            return ChargeMath.toEnergy(energy.capacity(), energy.trEnergyPerCharge()) * context.getAmount();
        }
    }
}
