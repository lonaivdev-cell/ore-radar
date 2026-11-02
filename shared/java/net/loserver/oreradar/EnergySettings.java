package net.loserver.oreradar;

import net.loserver.oreradar.core.config.ServerConfig;
import net.loserver.oreradar.core.net.ConfigPacket;

/**
 * The energy rules the radar item obeys wherever it is: its charge bar and tooltip on the client,
 * drain and energy capabilities on the server. The server applies its config at start and on every
 * reload, and {@link #release}s it when it stops. A client connected to another server applies the
 * limits from that server's config packet ({@link #applyLimits}), which carry everything but the
 * energy ratios, and {@link #reset}s on disconnect.
 *
 * <p>While a server runs in this JVM, its config wins and the client's calls change nothing. In
 * singleplayer both would set the same values anyway, but the client leaves the world before the
 * integrated server has written the radars' last drain: a reset then would cut every radar's
 * charge to the default capacity as it is saved.
 *
 * @param enabled           radars use charge at all
 * @param capacity          charge of a full radar
 * @param drainPerSecond    charge an active radar uses per second
 * @param externalChargers  other mods' chargers may fill radars
 * @param fePerCharge       Forge Energy per unit of charge
 * @param trEnergyPerCharge Team Reborn Energy per unit of charge
 */
public record EnergySettings(boolean enabled, int capacity, int drainPerSecond, boolean externalChargers, int fePerCharge,
                             int trEnergyPerCharge) {
    private static final EnergySettings DEFAULTS = of(ServerConfig.defaults(false));
    private static volatile EnergySettings current = DEFAULTS;
    /** A server in this JVM (dedicated or integrated) applied its config and has not stopped. */
    private static volatile boolean serverRunning;

    public static EnergySettings current() {
        return current;
    }

    public static EnergySettings of(ServerConfig config) {
        return new EnergySettings(config.energyEnabled(), config.capacity(), config.drainPerSecond(),
                config.energyEnabled() && config.acceptExternalChargers(), config.fePerCharge(), config.trEnergyPerCharge());
    }

    /** Server: the config just loaded. */
    public static void apply(ServerConfig config) {
        current = of(config);
        serverRunning = true;
    }

    /** Server: stopped; back to the defaults until a client joins another server. */
    public static void release() {
        serverRunning = false;
        current = DEFAULTS;
    }

    /** Client: the limits from the server's config packet; the ratios stay as they are. */
    public static void applyLimits(ConfigPacket.Limits limits) {
        if (serverRunning) return;
        EnergySettings now = current;
        current = new EnergySettings(limits.energy(), limits.chargeCapacity(), limits.drainPerSecond(), limits.externalChargers(),
                now.fePerCharge, now.trEnergyPerCharge);
    }

    /** Client: back to the defaults after leaving a server. */
    public static void reset() {
        if (!serverRunning) current = DEFAULTS;
    }
}
