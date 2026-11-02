package net.loserver.oreradar.client;

import java.io.IOException;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

import net.loserver.oreradar.EnergySettings;
import net.loserver.oreradar.OreRadar;
import net.loserver.oreradar.client.screen.RadarScreen;
import net.loserver.oreradar.core.ActiveFrom;
import net.loserver.oreradar.core.config.ClientConfig;
import net.loserver.oreradar.core.config.ClientData;
import net.loserver.oreradar.core.config.EffectiveClientSettings;
import net.loserver.oreradar.core.config.LoadResult;
import net.loserver.oreradar.core.net.ClientPrefs;
import net.loserver.oreradar.core.net.ConfigPacket;
import net.loserver.oreradar.core.net.HelloPacket;
import net.loserver.oreradar.core.net.PacketException;
import net.loserver.oreradar.core.net.PowerPacket;
import net.loserver.oreradar.core.net.PrefsPacket;
import net.loserver.oreradar.core.net.ProtocolMismatchException;
import net.loserver.oreradar.core.net.RadarProtocol;
import net.loserver.oreradar.core.net.ScanPacket;
import net.loserver.oreradar.core.net.SelectPacket;
import net.loserver.oreradar.core.scan.ActiveRadar;
import net.loserver.oreradar.core.scan.Beep;
import net.loserver.oreradar.item.RadarItem;
import net.loserver.oreradar.platform.Services;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.item.ClampedItemPropertyFunction;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

/**
 * The client side of Ore Radar (DESIGN §6): its settings and per-server lists, the session with the
 * server (hello, config, scans), and the beep. The screens, HUD and outline renderer read their state
 * from here. The server scans; the client only draws what it was sent, and does nothing at all on a
 * server that never answered hello. Client thread only.
 */
public final class RadarClient implements ClientBridge {
    /** What the client knows about the server's Ore Radar. */
    public enum Connection {
        /** Not in a world. */
        OFFLINE,
        /** Hello sent, no config yet: the server may not run Ore Radar at all. */
        WAITING,
        READY,
        /** The server speaks another protocol version; the radar stays inert. */
        INCOMPATIBLE
    }

    /** How long after joining the screen says "waiting" before it concludes the server has no Ore Radar. */
    private static final long CONFIG_GRACE_MILLIS = 5000;
    /** A changed setting is written after this many quiet ticks, so dragging a slider writes the file once. */
    private static final int SAVE_DELAY_TICKS = 20;
    /** Below this height difference the readouts leave the ↑/↓ out: the block is about at eye level (1.0). */
    private static final int MIN_SHOWN_HEIGHT = 2;

    private static RadarClient instance;

    private final Path configFile;
    private final Path dataFile;
    private final Function<String, String> modNames;
    private ClientConfig config;
    private ClientData data;
    private boolean configDirty;
    private int saveCountdown;

    private Connection connection = Connection.OFFLINE;
    private long joinedAt;
    private int serverProtocol;
    private String serverKey = "";
    @Nullable private ConfigPacket.Limits limits;
    private CatalogModel catalog = CatalogModel.EMPTY;
    @Nullable private EffectiveClientSettings effective;
    @Nullable private ScanView scan;
    private final Beep.Timer beepTimer = new Beep.Timer();
    /** Bad payloads are logged once per channel and connection, so a broken server cannot flood the log. */
    private final Set<RadarProtocol.Channel> warnedChannels = EnumSet.noneOf(RadarProtocol.Channel.class);
    @Nullable private String lastTab;

    private RadarClient(Path configDir, Function<String, String> modNames) {
        this.configFile = configDir.resolve(ClientConfig.FILE_NAME);
        this.dataFile = configDir.resolve(ClientData.FILE_NAME);
        this.modNames = modNames;
        this.config = ClientConfig.defaults();
        loadConfig();
        LoadResult<ClientData> loaded = ClientData.load(dataFile);
        loaded.warnings().forEach(w -> OreRadar.LOGGER.warn("{}", w));
        this.data = loaded.config();
    }

    /**
     * Creates the client once, from the loader's client entrypoint, and installs it as the
     * {@link ClientBridge}. Reads (and if need be writes, or replaces 1.0's) {@code oreradar-client.toml}.
     *
     * @param modNames a mod's display name by its id, for tooltips and the {@code @mod} search
     */
    public static RadarClient create(Path configDir, Function<String, String> modNames) {
        if (instance != null) throw new IllegalStateException("RadarClient already created");
        instance = new RadarClient(configDir, modNames);
        ClientBridge.install(instance);
        return instance;
    }

    public static RadarClient get() {
        return instance;
    }

    // ------------------------------------------------------------------ settings

    public ClientConfig config() {
        return config;
    }

    /**
     * Applies changed settings at once and writes them shortly after. Tells the server when what it
     * scans for changed (DESIGN §7 prefs).
     */
    public void updateConfig(ClientConfig next) {
        ClientPrefs before = config.prefs();
        config = next;
        if (limits != null) effective = EffectiveClientSettings.of(config, limits);
        ClientPrefs after = next.prefs();
        if (!after.equals(before) && connection != Connection.OFFLINE && connection != Connection.INCOMPATIBLE) {
            send(RadarProtocol.Channel.PREFS, new PrefsPacket(after).toBytes());
        }
        configDirty = true;
        saveCountdown = SAVE_DELAY_TICKS;
    }

    /** Writes pending setting changes now; the settings screen calls it when it closes. */
    public void flushConfig() {
        if (!configDirty) return;
        configDirty = false;
        try {
            config.save(configFile);
        } catch (IOException e) {
            OreRadar.LOGGER.warn("Could not write {}: {}", configFile, e.toString());
        }
    }

    private void loadConfig() {
        LoadResult<ClientConfig> loaded = ClientConfig.load(configFile, config);
        loaded.warnings().forEach(w -> OreRadar.LOGGER.warn("{}: {}", configFile.getFileName(), w));
        config = loaded.config();
    }

    public String modName(String modId) {
        return modNames.apply(modId);
    }

    // ------------------------------------------------------------------ session

    /**
     * The player joined a world: re-read the settings (they may have been edited by hand) and say
     * hello. A server with Ore Radar answers with its config; any other server ignores it, and the
     * radar stays inert.
     */
    public void onJoin() {
        flushConfig();
        loadConfig();
        clearSession();
        Minecraft mc = Minecraft.getInstance();
        serverKey = keyFor(mc);
        connection = Connection.WAITING;
        joinedAt = Util.getMillis();
        send(RadarProtocol.Channel.HELLO, new HelloPacket(config.prefs()).toBytes());
    }

    /** The player left the world: forget the server, its catalog and its energy rules. */
    public void onDisconnect() {
        flushConfig();
        clearSession();
        connection = Connection.OFFLINE;
        EnergySettings.reset();
    }

    private void clearSession() {
        limits = null;
        effective = null;
        catalog = CatalogModel.EMPTY;
        scan = null;
        serverProtocol = 0;
        warnedChannels.clear();
        beepTimer.reset();
        FrameCapture.invalidate();
    }

    public Connection connection() {
        return connection;
    }

    /** True while the client has joined but the server has not answered yet, for a short while. */
    public boolean isAwaitingConfig() {
        return connection == Connection.WAITING && Util.getMillis() - joinedAt < CONFIG_GRACE_MILLIS;
    }

    /** The protocol version an incompatible server spoke. */
    public int serverProtocol() {
        return serverProtocol;
    }

    @Nullable
    public ConfigPacket.Limits limits() {
        return limits;
    }

    /** The client's settings capped by the server's; null until the server's config arrives. */
    @Nullable
    public EffectiveClientSettings effective() {
        return effective;
    }

    public CatalogModel catalog() {
        return catalog;
    }

    @Nullable
    ScanView scan() {
        return scan;
    }

    /** The last scan packet, or null before the first one in this world. */
    @Nullable
    public ScanPacket lastScan() {
        return scan != null ? scan.packet() : null;
    }

    @Override
    public void receive(RadarProtocol.Channel channel, byte[] payload) {
        if (Minecraft.getInstance().player == null) return;
        try {
            switch (channel) {
                case CONFIG -> onConfig(ConfigPacket.decode(payload));
                case SCAN -> onScan(ScanPacket.decode(payload));
                default -> {
                    // Client-to-server channels never arrive here.
                }
            }
        } catch (ProtocolMismatchException e) {
            if (connection != Connection.INCOMPATIBLE) {
                OreRadar.LOGGER.warn("This server runs Ore Radar protocol {}, this client {}; the radar stays off",
                        e.remoteVersion(), RadarProtocol.VERSION);
            }
            clearSession();
            serverProtocol = e.remoteVersion();
            connection = Connection.INCOMPATIBLE;
        } catch (PacketException e) {
            if (warnedChannels.add(channel)) {
                OreRadar.LOGGER.warn("Ignoring a malformed {} payload from the server ({} bytes): {}", channel.id(), payload.length,
                        e.getMessage());
            }
        }
    }

    private void onConfig(ConfigPacket packet) {
        if (connection == Connection.INCOMPATIBLE) return;
        limits = packet.limits();
        catalog = CatalogModel.of(packet);
        effective = EffectiveClientSettings.of(config, limits);
        EnergySettings.applyLimits(limits);
        connection = Connection.READY;
    }

    private void onScan(ScanPacket packet) {
        ClientLevel level = Minecraft.getInstance().level;
        if (connection != Connection.READY || effective == null || level == null) return;
        scan = new ScanView(packet, effective.maxOutlines(), level);
    }

    private boolean canSend() {
        ClientPacketListener listener = Minecraft.getInstance().getConnection();
        return listener != null && connection != Connection.OFFLINE;
    }

    private void send(RadarProtocol.Channel channel, byte[] payload) {
        if (canSend()) Services.PLATFORM.sendToServer(channel, payload);
    }

    /** The {@link ClientData} key of the world just joined: the singleplayer world's name or the server's address. */
    private static String keyFor(Minecraft mc) {
        IntegratedServer local = mc.getSingleplayerServer();
        if (local != null) return ClientData.singleplayerKey(local.getWorldData().getLevelName());
        ServerData server = mc.getCurrentServer();
        if (server != null) return ClientData.serverKey(server.ip);
        ClientPacketListener listener = mc.getConnection();
        return listener != null ? ClientData.serverKey(listener.getConnection().getRemoteAddress().toString()) : "";
    }

    // ------------------------------------------------------------------ actions from the screen

    /** True when the server runs a compatible Ore Radar and has said what it allows. */
    public boolean isReady() {
        return connection == Connection.READY && limits != null && effective != null;
    }

    /** Tracks a spec with the radar in that slot; "" clears its target. The server validates and answers. */
    public void select(int slot, String spec) {
        if (!isReady() || !RadarProtocol.isValidSlot(slot)) return;
        send(RadarProtocol.Channel.SELECT, new SelectPacket(slot, spec).toBytes());
        if (!spec.isEmpty() && data.addRecent(serverKey, spec)) saveData();
    }

    public void power(int slot, boolean on) {
        if (!isReady() || !RadarProtocol.isValidSlot(slot)) return;
        send(RadarProtocol.Channel.POWER, new PowerPacket(slot, on).toBytes());
    }

    public boolean isFavourite(String spec) {
        return data.isFavourite(serverKey, spec);
    }

    public void toggleFavourite(String spec) {
        if (data.setFavourite(serverKey, spec, !data.isFavourite(serverKey, spec))) saveData();
    }

    public List<String> favourites() {
        return data.favourites(serverKey);
    }

    public List<String> recents() {
        return data.recents(serverKey);
    }

    private void saveData() {
        try {
            data.save(dataFile);
        } catch (IOException e) {
            OreRadar.LOGGER.warn("Could not write {}: {}", dataFile, e.toString());
        }
    }

    /** The radar screen's last tab in this session: the screen always reopens on it. */
    @Nullable
    public String lastTab() {
        return lastTab;
    }

    public void setLastTab(String tab) {
        lastTab = tab;
    }

    // ------------------------------------------------------------------ the radar in the inventory

    /** The slot the server reports as the active radar, or -1. */
    public int activeSlot() {
        ScanPacket last = lastScan();
        return last != null && last.status() != ScanPacket.Status.IDLE ? last.slot() : -1;
    }

    /** The stack in a protocol slot (0-35, off hand 40), or empty. */
    public static ItemStack stackIn(LocalPlayer player, int slot) {
        return RadarProtocol.isValidSlot(slot) ? player.getInventory().getItem(slot) : ItemStack.EMPTY;
    }

    /** The radar the keybind opens: the active one, else the first in hand, hotbar or inventory order; -1 for none. */
    private int radarSlotForKey(LocalPlayer player) {
        int active = activeSlot();
        if (active >= 0 && stackIn(player, active).getItem() instanceof RadarItem) return active;
        for (int slot : ActiveRadar.searchOrder(player.getInventory().selected, ActiveFrom.INVENTORY)) {
            if (stackIn(player, slot).getItem() instanceof RadarItem) return slot;
        }
        return -1;
    }

    /**
     * The charge of the radar in a slot: live from the last scan if that radar is the active one
     * (the item is written only every few seconds), else as stored on the item.
     */
    public int chargeOf(LocalPlayer player, int slot) {
        ScanPacket last = lastScan();
        if (last != null && last.slot() == slot && last.status() != ScanPacket.Status.IDLE) return last.charge();
        // Against the server's capacity: the config packet applied it to EnergySettings.
        return RadarItem.charge(stackIn(player, slot));
    }

    @Override
    public void openRadarScreen(int slot) {
        Minecraft.getInstance().setScreen(new RadarScreen(this, slot));
    }

    @Override
    public Component targetName(String spec) {
        CatalogModel.Entry entry = catalog.find(spec);
        return entry != null ? entry.name() : null;
    }

    // ------------------------------------------------------------------ the nearest match

    /**
     * Where the nearest match is from the player's eyes, or null when the last scan found nothing
     * or the server sent no position.
     *
     * @param dx       block centre minus eye, east
     * @param dz       block centre minus eye, south
     * @param distance straight-line distance in blocks
     * @param height   whole blocks above (+) or below (-) eye level, 0 when about level
     */
    public record Nearest(double dx, double dz, double distance, int height) {}

    @Nullable
    public Nearest nearest(LocalPlayer player) {
        if (scan == null || scan.status() != ScanPacket.Status.FOUND) return null;
        BlockPos pos = scan.nearest();
        if (pos == null) return null;
        double dx = pos.getX() + 0.5 - player.getX();
        double dy = pos.getY() + 0.5 - player.getEyeY();
        double dz = pos.getZ() + 0.5 - player.getZ();
        int height = (int) Math.round(dy);
        return new Nearest(dx, dz, Math.sqrt(dx * dx + dy * dy + dz * dz), Math.abs(height) >= MIN_SHOWN_HEIGHT ? height : 0);
    }

    // ------------------------------------------------------------------ ticking

    /** Once per client tick, from the loader's end-of-tick event. */
    public void tick() {
        Minecraft mc = Minecraft.getInstance();
        if (configDirty && --saveCountdown <= 0) flushConfig();
        LocalPlayer player = mc.player;
        if (player == null) return;
        while (RadarKeys.OPEN.consumeClick()) {
            if (mc.screen == null) openRadarScreen(radarSlotForKey(player));
        }
        if (scan != null && !scan.isFor(mc.level)) onLevelChanged(mc.level);
        if (scan != null && scan.hasOutlines()) scan.dropMined();
        tickBeep(mc, player);
    }

    /**
     * Another dimension: a find's positions mean nothing in it, so a find is dropped until the next
     * scan. Any other status (off, out of charge, disabled here) still holds, and the server sends a
     * status only when it changes, so it is kept.
     */
    private void onLevelChanged(@Nullable ClientLevel level) {
        ScanPacket last = scan.packet();
        scan = level != null && last.status() != ScanPacket.Status.FOUND && effective != null
                ? new ScanView(last, effective.maxOutlines(), level)
                : null;
    }

    private void tickBeep(Minecraft mc, LocalPlayer player) {
        if (!isReady() || !effective.beep() || mc.isPaused()) {
            beepTimer.reset();
            return;
        }
        Nearest nearest = nearest(player);
        double distance = nearest != null ? nearest.distance() : Double.NaN;
        if (!beepTimer.tick(distance, limits.beepMinIntervalTicks(), limits.beepMaxRange())) return;
        ResourceLocation sound = ResourceLocation.tryParse(config.beepSound());
        float volume = (float) config.beepVolume();
        if (sound == null || volume <= 0) return;
        float pitch = Beep.pitch(config.beepPitch(), distance, limits.beepMaxRange());
        // Relative and unattenuated: the beep comes from the radar in the player's hand.
        mc.getSoundManager().play(new SimpleSoundInstance(sound, SoundSource.PLAYERS, volume, pitch,
                SoundInstance.createUnseededRandom(), false, 0, SoundInstance.Attenuation.NONE, 0, 0, 0, true));
    }

    // ------------------------------------------------------------------ item model

    /**
     * The radar item's {@code oreradar:state} model predicate: 0 idle (off or no target), 1 when the
     * last scan of this very stack found something, else 0.5 (scanning).
     */
    public final ClampedItemPropertyFunction itemState = (stack, level, entity, seed) -> {
        if (RadarItem.isOff(stack) || RadarItem.target(stack) == null) return 0F;
        ScanPacket last = lastScan();
        LocalPlayer player = Minecraft.getInstance().player;
        if (last != null && last.status() == ScanPacket.Status.FOUND && player != null && entity == player
                && stackIn(player, last.slot()) == stack) {
            return 1F;
        }
        return 0.5F;
    };
}
