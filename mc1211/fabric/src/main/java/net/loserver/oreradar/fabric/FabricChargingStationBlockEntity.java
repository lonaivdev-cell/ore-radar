package net.loserver.oreradar.fabric;

import net.fabricmc.fabric.api.transfer.v1.storage.StoragePreconditions;
import net.fabricmc.fabric.api.transfer.v1.transaction.TransactionContext;
import net.fabricmc.fabric.api.transfer.v1.transaction.base.SnapshotParticipant;
import net.loserver.oreradar.EnergySettings;
import net.loserver.oreradar.station.ChargingStationBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import team.reborn.energy.api.EnergyStorage;

/**
 * The station on Fabric: Team Reborn Energy in on every side (DESIGN §3). Fabric's transfer API
 * already moves items through the station's {@link net.minecraft.world.WorldlyContainer} rules.
 */
final class FabricChargingStationBlockEntity extends ChargingStationBlockEntity {
    private final Energy energy = new Energy(this);

    FabricChargingStationBlockEntity(BlockPos pos, BlockState state) {
        super(pos, state);
    }

    EnergyStorage energy() {
        return energy;
    }

    /**
     * TR Energy into the buffer at {@code trEnergyPerCharge}. Insertions are transactional: they are
     * held as pending energy and reach the buffer only when the outermost transaction commits, so
     * an aborted transfer leaves the station untouched.
     */
    private static final class Energy extends SnapshotParticipant<Long> implements EnergyStorage {
        private final ChargingStationBlockEntity station;
        /** Energy accepted by transactions still open. */
        private long pending;

        Energy(ChargingStationBlockEntity station) {
            this.station = station;
        }

        @Override
        public long insert(long maxAmount, TransactionContext transaction) {
            StoragePreconditions.notNegative(maxAmount);
            long offered = Math.min(maxAmount, Long.MAX_VALUE - pending);
            long accepted = station.receiveEnergy(pending + offered, ratio(), true) - pending;
            if (accepted <= 0) return 0;
            updateSnapshots(transaction);
            pending += accepted;
            return accepted;
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
            return station.energyStored(ratio()) + pending;
        }

        @Override
        public long getCapacity() {
            return station.maxEnergyStored(ratio());
        }

        @Override
        protected Long createSnapshot() {
            return pending;
        }

        @Override
        protected void readSnapshot(Long snapshot) {
            pending = snapshot;
        }

        @Override
        protected void onFinalCommit() {
            station.receiveEnergy(pending, ratio(), false);
            pending = 0;
        }

        private static int ratio() {
            return EnergySettings.current().trEnergyPerCharge();
        }
    }
}
