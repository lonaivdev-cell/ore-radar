package net.loserver.oreradar.paper;

import org.bukkit.World;

import net.loserver.oreradar.core.energy.ChargeDrain;
import net.loserver.oreradar.core.net.ScanPacket;
import net.loserver.oreradar.core.scan.ActiveRadar;
import net.loserver.oreradar.core.scan.Beep;
import net.loserver.oreradar.core.scan.EarlyScans;
import net.loserver.oreradar.core.scan.RadarScan;
import net.loserver.oreradar.core.scan.ScanSettings;

/** What the plugin keeps per online player. Server thread only. */
final class PlayerSession {
    /** A scan in progress; one that runs out of budget continues on later ticks (DESIGN §5 "Time budget"). */
    static final class Job {
        final RadarScan scan;
        final ChunkScanWorld view;
        final ScanSettings settings;
        final World world;
        /** CPU time and ticks spent so far. */
        long nanos;
        int steps;

        /** A job is dropped whenever the radar, its target or the world changes, so it needs no record of them. */
        Job(RadarScan scan, ChunkScanWorld view, ScanSettings settings, World world) {
            this.scan = scan;
            this.view = view;
            this.settings = settings;
            this.world = world;
        }
    }

    final ChargeDrain drain = new ChargeDrain();
    /**
     * Drain the radar in use ({@link #radarId}) has been charged but not yet written to its item:
     * written when the lore's charge step changes, or when the radar stops being the one in use
     * (see {@link RadarService}).
     */
    int owed;
    /** The radar in use drained on the last tick, so stopping must write what it owes. */
    boolean draining;
    final Beep.Timer beep = new Beep.Timer();
    final OutlineDisplays outlines = new OutlineDisplays();
    final CompassNeedle needle = new CompassNeedle();

    /** The radar being reported on: its slot (-1 for none), id, target and state as of this tick. */
    int slot = -1;
    Long radarId;
    String spec;
    ActiveRadar.State state = ActiveRadar.State.NONE;
    World world;
    /** A scan should start as soon as the tick's budget allows: the cadence came, or an early scan was granted. */
    boolean scanDue;
    final EarlyScans early = new EarlyScans();

    Job job;
    /** The last status, and the last FOUND body (null otherwise). */
    ScanPacket.Status status = ScanPacket.Status.IDLE;
    ScanPacket.Found found;

    long nextActionBar;
    /** The status the action bar last showed, so a line that does not change is sent once. */
    ScanPacket.Status shownStatus;
    /** A one-off action-bar message stays up until this tick before the status line returns. */
    long messageUntil;
    long lastUse = Long.MIN_VALUE / 2;

    /** The last finished scan, for {@code /oreradar info}. */
    long lastScanMicros = -1;
    int lastScanSteps;
    int lastScanMatches;
    ChunkScanWorld.Stats lastScanStats;
}
