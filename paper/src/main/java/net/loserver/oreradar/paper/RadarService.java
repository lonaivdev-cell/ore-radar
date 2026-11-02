package net.loserver.oreradar.paper;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.scheduler.BukkitTask;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.loserver.oreradar.core.config.ClientConfig;
import net.loserver.oreradar.core.config.ServerConfig;
import net.loserver.oreradar.core.designation.TargetRules;
import net.loserver.oreradar.core.energy.ChargeDrain;
import net.loserver.oreradar.core.net.BlockPosPacking;
import net.loserver.oreradar.core.net.ClientPrefs;
import net.loserver.oreradar.core.net.RadarProtocol;
import net.loserver.oreradar.core.net.ScanPacket;
import net.loserver.oreradar.core.scan.ActiveRadar;
import net.loserver.oreradar.core.scan.Beep;
import net.loserver.oreradar.core.scan.RadarScan;
import net.loserver.oreradar.core.scan.ScanBudget;
import net.loserver.oreradar.core.scan.ScanMath;
import net.loserver.oreradar.core.scan.ScanResult;
import net.loserver.oreradar.core.scan.ScanSettings;

/**
 * Radars at work (DESIGN §5, §8): every tick, for every online player, picks the active radar,
 * drains its charge, runs or continues the scan within the tick's shared time budget, and shows
 * the result as compass needle, action bar, beep and block-display outlines. Every player is
 * served as a vanilla client: the plugin speaks no mod protocol. Runs on the server thread from
 * one repeating task.
 *
 * <p><b>When a radar is written.</b> A vanilla client replays the hand's equip animation whenever
 * the held item changes, so the drain of the radar in use is kept here and written to the item
 * only when the lore's 5% step changes or the radar runs empty ({@link RadarItems#chargeStep}),
 * when it stops being the radar in use (put away, switched off), and on quit, world change and
 * plugin disable. The action bar always shows the exact charge. A radar that leaves the player
 * first (dropped, put in a chest, dropped at death) keeps that debt under its id, paid when it is
 * next in use, by anyone; only a server restart forgets it.
 */
final class RadarService {
    static final String USE_PERMISSION = "oreradar.use";
    /** 1.0's cooldown: holding the use key repeats the use every 4 ticks, which must not toggle twice. */
    private static final int USE_COOLDOWN_TICKS = 10;
    /** Vanilla clients fade the action bar after about 3 s; refresh it well before. */
    private static final int ACTION_BAR_TICKS = 20;
    private static final int MESSAGE_TICKS = 40;
    /** Log a failing player tick at most this often, not every tick. */
    private static final int ERROR_LOG_TICKS = 1200;
    private static final int PREFILTER_CACHE_LIMIT = 256;
    private static final int STRAY_DRAIN_LIMIT = 1024;
    /** The mod client's default beep, so a radar sounds the same on Paper as with the mod. */
    private static final String BEEP_SOUND = ClientConfig.BEEP_SOUND.defaultValue();
    private static final float BEEP_VOLUME = ClientConfig.BEEP_VOLUME.defaultValue().floatValue();
    private static final double BEEP_PITCH = ClientConfig.BEEP_PITCH.defaultValue();

    private final OreRadarPlugin plugin;
    private final Map<UUID, PlayerSession> sessions = new HashMap<>();
    /** Palette pre-filter states per target spec; empty when they cannot be listed. Cleared on reload. */
    private final Map<String, Optional<List<BlockData>>> prefilters = new HashMap<>();
    private final Map<UUID, Long> lastErrorLog = new HashMap<>();
    /** Drain owed by radars that left their player before it could be written, by radar id; paid when one is next in use. */
    private final Map<Long, Integer> strayDrain = new LinkedHashMap<>() {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Long, Integer> eldest) {
            return size() > STRAY_DRAIN_LIMIT;
        }
    };
    private final ScanBudget budget = ScanBudget.perTick();
    private BukkitTask task;
    private long tick;

    RadarService(OreRadarPlugin plugin) {
        this.plugin = plugin;
    }

    void start() {
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 1, 1);
    }

    /** Stops scanning and undoes everything shown to players (plugin disable). */
    void stop() {
        if (task != null) task.cancel();
        task = null;
        for (Player p : Bukkit.getOnlinePlayers()) quit(p);
        sessions.clear();
    }

    PlayerSession session(Player p) {
        return sessions.computeIfAbsent(p.getUniqueId(), id -> new PlayerSession());
    }

    /** One server tick: every online player, all scans within one {@code scanBudgetMicros} (DESIGN §5). */
    private void tick() {
        tick++;
        ServerRules r = plugin.rules();
        List<Player> online = new ArrayList<>(Bukkit.getOnlinePlayers());
        if (online.isEmpty()) return;
        budget.startTick(r.config().scanBudgetMicros(), online.size());
        for (int i = 0; i < online.size(); i++) {
            Player p = online.get(budget.playerAt(i));
            PlayerSession s = session(p);
            try {
                tickPlayer(p, s, r);
            } catch (RuntimeException e) {
                // One player's odd item or a bug must not stop radars for everyone, nor flood the log.
                s.job = null;
                Long last = lastErrorLog.get(p.getUniqueId());
                if (last == null || tick - last >= ERROR_LOG_TICKS) {
                    lastErrorLog.put(p.getUniqueId(), tick);
                    plugin.getLogger().log(Level.WARNING, "Radar update failed for " + p.getName(), e);
                }
            }
        }
    }

    private void tickPlayer(Player p, PlayerSession s, ServerRules r) {
        ServerConfig c = r.config();
        s.early.tick(c.scanIntervalTicks());
        ScanSettings settings = ScanSettings.of(c, ClientPrefs.DEFAULT);
        PlayerInventory inv = p.getInventory();
        ActiveRadar.Selection sel = p.hasPermission(USE_PERMISSION)
                ? ActiveRadar.select(inv.getHeldItemSlot(), settings.activeFrom(), slot -> state(inv.getItem(slot), r))
                : ActiveRadar.Selection.NONE;
        RadarItems.RadarData data = sel.slot() < 0 ? null : RadarItems.read(inv.getItem(sel.slot()));
        String spec = data == null ? null : data.spec();
        Long id = data == null ? null : data.id();
        if (data != null && id == null) id = giveId(inv, sel.slot(), r);
        World world = p.getWorld();
        boolean denied = sel.active() && c.isDimensionDenied(world.getKey().toString());
        boolean active = sel.active() && !denied;
        if (sel.slot() >= 0 && s.slot >= 0 && id != null && id.equals(s.radarId) && Objects.equals(spec, s.spec) && world == s.world) {
            // The same radar, perhaps moved (a hand swap): its scan, outlines and unwritten drain stay with it.
            if (sel.slot() != s.slot) radarMoved(s, sel.slot());
            if (sel.state() != s.state) {
                // Switched off, emptied or recharged: what it shows changes now, not at the cadence.
                clearResult(s);
                s.early.want();
            }
        } else if (sel.slot() != s.slot || !Objects.equals(id, s.radarId) || !Objects.equals(spec, s.spec) || world != s.world) {
            switchRadar(p, s, r, sel.slot(), id, spec, world);
        }
        if (!active && s.draining) settle(p, s, r); // stopped draining: nothing more to wait for
        s.state = sel.state();
        s.draining = active && c.energyEnabled();

        if (s.draining) {
            int units = s.drain.tick(p.getGameMode() == GameMode.CREATIVE, c.drainPerSecond());
            if (units > 0) {
                s.owed += units;
                drainInHand(inv, s, r);
            }
        }

        boolean cadence = (tick + p.getEntityId()) % c.scanIntervalTicks() == 0;
        if (cadence) s.needle.clearStale(p);
        if (!active) {
            reportInactive(p, s, denied);
        } else {
            // A change scans at once while early scans are left (DESIGN §5), so scrolling the
            // hotbar cannot start a scan every tick; otherwise it waits for the cadence.
            if (s.job == null && !s.scanDue && (cadence || s.early.take())) s.scanDue = true;
            if (s.job != null) {
                if (!budget.expired()) step(p, s, r);
            } else if (s.scanDue && !budget.expired()) {
                s.scanDue = false;
                s.early.started();
                startScan(p, s, r, settings);
            }
        }
        feedback(p, s, r, active);
    }

    private static ActiveRadar.State state(ItemStack item, ServerRules r) {
        if (item == null || item.getType() != Material.COMPASS) return ActiveRadar.State.NONE;
        RadarItems.RadarData d = RadarItems.read(item);
        if (d == null || d.spec() == null || !r.rules().resolve(d.spec()).ok()) return ActiveRadar.State.NONE;
        if (d.off()) return ActiveRadar.State.OFF;
        ServerConfig c = r.config();
        if (c.energyEnabled() && d.charge(c.capacity()) <= 0) return ActiveRadar.State.NO_CHARGE;
        return ActiveRadar.State.READY;
    }

    /** A different radar, target or world: settle the old radar's charge and drop what was shown for it. */
    private void switchRadar(Player p, PlayerSession s, ServerRules r, int slot, Long id, String spec, World world) {
        settle(p, s, r);
        s.drain.reset();
        clearResult(s);
        s.beep.reset();
        s.outlines.clear();
        s.needle.clear(p);
        s.slot = slot;
        s.radarId = id;
        s.spec = spec;
        s.world = world;
        s.early.want();
        s.nextActionBar = tick;
        s.shownStatus = null;
        // Drain this radar still owes from before it left a player.
        Integer stray = id == null ? null : strayDrain.remove(id);
        if (stray != null) s.owed += stray;
    }

    /** The radar in use moved within reach (a hand swap); its needle goes with it. */
    private static void radarMoved(PlayerSession s, int slot) {
        s.slot = slot;
        s.needle.moved(slot);
    }

    private static long giveId(PlayerInventory inv, int slot, ServerRules r) {
        ItemStack item = inv.getItem(slot);
        long id = RadarItems.assignId(item, r);
        inv.setItem(slot, item);
        return id;
    }

    /**
     * New drain on the radar in use, still in its slot: written to the item only when the lore's
     * charge step would change; meanwhile, where that cannot re-bob the hand, the bar follows it.
     */
    private static void drainInHand(PlayerInventory inv, PlayerSession s, ServerRules r) {
        ItemStack item = inv.getItem(s.slot);
        RadarItems.RadarData d = RadarItems.read(item);
        if (d == null) return;
        int capacity = r.config().capacity();
        int stored = d.charge(capacity);
        int live = ChargeDrain.apply(stored, s.owed);
        if (RadarItems.chargeStep(live, capacity) != RadarItems.chargeStep(stored, capacity)) {
            RadarItems.setCharge(item, live, r);
            s.owed = 0;
        } else if (!RadarItems.showLiveCharge(item, live, r)) {
            return;
        }
        inv.setItem(s.slot, item);
    }

    /**
     * Writes everything the radar in use owes now: the drain kept back, and what has accrued since
     * the last hand-out. It may have moved within the inventory by now, so it is found by its id;
     * one no longer there keeps the debt under its id until it is next in use.
     */
    private void settle(Player p, PlayerSession s, ServerRules r) {
        int owed = s.owed + s.drain.flush();
        s.owed = 0;
        if (owed <= 0) return;
        PlayerInventory inv = p.getInventory();
        int slot = slotOf(inv, s.slot, s.radarId);
        if (slot >= 0) drain(inv, slot, owed, r);
        else park(s.radarId, owed);
    }

    /** Writes the radar in use now, wherever it is ({@code /oreradar charge}, before it sets a new charge). */
    void settle(Player p) {
        PlayerSession s = sessions.get(p.getUniqueId());
        if (s != null) settle(p, s, plugin.rules());
    }

    private void park(Long id, int owed) {
        if (id != null && owed > 0) strayDrain.merge(id, owed, Integer::sum);
    }

    /** Where the radar with this id is now: its old slot if still there, else any slot holding it; -1 if gone. */
    private static int slotOf(PlayerInventory inv, int oldSlot, Long id) {
        if (oldSlot >= 0 && holds(inv, oldSlot, id)) return oldSlot;
        if (id == null) return -1; // a radar without an id is known only by where it was
        for (int slot = 0; slot <= RadarProtocol.OFFHAND_SLOT; slot++) {
            if (RadarProtocol.isValidSlot(slot) && holds(inv, slot, id)) return slot;
        }
        return -1;
    }

    private static boolean holds(PlayerInventory inv, int slot, Long id) {
        RadarItems.RadarData d = RadarItems.read(inv.getItem(slot));
        return d != null && Objects.equals(d.id(), id);
    }

    /** Forgets the scan and its result; {@code status} stays FOUND only alongside a {@code found}. */
    private static void clearResult(PlayerSession s) {
        s.job = null;
        s.found = null;
        s.status = ScanPacket.Status.IDLE;
    }

    private static void drain(PlayerInventory inv, int slot, int amount, ServerRules r) {
        ItemStack item = inv.getItem(slot);
        if (charge(item, amount, r)) inv.setItem(slot, item);
    }

    /** Takes {@code amount} off a radar's stored charge; false if the item is not a radar. */
    private static boolean charge(ItemStack item, int amount, ServerRules r) {
        RadarItems.RadarData d = RadarItems.read(item);
        if (d == null) return false;
        RadarItems.setCharge(item, ChargeDrain.apply(d.charge(r.config().capacity()), amount), r);
        return true;
    }

    private void startScan(Player p, PlayerSession s, ServerRules r, ScanSettings settings) {
        TargetRules.Resolution<Material> res = r.rules().resolve(s.spec);
        if (!res.ok()) return;
        ServerConfig c = r.config();
        World w = p.getWorld();
        Location eye = p.getEyeLocation();
        // A player is shown at most maxDisplayOutlines blocks; keeping more would be wasted work.
        RadarScan scan = new RadarScan(eye.getX(), eye.getY(), eye.getZ(), c.maxRadius(), settings.probeChunks(),
                Math.min(settings.maxMarkers(), c.paperMaxDisplayOutlines()), w.getMinHeight(), w.getMaxHeight());
        ChunkScanWorld view = new ChunkScanWorld(w, EnumSet.copyOf(res.blocks()), prefilter(res), budget);
        s.job = new PlayerSession.Job(scan, view, settings, w);
        step(p, s, r);
    }

    private List<BlockData> prefilter(TargetRules.Resolution<Material> res) {
        String key = res.spec().toString();
        Optional<List<BlockData>> cached = prefilters.get(key);
        if (cached == null) {
            List<BlockData> states;
            try {
                states = TargetStates.of(res.blocks());
            } catch (IllegalStateException e) {
                plugin.getLogger().log(Level.WARNING, "Scanning " + key + " without the palette pre-filter", e);
                states = null;
            }
            cached = Optional.ofNullable(states);
            if (prefilters.size() >= PREFILTER_CACHE_LIMIT) prefilters.clear();
            prefilters.put(key, cached);
        }
        return cached.orElse(null);
    }

    /** The player's scan for as much of this tick's budget as is left; publishes the result when it finishes. */
    private void step(Player p, PlayerSession s, ServerRules r) {
        PlayerSession.Job job = s.job;
        long start = System.nanoTime();
        boolean done = job.scan.step(job.view, budget.deadline());
        long took = System.nanoTime() - start;
        job.nanos += took;
        job.steps++;
        budget.spent(took);
        if (!done) return;
        s.job = null;
        ScanResult result = job.scan.result();
        s.lastScanMicros = job.nanos / 1000;
        s.lastScanSteps = job.steps;
        s.lastScanMatches = result.totalMatches();
        s.lastScanStats = job.view.stats();
        s.found = result.found() ? result.toFound(job.settings, job.world.getMinHeight(), job.world.getMaxHeight()) : null;
        s.status = s.found != null ? ScanPacket.Status.FOUND : ScanPacket.Status.NONE_FOUND;
        showOutlines(p, s, r);
        pointNeedle(p, s, r);
        s.nextActionBar = tick;
    }

    /** No active radar: nothing to scan or show; the status says why (OFF, NO_CHARGE, DIMENSION_DISABLED) or IDLE. */
    private static void reportInactive(Player p, PlayerSession s, boolean denied) {
        s.job = null;
        s.status = denied ? ScanPacket.Status.DIMENSION_DISABLED : switch (s.state) {
            case OFF -> ScanPacket.Status.OFF;
            case NO_CHARGE -> ScanPacket.Status.NO_CHARGE;
            default -> ScanPacket.Status.IDLE;
        };
        s.found = null;
        s.outlines.clear();
        s.needle.clear(p);
    }

    /** The radar in use's charge as it really is: what the item says, less the drain not yet written. */
    private int liveCharge(Player p, PlayerSession s, ServerRules r) {
        RadarItems.RadarData d = s.slot < 0 ? null : RadarItems.read(p.getInventory().getItem(s.slot));
        return d == null ? 0 : Math.max(0, d.charge(r.config().capacity()) - s.drain.pending() - s.owed);
    }

    /** The charge of the radar in that slot, counting drain not yet written if it is the one in use; for the chest GUI. */
    int chargeOf(Player p, int slot) {
        PlayerSession s = session(p);
        if (s.slot == slot) return liveCharge(p, s, plugin.rules());
        RadarItems.RadarData d = RadarItems.read(p.getInventory().getItem(slot));
        return d == null ? 0 : d.charge(plugin.rules().config().capacity());
    }

    private void showOutlines(Player p, PlayerSession s, ServerRules r) {
        ServerConfig c = r.config();
        if (c.outlinesEnabled() && s.found != null && s.found.markers().length > 0) {
            s.outlines.show(plugin, p, s.found.markers(), c.paperMaxDisplayOutlines(), c.paperGlowColor(), c.outlinesThroughWalls(),
                    c.maxRadius());
        } else {
            s.outlines.clear();
        }
    }

    /** Points the radar's compass needle at the (rounded) nearest match, or restores it (DESIGN §8 "Arrow"). */
    private void pointNeedle(Player p, PlayerSession s, ServerRules r) {
        Location target = null;
        if (r.config().arrowEnabled() && s.found != null && s.found.hasNearest()) {
            long pos = s.found.nearestPos();
            target = new Location(p.getWorld(), BlockPosPacking.x(pos), BlockPosPacking.y(pos), BlockPosPacking.z(pos));
        }
        Set<Material> blocks = r.rules().resolve(s.spec).blocks();
        World w = p.getWorld();
        // A rounded position is a grid cell, not a block that could be checked.
        s.needle.point(p, s.slot, target, r.config().arrowPrecisionBlocks() > 1 ? null
                : at -> w.isChunkLoaded(at.getBlockX() >> 4, at.getBlockZ() >> 4) && blocks.contains(w.getBlockAt(at).getType()));
    }

    /**
     * Beep and action bar, every tick. The beep plays in the "Players" sound category, which a
     * player can turn down on its own, as the mod's client does. The action bar is shared with
     * the server and other plugins, so only a working radar refreshes it; the reason one is not
     * working (off, out of charge, disabled here) is said once.
     */
    private void feedback(Player p, PlayerSession s, ServerRules r, boolean active) {
        ServerConfig c = r.config();
        double distance = active ? nearestDistance(p, s) : Double.NaN;
        if (c.beepEnabled() && !Double.isNaN(distance)) {
            if (s.beep.tick(distance, c.beepMinIntervalTicks(), c.beepMaxRange())) {
                p.playSound(p.getLocation(), BEEP_SOUND, SoundCategory.PLAYERS, BEEP_VOLUME,
                        Beep.pitch(BEEP_PITCH, distance, c.beepMaxRange()));
            }
        } else {
            s.beep.reset();
        }
        if (tick < s.nextActionBar || tick < s.messageUntil) return;
        Component line = statusLine(p, s, r, active);
        if (line == null || (!active && s.status == s.shownStatus)) {
            if (line == null) s.shownStatus = null;
            return;
        }
        s.shownStatus = s.status;
        s.nextActionBar = tick + ACTION_BAR_TICKS;
        p.sendActionBar(line);
    }

    private static double nearestDistance(Player p, PlayerSession s) {
        if (s.found == null || !s.found.hasNearest()) return Double.NaN;
        Location eye = p.getEyeLocation();
        return ScanMath.distance(s.found.nearestPos(), eye.getX(), eye.getY(), eye.getZ());
    }

    /** {@code ⛏ Iron Ore · 23 m ↓5 · ⚡ 64%} (DESIGN §8 "Arrow"), or the reason the radar is not finding anything. */
    Component statusLine(Player p, PlayerSession s, ServerRules r, boolean active) {
        if (s.slot < 0 || s.spec == null || s.state == ActiveRadar.State.NONE) return null;
        ServerConfig c = r.config();
        String detail = switch (s.status) {
            case FOUND -> foundDetail(p, s.found, c);
            case NONE_FOUND -> "nothing within " + c.maxRadius() + " m";
            case OFF -> "off";
            case NO_CHARGE -> "out of charge";
            case DIMENSION_DISABLED -> "disabled in this dimension";
            case IDLE -> active ? "scanning…" : null;
        };
        if (detail == null) return null;
        Component dot = Component.text(" · ", Texts.MUTED);
        TextComponent.Builder line = Component.text()
                .append(Component.text("⛏ ", Texts.MUTED))
                .append(Texts.targetName(r.rules(), s.spec).color(Texts.ACCENT))
                .append(dot)
                .append(Component.text(detail, s.status == ScanPacket.Status.FOUND ? NamedTextColor.WHITE : Texts.WARN));
        if (c.energyEnabled()) {
            int percent = Texts.percent(liveCharge(p, s, r), c.capacity());
            line.append(dot).append(Component.text("⚡ " + percent + "%", percent <= 10 ? Texts.WARN : Texts.ENERGY));
        }
        return line.build();
    }

    private static String foundDetail(Player p, ScanPacket.Found found, ServerConfig c) {
        if (!c.arrowShowDistance() || !found.hasNearest()) {
            return Texts.number(found.totalMatches()) + " within " + found.outlineRadius() + " m";
        }
        Location eye = p.getEyeLocation();
        long pos = found.nearestPos();
        long meters = Math.round(ScanMath.distance(pos, eye.getX(), eye.getY(), eye.getZ()));
        int dy = BlockPosPacking.y(pos) - p.getLocation().getBlockY();
        return meters + " m" + (dy > 0 ? " ↑" + dy : dy < 0 ? " ↓" + -dy : "");
    }

    /** The status line of the radar in that slot, if it is the one in use; for the chest GUI. */
    Component statusFor(Player p, int slot) {
        PlayerSession s = session(p);
        return s.slot == slot ? statusLine(p, s, plugin.rules(), s.state == ActiveRadar.State.READY) : null;
    }

    /** A one-off action-bar message, held for two seconds before the status line returns. */
    void message(Player p, Component text) {
        PlayerSession s = session(p);
        p.sendActionBar(text);
        s.messageUntil = tick + MESSAGE_TICKS;
        s.nextActionBar = s.messageUntil;
        s.shownStatus = null; // the status line comes back after it
    }

    /** Rate-limits radar uses; false while the 10-tick cooldown runs. */
    boolean tryUse(Player p) {
        PlayerSession s = session(p);
        if (tick - s.lastUse < USE_COOLDOWN_TICKS) return false;
        s.lastUse = tick;
        return true;
    }

    /** Opens the chest GUI for the radar in that slot. */
    void openRadar(Player p, int slot) {
        RadarMenu.open(plugin, p, slot, null);
    }

    void join(Player p) {
        session(p);
    }

    /** Settles charge and removes everything shown; the session ends. */
    void quit(Player p) {
        PlayerSession s = sessions.remove(p.getUniqueId());
        lastErrorLog.remove(p.getUniqueId());
        if (s == null) return;
        settle(p, s, plugin.rules());
        s.outlines.clear();
        s.needle.clear(p);
    }

    /**
     * Death. The stacks that drop no longer follow the inventory, so what the radar in use owes
     * waits under its id and is paid when it is next in use; forgetting the id makes the next tick
     * switch to the radar afresh, which with keepInventory is at once. The result was for where
     * the player died.
     */
    void died(Player p) {
        PlayerSession s = session(p);
        park(s.radarId, s.owed + s.drain.flush());
        s.owed = 0;
        s.radarId = null;
        clearResult(s);
        s.outlines.clear();
        // The radars dropped have their needles removed by RadarListener; one kept is cleared at the cadence.
        s.needle.forget();
    }

    /** The radar's drain is written before it scans in the new world; needles pointing into the old one go. */
    void changedWorld(Player p) {
        PlayerSession s = session(p);
        settle(p, s, plugin.rules());
        clearResult(s);
        s.outlines.clear();
        s.needle.forget();
        s.needle.clearStale(p);
    }

    /** After a reload: drop scans built on the old rules and re-render radar lore. */
    void rulesReloaded() {
        prefilters.clear();
        ServerRules r = plugin.rules();
        for (Player p : Bukkit.getOnlinePlayers()) {
            PlayerSession s = session(p);
            s.job = null;
            s.scanDue = true;
            settle(p, s, r); // every radar is rewritten below anyway, so the one in use is exact too
            PlayerInventory inv = p.getInventory();
            for (int slot = 0; slot < inv.getSize(); slot++) {
                ItemStack item = inv.getItem(slot);
                if (item == null || item.getType() != Material.COMPASS || !RadarItems.isRadar(item)) continue;
                RadarItems.refresh(item, r);
                inv.setItem(slot, item);
            }
        }
    }

    // ---- /oreradar info ----

    int players() {
        return sessions.size();
    }

    /** What all scans together took per tick lately, for {@code /oreradar info}. */
    ScanBudget.Load scanLoad() {
        return budget.recent();
    }

    /** The player's radar and last scan, for {@code /oreradar info}. */
    List<Component> describe(Player p) {
        PlayerSession s = session(p);
        ServerRules r = plugin.rules();
        List<Component> out = new ArrayList<>();
        Component status = statusLine(p, s, r, s.state == ActiveRadar.State.READY);
        out.add(status == null ? Component.text("No radar with a target in reach", Texts.MUTED)
                : Component.text("Radar in slot " + s.slot + ": ", Texts.MUTED).append(status));
        if (s.lastScanMicros < 0) {
            out.add(Component.text("No scan yet", Texts.MUTED));
        } else {
            ChunkScanWorld.Stats st = s.lastScanStats;
            out.add(Component.text("Last scan: " + Texts.number(s.lastScanMicros) + " µs over " + s.lastScanSteps + " tick"
                    + (s.lastScanSteps == 1 ? "" : "s") + ", " + Texts.number(s.lastScanMatches) + " matches; " + st.chunks + " chunks ("
                    + st.chunksSkipped + " skipped by the palette check), " + st.sections + " sections read", Texts.MUTED));
        }
        if (s.outlines.size() > 0) out.add(Component.text("Outlines shown to you: " + s.outlines.size()
                + " (at most " + r.config().paperMaxDisplayOutlines() + ")", Texts.MUTED));
        return out;
    }
}
