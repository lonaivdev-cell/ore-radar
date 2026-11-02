package net.loserver.oreradar.client;

import java.util.Objects;

import net.loserver.oreradar.core.net.RadarProtocol;
import net.minecraft.network.chat.Component;

/**
 * The seam between common code and the client. Common and server code never reference client
 * classes; where they need the client (opening the radar screen, handing over a packet from the
 * server, naming a target in a tooltip) they call the installed bridge. Until the client
 * entrypoint installs one, and always on a dedicated server, the default does nothing.
 * Every method is called on the client thread.
 */
public interface ClientBridge {
    /** Does nothing: a dedicated server, or a client that has not installed its bridge yet. */
    ClientBridge NONE = new ClientBridge() {};

    /**
     * Opens the radar screen for the radar in that inventory slot (protocol numbering: 0-35, off
     * hand 40). Called when the player uses a radar.
     */
    default void openRadarScreen(int slot) {}

    /**
     * A payload arrived from the server on a server-to-client channel ({@code config} or
     * {@code scan}), not yet decoded. Decode it with core's {@code X.decode(payload)}, which
     * throws {@code PacketException} for garbage.
     */
    default void receive(RadarProtocol.Channel channel, byte[] payload) {}

    /**
     * The name of a target spec for tooltips, from the catalog the server sent; null when the
     * client does not know it, and common code then derives one from the registries.
     */
    default Component targetName(String spec) {
        return null;
    }

    static ClientBridge get() {
        return Holder.current;
    }

    /** Called once by the client entrypoint. */
    static void install(ClientBridge bridge) {
        Holder.current = Objects.requireNonNull(bridge, "bridge");
    }

    /** Interfaces cannot hold mutable static state, so it lives here. */
    final class Holder {
        private static volatile ClientBridge current = NONE;

        private Holder() {}
    }
}
