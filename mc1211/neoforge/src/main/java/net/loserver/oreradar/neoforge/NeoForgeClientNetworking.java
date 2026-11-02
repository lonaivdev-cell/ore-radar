package net.loserver.oreradar.neoforge;

import net.loserver.oreradar.OreRadar;
import net.loserver.oreradar.core.net.RadarProtocol;
import net.loserver.oreradar.network.RadarPayload;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;

/**
 * The client half of {@link NeoForgeNetworking}: client-to-server payloads. A class of its own so
 * that a dedicated server, which never calls it, never loads the client classes it names.
 */
final class NeoForgeClientNetworking {
    private NeoForgeClientNetworking() {}

    /**
     * NeoForge throws for a payload on a channel the server has not offered, as on a server without
     * Ore Radar, so such a payload is not sent. A NeoForge server with Ore Radar offers every channel
     * before the player joins.
     */
    static void sendToServer(RadarProtocol.Channel channel, byte[] payload) {
        ClientPacketListener connection = Minecraft.getInstance().getConnection();
        if (connection == null || !connection.hasChannel(RadarPayload.type(channel))) {
            OreRadar.LOGGER.debug("Not sending {}: the server has no such channel", channel.id());
            return;
        }
        connection.send(new RadarPayload(channel, payload));
    }
}
