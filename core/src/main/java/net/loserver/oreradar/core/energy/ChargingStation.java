package net.loserver.oreradar.core.energy;

import net.loserver.oreradar.core.config.ServerConfig;

/**
 * The charging station's logic (DESIGN §3), free of Minecraft: a charge buffer fed by fuel items
 * and by other mods' energy, emptied into a radar. The block entity owns one instance and persists
 * {@link #buffer()}. Settings are passed in on every call so a reload applies at once.
 * Not thread-safe; use it from the server thread.
 */
public final class ChargingStation {
    /** The server-config values the station uses. */
    public record Settings(int bufferCapacity, int chargeRatePerTick, int maxInputPerTick, int radarCapacity) {
        public Settings {
            if (bufferCapacity < 1 || chargeRatePerTick < 1 || maxInputPerTick < 0 || radarCapacity < 1) {
                throw new IllegalArgumentException("bad station settings");
            }
        }

        public static Settings of(ServerConfig c) {
            return new Settings(c.bufferCapacity(), c.chargeRatePerTick(), c.maxInputPerTick(), c.capacity());
        }
    }

    /**
     * @param consumeFuel remove one item from the fuel slot
     * @param radarCharge the radar's new charge (unchanged, or -1 if there was no radar)
     * @param charging    charge moved into the radar this tick: the block state's {@code lit}
     */
    public record TickResult(boolean consumeFuel, int radarCharge, boolean charging) {}

    private int buffer;
    /** Charge taken from energy since the last tick, against maxInputPerTick. */
    private int inputThisTick;
    /** Energy received but not yet a whole unit of charge, in units of remainderRatio. Not persisted. */
    private long remainder;
    private int remainderRatio;

    public int buffer() {
        return buffer;
    }

    /** Restores the buffer (e.g. from NBT), clamped to [0, bufferCapacity]. */
    public void setBuffer(int value, Settings s) {
        buffer = Math.max(0, Math.min(s.bufferCapacity(), value));
    }

    /**
     * One game tick: burn one fuel item into the buffer if it fits whole, then push up to
     * chargeRatePerTick into the radar.
     *
     * @param fuelValue   charge one item in the fuel slot is worth, 0 for none or not a fuel
     * @param radarCharge the radar's charge, -1 if the radar slot is empty
     */
    public TickResult tick(Settings s, int fuelValue, int radarCharge) {
        inputThisTick = 0;
        buffer = Math.min(buffer, s.bufferCapacity());
        boolean consume = false;
        if (fuelValue > 0 && (long) buffer + fuelValue <= s.bufferCapacity()) {
            buffer += fuelValue;
            consume = true;
        }
        int charge = radarCharge;
        boolean charging = false;
        if (radarCharge >= 0 && radarCharge < s.radarCapacity() && buffer > 0) {
            int move = Math.min(Math.min(s.chargeRatePerTick(), buffer), s.radarCapacity() - radarCharge);
            buffer -= move;
            charge += move;
            charging = move > 0;
        }
        return new TickResult(consume, charge, charging);
    }

    /**
     * Energy offered by a cable or another mod (FE, TR Energy), converted at {@code ratio} energy per
     * unit of charge and capped by buffer room and maxInputPerTick. Fractions of a unit are kept, so
     * a source that pushes less than ratio per call still charges the station.
     *
     * @return the energy accepted (what the energy API should report)
     */
    public long receiveEnergy(Settings s, long offered, int ratio, boolean simulate) {
        if (offered <= 0 || ratio <= 0) return 0;
        long carried = ratio == remainderRatio ? remainder : 0;
        long room = Math.min((long) s.bufferCapacity() - buffer, (long) s.maxInputPerTick() - inputThisTick);
        if (room <= 0) return 0;
        long accepted = Math.min(offered, room * ratio - carried);
        if (accepted <= 0 || simulate) return Math.max(0, accepted);
        long total = carried + accepted;
        int units = (int) (total / ratio);
        buffer += units;
        inputThisTick += units;
        remainder = total % ratio;
        remainderRatio = ratio;
        return accepted;
    }

    /** The buffer in energy units, for an energy API's "stored" query. */
    public long energyStored(int ratio) {
        return ChargeMath.toEnergy(buffer, ratio);
    }

    public static long maxEnergyStored(Settings s, int ratio) {
        return ChargeMath.toEnergy(s.bufferCapacity(), ratio);
    }

    /** Comparator output 0-15 from the buffer level: 0 only when empty, 15 only when full. */
    public int comparatorLevel(Settings s) {
        if (buffer <= 0) return 0;
        return (int) Math.min(15, 1 + (long) buffer * 14 / s.bufferCapacity());
    }

    /** Automation may pull a radar out of the bottom only once it is full. */
    public static boolean canExtractRadar(int charge, int radarCapacity) {
        return charge >= radarCapacity;
    }
}
