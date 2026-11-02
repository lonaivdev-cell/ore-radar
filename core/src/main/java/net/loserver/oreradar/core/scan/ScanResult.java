package net.loserver.oreradar.core.scan;

import java.util.Arrays;

import net.loserver.oreradar.core.net.ScanPacket;

/** The outcome of a finished scan, before the server decides what to send. Immutable. */
public final class ScanResult {
    private final boolean found;
    private final long nearest;
    private final double nearestDistance;
    private final int outlineRadius;
    private final int totalMatches;
    private final long[] markers;
    /** The scan's eye, reach and cap, to measure the outline radius again from a rounded nearest. */
    private final double eyeX, eyeY, eyeZ;
    private final int probeChunks;
    private final int maxRadius;

    ScanResult(boolean found, long nearest, double nearestDistance, int outlineRadius, int totalMatches, long[] markers,
               double eyeX, double eyeY, double eyeZ, int probeChunks, int maxRadius) {
        this.found = found;
        this.nearest = nearest;
        this.nearestDistance = nearestDistance;
        this.outlineRadius = outlineRadius;
        this.totalMatches = totalMatches;
        this.markers = markers;
        this.eyeX = eyeX;
        this.eyeY = eyeY;
        this.eyeZ = eyeZ;
        this.probeChunks = probeChunks;
        this.maxRadius = maxRadius;
    }

    static ScanResult nothing(int maxRadius) {
        return new ScanResult(false, 0, Double.NaN, maxRadius, 0, new long[0], 0, 0, 0, 0, maxRadius);
    }

    public boolean found() {
        return found;
    }

    /** The exact nearest match (packed); only meaningful when found. */
    public long nearest() {
        return nearest;
    }

    /** Eye-to-block-centre distance of the nearest match; NaN when nothing was found. */
    public double nearestDistance() {
        return nearestDistance;
    }

    /** How far the matches counted and outlined reach; maxRadius when nothing was found. */
    public int outlineRadius() {
        return outlineRadius;
    }

    /** Every match within the outline radius, however many markers were kept. */
    public int totalMatches() {
        return totalMatches;
    }

    /** The nearest matches within the outline radius, nearest first, at most the scan's maxMarkers. */
    public long[] markers() {
        return markers.clone();
    }

    public int markerCount() {
        return markers.length;
    }

    /**
     * The {@code FOUND} body this player may receive (DESIGN §5): markers only if outlines are on,
     * the nearest only if arrow, distance or beep is on, rounded to the server's precision.
     *
     * <p>Without markers the outline radius is measured to the rounded nearest too. The exact radius
     * is the exact distance plus a constant the client chose, so a few readings from different spots
     * would pin down the block the rounding hides. With markers it stays exact: they are exact anyway.
     *
     * @param minY          the dimension's lowest block y, for clamping the rounded position
     * @param maxYExclusive one above its highest block y
     */
    public ScanPacket.Found toFound(ScanSettings settings, int minY, int maxYExclusive) {
        if (!found) throw new IllegalStateException("nothing was found");
        long[] sent = settings.sendMarkers() ? Arrays.copyOf(markers, Math.min(markers.length, settings.maxMarkers())) : new long[0];
        long rounded = Precision.round(nearest, settings.precisionBlocks(), minY, maxYExclusive);
        int radius = settings.sendMarkers() ? outlineRadius
                : ScanMath.outlineRadius(ScanMath.distance(rounded, eyeX, eyeY, eyeZ), probeChunks, maxRadius);
        long near = settings.sendNearest() ? rounded : 0;
        return new ScanPacket.Found(settings.sendNearest(), near, radius, totalMatches, totalMatches > sent.length, sent);
    }
}
