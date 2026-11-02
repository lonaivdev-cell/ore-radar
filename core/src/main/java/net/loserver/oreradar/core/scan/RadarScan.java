package net.loserver.oreradar.core.scan;

import net.loserver.oreradar.core.util.Deadline;

/**
 * One server-side scan (DESIGN §5): loaded chunks in rings, nearest first; within each chunk the
 * sections nearest the player's eye first; a section only if it lies within the current cut-off and
 * its palette may hold the target. The first match fixes the outline radius, and the scan stops at
 * the first chunk beyond it.
 *
 * <p>The platform supplies a {@link World} view. Call {@link #step} with a deadline: true means
 * finished and {@link #result()} is ready. False means the budget ran out (DESIGN §5 "Time budget"):
 * keep this object and call {@code step} again on a later tick, with a fresh deadline, until it
 * returns true; send the previous result meanwhile. The scan keeps its place and everything it has
 * found, so a continued scan ends with the same result as one unbounded step over the same blocks.
 * Between steps the platform may drop its chunk reference: an interrupted chunk is selected again,
 * and if it is no longer loaded the rest of it is skipped. Not thread-safe; one instance per scan.
 */
public final class RadarScan {
    /** The platform's view of one dimension. Called on the thread that runs the scan. */
    public interface World {
        /** Prepares chunk (x, z) for the calls below; false if it is not loaded. Must never load or generate it. */
        boolean selectChunk(int chunkX, int chunkZ);

        /** Palette pre-filter for a section of the selected chunk (by section y, i.e. blockY >> 4): false skips it. */
        boolean mayContain(int sectionY);

        /** Reports every block of that section that matches the target to the sink, in absolute coordinates. */
        void scanSection(int sectionY, MatchSink sink);
    }

    @FunctionalInterface
    public interface MatchSink {
        void match(int x, int y, int z);
    }

    private final double eyeY;
    private final ChunkOrder chunks;
    private final int[] sections;
    private final MatchCollector collector;
    private int chunkIndex;
    private int sectionIndex;
    private boolean selected;
    private boolean done;

    /**
     * @param x,eyeY,z      the player's eye position
     * @param maxRadius     blocks, server-capped
     * @param probeChunks   the player's effective outline reach in chunks
     * @param maxMarkers    positions to keep; 0 when no outlines will be sent (matches are still counted)
     * @param minY          the dimension's lowest block y
     * @param maxYExclusive one above its highest block y
     */
    public RadarScan(double x, double eyeY, double z, int maxRadius, int probeChunks, int maxMarkers, int minY, int maxYExclusive) {
        this.eyeY = eyeY;
        this.chunks = ChunkOrder.around(x, z, maxRadius);
        this.sections = sectionOrder(eyeY, Math.floorDiv(minY, 16), Math.floorDiv(maxYExclusive - 1, 16) + 1);
        this.collector = new MatchCollector(x, eyeY, z, maxRadius, probeChunks, maxMarkers);
    }

    /** Scans until finished (true) or until the deadline passes (false: call again to continue). */
    public boolean step(World world, Deadline deadline) {
        while (!done && chunkIndex < chunks.size()) {
            double h = chunks.distance(chunkIndex);
            if (h > collector.limit()) break;
            if (!selected && !world.selectChunk(chunks.chunkX(chunkIndex), chunks.chunkZ(chunkIndex))) {
                // Not loaded, or unloaded since an earlier step stopped inside it: skip the rest of it.
                chunkIndex++;
                sectionIndex = 0;
                continue;
            }
            selected = true;
            while (sectionIndex < sections.length) {
                int sy = sections[sectionIndex++];
                double dy = ScanMath.axisDistance(eyeY, sy * 16.0, sy * 16.0 + 16);
                double lim = collector.limit();
                if (h * h + dy * dy > lim * lim || !world.mayContain(sy)) continue;
                world.scanSection(sy, collector);
                if (deadline.expired() && sectionIndex < sections.length) {
                    selected = false; // the platform may drop its chunk reference between calls
                    return false;
                }
            }
            chunkIndex++;
            sectionIndex = 0;
            selected = false;
            if (deadline.expired() && chunkIndex < chunks.size()) return false;
        }
        done = true;
        return true;
    }

    public boolean isDone() {
        return done;
    }

    public ScanResult result() {
        if (!done) throw new IllegalStateException("scan not finished");
        return collector.result();
    }

    /** Section ys from the eye's section outwards, alternating up and down, within [minSection, maxSection). */
    static int[] sectionOrder(double eyeY, int minSection, int maxSection) {
        int count = Math.max(0, maxSection - minSection);
        int[] out = new int[count];
        if (count == 0) return out;
        int start = Math.max(minSection, Math.min(maxSection - 1, Math.floorDiv((int) Math.floor(eyeY), 16)));
        int n = 0;
        out[n++] = start;
        for (int d = 1; n < count; d++) {
            if (start + d < maxSection) out[n++] = start + d;
            if (start - d >= minSection) out[n++] = start - d;
        }
        return out;
    }
}
