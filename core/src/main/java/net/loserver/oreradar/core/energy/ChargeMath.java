package net.loserver.oreradar.core.energy;

/** Radar charge arithmetic shared by the item, its tooltip and bar, and energy capabilities. */
public final class ChargeMath {
    /** Vanilla's item bar is 13 pixels wide. */
    public static final int BAR_WIDTH = 13;

    private ChargeMath() {}

    /** The charge stored on an item; a missing value means full (1.0 and fresh radars, DESIGN §3). */
    public static int read(Integer stored, int capacity) {
        return stored == null ? capacity : Math.max(0, Math.min(capacity, stored));
    }

    public static int barWidth(int charge, int capacity) {
        if (capacity <= 0) return 0;
        return (int) Math.round(BAR_WIDTH * Math.max(0, Math.min(capacity, charge)) / (double) capacity);
    }

    /** 0xRRGGBB from red (empty) through yellow and green to cyan (full). */
    public static int barColor(int charge, int capacity) {
        double f = capacity <= 0 ? 0 : Math.max(0, Math.min(1, charge / (double) capacity));
        return hueToRgb(f * 0.5);
    }

    /** Whole minutes of active use left, rounded up; -1 when the radar does not drain. */
    public static long minutesLeft(int charge, int drainPerSecond) {
        if (drainPerSecond <= 0) return -1;
        long seconds = (Math.max(0, charge) + (long) drainPerSecond - 1) / drainPerSecond;
        return (seconds + 59) / 60;
    }

    /**
     * Energy an item storage accepts from an external charger: whole units of charge only (an item
     * has nowhere to keep a fraction), at most {@code room} of them.
     *
     * @param ratio energy per unit of charge (fePerCharge or trEnergyPerCharge)
     * @return energy taken; the charge gained is that divided by ratio
     */
    public static long receive(long offeredEnergy, int ratio, long room) {
        if (offeredEnergy <= 0 || ratio <= 0 || room <= 0) return 0;
        return Math.min(offeredEnergy / ratio, room) * ratio;
    }

    /** Charge expressed in energy units. */
    public static long toEnergy(long charge, int ratio) {
        return charge * ratio;
    }

    /** For int-based energy APIs (Forge's IEnergyStorage). */
    public static int clampToInt(long v) {
        return (int) Math.max(Integer.MIN_VALUE, Math.min(Integer.MAX_VALUE, v));
    }

    /** Fully saturated, full-value colour of a hue in [0, 1). */
    private static int hueToRgb(double hue) {
        double h = (hue - Math.floor(hue)) * 6;
        int sector = (int) h;
        double f = h - sector;
        int up = (int) Math.round(255 * f), down = 255 - up;
        return switch (sector) {
            case 0 -> 0xFF0000 | up << 8;
            case 1 -> down << 16 | 0x00FF00;
            case 2 -> 0x00FF00 | up;
            case 3 -> down << 8 | 0x0000FF;
            case 4 -> up << 16 | 0x0000FF;
            default -> 0xFF0000 | down;
        };
    }
}
