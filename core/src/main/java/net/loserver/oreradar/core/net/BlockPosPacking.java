package net.loserver.oreradar.core.net;

/**
 * Minecraft's {@code BlockPos.asLong} packing, bit for bit: X in the top 26 bits, Z in the next 26,
 * Y in the low 12, each two's complement and sign-extended on the way out. Coordinates outside
 * those widths wrap exactly as vanilla's do.
 */
public final class BlockPosPacking {
    public static final int X_BITS = 26;
    public static final int Z_BITS = 26;
    public static final int Y_BITS = 12;
    private static final int X_SHIFT = Y_BITS + Z_BITS;
    private static final int Z_SHIFT = Y_BITS;
    private static final long X_MASK = (1L << X_BITS) - 1;
    private static final long Z_MASK = (1L << Z_BITS) - 1;
    private static final long Y_MASK = (1L << Y_BITS) - 1;

    private BlockPosPacking() {}

    public static long asLong(int x, int y, int z) {
        return (x & X_MASK) << X_SHIFT | (z & Z_MASK) << Z_SHIFT | (y & Y_MASK);
    }

    public static int x(long packed) {
        return (int) (packed >> X_SHIFT);
    }

    public static int y(long packed) {
        return (int) (packed << (64 - Y_BITS) >> (64 - Y_BITS));
    }

    public static int z(long packed) {
        return (int) (packed << (64 - Z_SHIFT - Z_BITS) >> (64 - Z_BITS));
    }

    public static long offset(long packed, int dx, int dy, int dz) {
        return asLong(x(packed) + dx, y(packed) + dy, z(packed) + dz);
    }
}
