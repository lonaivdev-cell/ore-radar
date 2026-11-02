package net.loserver.oreradar.server;

import java.util.function.Consumer;

import net.loserver.oreradar.core.config.ServerConfig;
import net.loserver.oreradar.core.designation.TargetRules;
import net.loserver.oreradar.core.energy.ChargeDrain;
import net.loserver.oreradar.core.net.ClientPrefs;
import net.loserver.oreradar.core.net.PowerPacket;
import net.loserver.oreradar.core.net.RadarPacket;
import net.loserver.oreradar.core.net.ScanPacket;
import net.loserver.oreradar.core.net.SelectPacket;
import net.loserver.oreradar.core.scan.ActiveRadar;
import net.loserver.oreradar.core.scan.EarlyScans;
import net.loserver.oreradar.core.scan.RadarScan;
import net.loserver.oreradar.core.scan.ScanBudget;
import net.loserver.oreradar.core.scan.ScanResult;
import net.loserver.oreradar.core.scan.ScanSettings;
import net.loserver.oreradar.item.RadarItem;
import net.loserver.oreradar.item.TargetNames;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/**
 * One player's radar on the server (DESIGN §5, §7): what their client asked for in hello and prefs,
 * which radar is active, its charge drain, and the scan in progress, which may span several ticks.
 * Server thread only.
 */
public final class PlayerRadar {
    /** How the player's last finished scan went, for {@code /oreradar info}. */
    public record ScanStats(long micros, int ticks, int matches, ScanPacket.Status status) {}

    /** What the active radar scans for; when any of it changes, the last result is about something else. */
    private record ScanKey(int slot, String spec, ResourceKey<Level> dimension) {}

    private ClientPrefs prefs = ClientPrefs.DEFAULT;
    private boolean hello;
    /** The config packet went out in this session; after that only a reload sends it again. */
    private boolean configSent;
    /** The protocol of a client that speaks another version, or 0. */
    private int incompatibleProtocol;
    /** A hello in another protocol came in this session (and was logged). */
    private boolean seenIncompatible;

    private final ChargeDrain drain = new ChargeDrain();
    /** The radar the accrued drain belongs to; written to when the active radar changes. */
    private ItemStack drainStack = ItemStack.EMPTY;

    /** The active radar as of the last tick, or null while none is; a scan in progress is always for it. */
    @Nullable
    private ScanKey activeKey;
    @Nullable
    private RadarScan scan;
    @Nullable
    private LevelScanWorld scanWorld;
    @Nullable
    private ScanSettings scanSettings;
    private long scanNanos;
    private int scanTicks;
    private boolean scanDue = true;
    /** Scans for a change (target, radar, dimension, prefs, a radar becoming active) before the cadence's turn. */
    private final EarlyScans early = new EarlyScans();

    @Nullable
    private ScanPacket lastSent;
    @Nullable
    private ScanStats lastScan;

    /**
     * The client said hello (or sent prefs) in our protocol: it gets scan packets from now on. A
     * hello from a client already greeted only carries prefs.
     */
    public void onHello(ClientPrefs prefs) {
        if (hello) {
            onPrefs(prefs);
            return;
        }
        this.prefs = prefs;
        hello = true;
        incompatibleProtocol = 0;
        lastSent = null;
        early.want();
    }

    /** The client changed its prefs; a scan with them follows soon. The same prefs again change nothing. */
    public void onPrefs(ClientPrefs prefs) {
        if (prefs.equals(this.prefs)) return;
        this.prefs = prefs;
        early.want();
    }

    /**
     * The client runs another protocol version: it stays inert, and gets nothing (DESIGN §7).
     *
     * @return true the first time in this session, so the caller logs it once
     */
    public boolean onIncompatible(int remoteProtocol) {
        hello = false;
        incompatibleProtocol = remoteProtocol;
        activeKey = null;
        abandonScan();
        stopDraining();
        boolean first = !seenIncompatible;
        seenIncompatible = true;
        return first;
    }

    public boolean saidHello() {
        return hello;
    }

    /** True once the client has been sent the config packet in this session. */
    public boolean hasConfig() {
        return configSent;
    }

    public void configSent() {
        configSent = true;
    }

    /** The other protocol version this player's client speaks, or 0 if none was seen. */
    public int incompatibleProtocol() {
        return incompatibleProtocol;
    }

    @Nullable
    public ScanStats lastScan() {
        return lastScan;
    }

    /** Scan on the next tick, from scratch: the rules changed, or an operator set the target. */
    public void requestScan() {
        abandonScan();
        scanDue = true;
    }

    /**
     * One server tick. Picks the active radar, drains its charge, and advances the scan, starting
     * one every {@code scanIntervalTicks} (staggered by entity id), or early when the player changed
     * what the radar shows, and continuing it on later ticks when {@code budget} runs out (DESIGN §5).
     * Results and status changes go to {@code out}.
     *
     * @param tick   the server's tick counter, for the scan cadence
     * @param budget the scan time of this tick, shared by every player
     */
    public void tick(ServerPlayer player, RadarRules rules, int tick, ScanBudget budget, Consumer<RadarPacket> out) {
        if (!hello) return;
        ServerConfig config = rules.config();
        early.tick(config.scanIntervalTicks());
        ScanSettings settings = ScanSettings.of(config, prefs);
        Inventory inventory = player.getInventory();
        boolean creative = player.isCreative();
        ActiveRadar.Selection selection = ActiveRadar.select(inventory.selected, settings.activeFrom(),
                slot -> slotState(inventory.getItem(slot), rules, creative));
        if (!selection.active()) {
            activeKey = null;
            stopDraining();
            abandonScan();
            if (selection.slot() < 0) {
                // Tell the client once that nothing is active any more.
                if (lastSent != null) sendIfChanged(ScanPacket.idle(), out);
            } else {
                ItemStack stack = inventory.getItem(selection.slot());
                sendIfChanged(new ScanPacket(selection.idleStatus(), specOf(stack), selection.slot(), reportedCharge(stack), null), out);
            }
            return;
        }

        int slot = selection.slot();
        ItemStack stack = inventory.getItem(slot);
        String spec = specOf(stack);
        ServerLevel level = player.serverLevel();
        if (config.isDimensionDenied(level.dimension().location().toString())) {
            activeKey = null;
            stopDraining();
            abandonScan();
            sendIfChanged(new ScanPacket(ScanPacket.Status.DIMENSION_DISABLED, spec, slot, reportedCharge(stack), null), out);
            return;
        }

        drain(stack, creative, config);

        ScanKey key = new ScanKey(slot, spec, level.dimension());
        if (!key.equals(activeKey)) {
            // Another radar, target or dimension, or a radar that just became active (switched on,
            // recharged): what the client shows is about something else.
            abandonScan();
            activeKey = key;
            early.want();
        }
        if ((tick + player.getId()) % config.scanIntervalTicks() == 0) scanDue = true;
        if (!(scan == null && scanDue) && early.take()) {
            // Now rather than at the cadence. New prefs may change what the scan in progress keeps,
            // so that one starts over too.
            abandonScan();
            scanDue = true;
        }
        if (budget.expired() || (scan == null && !scanDue)) return;
        if (scan == null) {
            Vec3 eye = player.getEyePosition();
            scan = new RadarScan(eye.x, eye.y, eye.z, config.maxRadius(), settings.probeChunks(), settings.maxMarkers(),
                    level.getMinBuildHeight(), level.getMaxBuildHeight());
            scanWorld = new LevelScanWorld(level, rules.targets().resolve(spec).blocks(), budget);
            scanSettings = settings;
            scanNanos = 0;
            scanTicks = 0;
            scanDue = false;
            early.started();
        }
        long start = System.nanoTime();
        boolean done = scan.step(scanWorld, budget.deadline());
        scanNanos += System.nanoTime() - start;
        scanTicks++;
        if (!done) return;

        ScanResult result = scan.result();
        ScanSettings startedWith = scanSettings;
        long micros = scanNanos / 1000;
        int ticks = scanTicks;
        abandonScan();
        ScanPacket packet = result.found()
                ? new ScanPacket(ScanPacket.Status.FOUND, spec, slot, reportedCharge(stack),
                        result.toFound(startedWith, level.getMinBuildHeight(), level.getMaxBuildHeight()))
                : new ScanPacket(ScanPacket.Status.NONE_FOUND, spec, slot, reportedCharge(stack), null);
        lastScan = new ScanStats(micros, ticks, result.totalMatches(), packet.status());
        lastSent = packet;
        out.accept(packet);
    }

    /**
     * {@code oreradar:select}: track a spec with the radar in that slot, if the rules allow it. The
     * next tick sees the new target and scans for it.
     */
    public void select(ServerPlayer player, SelectPacket packet, RadarRules rules) {
        ItemStack stack = player.getInventory().getItem(packet.slot());
        // The radar may have moved since the client sent this; then there is nothing to change.
        if (!(stack.getItem() instanceof RadarItem)) return;
        if (packet.spec().isEmpty()) {
            RadarItem.setTarget(stack, null);
            player.displayClientMessage(Component.translatable("oreradar.msg.cleared"), true);
        } else {
            track(player, stack, rules.targets().resolve(packet.spec()));
        }
    }

    /** {@code oreradar:power}: switch the radar in that slot on or off; the next tick reports or scans. */
    public void power(ServerPlayer player, PowerPacket packet) {
        ItemStack stack = player.getInventory().getItem(packet.slot());
        if (!(stack.getItem() instanceof RadarItem)) return;
        RadarItem.setOff(stack, !packet.on());
        player.displayClientMessage(Component.translatable(packet.on() ? "oreradar.msg.on" : "oreradar.msg.off"), true);
    }

    /** Sets the radar's target to what a spec resolved to, or tells the player why not. */
    public static void track(ServerPlayer player, ItemStack radar, TargetRules.Resolution<Block> resolution) {
        switch (resolution.status()) {
            case OK -> {
                RadarItem.setTarget(radar, resolution.spec().toString());
                player.displayClientMessage(Component.translatable("oreradar.msg.tracking", TargetNames.of(resolution)), true);
            }
            case NOT_ALLOWED -> player.displayClientMessage(
                    Component.translatable("oreradar.msg.untrackable", TargetNames.of(resolution)), true);
            default -> player.displayClientMessage(Component.translatable("oreradar.msg.unknown_target",
                    resolution.spec() == null ? "?" : resolution.spec().toString()), true);
        }
    }

    /** The player left: write the drain accrued so far. */
    public void onLeave() {
        stopDraining();
        abandonScan();
    }

    private ActiveRadar.State slotState(ItemStack stack, RadarRules rules, boolean creative) {
        if (!(stack.getItem() instanceof RadarItem)) return ActiveRadar.State.NONE;
        String spec = RadarItem.target(stack);
        if (spec == null) return ActiveRadar.State.NONE;
        // Item data can hold anything (creative clients, /give, the world upgrader, other mods): a spec
        // that does not even parse is no target, and is never reported, since a scan packet takes
        // only valid lengths.
        TargetRules.Resolution<Block> target = rules.targets().resolve(spec);
        if (target.status() == TargetRules.Status.INVALID) return ActiveRadar.State.NONE;
        if (RadarItem.isOff(stack)) return ActiveRadar.State.OFF;
        if (rules.config().energyEnabled() && !creative && reportedCharge(stack) <= 0) return ActiveRadar.State.NO_CHARGE;
        // A target the rules no longer allow (a pack or config change) does nothing.
        return target.ok() ? ActiveRadar.State.READY : ActiveRadar.State.NONE;
    }

    private void drain(ItemStack stack, boolean creative, ServerConfig config) {
        if (!config.energyEnabled()) {
            drain.reset();
            drainStack = ItemStack.EMPTY;
            return;
        }
        if (stack != drainStack) {
            stopDraining();
            drainStack = stack;
        }
        int units = drain.tick(creative, config.drainPerSecond());
        if (units > 0) RadarItem.setCharge(stack, ChargeDrain.apply(RadarItem.charge(stack), units));
    }

    /** Writes what the last active radar accrued and forgets it. */
    private void stopDraining() {
        int units = drain.flush();
        if (units > 0 && drainStack.getItem() instanceof RadarItem) {
            RadarItem.setCharge(drainStack, ChargeDrain.apply(RadarItem.charge(drainStack), units));
        }
        drain.reset();
        drainStack = ItemStack.EMPTY;
    }

    private void abandonScan() {
        scan = null;
        scanWorld = null;
        scanSettings = null;
    }

    /** The charge the player sees: what the item holds less the drain not yet written to it. */
    private int reportedCharge(ItemStack stack) {
        int pending = stack == drainStack ? drain.pending() : 0;
        return Math.max(0, RadarItem.charge(stack) - pending);
    }

    private void sendIfChanged(ScanPacket packet, Consumer<RadarPacket> out) {
        if (packet.equals(lastSent)) return;
        lastSent = packet;
        out.accept(packet);
    }

    private static String specOf(ItemStack stack) {
        String spec = RadarItem.target(stack);
        return spec == null ? "" : spec;
    }
}
