package net.loserver.oreradar.core.scan;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.Random;

import org.junit.jupiter.api.Test;

import net.loserver.oreradar.core.ActiveFrom;
import net.loserver.oreradar.core.config.ServerConfig;
import net.loserver.oreradar.core.net.BlockPosPacking;
import net.loserver.oreradar.core.net.ClientPrefs;
import net.loserver.oreradar.core.net.ScanPacket;

class ScanMathTest {

    @Test
    void nearestSortGivesTheKSmallestInOrderWithValuesPaired() {
        // Port of 1.0's GeometryCheck.checkSorting.
        Random rnd = new Random(42);
        for (int round = 0; round < 300; round++) {
            int n = 1 + rnd.nextInt(3000), k = 1 + rnd.nextInt(n);
            double[] key = new double[n];
            long[] val = new long[n];
            for (int i = 0; i < n; i++) {
                key[i] = rnd.nextInt(round % 3 == 0 ? 20 : 100000); // every third round: heavy ties
                val[i] = Double.doubleToLongBits(key[i]) ^ 0x5DEECE66DL;
            }
            double[] expect = key.clone();
            Arrays.sort(expect);
            NearestSort.selectSmallest(key, val, n, k);
            NearestSort.sortByKey(key, val, 0, k - 1);
            for (int i = 0; i < k; i++) {
                assertEquals(expect[i], key[i], "round " + round + " index " + i);
                assertEquals(Double.doubleToLongBits(key[i]) ^ 0x5DEECE66DL, val[i], "value travels with its key");
            }
        }
    }

    @Test
    void outlineRadius() {
        assertEquals(33, ScanMath.outlineRadius(0.4, 2, 96));
        assertEquals(96, ScanMath.outlineRadius(95.2, 2, 96));
        assertEquals(10, ScanMath.outlineRadius(10, 0, 96));
        assertEquals(11, ScanMath.outlineRadius(10.0001, 0, 96));
        assertEquals(7, ScanMath.outlineRadius(7, -1, 96), "a negative probe counts as none");
        assertEquals(256, ScanMath.outlineRadius(200, 64, 256));
        assertEquals(0, ScanMath.ceilDistance(0));
        assertEquals(2, ScanMath.ceilDistance(2.0001));
        assertEquals(0.0, ScanMath.axisDistance(5, 0, 16));
        assertEquals(3.0, ScanMath.axisDistance(-3, 0, 16));
        assertEquals(4.0, ScanMath.axisDistance(20, 0, 16));
    }

    @Test
    void chunkOrderIsNearestFirstAndComplete() {
        for (double[] at : new double[][] {{0.5, 0.5}, {-0.5, -0.5}, {15.99, -16.01}, {-30_000_000.5, 29_999_999.5}}) {
            for (int radius : new int[] {0, 16, 96, 256}) {
                ChunkOrder o = ChunkOrder.around(at[0], at[1], radius);
                int pcx = (int) Math.floor(at[0]) >> 4, pcz = (int) Math.floor(at[1]) >> 4;
                assertEquals(pcx, o.chunkX(0));
                assertEquals(pcz, o.chunkZ(0));
                assertEquals(0.0, o.distance(0));
                int expected = 0;
                for (int cx = pcx - 20; cx <= pcx + 20; cx++) {
                    for (int cz = pcz - 20; cz <= pcz + 20; cz++) {
                        double dx = ScanMath.axisDistance(at[0], cx * 16.0, cx * 16.0 + 16), dz = ScanMath.axisDistance(at[1], cz * 16.0, cz * 16.0 + 16);
                        if (Math.sqrt(dx * dx + dz * dz) <= radius) expected++;
                    }
                }
                assertEquals(expected, o.size(), "at " + Arrays.toString(at) + " r " + radius);
                for (int i = 1; i < o.size(); i++) {
                    assertTrue(o.distance(i) >= o.distance(i - 1));
                    assertTrue(o.distance(i) <= radius);
                }
            }
        }
        assertEquals(1, ChunkOrder.around(8.5, 8.5, 0).size());
    }

    @Test
    void precisionRoundsToFixedCellCentres() {
        long p = BlockPosPacking.asLong(-1, 70, 15);
        assertEquals(p, Precision.round(p, 1, -64, 320));
        assertEquals(BlockPosPacking.asLong(-8, 72, 8), Precision.round(p, 16, -64, 320), "16 = the chunk centre");
        assertEquals(BlockPosPacking.asLong(-8, 72, 8), Precision.round(BlockPosPacking.asLong(-16, 64, 0), 16, -64, 320), "same cell, same answer");
        assertEquals(BlockPosPacking.asLong(-24, 72, 24), Precision.round(BlockPosPacking.asLong(-17, 79, 16), 16, -64, 320));
        assertEquals(-56, BlockPosPacking.y(Precision.round(BlockPosPacking.asLong(0, -64, 0), 16, -64, 320)));
        assertEquals(-64, BlockPosPacking.y(Precision.round(BlockPosPacking.asLong(0, -64, 0), 10, -64, 320)), "clamped to the world bottom");
        assertEquals(319, BlockPosPacking.y(Precision.round(BlockPosPacking.asLong(0, 319, 0), 50, -64, 320)), "clamped to the world top");
        assertEquals(BlockPosPacking.asLong(1, 1, 1), Precision.round(BlockPosPacking.asLong(0, 1, 1), 2, -64, 320));
        assertEquals(BlockPosPacking.asLong(-29_999_992, 8, 8),
                Precision.round(BlockPosPacking.asLong(-30_000_000, 0, 0), 16, -64, 320));
    }

    @Test
    void beepIntervalShrinksWithDistance() {
        assertEquals(4, Beep.intervalTicks(0, 4, 64));
        assertEquals(40, Beep.intervalTicks(64, 4, 64));
        assertEquals(22, Beep.intervalTicks(32, 4, 64));
        assertEquals(-1, Beep.intervalTicks(64.01, 4, 64), "silent beyond maxRange");
        assertEquals(-1, Beep.intervalTicks(Double.NaN, 4, 64));
        assertEquals(-1, Beep.intervalTicks(1, 4, 0));
        assertEquals(50, Beep.intervalTicks(10, 50, 64), "never faster than the server's minimum");
        assertEquals(1, Beep.intervalTicks(0, 0, 64));
        int last = 0;
        for (double d = 0; d <= 64; d += 0.25) {
            int i = Beep.intervalTicks(d, 4, 64);
            assertTrue(i >= last && i >= 4);
            last = i;
        }
        assertEquals(1.4f * 1.15f, Beep.pitch(1.4, 0, 64), 1e-6);
        assertEquals(1.4f * 0.85f, Beep.pitch(1.4, 64, 64), 1e-6);
        assertEquals(2.0f, Beep.pitch(2.0, 0, 64));
        assertEquals(0.5f, Beep.pitch(0.5, 100, 64));
    }

    @Test
    void beepTimer() {
        Beep.Timer t = new Beep.Timer();
        int beeps = 0;
        for (int tick = 1; tick <= 12; tick++) {
            if (t.tick(0, 4, 64)) {
                beeps++;
                assertEquals(0, tick % 4, "beeps every 4 ticks");
            }
        }
        assertEquals(3, beeps);
        for (int i = 0; i < 3; i++) t.tick(0, 4, 64);
        assertFalse(t.tick(Double.NaN, 4, 64), "nothing found: silent, and the count restarts");
        assertFalse(t.tick(0, 4, 64));
        t.reset();
    }

    @Test
    void activeRadarSearchOrder() {
        assertArrayEquals(new int[] {3, 40}, ActiveRadar.searchOrder(3, ActiveFrom.HANDS));
        assertArrayEquals(new int[] {3, 40, 0, 1, 2, 4, 5, 6, 7, 8}, ActiveRadar.searchOrder(3, ActiveFrom.HOTBAR));
        int[] all = ActiveRadar.searchOrder(0, ActiveFrom.INVENTORY);
        assertEquals(37, all.length);
        assertEquals(0, all[0]);
        assertEquals(40, all[1]);
        assertEquals(35, all[36]);
        assertThrows(IllegalArgumentException.class, () -> ActiveRadar.searchOrder(9, ActiveFrom.HANDS));
    }

    @Test
    void activeRadarSelection() {
        ActiveRadar.State[] inv = new ActiveRadar.State[41];
        Arrays.fill(inv, ActiveRadar.State.NONE);
        inv[5] = ActiveRadar.State.OFF;
        inv[20] = ActiveRadar.State.READY;
        assertEquals(new ActiveRadar.Selection(20, ActiveRadar.State.READY), ActiveRadar.select(0, ActiveFrom.INVENTORY, s -> inv[s]));
        ActiveRadar.Selection hotbar = ActiveRadar.select(0, ActiveFrom.HOTBAR, s -> inv[s]);
        assertEquals(new ActiveRadar.Selection(5, ActiveRadar.State.OFF), hotbar, "no ready radar there: report why");
        assertEquals(ScanPacket.Status.OFF, hotbar.idleStatus());
        assertFalse(hotbar.active());
        assertEquals(ActiveRadar.Selection.NONE, ActiveRadar.select(0, ActiveFrom.HANDS, s -> inv[s]));
        assertEquals(ScanPacket.Status.IDLE, ActiveRadar.Selection.NONE.idleStatus());
        inv[40] = ActiveRadar.State.NO_CHARGE;
        inv[0] = ActiveRadar.State.READY;
        assertEquals(new ActiveRadar.Selection(0, ActiveRadar.State.READY), ActiveRadar.select(0, ActiveFrom.HANDS, s -> inv[s]), "main hand first");
        inv[0] = ActiveRadar.State.NONE;
        ActiveRadar.Selection off = ActiveRadar.select(0, ActiveFrom.HANDS, s -> inv[s]);
        assertEquals(40, off.slot());
        assertEquals(ScanPacket.Status.NO_CHARGE, off.idleStatus());
    }

    @Test
    void scanSettingsCombineServerAndPlayer() {
        ServerConfig server = ServerConfig.defaults(false);
        ScanSettings def = ScanSettings.of(server, ClientPrefs.DEFAULT);
        assertEquals(new ScanSettings(ActiveFrom.HANDS, 2, 256, true, true, 1), def);

        ClientPrefs greedy = new ClientPrefs(ActiveFrom.INVENTORY, 8, true, true, true);
        ScanSettings capped = ScanSettings.of(server.with(ServerConfig.ACTIVE_FROM_MAX, ActiveFrom.HOTBAR), greedy);
        assertEquals(ActiveFrom.HOTBAR, capped.activeFrom());
        assertEquals(2, capped.probeChunks());

        ScanSettings noOutlines = ScanSettings.of(server.with(ServerConfig.OUTLINES_ENABLED, false), greedy);
        assertFalse(noOutlines.sendMarkers());
        assertEquals(0, noOutlines.maxMarkers());

        ServerConfig onlyBeep = server.with(ServerConfig.ARROW_ENABLED, false).with(ServerConfig.ARROW_SHOW_DISTANCE, false);
        assertTrue(ScanSettings.of(onlyBeep, greedy).sendNearest());
        assertFalse(ScanSettings.of(onlyBeep, new ClientPrefs(ActiveFrom.HANDS, -1, true, true, false)).sendNearest(),
                "the player muted the beep, and the server allows nothing else that needs the nearest");
        assertFalse(ScanSettings.of(onlyBeep.with(ServerConfig.BEEP_ENABLED, false), greedy).sendNearest());
        assertTrue(ScanSettings.of(server.with(ServerConfig.ARROW_ENABLED, false), greedy).sendNearest(), "distance alone needs it");
    }

    @Test
    void foundBodyHonoursWhatThePlayerMayReceive() {
        RadarScanTest.FakeWorld w = new RadarScanTest.FakeWorld().add(5, 64, 5).add(6, 64, 5).add(7, 64, 5);
        ScanResult r = RadarScanTest.scan(w, 0.5, 64.5, 0.5, 96, 2, 256);
        ScanPacket.Found all = r.toFound(new ScanSettings(ActiveFrom.HANDS, 2, 256, true, true, 1), -64, 320);
        assertTrue(all.hasNearest());
        assertEquals(BlockPosPacking.asLong(5, 64, 5), all.nearestPos());
        assertEquals(3, all.markers().length);
        assertFalse(all.truncated());

        ScanPacket.Found two = r.toFound(new ScanSettings(ActiveFrom.HANDS, 2, 2, true, true, 16), -64, 320);
        assertEquals(2, two.markers().length);
        assertTrue(two.truncated());
        assertEquals(BlockPosPacking.asLong(8, 72, 8), two.nearestPos(), "rounded to the chunk centre");

        ScanPacket.Found bare = r.toFound(new ScanSettings(ActiveFrom.HANDS, 2, 0, false, false, 1), -64, 320);
        assertFalse(bare.hasNearest());
        assertEquals(0, bare.nearestPos());
        assertEquals(0, bare.markers().length);
        assertEquals(3, bare.totalMatches());
        assertEquals(r.outlineRadius(), bare.outlineRadius());
        assertTrue(bare.truncated(), "more matches exist than markers were sent");
    }

    @Test
    void withoutMarkersTheRadiusTellsNoMoreThanTheRoundedNearest() {
        // Outlines off, chunk precision, and a client that asked for no probe chunks: an exact radius
        // would be ceil(distance), and four of them pinned the ore down to a handful of blocks.
        ScanSettings coarse = new ScanSettings(ActiveFrom.HANDS, 0, 0, false, true, 16);
        double[][] eyes = {{0.5, 10.62, 0.5}, {10.5, 10.62, 0.5}, {0.5, 20.62, 0.5}, {0.5, 10.62, -10.5}};
        for (double[] eye : eyes) {
            ScanPacket.Found a = RadarScanTest.scan(new RadarScanTest.FakeWorld().add(37, 11, -21), eye[0], eye[1], eye[2], 96, 0, 0)
                    .toFound(coarse, -64, 320);
            ScanPacket.Found b = RadarScanTest.scan(new RadarScanTest.FakeWorld().add(33, 2, -30), eye[0], eye[1], eye[2], 96, 0, 0)
                    .toFound(coarse, -64, 320);
            assertEquals(a, b, "two ores in one 16-block cell look the same from " + Arrays.toString(eye));
            long cellCentre = BlockPosPacking.asLong(40, 8, -24);
            assertEquals(cellCentre, a.nearestPos());
            assertEquals((int) Math.ceil(ScanMath.distance(cellCentre, eye[0], eye[1], eye[2])), a.outlineRadius(), "measured to the cell centre");
        }

        ScanResult r = RadarScanTest.scan(new RadarScanTest.FakeWorld().add(37, 11, -21), 0.5, 10.62, 0.5, 96, 0, 256);
        ScanSettings outlined = new ScanSettings(ActiveFrom.HANDS, 0, 256, true, true, 16);
        assertEquals(r.outlineRadius(), r.toFound(outlined, -64, 320).outlineRadius(), "markers are exact, so the radius stays exact");
        ScanSettings hidden = new ScanSettings(ActiveFrom.HANDS, 0, 0, false, false, 16);
        assertEquals(r.toFound(coarse, -64, 320).outlineRadius(), r.toFound(hidden, -64, 320).outlineRadius(),
                "rounded too when the nearest is not sent at all");
    }
}
