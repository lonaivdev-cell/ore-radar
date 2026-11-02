package net.loserver.oreradar.core.scan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class EarlyScansTest {
    private static boolean change(EarlyScans e) {
        e.want();
        return e.take();
    }

    @Test
    void threeBackToBackThenOnePerScanInterval() {
        EarlyScans e = new EarlyScans();
        for (int i = 0; i < EarlyScans.BURST; i++) assertTrue(change(e), "early scan " + (i + 1));
        assertFalse(change(e), "the burst is used up");
        for (int t = 1; t < 20; t++) {
            e.tick(20);
            assertFalse(e.take(), "tick " + t + ": not refilled yet");
        }
        e.tick(20);
        assertTrue(e.take(), "the change still waiting gets the refilled scan");
        assertFalse(change(e), "and only that one");
    }

    @Test
    void aClientChangingSomethingEveryTickGetsOneScanPerInterval() {
        EarlyScans e = new EarlyScans();
        int scans = 0;
        for (int t = 0; t < 200; t++) {
            e.tick(20);
            if (change(e)) scans++;
        }
        assertEquals(EarlyScans.BURST + 9, scans, "3 at once, then one at ticks 20, 40, ... 180");
    }

    @Test
    void nothingIsTakenWithoutAChange() {
        EarlyScans e = new EarlyScans();
        assertFalse(e.take());
        e.want();
        assertTrue(e.take());
        assertFalse(e.take(), "one change, one scan");
        for (int i = 1; i < EarlyScans.BURST; i++) assertTrue(change(e), "unused early scans are still there");
    }

    @Test
    void aScanThatStartsAnywayCoversTheChangeForFree() {
        EarlyScans e = new EarlyScans();
        e.want();
        e.started();
        assertFalse(e.take(), "the cadence scan covered it");
        for (int i = 0; i < EarlyScans.BURST; i++) assertTrue(change(e), "and used no early scan");
    }

    @Test
    void refillsStopAtTheBurstAndFollowAShorterInterval() {
        EarlyScans e = new EarlyScans();
        for (int t = 0; t < 1000; t++) e.tick(20);
        for (int i = 0; i < EarlyScans.BURST; i++) assertTrue(change(e));
        assertFalse(change(e), "idle time does not bank more than the burst");
        for (int t = 0; t < 50; t++) e.tick(200);
        e.tick(5);
        assertTrue(e.take(), "after a reload to a shorter interval, time already waited counts");
    }
}
