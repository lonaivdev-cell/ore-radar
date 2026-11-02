package net.loserver.oreradar.core.scan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import net.loserver.oreradar.core.util.Deadline;

class ScanBudgetTest {

    /** A world whose every section takes about 50 µs, reported to the budget as a platform does. */
    static final class TimedWorld extends RadarScanTest.SlowWorld {
        final ScanBudget budget;

        TimedWorld(ScanBudget budget) {
            this.budget = budget;
            paletteSaysYes = true;
        }

        @Override
        public void scanSection(int sectionY, RadarScan.MatchSink sink) {
            long start = System.nanoTime();
            while (System.nanoTime() - start < 50_000) Thread.onSpinWait();
            super.scanSection(sectionY, sink);
            budget.sectionScanned(System.nanoTime() - start);
        }
    }

    /** Nothing to find: a scan visits every section within 64 blocks, hundreds of them. */
    static RadarScan longScan() {
        return new RadarScan(0.5, 64.5, 0.5, 64, 2, 16, RadarScanTest.MIN_Y, RadarScanTest.MAX_Y);
    }

    @Test
    void oneBudgetPerTickIsSharedByEveryPlayer() {
        ScanBudget budget = ScanBudget.perTick();
        TimedWorld world = new TimedWorld(budget);
        RadarScan[] scans = {longScan(), longScan(), longScan()};
        for (int tick = 0; tick < 6; tick++) {
            budget.startTick(2000, scans.length);
            List<Integer> stepped = new ArrayList<>();
            for (int i = 0; i < scans.length; i++) {
                int player = budget.playerAt(i);
                if (budget.expired()) break; // as the servers do: this tick's scan time is spent
                assertSame(budget.deadline(), budget.deadline(), "one deadline for the whole tick");
                assertFalse(scans[player].step(world, budget.deadline()), "a long scan does not finish in one tick");
                stepped.add(player);
            }
            assertTrue(budget.expired(), "a step that did not finish has used up the tick's budget");
            // Each long scan stops only at the shared deadline, so only the first player of the tick
            // gets to scan: the others wait, and the budget does not grow with the player count.
            assertEquals(List.of(budget.playerAt(0)), stepped, "tick " + tick);
        }
    }

    @Test
    void theFirstPlayerRotatesEveryTick() {
        ScanBudget budget = ScanBudget.perTick();
        List<Integer> firsts = new ArrayList<>();
        for (int tick = 0; tick < 7; tick++) {
            budget.startTick(2000, 3);
            firsts.add(budget.playerAt(0));
            List<Integer> order = List.of(budget.playerAt(0), budget.playerAt(1), budget.playerAt(2));
            assertEquals(3, order.stream().distinct().count(), "every player once per tick: " + order);
        }
        assertEquals(List.of(1, 2, 0, 1, 2, 0, 1), firsts);

        budget.startTick(2000, 2); // a player left: the rotation stays within the new list
        assertTrue(budget.playerAt(0) < 2 && budget.playerAt(1) < 2);
        budget.startTick(2000, 0); // nobody online must not divide by zero
    }

    @Test
    void theDeadlineLeavesRoomForOneMoreSection() {
        ScanBudget budget = ScanBudget.perTick();
        budget.startTick(2000, 1);
        long fresh = budget.allowanceMicros();
        assertTrue(fresh < 2000 && fresh >= 1800, "a generous reserve before any section was timed: " + fresh);

        budget.sectionScanned(400_000); // a 400 µs section
        budget.startTick(2000, 1);
        assertEquals((2_000_000 - (400_000 - (400_000 >> 6))) / 1000, budget.allowanceMicros(), "decayed by 1/64 a tick");

        budget.sectionScanned(5_000_000); // a GC pause during a section
        budget.startTick(2000, 1);
        assertEquals(1500, budget.allowanceMicros(), "the reserve is capped at a quarter of the budget");

        for (int i = 0; i < 800; i++) budget.startTick(2000, 1); // 40 s of quick ticks
        assertTrue(budget.allowanceMicros() >= 1999, "the reserve decays: " + budget.allowanceMicros());

        budget.sectionScanned(30_000); // longer than what is left of the reserve: becomes the reserve
        budget.startTick(2000, 1);
        assertEquals((2_000_000 - (30_000 - (30_000 >> 6))) / 1000, budget.allowanceMicros());
    }

    @Test
    void anUnlimitedBudgetNeverRunsOut() {
        ScanBudget budget = ScanBudget.unlimited();
        budget.startTick(0, 2);
        assertSame(Deadline.NONE, budget.deadline());
        assertFalse(budget.expired());
        assertEquals(1, budget.playerAt(0));
    }

    @Test
    void recentLoadCoversTheTicksBeforeThisOne() {
        ScanBudget budget = ScanBudget.perTick();
        assertEquals(new ScanBudget.Load(0, 0), budget.recent());
        budget.startTick(2000, 1);
        budget.spent(1_000_000);
        budget.spent(500_000);
        assertEquals(new ScanBudget.Load(0, 0), budget.recent(), "the current tick is not counted");
        budget.startTick(2000, 1);
        budget.spent(500_000);
        budget.startTick(2000, 1);
        assertEquals(new ScanBudget.Load(1000, 1500), budget.recent());
        for (int i = 0; i < ScanBudget.RECENT_TICKS; i++) budget.startTick(2000, 1);
        assertEquals(new ScanBudget.Load(0, 0), budget.recent(), "old ticks age out");
    }
}
