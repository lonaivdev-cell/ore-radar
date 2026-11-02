package net.loserver.oreradar.core.energy;

/**
 * Per-player charge drain (DESIGN §5): {@code drainPerSecond} accrues every tick a radar is active,
 * but is handed out only every {@link #WRITE_INTERVAL_TICKS}, so the item (and the inventory sync)
 * is written rarely. Fractions of a unit carry over. Creative players never drain.
 */
public final class ChargeDrain {
    /** Ticks between hand-outs (5 s): the mods write a draining radar's item this often, not every tick. */
    public static final int WRITE_INTERVAL_TICKS = 100;

    private int ticks;
    /** Accrued drain in twentieths of a unit (one tick of drainPerSecond). */
    private long twentieths;

    /**
     * Call once per server tick while the player's radar is active.
     *
     * @return the charge to take off the item now; 0 between writes
     */
    public int tick(boolean creative, int drainPerSecond) {
        if (creative || drainPerSecond <= 0) return 0;
        twentieths += drainPerSecond;
        if (++ticks < WRITE_INTERVAL_TICKS) return 0;
        ticks = 0;
        return take();
    }

    /** Accrued whole units not yet written, e.g. to show a live charge between writes. */
    public int pending() {
        return (int) (twentieths / 20);
    }

    /** Hands out the accrued whole units now (e.g. when the player switches radars) and restarts the interval. */
    public int flush() {
        ticks = 0;
        return take();
    }

    public void reset() {
        ticks = 0;
        twentieths = 0;
    }

    private int take() {
        int units = (int) Math.min(Integer.MAX_VALUE, twentieths / 20);
        twentieths -= units * 20L;
        return units;
    }

    /** The charge left after a drain, never below zero. */
    public static int apply(int charge, int drain) {
        return Math.max(0, charge - Math.max(0, drain));
    }
}
