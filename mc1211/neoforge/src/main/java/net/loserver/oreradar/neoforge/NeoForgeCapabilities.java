package net.loserver.oreradar.neoforge;

import net.loserver.oreradar.EnergySettings;
import net.loserver.oreradar.content.RadarContent;
import net.loserver.oreradar.core.energy.ChargeMath;
import net.loserver.oreradar.item.RadarItem;
import net.loserver.oreradar.station.ChargingStationBlockEntity;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;
import net.neoforged.neoforge.energy.IEnergyStorage;
import net.neoforged.neoforge.items.wrapper.InvWrapper;
import net.neoforged.neoforge.items.wrapper.SidedInvWrapper;

/**
 * The station's and the radar's capabilities on NeoForge (DESIGN §3): FE into the station on every
 * side; per face an item handler that follows the station's {@link net.minecraft.world.WorldlyContainer}
 * rules, and without a side one that reaches both slots (as Forge gave every container); and, with
 * {@code acceptExternalChargers}, FE into the radar item from other mods' chargers.
 */
final class NeoForgeCapabilities {
    private NeoForgeCapabilities() {}

    static void register(RegisterCapabilitiesEvent event) {
        event.registerBlockEntity(Capabilities.EnergyStorage.BLOCK, RadarContent.STATION_BLOCK_ENTITY.get(),
                (station, side) -> new StationEnergy(station));
        event.registerBlockEntity(Capabilities.ItemHandler.BLOCK, RadarContent.STATION_BLOCK_ENTITY.get(),
                (station, side) -> side == null ? new InvWrapper(station) : new SidedInvWrapper(station, side));
        event.registerItem(Capabilities.EnergyStorage.ITEM,
                (stack, context) -> EnergySettings.current().externalChargers() ? new RadarEnergy(stack) : null,
                RadarContent.RADAR.get());
    }

    /** FE into the buffer at {@code fePerCharge}; nothing comes out. */
    private record StationEnergy(ChargingStationBlockEntity station) implements IEnergyStorage {
        @Override
        public int receiveEnergy(int maxReceive, boolean simulate) {
            return (int) station.receiveEnergy(maxReceive, ratio(), simulate);
        }

        @Override
        public int extractEnergy(int maxExtract, boolean simulate) {
            return 0;
        }

        @Override
        public int getEnergyStored() {
            return ChargeMath.clampToInt(station.energyStored(ratio()));
        }

        @Override
        public int getMaxEnergyStored() {
            return ChargeMath.clampToInt(station.maxEnergyStored(ratio()));
        }

        @Override
        public boolean canExtract() {
            return false;
        }

        @Override
        public boolean canReceive() {
            return true;
        }

        private static int ratio() {
            return EnergySettings.current().fePerCharge();
        }
    }

    /**
     * The radar's charge as FE, at {@code fePerCharge}. It only takes energy, in whole units of
     * charge, and writes the charge component of the stack it was asked for.
     */
    private record RadarEnergy(ItemStack stack) implements IEnergyStorage {
        @Override
        public int receiveEnergy(int maxReceive, boolean simulate) {
            EnergySettings energy = EnergySettings.current();
            if (!energy.externalChargers() || stack.getCount() != 1) return 0;
            int charge = RadarItem.charge(stack);
            long accepted = ChargeMath.receive(maxReceive, energy.fePerCharge(), energy.capacity() - charge);
            if (accepted > 0 && !simulate) RadarItem.setCharge(stack, charge + (int) (accepted / energy.fePerCharge()));
            return (int) accepted;
        }

        @Override
        public int extractEnergy(int maxExtract, boolean simulate) {
            return 0;
        }

        @Override
        public int getEnergyStored() {
            return ChargeMath.clampToInt(ChargeMath.toEnergy(RadarItem.charge(stack), EnergySettings.current().fePerCharge()));
        }

        @Override
        public int getMaxEnergyStored() {
            EnergySettings energy = EnergySettings.current();
            return ChargeMath.clampToInt(ChargeMath.toEnergy(energy.capacity(), energy.fePerCharge()));
        }

        @Override
        public boolean canExtract() {
            return false;
        }

        @Override
        public boolean canReceive() {
            return EnergySettings.current().externalChargers();
        }
    }
}
