package net.loserver.oreradar.core.scan;

import net.loserver.oreradar.core.net.BlockPosPacking;

/**
 * Distances as the radar measures them: from the player's eye to the centre of a block. A match
 * lies within an integer radius R when its distance is at most R, i.e. {@code ceilDistance <= R}.
 */
public final class ScanMath {
    private ScanMath() {}

    public static double distanceSq(long pos, double x, double y, double z) {
        double dx = BlockPosPacking.x(pos) + 0.5 - x;
        double dy = BlockPosPacking.y(pos) + 0.5 - y;
        double dz = BlockPosPacking.z(pos) + 0.5 - z;
        return dx * dx + dy * dy + dz * dz;
    }

    public static double distance(long pos, double x, double y, double z) {
        return Math.sqrt(distanceSq(pos, x, y, z));
    }

    /** The smallest integer radius that contains a match this far away. */
    public static int ceilDistance(double distanceSq) {
        return (int) Math.ceil(Math.sqrt(distanceSq));
    }

    /**
     * How far outlines reach: {@code min(d + probeChunks * 16, maxRadius)}, with d the nearest
     * match's distance rounded up to a whole block (DESIGN §5).
     */
    public static int outlineRadius(double nearestDistance, int probeChunks, int maxRadius) {
        long r = (long) Math.ceil(nearestDistance) + (long) Math.max(0, probeChunks) * 16;
        return (int) Math.min(r, maxRadius);
    }

    /** Distance from v to the interval [lo, hi] along one axis; 0 inside it. */
    public static double axisDistance(double v, double lo, double hi) {
        return Math.max(Math.max(lo - v, v - hi), 0);
    }
}
