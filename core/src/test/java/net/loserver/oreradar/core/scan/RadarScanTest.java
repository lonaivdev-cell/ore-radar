package net.loserver.oreradar.core.scan;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import org.junit.jupiter.api.Test;

import net.loserver.oreradar.core.ActiveFrom;
import net.loserver.oreradar.core.net.BlockPosPacking;
import net.loserver.oreradar.core.util.Deadline;

class RadarScanTest {
    static final int MIN_Y = -64, MAX_Y = 320;

    /** A world holding only the matching blocks, grouped by section like a real palette would allow. */
    static class FakeWorld implements RadarScan.World {
        final Map<Long, List<int[]>> sections = new HashMap<>();
        final Set<Long> unloaded = new HashSet<>();
        final List<long[]> selected = new ArrayList<>();
        boolean paletteSaysYes;
        int cx, cz;

        FakeWorld add(int x, int y, int z) {
            sections.computeIfAbsent(key(x >> 4, y >> 4, z >> 4), k -> new ArrayList<>()).add(new int[] {x, y, z});
            return this;
        }

        static long key(int cx, int sy, int cz) {
            return BlockPosPacking.asLong(cx, sy, cz);
        }

        @Override
        public boolean selectChunk(int chunkX, int chunkZ) {
            selected.add(new long[] {chunkX, chunkZ});
            cx = chunkX;
            cz = chunkZ;
            return !unloaded.contains(BlockPosPacking.asLong(chunkX, 0, chunkZ));
        }

        @Override
        public boolean mayContain(int sectionY) {
            return paletteSaysYes || sections.containsKey(key(cx, sectionY, cz));
        }

        @Override
        public void scanSection(int sectionY, RadarScan.MatchSink sink) {
            for (int[] p : sections.getOrDefault(key(cx, sectionY, cz), List.of())) sink.match(p[0], p[1], p[2]);
        }
    }

    static ScanResult scan(FakeWorld w, double x, double y, double z, int maxRadius, int probe, int maxMarkers) {
        RadarScan s = new RadarScan(x, y, z, maxRadius, probe, maxMarkers, MIN_Y, MAX_Y);
        assertTrue(s.step(w, Deadline.NONE));
        return s.result();
    }

    @Test
    void findsTheNearestAndOutlinesWithinTheProbeRadius() {
        FakeWorld w = new FakeWorld().add(3, 64, 0).add(10, 64, 0).add(40, 64, 0).add(60, 64, 0).add(-200, 64, 0);
        ScanResult r = scan(w, 0.5, 64.5, 0.5, 96, 2, 256);
        assertTrue(r.found());
        assertEquals(BlockPosPacking.asLong(3, 64, 0), r.nearest());
        assertEquals(3.0, r.nearestDistance(), 1e-9);
        assertEquals(3 + 32, r.outlineRadius());
        assertEquals(2, r.totalMatches(), "40 and 60 are past 35");
        assertArrayEquals(new long[] {BlockPosPacking.asLong(3, 64, 0), BlockPosPacking.asLong(10, 64, 0)}, r.markers());
    }

    @Test
    void nothingFound() {
        ScanResult r = scan(new FakeWorld().add(500, 64, 500), 0, 64, 0, 96, 2, 256);
        assertFalse(r.found());
        assertEquals(0, r.totalMatches());
        assertEquals(96, r.outlineRadius());
        assertEquals(0, r.markerCount());
        assertTrue(Double.isNaN(r.nearestDistance()));
        assertThrows(IllegalStateException.class, () -> r.toFound(new ScanSettings(ActiveFrom.HANDS, 2, 256, true, true, 1), MIN_Y, MAX_Y));
    }

    @Test
    void markersAreCappedNearestFirstAndTheTotalStaysExact() {
        FakeWorld w = new FakeWorld();
        for (int i = 1; i <= 50; i++) w.add(i, 0, 0);
        ScanResult r = scan(w, 0.5, 0.5, 0.5, 96, 8, 10);
        assertEquals(50, r.totalMatches());
        assertEquals(10, r.markerCount());
        long[] m = r.markers();
        for (int i = 0; i < 10; i++) assertEquals(BlockPosPacking.asLong(i + 1, 0, 0), m[i]);
        ScanResult none = scan(w, 0.5, 0.5, 0.5, 96, 8, 0);
        assertEquals(50, none.totalMatches(), "counted even when no markers are kept");
        assertEquals(0, none.markerCount());
    }

    @Test
    void worldBottomNegativeCoordinatesAndEyesOutsideTheWorld() {
        FakeWorld w = new FakeWorld().add(-1, -64, -1).add(-17, -64, -17).add(-1, 319, -1);
        ScanResult low = scan(w, -0.5, -60.0, -0.5, 64, 0, 16);
        assertEquals(BlockPosPacking.asLong(-1, -64, -1), low.nearest());
        assertEquals(1, low.totalMatches(), "probe 0: only matches as close as the nearest, rounded up");
        ScanResult above = scan(w, -0.5, 400.0, -0.5, 96, 8, 16);
        assertEquals(BlockPosPacking.asLong(-1, 319, -1), above.nearest(), "an eye above the build limit still scans down");
        assertEquals(19, RadarScan.sectionOrder(400, -4, 20)[0]);
        assertArrayEquals(new int[] {4, 5, 3, 6, 2, 7, 1, 0}, RadarScan.sectionOrder(70, 0, 8));
        assertArrayEquals(new int[] {-4, -3, -2}, RadarScan.sectionOrder(-1000, -4, -1));
    }

    @Test
    void unloadedChunksAndPaletteMissesAreSkipped() {
        FakeWorld w = new FakeWorld().add(20, 64, 0).add(40, 64, 0);
        w.unloaded.add(BlockPosPacking.asLong(1, 0, 0));
        ScanResult r = scan(w, 0.5, 64.5, 0.5, 96, 0, 16);
        assertEquals(BlockPosPacking.asLong(40, 64, 0), r.nearest(), "the match in the unloaded chunk is invisible");
        FakeWorld blind = new FakeWorld() {
            @Override
            public boolean mayContain(int sectionY) {
                return false;
            }
        }.add(2, 64, 2);
        assertFalse(scan(blind, 0.5, 64.5, 0.5, 96, 0, 16).found());
    }

    @Test
    void theScanStopsAtTheFirstChunkBeyondTheCutOff() {
        FakeWorld w = new FakeWorld().add(1, 64, 1);
        RadarScan s = new RadarScan(0.5, 64.5, 0.5, 256, 0, 16, MIN_Y, MAX_Y);
        assertTrue(s.step(w, Deadline.NONE));
        // Nearest is ~1.2 blocks away, so only the 2x2 chunks within 2 blocks of the eye can matter.
        assertTrue(w.selected.size() <= 4, "visited " + w.selected.size());
        assertEquals(1, s.result().totalMatches());
    }

    @Test
    void aScanContinuedAcrossDeadlinesGivesTheSameResult() {
        FakeWorld w = new FakeWorld();
        Random rnd = new Random(3);
        for (int i = 0; i < 400; i++) w.add(rnd.nextInt(160) - 80, rnd.nextInt(120) - 64, rnd.nextInt(160) - 80);
        w.paletteSaysYes = true;
        ScanResult once = scan(w, 0.5, 0.5, 0.5, 96, 8, 64);
        RadarScan s = new RadarScan(0.5, 0.5, 0.5, 96, 8, 64, MIN_Y, MAX_Y);
        int steps = 0;
        while (!s.step(w, Deadline.afterMicros(0))) steps++;
        assertTrue(steps > 0);
        assertTrue(s.isDone());
        ScanResult resumed = s.result();
        assertEquals(once.nearest(), resumed.nearest());
        assertEquals(once.totalMatches(), resumed.totalMatches());
        assertEquals(once.outlineRadius(), resumed.outlineRadius());
        assertArrayEquals(distances(once.markers(), 0.5, 0.5, 0.5), distances(resumed.markers(), 0.5, 0.5, 0.5));
        assertThrows(IllegalStateException.class, () -> new RadarScan(0, 0, 0, 16, 0, 0, MIN_Y, MAX_Y).result());
    }

    /** Spends a little time per section so that a 1 µs budget runs out after every section scanned. */
    static class SlowWorld extends FakeWorld {
        int sectionsScanned;

        @Override
        public void scanSection(int sectionY, RadarScan.MatchSink sink) {
            sectionsScanned++;
            long start = System.nanoTime();
            while (System.nanoTime() - start < 5_000) Thread.onSpinWait();
            super.scanSection(sectionY, sink);
        }
    }

    @Test
    void aLargeScanWithATinyBudgetFinishesOverManyTicksWithTheUnboundedResult() {
        SlowWorld w = new SlowWorld();
        Random rnd = new Random(7);
        // Thousands of matches, none closer than 60 blocks: the cut-off stays wide, so most of the
        // 96-block sphere is visited, and far more matches exist than markers are kept.
        while (w.sections.values().stream().mapToInt(List::size).sum() < 20_000) {
            int x = rnd.nextInt(193) - 96, y = rnd.nextInt(193) - 32, z = rnd.nextInt(193) - 96;
            double d = Math.sqrt((x + 0.5 - 0.5) * (x + 0.5 - 0.5) + (y + 0.5 - 64.5) * (y + 0.5 - 64.5) + (z + 0.5 - 0.5) * (z + 0.5 - 0.5));
            if (d >= 60 && d <= 96) w.add(x, y, z);
        }
        w.paletteSaysYes = true; // every section in range is scanned: the worst case for the budget
        for (int i = 0; i < 6; i++) w.unloaded.add(BlockPosPacking.asLong(rnd.nextInt(13) - 6, 0, rnd.nextInt(13) - 6));

        RadarScan unbounded = new RadarScan(0.5, 64.5, 0.5, 96, 2, 256, MIN_Y, MAX_Y);
        assertTrue(unbounded.step(w, Deadline.NONE));
        ScanResult once = unbounded.result();
        int sections = w.sectionsScanned;

        w.sectionsScanned = 0;
        RadarScan s = new RadarScan(0.5, 64.5, 0.5, 96, 2, 256, MIN_Y, MAX_Y);
        int ticks = 1;
        while (!s.step(w, Deadline.afterMicros(1))) {
            assertFalse(s.isDone());
            assertThrows(IllegalStateException.class, s::result);
            ticks++;
        }
        assertEquals(sections, w.sectionsScanned, "no section scanned twice or skipped");
        assertTrue(sections > 1000, "a large scan: " + sections + " sections");
        assertTrue(ticks >= sections, "at most one section per tick with this budget: " + ticks + " ticks");
        assertTrue(s.step(w, Deadline.afterMicros(1)), "a finished scan stays finished");

        ScanResult resumed = s.result();
        assertTrue(once.found());
        assertTrue(once.totalMatches() > 256, "more matches than markers: " + once.totalMatches());
        assertEquals(once.nearest(), resumed.nearest());
        assertEquals(once.nearestDistance(), resumed.nearestDistance());
        assertEquals(once.outlineRadius(), resumed.outlineRadius());
        assertEquals(once.totalMatches(), resumed.totalMatches());
        assertArrayEquals(once.markers(), resumed.markers());
    }

    @Test
    void aChunkUnloadedBetweenTicksIsSkippedAndTheNextOneIsScannedWhole() {
        // Chunk (0, 0) is loaded when the scan starts there and unloaded before the next tick.
        SlowWorld w = new SlowWorld() {
            int visitsToHome;

            @Override
            public boolean selectChunk(int chunkX, int chunkZ) {
                if (chunkX == 0 && chunkZ == 0 && visitsToHome++ > 0) unloaded.add(BlockPosPacking.asLong(0, 0, 0));
                return super.selectChunk(chunkX, chunkZ);
            }
        };
        w.paletteSaysYes = true;
        w.add(20, 64, 8).add(-5, 64, 8).add(8, 64, 20).add(8, 64, -5); // the eye's section of each neighbour
        RadarScan s = new RadarScan(8.5, 64.5, 8.5, 32, 2, 16, MIN_Y, MAX_Y);
        assertFalse(s.step(w, Deadline.afterMicros(1)), "stops after the first section of the home chunk");
        assertTrue(s.step(w, Deadline.NONE));
        assertEquals(4, s.result().totalMatches(), "each neighbour scanned from its first section");
        assertEquals(12.0, s.result().nearestDistance(), 1e-9);
    }

    @Test
    void matchesABruteForceScanOnRandomWorlds() {
        Random rnd = new Random(42);
        for (int round = 0; round < 300; round++) {
            FakeWorld w = new FakeWorld();
            int n = rnd.nextInt(round % 5 == 0 ? 3000 : 60);
            List<int[]> all = new ArrayList<>();
            Set<Long> seen = new HashSet<>();
            for (int i = 0; i < n; i++) {
                int[] p = {rnd.nextInt(300) - 150, rnd.nextInt(384) - 64, rnd.nextInt(300) - 150};
                if (!seen.add(BlockPosPacking.asLong(p[0], p[1], p[2]))) continue;
                all.add(p);
                w.add(p[0], p[1], p[2]);
            }
            double x = rnd.nextDouble() * 100 - 50, y = rnd.nextDouble() * 384 - 64, z = rnd.nextDouble() * 100 - 50;
            int maxRadius = 16 + rnd.nextInt(241), probe = rnd.nextInt(9), maxMarkers = rnd.nextInt(5) == 0 ? 0 : 1 + rnd.nextInt(300);
            ScanResult r = scan(w, x, y, z, maxRadius, probe, maxMarkers);

            double[] d = all.stream().mapToDouble(p -> ScanMath.distance(BlockPosPacking.asLong(p[0], p[1], p[2]), x, y, z))
                    .filter(v -> Math.ceil(v) <= maxRadius).sorted().toArray();
            String ctx = "round " + round;
            if (d.length == 0) {
                assertFalse(r.found(), ctx);
                continue;
            }
            assertEquals(d[0], r.nearestDistance(), 1e-9, ctx);
            int radius = ScanMath.outlineRadius(d[0], probe, maxRadius);
            assertEquals(radius, r.outlineRadius(), ctx);
            long within = Arrays.stream(d).filter(v -> Math.ceil(v) <= radius).count();
            assertEquals(within, r.totalMatches(), ctx);
            double[] want = Arrays.copyOf(d, (int) Math.min(within, maxMarkers));
            assertArrayEquals(want, distances(r.markers(), x, y, z), 1e-9, ctx);
        }
    }

    static double[] distances(long[] markers, double x, double y, double z) {
        return Arrays.stream(markers).mapToDouble(p -> ScanMath.distance(p, x, y, z)).toArray();
    }
}
