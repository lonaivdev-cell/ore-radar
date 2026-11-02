package net.loserver.oreradar.core.scan;

/**
 * The chunks within a horizontal radius of a point, nearest first, measured to each chunk's
 * column (0 for the chunk the point is in). Scanning in this order lets a scan stop as soon as the
 * next chunk lies beyond the current cut-off.
 */
public final class ChunkOrder {
    private final int[] cx;
    private final int[] cz;
    private final double[] distance;

    private ChunkOrder(int[] cx, int[] cz, double[] distance) {
        this.cx = cx;
        this.cz = cz;
        this.distance = distance;
    }

    public static ChunkOrder around(double x, double z, int radius) {
        int pcx = (int) Math.floor(x) >> 4, pcz = (int) Math.floor(z) >> 4;
        int r = (Math.max(0, radius) >> 4) + 1;
        int side = 2 * r + 1;
        double[] key = new double[side * side];
        long[] val = new long[side * side];
        int n = 0;
        for (int i = 0; i < side; i++) {
            for (int j = 0; j < side; j++) {
                int cx = pcx + i - r, cz = pcz + j - r;
                double dx = ScanMath.axisDistance(x, cx * 16.0, cx * 16.0 + 16);
                double dz = ScanMath.axisDistance(z, cz * 16.0, cz * 16.0 + 16);
                double d = Math.sqrt(dx * dx + dz * dz);
                if (d > radius) continue;
                key[n] = d;
                val[n] = (long) cx << 32 | (cz & 0xFFFFFFFFL);
                n++;
            }
        }
        NearestSort.sortByKey(key, val, 0, n - 1);
        int[] xs = new int[n], zs = new int[n];
        double[] ds = new double[n];
        for (int k = 0; k < n; k++) {
            xs[k] = (int) (val[k] >> 32);
            zs[k] = (int) val[k];
            ds[k] = key[k];
        }
        return new ChunkOrder(xs, zs, ds);
    }

    public int size() {
        return cx.length;
    }

    public int chunkX(int i) {
        return cx[i];
    }

    public int chunkZ(int i) {
        return cz[i];
    }

    /** Horizontal distance from the point to chunk i's column; non-decreasing in i. */
    public double distance(int i) {
        return distance[i];
    }
}
