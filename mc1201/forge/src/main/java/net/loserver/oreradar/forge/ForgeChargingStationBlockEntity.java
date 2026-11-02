package net.loserver.oreradar.forge;

import net.loserver.oreradar.EnergySettings;
import net.loserver.oreradar.core.energy.ChargeMath;
import net.loserver.oreradar.station.ChargingStationBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.common.util.LazyOptional;
import net.minecraftforge.energy.IEnergyStorage;
import net.minecraftforge.items.IItemHandler;
import net.minecraftforge.items.wrapper.SidedInvWrapper;
import org.jetbrains.annotations.Nullable;

/**
 * The station on Forge (DESIGN §3): FE in on every side, and an item handler per face that follows
 * the {@link net.minecraft.world.WorldlyContainer} rules (Forge's container block entity already
 * gives the side-less one).
 */
final class ForgeChargingStationBlockEntity extends ChargingStationBlockEntity {
    private LazyOptional<IEnergyStorage> energy = LazyOptional.of(() -> new Energy(this));
    private LazyOptional<? extends IItemHandler>[] faces = createFaces();

    ForgeChargingStationBlockEntity(BlockPos pos, BlockState state) {
        super(pos, state);
    }

    @Override
    public <T> LazyOptional<T> getCapability(Capability<T> capability, @Nullable Direction side) {
        if (!remove) {
            if (capability == ForgeCapabilities.ENERGY) return energy.cast();
            if (capability == ForgeCapabilities.ITEM_HANDLER && side != null) {
                return switch (side) {
                    case UP -> faces[0].cast();
                    case DOWN -> faces[1].cast();
                    default -> faces[2].cast();
                };
            }
        }
        return super.getCapability(capability, side);
    }

    @Override
    public void invalidateCaps() {
        super.invalidateCaps();
        energy.invalidate();
        for (LazyOptional<? extends IItemHandler> face : faces) face.invalidate();
    }

    @Override
    public void reviveCaps() {
        super.reviveCaps();
        energy = LazyOptional.of(() -> new Energy(this));
        faces = createFaces();
    }

    /** Every horizontal side behaves alike, so one handler serves them all. */
    private LazyOptional<? extends IItemHandler>[] createFaces() {
        return SidedInvWrapper.create(this, Direction.UP, Direction.DOWN, Direction.NORTH);
    }

    /** FE into the buffer at {@code fePerCharge}; nothing comes out. */
    private record Energy(ChargingStationBlockEntity station) implements IEnergyStorage {
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
}
