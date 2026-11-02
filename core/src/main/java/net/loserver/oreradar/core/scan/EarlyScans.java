package net.loserver.oreradar.core.scan;

/**
 * One player's early scans (DESIGN §5). When what the radar shows changes (another radar, target,
 * dimension or prefs, a radar switched on or recharged), the server scans at once instead of at the
 * player's next cadence slot. Up to {@link #BURST} early scans may start back to back, so a player
 * clicking about is never kept waiting; after that one more becomes available every
 * {@code scanIntervalTicks}, so a client that changes something every tick cannot make the server
 * scan for it every tick. Shared by the mods and the Paper plugin. Server thread only.
 */
public final class EarlyScans {
    public static final int BURST = 3;

    private int left = BURST;
    private int refillTicks;
    private boolean wanted;

    /** What the radar shows changed: scan again soon. */
    public void want() {
        wanted = true;
    }

    /** Call once per server tick: refills one early scan every {@code scanIntervalTicks}, up to {@link #BURST}. */
    public void tick(int scanIntervalTicks) {
        if (left < BURST && ++refillTicks >= scanIntervalTicks) {
            left++;
            refillTicks = 0;
        }
    }

    /**
     * Whether to start a scan now, from scratch, for a change. Ask only when no scan is about to
     * start anyway: a cadence scan covers the change for free (call {@link #started}).
     *
     * @return true, using up one early scan, if a change is waiting and an early scan is left
     */
    public boolean take() {
        if (!wanted || left == 0) return false;
        left--;
        wanted = false;
        return true;
    }

    /** A scan started, early or at the cadence: it covers every change so far. */
    public void started() {
        wanted = false;
    }
}
