package net.loserver.oreradar.forge;

import net.loserver.oreradar.EnergySettings;
import net.loserver.oreradar.core.energy.ChargeMath;
import net.loserver.oreradar.item.RadarItem;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.common.capabilities.ICapabilityProvider;
import net.minecraftforge.common.util.LazyOptional;
import net.minecraftforge.energy.IEnergyStorage;
import org.jetbrains.annotations.Nullable;

/** The radar on Forge: no re-equip bob for its own data changes, and FE from other mods' chargers (DESIGN §3). */
final class ForgeRadarItem extends RadarItem {
    ForgeRadarItem(Properties properties) {
        super(properties);
    }

    @Override
    public boolean shouldCauseReequipAnimation(ItemStack oldStack, ItemStack newStack, boolean slotChanged) {
        return slotChanged || isDifferentItem(oldStack, newStack);
    }

    @Override
    public ICapabilityProvider initCapabilities(ItemStack stack, @Nullable CompoundTag nbt) {
        return new Energy(stack);
    }

    /**
     * The radar's charge as FE, at {@code fePerCharge}. Present only while
     * {@code acceptExternalChargers} is on; it only takes energy, in whole units of charge.
     */
    private static final class Energy implements IEnergyStorage, ICapabilityProvider {
        private final ItemStack stack;
        private final LazyOptional<IEnergyStorage> self = LazyOptional.of(() -> this);

        Energy(ItemStack stack) {
            this.stack = stack;
        }

        @Override
        public <T> LazyOptional<T> getCapability(Capability<T> capability, @Nullable Direction side) {
            return capability == ForgeCapabilities.ENERGY && EnergySettings.current().externalChargers() ? self.cast() : LazyOptional.empty();
        }

        @Override
        public int receiveEnergy(int maxReceive, boolean simulate) {
            EnergySettings energy = EnergySettings.current();
            if (!energy.externalChargers() || stack.getCount() != 1) return 0;
            int charge = charge(stack);
            long accepted = ChargeMath.receive(maxReceive, energy.fePerCharge(), energy.capacity() - charge);
            if (accepted > 0 && !simulate) setCharge(stack, charge + (int) (accepted / energy.fePerCharge()));
            return (int) accepted;
        }

        @Override
        public int extractEnergy(int maxExtract, boolean simulate) {
            return 0;
        }

        @Override
        public int getEnergyStored() {
            return ChargeMath.clampToInt(ChargeMath.toEnergy(charge(stack), EnergySettings.current().fePerCharge()));
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
