package net.loserver.oreradar.fabric;

import io.netty.buffer.Unpooled;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.loserver.oreradar.core.net.RadarProtocol;
import net.loserver.oreradar.server.RadarServer;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

/**
 * DESIGN §7 on Fabric: each channel id carries core's bytes as the whole payload, the same bytes
 * the Forge mod sends. The client side lives in {@code FabricClientNetworking}, which only the
 * client entrypoint loads.
 */
final class FabricNetworking {
    private FabricNetworking() {}

    static void registerServerReceivers() {
        for (RadarProtocol.Channel channel : RadarProtocol.Channel.values()) {
            if (channel.direction() != RadarProtocol.Direction.CLIENT_TO_SERVER) continue;
            ServerPlayNetworking.registerGlobalReceiver(id(channel), (server, player, handler, buffer, sender) -> {
                byte[] payload = bytes(buffer);
                server.execute(() -> {
                    RadarServer radar = RadarServer.get();
                    if (radar != null) radar.receive(player, channel, payload);
                });
            });
        }
    }

    static void sendToPlayer(ServerPlayer player, RadarProtocol.Channel channel, byte[] payload) {
        // A player that never joined (a GameTest stand-in) has no connection to send on.
        if (player.connection == null) return;
        ServerPlayNetworking.send(player, id(channel), wrap(payload));
    }

    /** Client side only; ClientPlayNetworking is loaded when this runs, not before. */
    static void sendToServer(RadarProtocol.Channel channel, byte[] payload) {
        ClientPlayNetworking.send(id(channel), wrap(payload));
    }

    /** Copies the payload out of the buffer, which is released once the handler returns. */
    static byte[] bytes(FriendlyByteBuf buffer) {
        byte[] payload = new byte[buffer.readableBytes()];
        buffer.readBytes(payload);
        return payload;
    }

    static ResourceLocation id(RadarProtocol.Channel channel) {
        return new ResourceLocation(RadarProtocol.NAMESPACE, channel.path());
    }

    private static FriendlyByteBuf wrap(byte[] payload) {
        return new FriendlyByteBuf(Unpooled.wrappedBuffer(payload));
    }
}
