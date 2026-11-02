package net.loserver.oreradar.core.scan;

/**
 * The Geiger-style beep (DESIGN §6): the interval shrinks with distance, never below the server's
 * {@code beep.minIntervalTicks}, silent beyond {@code beep.maxRange}.
 */
public final class Beep {
    /** The slowest beep, at maxRange. */
    public static final int SLOWEST_INTERVAL_TICKS = 40;

    private Beep() {}

    /**
     * Ticks between beeps for a match this far away (blocks): minInterval at distance 0 rising
     * linearly to {@link #SLOWEST_INTERVAL_TICKS} at maxRange. -1 means silent.
     */
    public static int intervalTicks(double distance, int minIntervalTicks, int maxRange) {
        if (Double.isNaN(distance) || maxRange <= 0 || distance > maxRange) return -1;
        int fastest = Math.max(1, minIntervalTicks);
        int slowest = Math.max(fastest, SLOWEST_INTERVAL_TICKS);
        double t = Math.max(0, distance) / maxRange;
        return (int) Math.round(fastest + (slowest - fastest) * t);
    }

    /** The client's pitch, raised up to 15 % as the match gets closer and lowered as much far away; within Minecraft's 0.5..2. */
    public static float pitch(double basePitch, double distance, int maxRange) {
        double t = maxRange <= 0 || Double.isNaN(distance) ? 1 : Math.min(1, Math.max(0, distance) / maxRange);
        double p = basePitch * (1.15 - 0.3 * t);
        return (float) Math.max(0.5, Math.min(2.0, p));
    }

    /** Counts client ticks between beeps. */
    public static final class Timer {
        private int ticks;

        /**
         * Advances one tick; true when a beep is due. Pass NaN when there is no nearest match.
         * The first beep sounds one interval after a match appears.
         */
        public boolean tick(double distance, int minIntervalTicks, int maxRange) {
            int interval = intervalTicks(distance, minIntervalTicks, maxRange);
            if (interval < 0) {
                ticks = 0;
                return false;
            }
            if (++ticks < interval) return false;
            ticks = 0;
            return true;
        }

        public void reset() {
            ticks = 0;
        }
    }
}
