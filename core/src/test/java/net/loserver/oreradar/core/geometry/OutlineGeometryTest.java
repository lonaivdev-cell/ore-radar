package net.loserver.oreradar.core.geometry;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.util.HashSet;
import java.util.Set;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.api.Test;

import net.loserver.oreradar.core.net.BlockPosPacking;

/** The hand-counted shapes of 1.0's GeometryCheck, plus the same shapes moved around the world. */
class OutlineGeometryTest {

    static long p(int x, int y, int z) {
        return BlockPosPacking.asLong(x, y, z);
    }

    static long[] shape(String name, int ox, int oy, int oz) {
        return switch (name) {
            case "single" -> new long[] {p(ox, oy, oz)};
            case "bar" -> new long[] {p(ox, oy, oz), p(ox + 1, oy, oz)};
            case "cube" -> {
                long[] cube = new long[8];
                int i = 0;
                for (int x = 0; x < 2; x++) for (int y = 0; y < 2; y++) for (int z = 0; z < 2; z++) cube[i++] = p(ox + x, oy + y, oz + z);
                yield cube;
            }
            case "diagonal" -> new long[] {p(ox, oy, oz), p(ox + 1, oy + 1, oz)};
            case "apart" -> new long[] {p(ox, oy, oz), p(ox + 5, oy, oz)};
            case "L" -> new long[] {p(ox, oy, oz), p(ox + 1, oy, oz), p(ox, oy + 1, oz)};
            case "empty" -> new long[0];
            default -> throw new IllegalArgumentException(name);
        };
    }

    /**
     * shape, unit edges, exposed faces (1.0's counts): a 2x1x1 bar is one box (8 edges along x, 4
     * along y, 4 along z; the seam merges away); a 2x2x2 cube has 12 edges of length 2; two
     * blocks touching along an edge share it; an L tromino has 16 edges on its two L faces plus 6
     * along z, the concave corner included.
     */
    @ParameterizedTest(name = "{0} at {1},{2},{3}")
    @CsvSource({
            "single,   0,   0,   0, 12,  6", "bar,  0, 0, 0, 16, 10", "cube, 0, 0, 0, 24, 24", "diagonal, 0, 0, 0, 23, 12",
            "apart,    0,   0,   0, 24, 12", "L,    0, 0, 0, 22, 14", "empty, 0, 0, 0, 0, 0",
            "single, -1, -64, -1, 12, 6", "bar, -1, -64, -1, 16, 10", "cube, -30000000, -64, 29999998, 24, 24",
            "diagonal, -8, -1, -8, 23, 12", "L, 12345, 318, -54321, 22, 14", "apart, -3, -64, 7, 24, 12",
    })
    void handCountedShapes(String name, int x, int y, int z, int edges, int faces) {
        OutlineGeometry g = OutlineGeometry.build(shape(name, x, y, z));
        assertEquals(edges, g.edgeCount(), name + " edges");
        assertEquals(faces, g.faceCount(), name + " faces");
        assertEquals(edges * 4, g.edges().length);
    }

    @Test
    void singleBlockHasItsTwelveEdgesAtTheRightCorners() {
        OutlineGeometry g = OutlineGeometry.build(new long[] {p(-1, -64, 5)});
        Set<String> edges = new HashSet<>();
        int[] e = g.edges();
        for (int i = 0; i < e.length; i += 4) edges.add(e[i] + "," + e[i + 1] + "," + e[i + 2] + "/" + e[i + 3]);
        Set<String> want = new HashSet<>();
        for (int a = 0; a < 2; a++) {
            for (int b = 0; b < 2; b++) {
                want.add((-1) + "," + (-64 + a) + "," + (5 + b) + "/0");
                want.add((-1 + a) + "," + (-64) + "," + (5 + b) + "/1");
                want.add((-1 + a) + "," + (-64 + b) + "," + 5 + "/2");
            }
        }
        assertEquals(want, edges);
        Set<Integer> dirs = new HashSet<>();
        int[] f = g.faces();
        for (int i = 0; i < f.length; i += 4) {
            assertEquals(-1, f[i]);
            assertEquals(-64, f[i + 1]);
            assertEquals(5, f[i + 2]);
            dirs.add(f[i + 3]);
        }
        assertEquals(Set.of(0, 1, 2, 3, 4, 5), dirs);
    }

    @Test
    void barDropsTheSharedFaces() {
        OutlineGeometry g = OutlineGeometry.build(shape("bar", 0, 0, 0));
        int[] f = g.faces();
        for (int i = 0; i < f.length; i += 4) {
            boolean innerOfFirst = f[i] == 0 && f[i + 3] == OutlineGeometry.EAST;
            boolean innerOfSecond = f[i] == 1 && f[i + 3] == OutlineGeometry.WEST;
            assertEquals(false, innerOfFirst || innerOfSecond, "a face between two matches is not drawn");
        }
    }

    @Test
    void duplicatesAndOrderDoNotMatter() {
        long[] l = shape("L", 3, -60, 3);
        long[] withDuplicates = {l[2], l[0], l[1], l[0], l[2]};
        OutlineGeometry a = OutlineGeometry.build(l);
        OutlineGeometry b = OutlineGeometry.build(withDuplicates);
        assertEquals(a.edgeCount(), b.edgeCount());
        assertEquals(a.faceCount(), b.faceCount());
        assertSame(OutlineGeometry.EMPTY, OutlineGeometry.build(new long[0]));
    }

    @Test
    void largeVeinStaysConsistent() {
        // A solid 10x10x10 cube: 12 edges of length 10, 6 faces of 100 squares.
        long[] cube = new long[1000];
        int i = 0;
        for (int x = 0; x < 10; x++) for (int y = 0; y < 10; y++) for (int z = 0; z < 10; z++) cube[i++] = p(x - 5, y - 64, z + 100);
        OutlineGeometry g = OutlineGeometry.build(cube);
        assertEquals(120, g.edgeCount());
        assertEquals(600, g.faceCount());
    }

    @Test
    void faceCornersFollowMinecraftDirections() {
        // DOWN (y = 0 plane), UP (y = 1), NORTH (z = 0), SOUTH (z = 1), WEST (x = 0), EAST (x = 1).
        int[][] fixedAxis = {{1, 0}, {1, 1}, {2, 0}, {2, 1}, {0, 0}, {0, 1}};
        for (int d = 0; d < 6; d++) {
            int[] c = OutlineGeometry.faceCorners(d);
            assertEquals(12, c.length);
            for (int k = 0; k < 4; k++) assertEquals(fixedAxis[d][1], c[3 * k + fixedAxis[d][0]], "direction " + d);
            int step = new int[] {OutlineGeometry.stepX(d), OutlineGeometry.stepY(d), OutlineGeometry.stepZ(d)}[fixedAxis[d][0]];
            assertEquals(fixedAxis[d][1] == 1 ? 1 : -1, step);
        }
        assertArrayEquals(new int[] {0, 1, 0, 0, 1, 1, 1, 1, 1, 1, 1, 0}, OutlineGeometry.faceCorners(OutlineGeometry.UP));
        OutlineGeometry.faceCorners(0)[0] = 99;
        assertEquals(0, OutlineGeometry.faceCorners(0)[0], "callers get a copy");
    }
}
