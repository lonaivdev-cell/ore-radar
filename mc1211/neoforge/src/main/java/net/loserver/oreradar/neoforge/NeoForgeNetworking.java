package net.loserver.oreradar.neoforge;

import net.loserver.oreradar.OreRadar;
import net.loserver.oreradar.client.ClientBridge;
import net.loserver.oreradar.core.net.RadarProtocol;
import net.loserver.oreradar.network.RadarPayload;
import net.loserver.oreradar.server.RadarServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/**
 * DESIGN §7 on NeoForge: one payload type per channel id, carrying core's bytes as the whole body
 * ({@link RadarPayload}). Every type is optional, so a client with Ore Radar joins a server without
 * it and the other way round; NeoForge then runs the handlers on the game thread. Exceptions there
 * are logged by NeoForge, which also guards the work it hands to the game thread.
 */
final class NeoForgeNetworking {
    /**
     * NeoForge's channel version, for its negotiation only; core's protocol version travels in hello
     * and config, so that a mismatch reads as "incompatible" rather than as a server without Ore Radar.
     */
    private static final String CHANNEL_VERSION = "1";

    private NeoForgeNetworking() {}

    static void register(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar(CHANNEL_VERSION).optional();
        for (RadarProtocol.Channel channel : RadarProtocol.Channel.values()) {
            if (channel.direction() == RadarProtocol.Direction.CLIENT_TO_SERVER) {
                registrar.playToServer(RadarPayload.type(channel), RadarPayload.codec(channel), (payload, context) -> {
                    RadarServer server = RadarServer.get();
                    if (server != null && context.player() instanceof ServerPlayer player) {
                        server.receive(player, channel, payload.bytes());
                    }
                });
            } else {
                // Common code only: ClientBridge is a no-op on a dedicated server, which never gets these.
                registrar.playToClient(RadarPayload.type(channel), RadarPayload.codec(channel),
                        (payload, context) -> ClientBridge.get().receive(channel, payload.bytes()));
            }
        }
    }

    static void sendToPlayer(ServerPlayer player, RadarProtocol.Channel channel, byte[] payload) {
        // A player that never joined (another mod's fake player) has no connection to send on.
        if (player.connection == null) return;
        // NeoForge throws for a payload the client has no channel for; only a client that said
        // hello is ever sent one, and it has the channels, so this is a safeguard.
        if (!player.connection.hasChannel(RadarPayload.type(channel))) {
            OreRadar.LOGGER.debug("Not sending {} to {}: their client has no such channel", channel.id(),
                    player.getGameProfile().getName());
            return;
        }
        PacketDistributor.sendToPlayer(player, new RadarPayload(channel, payload));
    }
}
