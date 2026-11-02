package net.loserver.oreradar.core.scan;

import net.loserver.oreradar.core.net.BlockPosPacking;

/**
 * Rounding of the nearest match before it is sent ({@code arrow.precisionBlocks}). The world is
 * cut into cells of that size anchored at 0, and a position becomes its cell's centre, so the
 * answer does not change as the player moves. 16 gives the chunk centre.
 */
public final class Precision {
    private Precision() {}

    /**
     * @param minY          the world's lowest block y
     * @param maxYExclusive one above the world's highest block y
     */
    public static long round(long pos, int precisionBlocks, int minY, int maxYExclusive) {
        if (precisionBlocks <= 1) return pos;
        int y = cell(BlockPosPacking.y(pos), precisionBlocks);
        y = Math.max(minY, Math.min(maxYExclusive - 1, y));
        return BlockPosPacking.asLong(cell(BlockPosPacking.x(pos), precisionBlocks), y, cell(BlockPosPacking.z(pos), precisionBlocks));
    }

    private static int cell(int v, int size) {
        return Math.floorDiv(v, size) * size + size / 2;
    }
}
