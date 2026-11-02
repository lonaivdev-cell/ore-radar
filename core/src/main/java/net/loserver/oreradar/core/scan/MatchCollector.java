package net.loserver.oreradar.core.scan;

import java.util.Arrays;

import net.loserver.oreradar.core.net.BlockPosPacking;

/**
 * Accumulates the matches of one scan. It tracks the nearest match, the current cut-off
 * ({@link #limit()}: the outline radius as it stands, or maxRadius before anything is found), an
 * exact count of matches per whole-block distance, and the nearest positions, keeping memory
 * bounded however common the target block is.
 */
final class MatchCollector implements RadarScan.MatchSink {
    private final double ox, oy, oz;
    private final int maxRadius;
    private final int probeChunks;
    private final int maxMarkers;
    /** Retained positions beyond this many are pruned to the nearest; at least maxMarkers. */
    private final int cap;
    /** histogram[k] = matches with ceilDistance k; exact, so totals agree with any integer radius. */
    private final int[] histogram;
    private long[] hits;
    private double[] hitDistSq;
    private int hitCount;
    private boolean found;
    private long nearest;
    private double nearestDistSq = Double.POSITIVE_INFINITY;
    private int limit;

    MatchCollector(double ox, double oy, double oz, int maxRadius, int probeChunks, int maxMarkers) {
        this.ox = ox;
        this.oy = oy;
        this.oz = oz;
        this.maxRadius = Math.max(0, maxRadius);
        this.probeChunks = probeChunks;
        this.maxMarkers = Math.max(0, maxMarkers);
        this.cap = this.maxMarkers == 0 ? 0 : Math.max(4 * this.maxMarkers, 1024);
        this.histogram = new int[this.maxRadius + 1];
        this.limit = this.maxRadius;
        int initial = Math.min(this.cap, 256);
        this.hits = new long[initial];
        this.hitDistSq = new double[initial];
    }

    /** Current cut-off in whole blocks: nothing farther can still matter. Only ever shrinks. */
    int limit() {
        return limit;
    }

    @Override
    public void match(int x, int y, int z) {
        double dx = x + 0.5 - ox, dy = y + 0.5 - oy, dz = z + 0.5 - oz;
        double d2 = dx * dx + dy * dy + dz * dz;
        int k = ScanMath.ceilDistance(d2);
        if (k > limit) return;
        histogram[k]++;
        long pos = BlockPosPacking.asLong(x, y, z);
        if (d2 < nearestDistSq) {
            found = true;
            nearestDistSq = d2;
            nearest = pos;
            limit = ScanMath.outlineRadius(Math.sqrt(d2), probeChunks, maxRadius);
        }
        if (cap == 0) return;
        if (hitCount == hits.length) {
            prune();
            if (hitCount == hits.length) {
                int grown = hits.length * 2;
                hits = Arrays.copyOf(hits, grown);
                hitDistSq = Arrays.copyOf(hitDistSq, grown);
            }
        }
        hits[hitCount] = pos;
        hitDistSq[hitCount] = d2;
        hitCount++;
    }

    /** Drops what is past the cut-off and, past the cap, all but the nearest cap positions. */
    private void prune() {
        int n = 0;
        for (int i = 0; i < hitCount; i++) {
            if (ScanMath.ceilDistance(hitDistSq[i]) <= limit) {
                hits[n] = hits[i];
                hitDistSq[n] = hitDistSq[i];
                n++;
            }
        }
        hitCount = n;
        if (hitCount > cap) {
            NearestSort.selectSmallest(hitDistSq, hits, hitCount, cap);
            hitCount = cap;
        }
    }

    ScanResult result() {
        if (!found) return ScanResult.nothing(maxRadius);
        int total = 0;
        for (int k = 0; k <= limit; k++) total += histogram[k];
        prune();
        int n = Math.min(hitCount, maxMarkers);
        NearestSort.selectSmallest(hitDistSq, hits, hitCount, n);
        NearestSort.sortByKey(hitDistSq, hits, 0, n - 1);
        return new ScanResult(true, nearest, Math.sqrt(nearestDistSq), limit, total, Arrays.copyOf(hits, n), ox, oy, oz, probeChunks,
                maxRadius);
    }
}
