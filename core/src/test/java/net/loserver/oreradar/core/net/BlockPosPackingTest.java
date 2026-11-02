package net.loserver.oreradar.core.net;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Random;

import org.junit.jupiter.api.Test;

class BlockPosPackingTest {
    /**
     * x, y, z, then what vanilla 1.20.1 {@code BlockPos.asLong} returned for them and what its
     * getX/getY/getZ read back. Generated against the Forge 1.20.1-47.4.0 jar on this machine.
     */
    private static final long[][] VANILLA = {
            {0, 0, 0, 0L, 0, 0, 0},
            {1, 2, 3, 274877919234L, 1, 2, 3},
            {-1, -1, -1, -1L, -1, -1, -1},
            {-64, -64, -64, -17317308395584L, -64, -64, -64},
            {30000000, 319, -30000000, 8246337360317907263L, 30000000, 319, -30000000},
            {-30000000, -2048, 29999999, -8246337085440002048L, -30000000, -2048, 29999999},
            {33554431, 2047, -33554432, 9223371899415824383L, 33554431, 2047, -33554432},
            {12345, -64, -54321, 3393642416635840L, 12345, -64, -54321},
            {-1, 0, 0, -274877906944L, -1, 0, 0},
            {0, -1, 0, 4095L, 0, -1, 0},
            {0, 0, -1, 274877902848L, 0, 0, -1},
            {33554432, 2048, 33554432, -9223371899415820288L, -33554432, -2048, -33554432},
            {7, 320, -9, 2199023219008L, 7, 320, -9},
    };

    /** Raw longs and vanilla's getX/getY/getZ of them. */
    private static final long[][] VANILLA_UNPACK = {
            {Long.MIN_VALUE, -33554432, 0, 0},
            {Long.MAX_VALUE, 33554431, -1, -1},
            {-1L, -1, -1, -1},
            {81985529216486895L, 298261, -529, -25650500},
            {-81985529216486896L, -298262, 528, 25650499},
    };

    @Test
    void matchesVanilla() {
        for (long[] v : VANILLA) {
            long packed = BlockPosPacking.asLong((int) v[0], (int) v[1], (int) v[2]);
            assertEquals(v[3], packed, "asLong" + java.util.Arrays.toString(v));
            assertEquals(v[4], BlockPosPacking.x(packed));
            assertEquals(v[5], BlockPosPacking.y(packed));
            assertEquals(v[6], BlockPosPacking.z(packed));
        }
        for (long[] v : VANILLA_UNPACK) {
            assertEquals(v[1], BlockPosPacking.x(v[0]));
            assertEquals(v[2], BlockPosPacking.y(v[0]));
            assertEquals(v[3], BlockPosPacking.z(v[0]));
        }
    }

    @Test
    void roundTripsTheWholeWorld() {
        Random rnd = new Random(1);
        for (int i = 0; i < 100_000; i++) {
            int x = rnd.nextInt(60_000_001) - 30_000_000;
            int y = rnd.nextInt(4064) - 2032;
            int z = rnd.nextInt(60_000_001) - 30_000_000;
            long p = BlockPosPacking.asLong(x, y, z);
            assertEquals(x, BlockPosPacking.x(p));
            assertEquals(y, BlockPosPacking.y(p));
            assertEquals(z, BlockPosPacking.z(p));
            assertEquals(BlockPosPacking.asLong(x + 1, y - 1, z + 2), BlockPosPacking.offset(p, 1, -1, 2));
        }
    }
}
