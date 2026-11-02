package net.loserver.oreradar.core.scan;

import net.loserver.oreradar.core.util.Deadline;

/**
 * The scan time of one server tick, shared by every player's scan (DESIGN §5 "Time budget"):
 * {@code scanBudgetMicros} bounds what radars add to a tick however many players scan. One instance
 * per server, server thread only.
 *
 * <p>Each tick: {@link #startTick}, then visit the players in the order {@link #playerAt} gives, and
 * hand each running scan {@link #deadline()} until {@link #expired()}. A scan that does not finish
 * continues on a later tick. The order rotates by one player every tick, so one heavy scan early in
 * the list cannot take every tick's budget.
 *
 * <p>A scan looks at its deadline only after each 16 x 16 x 16 section, so a deadline at the very end
 * of the budget would let the tick overrun it by one section. The deadline therefore leaves room for
 * one more: the longest section reported to {@link #sectionScanned} lately. That reserve decays so
 * that one slow moment (a GC pause) does not shrink the budget for long, and it never takes more
 * than a quarter of the budget.
 *
 * <p>It also keeps what the scans took in recent ticks ({@link #spent}, {@link #recent}), for
 * {@code /oreradar info}.
 */
public final class ScanBudget {
    /** Before any section was timed: generous for one section on a slow machine. */
    static final long INITIAL_RESERVE_NANOS = 100_000;
    /** The reserve never takes more than this share of the budget. */
    static final int MAX_RESERVE_DIVISOR = 4;
    /** Ticks of scan time kept for {@link #recent}: 5 seconds. */
    public static final int RECENT_TICKS = 100;

    /** Scan time per tick over the recent ticks, in microseconds. */
    public record Load(long averageMicros, long maxMicros) {}

    private final boolean unlimited;
    private long reserveNanos = INITIAL_RESERVE_NANOS;
    private Deadline deadline = Deadline.NONE;
    private long allowanceMicros = Long.MAX_VALUE;
    private int players;
    private int first;
    private final long[] spentNanos = new long[RECENT_TICKS];
    private int ticks;

    private ScanBudget(boolean unlimited) {
        this.unlimited = unlimited;
    }

    /** The server's budget: {@link #startTick} sets a real deadline. */
    public static ScanBudget perTick() {
        return new ScanBudget(false);
    }

    /** Never runs out; for GameTests, where the result matters and the time does not. */
    public static ScanBudget unlimited() {
        return new ScanBudget(true);
    }

    /**
     * Starts a tick with {@code budgetMicros} for all scans together, among {@code players} players
     * (the size of the list {@link #playerAt} indexes into).
     */
    public void startTick(int budgetMicros, int players) {
        this.players = Math.max(0, players);
        first = this.players == 0 ? 0 : (first + 1) % this.players;
        ticks++;
        spentNanos[ticks % RECENT_TICKS] = 0;
        if (unlimited) return;
        reserveNanos -= reserveNanos >> 6; // about 2% a tick: halves in about 2 seconds
        long budgetNanos = Math.max(0, budgetMicros) * 1000L;
        long reserve = Math.min(reserveNanos, budgetNanos / MAX_RESERVE_DIVISOR);
        allowanceMicros = (budgetNanos - reserve) / 1000;
        deadline = Deadline.afterMicros(allowanceMicros);
    }

    /** The index, into this tick's player list, of the {@code i}-th player to visit (0 ≤ i < players). */
    public int playerAt(int i) {
        return (first + i) % players;
    }

    /** The deadline to hand to {@link RadarScan#step}; the same for every player this tick. */
    public Deadline deadline() {
        return deadline;
    }

    /** True once this tick's scan time is used up: start or continue no more scans until the next tick. */
    public boolean expired() {
        return deadline.expired();
    }

    /**
     * Called by the platform's {@link RadarScan.World} after each section it reads, with the time that
     * section took (or the time since the scan last looked at its deadline).
     */
    public void sectionScanned(long nanos) {
        if (nanos > reserveNanos) reserveNanos = nanos;
    }

    /** A scan step of this tick took this long. */
    public void spent(long nanos) {
        spentNanos[ticks % RECENT_TICKS] += nanos;
    }

    /** Scan time per tick over the last {@value #RECENT_TICKS} ticks that were started, the current one excluded. */
    public Load recent() {
        int n = Math.max(0, Math.min(ticks - 1, RECENT_TICKS - 1));
        long total = 0, max = 0;
        for (int i = 1; i <= n; i++) {
            long spent = spentNanos[Math.floorMod(ticks - i, RECENT_TICKS)];
            total += spent;
            max = Math.max(max, spent);
        }
        return new Load(n == 0 ? 0 : total / n / 1000, max / 1000);
    }

    /** How long this tick's deadline is: the budget less the reserve for one more section; for tests. */
    long allowanceMicros() {
        return allowanceMicros;
    }
}
