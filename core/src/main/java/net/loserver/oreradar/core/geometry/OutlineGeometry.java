package net.loserver.oreradar.core.geometry;

import java.util.Arrays;

import net.loserver.oreradar.core.net.BlockPosPacking;
import net.loserver.oreradar.core.util.LongHashSet;

/**
 * The outline of a set of matched blocks as one shape (1.0's merger): faces between two matched
 * blocks are dropped, and an edge where two exposed faces continue in the same plane is merged
 * away, so a vein reads as one silhouette instead of a grid of cubes.
 *
 * <p>Directions use Minecraft's {@code Direction} ordinals (DOWN, UP, NORTH, SOUTH, WEST, EAST)
 * and axes its {@code Axis} ordinals (X, Y, Z), so renderers can index vanilla tables directly.
 */
public final class OutlineGeometry {
    public static final int DOWN = 0, UP = 1, NORTH = 2, SOUTH = 3, WEST = 4, EAST = 5;
    public static final int AXIS_X = 0, AXIS_Y = 1, AXIS_Z = 2;

    private static final int[] STEP_X = {0, 0, 0, 0, -1, 1};
    private static final int[] STEP_Y = {-1, 1, 0, 0, 0, 0};
    private static final int[] STEP_Z = {0, 0, -1, 1, 0, 0};
    private static final int[] AXIS = {AXIS_Y, AXIS_Y, AXIS_Z, AXIS_Z, AXIS_X, AXIS_X};
    private static final boolean[] POSITIVE = {false, true, false, true, false, true};
    private static final int[][] FACE_CORNERS = new int[6][];

    static {
        // The four corners of each face of a unit cube, in winding order, as x, y, z triples.
        int[][] uv = {{0, 0}, {1, 0}, {1, 1}, {0, 1}};
        for (int d = 0; d < 6; d++) {
            int a = AXIS[d], u = (a + 1) % 3, v = (a + 2) % 3;
            int[] c = new int[12];
            for (int k = 0; k < 4; k++) {
                c[3 * k + a] = POSITIVE[d] ? 1 : 0;
                c[3 * k + u] = uv[k][0];
                c[3 * k + v] = uv[k][1];
            }
            FACE_CORNERS[d] = c;
        }
    }

    public static final OutlineGeometry EMPTY = new OutlineGeometry(new int[0], new int[0]);

    private final int[] edges;
    private final int[] faces;

    private OutlineGeometry(int[] edges, int[] faces) {
        this.edges = edges;
        this.faces = faces;
    }

    /** Builds the outline of these packed block positions; duplicates are ignored. */
    public static OutlineGeometry build(long[] positions) {
        if (positions.length == 0) return EMPTY;
        LongHashSet set = new LongHashSet(positions);
        LongHashSet done = new LongHashSet(positions.length);
        LongHashSet[] seen = {new LongHashSet(positions.length * 4), new LongHashSet(positions.length * 4),
                new LongHashSet(positions.length * 4)};
        IntList edges = new IntList(positions.length * 16);
        IntList faces = new IntList(positions.length * 8);
        int[] c = new int[3];
        for (long pos : positions) {
            if (!done.add(pos)) continue;
            int bx = BlockPosPacking.x(pos), by = BlockPosPacking.y(pos), bz = BlockPosPacking.z(pos);
            for (int f = 0; f < 6; f++) {
                if (set.contains(BlockPosPacking.asLong(bx + STEP_X[f], by + STEP_Y[f], bz + STEP_Z[f]))) continue;
                faces.add(bx, by, bz, f);
                int fa = AXIS[f];
                for (int e = 0; e < 6; e++) {
                    if (AXIS[e] == fa) continue;
                    boolean hasE = set.contains(BlockPosPacking.asLong(bx + STEP_X[e], by + STEP_Y[e], bz + STEP_Z[e]));
                    boolean hasEF = set.contains(BlockPosPacking.asLong(bx + STEP_X[e] + STEP_X[f],
                            by + STEP_Y[e] + STEP_Y[f], bz + STEP_Z[e] + STEP_Z[f]));
                    if (hasE && !hasEF) continue; // the face carries on flat into the neighbour
                    int ea = AXIS[e];
                    c[0] = bx;
                    c[1] = by;
                    c[2] = bz;
                    if (POSITIVE[f]) c[fa]++;
                    if (POSITIVE[e]) c[ea]++;
                    int axis = 3 - fa - ea;
                    if (seen[axis].add(BlockPosPacking.asLong(c[0], c[1], c[2]))) edges.add(c[0], c[1], c[2], axis);
                }
            }
        }
        return new OutlineGeometry(edges.toArray(), faces.toArray());
    }

    /**
     * Unit edges as (x, y, z, axis) quadruples: the edge runs from corner (x, y, z) one block along
     * the axis. Shared with the caller; don't modify.
     */
    public int[] edges() {
        return edges;
    }

    /** Exposed faces as (x, y, z, direction) quadruples, (x, y, z) being the block. Shared; don't modify. */
    public int[] faces() {
        return faces;
    }

    public int edgeCount() {
        return edges.length / 4;
    }

    public int faceCount() {
        return faces.length / 4;
    }

    /** The four corners of a block's face, offsets 0 or 1 from the block's minimum corner: x0,y0,z0, ..., x3,y3,z3. */
    public static int[] faceCorners(int direction) {
        return FACE_CORNERS[direction].clone();
    }

    /** Minecraft's unit step of a direction along x, y, z. */
    public static int stepX(int direction) {
        return STEP_X[direction];
    }

    public static int stepY(int direction) {
        return STEP_Y[direction];
    }

    public static int stepZ(int direction) {
        return STEP_Z[direction];
    }

    /** Growable int array, four values at a time. */
    private static final class IntList {
        private int[] a;
        private int n;

        IntList(int capacity) {
            a = new int[Math.max(16, capacity)];
        }

        void add(int v0, int v1, int v2, int v3) {
            if (n + 4 > a.length) a = Arrays.copyOf(a, a.length * 2);
            a[n] = v0;
            a[n + 1] = v1;
            a[n + 2] = v2;
            a[n + 3] = v3;
            n += 4;
        }

        int[] toArray() {
            return Arrays.copyOf(a, n);
        }
    }
}
