package net.loserver.oreradar.server;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

import net.loserver.oreradar.EnergySettings;
import net.loserver.oreradar.OreRadar;
import net.loserver.oreradar.core.config.LoadResult;
import net.loserver.oreradar.core.config.ServerConfig;
import net.loserver.oreradar.core.designation.TargetSpec;
import net.loserver.oreradar.core.net.ClientPrefs;
import net.loserver.oreradar.core.net.HelloPacket;
import net.loserver.oreradar.core.net.PacketException;
import net.loserver.oreradar.core.net.PowerPacket;
import net.loserver.oreradar.core.net.PrefsPacket;
import net.loserver.oreradar.core.net.ProtocolMismatchException;
import net.loserver.oreradar.core.net.RadarPacket;
import net.loserver.oreradar.core.net.RadarProtocol;
import net.loserver.oreradar.core.net.SelectPacket;
import net.loserver.oreradar.core.scan.ScanBudget;
import net.loserver.oreradar.platform.Services;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import org.jetbrains.annotations.Nullable;

/**
 * Ore Radar on a running server: the config and the rules built from it, every player's radar, the
 * per-tick scan budget and the packets from clients. One exists from server start to server stop
 * ({@link #get()}); the loaders forward their lifecycle, tick, network and player events here.
 * Server thread only.
 */
public final class RadarServer {
    @Nullable
    private static volatile RadarServer running;

    private final MinecraftServer server;
    private final Path configFile;
    private ServerConfig config;
    private RadarRules rules;
    /** Warnings the last rules build logged (a trimmed catalog, colliding group ids), for /oreradar reload. */
    private int ruleWarnings;
    private final Map<UUID, PlayerRadar> players = new HashMap<>();
    private final ScanBudget budget = ScanBudget.perTick();

    private RadarServer(MinecraftServer server, Path configFile, ServerConfig config) {
        this.server = server;
        this.configFile = configFile;
        this.config = config;
    }

    /** The radar of the running server, or null when none is running (e.g. on a client at the title screen). */
    @Nullable
    public static RadarServer get() {
        return running;
    }

    /**
     * Server starting: reads {@code config/oreradar-server.toml} (written with its comments when
     * missing) and builds the rules from the datapack just loaded.
     */
    public static void start(MinecraftServer server) {
        Path file = Services.PLATFORM.getConfigDir().resolve(ServerConfig.FILE_NAME);
        LoadResult<ServerConfig> loaded = ServerConfig.load(file, null, false);
        logLoad(file, loaded);
        RadarServer radar = new RadarServer(server, file, loaded.config());
        radar.rebuild();
        running = radar;
    }

    public static void stop() {
        running = null;
        EnergySettings.release();
    }

    /**
     * A datapack load has bound new tags. At start this happens before the server runs, and
     * {@link #start} builds the rules; after a {@code /reload} the rules are rebuilt here and sent
     * again, since groups, tags and the untrackable list may all have changed.
     */
    public static void onTagsLoaded() {
        RadarServer radar = running;
        if (radar != null) {
            radar.rebuild();
            radar.sendConfigToAll();
        }
    }

    public ServerConfig config() {
        return config;
    }

    public RadarRules rules() {
        return rules;
    }

    public int ruleWarnings() {
        return ruleWarnings;
    }

    public Path configFile() {
        return configFile;
    }

    /** The player's radar state, or null if they never sent an Ore Radar packet. */
    @Nullable
    public PlayerRadar player(UUID id) {
        return players.get(id);
    }

    /** How many players' clients said hello in our protocol. */
    public int playersWithOreRadar() {
        int n = 0;
        for (PlayerRadar p : players.values()) {
            if (p.saidHello()) n++;
        }
        return n;
    }

    /**
     * {@code /oreradar reload}: reads the file again and resends config. On a syntax error the
     * previous config stays (core never throws) and the result says why.
     */
    public LoadResult<ServerConfig> reload() {
        LoadResult<ServerConfig> loaded = ServerConfig.load(configFile, config, false);
        logLoad(configFile, loaded);
        config = loaded.config();
        rebuild();
        sendConfigToAll();
        return loaded;
    }

    /** One server tick: every player who said hello, all within one scan budget (DESIGN §5). */
    public void tick() {
        List<ServerPlayer> online = server.getPlayerList().getPlayers();
        if (online.isEmpty() || players.isEmpty()) return;
        budget.startTick(config.scanBudgetMicros(), online.size());
        int tick = server.getTickCount();
        for (int i = 0; i < online.size(); i++) {
            ServerPlayer player = online.get(budget.playerAt(i));
            PlayerRadar radar = players.get(player.getUUID());
            if (radar != null) radar.tick(player, rules, tick, budget, sender(player));
        }
    }

    /**
     * A client-to-server payload, on the server thread. Garbage is logged and ignored: a bad packet
     * never disconnects the player or reaches the game (DESIGN §10).
     */
    public void receive(ServerPlayer player, RadarProtocol.Channel channel, byte[] payload) {
        try {
            switch (channel) {
                case HELLO, PREFS -> onClientPrefs(player, channel, payload);
                case SELECT -> radar(player).select(player, SelectPacket.decode(payload), rules);
                case POWER -> radar(player).power(player, PowerPacket.decode(payload));
                default -> OreRadar.LOGGER.debug("Ignoring {} from {}: not a client-to-server channel", channel.id(),
                        player.getGameProfile().getName());
            }
        } catch (PacketException e) {
            OreRadar.LOGGER.debug("Ignoring a malformed {} from {}: {}", channel.id(), player.getGameProfile().getName(), e.getMessage());
        }
    }

    /**
     * Sneak + use on a block: track its group, else the block, unless the rules forbid it. The
     * player's next tick sees the new target and scans for it.
     */
    public void trackBlock(ServerPlayer player, ItemStack radar, Block block) {
        TargetSpec spec = rules.targets().specFor(block);
        if (spec == null) {
            player.displayClientMessage(Component.translatable("oreradar.msg.untrackable", block.getName()), true);
            return;
        }
        PlayerRadar.track(player, radar, rules.targets().resolve(spec.toString()));
    }

    public void onPlayerLeft(UUID id) {
        PlayerRadar radar = players.remove(id);
        if (radar != null) radar.onLeave();
    }

    /**
     * Hello, or prefs: both carry the client's prefs. The first hello of a session (or prefs from a
     * client we have not greeted) is answered with the config, once: the config can be large, and
     * a reload sends it again anyway. Later hellos and prefs only affect the next scans.
     */
    private void onClientPrefs(ServerPlayer player, RadarProtocol.Channel channel, byte[] payload) {
        PlayerRadar radar = radar(player);
        ClientPrefs prefs;
        try {
            prefs = channel == RadarProtocol.Channel.HELLO ? HelloPacket.decode(payload).prefs() : PrefsPacket.decode(payload).prefs();
        } catch (ProtocolMismatchException e) {
            if (radar.onIncompatible(e.remoteVersion())) {
                OreRadar.LOGGER.info("{} runs Ore Radar protocol {}, this server {}: their radar stays off",
                        player.getGameProfile().getName(), e.remoteVersion(), RadarProtocol.VERSION);
            }
            return;
        }
        if (channel == RadarProtocol.Channel.PREFS && radar.saidHello()) {
            radar.onPrefs(prefs);
            return;
        }
        radar.onHello(prefs);
        if (!radar.hasConfig()) sendConfig(player, radar);
    }

    private PlayerRadar radar(ServerPlayer player) {
        return players.computeIfAbsent(player.getUUID(), id -> new PlayerRadar());
    }

    private Consumer<RadarPacket> sender(ServerPlayer player) {
        return packet -> Services.PLATFORM.sendToPlayer(player, packet.channel(), packet.toBytes());
    }

    private void rebuild() {
        List<String> warnings = new ArrayList<>();
        rules = RadarRules.build(config, PackGroups.latest(), warnings);
        warnings.forEach(w -> OreRadar.LOGGER.warn("{}: {}", ServerConfig.FILE_NAME, w));
        ruleWarnings = warnings.size();
        EnergySettings.apply(config);
        players.values().forEach(PlayerRadar::requestScan);
        OreRadar.LOGGER.info("Ore Radar rules: {} targets ({} trackable blocks, {} groups) in {} categories",
                rules.targets().catalog().size(), rules.targets().allowed().size(), rules.targets().groups().size(),
                rules.targets().categories().size());
    }

    private void sendConfigToAll() {
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            PlayerRadar radar = players.get(player.getUUID());
            if (radar != null && radar.saidHello()) sendConfig(player, radar);
        }
    }

    private void sendConfig(ServerPlayer player, PlayerRadar radar) {
        Services.PLATFORM.sendToPlayer(player, RadarProtocol.Channel.CONFIG, rules.configPacket());
        radar.configSent();
    }

    private static void logLoad(Path file, LoadResult<ServerConfig> loaded) {
        loaded.warnings().forEach(w -> OreRadar.LOGGER.warn("{}: {}", file.getFileName(), w));
        if (loaded.fileWritten()) OreRadar.LOGGER.info("Wrote {}", file);
    }
}
