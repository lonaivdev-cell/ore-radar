package net.loserver.oreradar.fabric;

import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.loserver.oreradar.core.net.RadarProtocol;
import net.loserver.oreradar.network.RadarPayload;
import net.loserver.oreradar.server.RadarServer;
import net.minecraft.server.level.ServerPlayer;

/**
 * DESIGN §7 on Fabric: one payload type per channel id, carrying core's bytes as the whole body
 * ({@link RadarPayload}), the same bytes the NeoForge mod sends. The client side
 * lives in {@code FabricClientNetworking}, which only the client entrypoint loads.
 */
final class FabricNetworking {
    private FabricNetworking() {}

    /** The payload types of both directions; both sides must know them before any connection. */
    static void registerPayloadTypes() {
        for (RadarProtocol.Channel channel : RadarProtocol.Channel.values()) {
            if (channel.direction() == RadarProtocol.Direction.CLIENT_TO_SERVER) {
                PayloadTypeRegistry.playC2S().register(RadarPayload.type(channel), RadarPayload.codec(channel));
            } else {
                PayloadTypeRegistry.playS2C().register(RadarPayload.type(channel), RadarPayload.codec(channel));
            }
        }
    }

    /** Fabric runs these on the server thread, and logs what a handler throws. */
    static void registerServerReceivers() {
        for (RadarProtocol.Channel channel : RadarProtocol.Channel.values()) {
            if (channel.direction() != RadarProtocol.Direction.CLIENT_TO_SERVER) continue;
            ServerPlayNetworking.registerGlobalReceiver(RadarPayload.type(channel), (payload, context) -> {
                RadarServer radar = RadarServer.get();
                if (radar != null) radar.receive(context.player(), channel, payload.bytes());
            });
        }
    }

    static void sendToPlayer(ServerPlayer player, RadarProtocol.Channel channel, byte[] payload) {
        // A player that never joined (another mod's fake player) has no connection to send on.
        if (player.connection == null) return;
        ServerPlayNetworking.send(player, new RadarPayload(channel, payload));
    }
}
