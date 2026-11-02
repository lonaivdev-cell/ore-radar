package net.loserver.oreradar.core.util;

/** A point in {@link System#nanoTime()} time after which work should stop. */
public final class Deadline {
    /** Never expires; for tests and GameTests, where the result matters more than the time. */
    public static final Deadline NONE = new Deadline(Long.MAX_VALUE, true);

    private final long nanos;
    private final boolean never;

    private Deadline(long nanos, boolean never) {
        this.nanos = nanos;
        this.never = never;
    }

    public static Deadline afterMicros(long micros) {
        return new Deadline(System.nanoTime() + Math.max(0, micros) * 1000L, false);
    }

    public boolean expired() {
        // Compare by difference: nanoTime may wrap.
        return !never && System.nanoTime() - nanos > 0;
    }
}
