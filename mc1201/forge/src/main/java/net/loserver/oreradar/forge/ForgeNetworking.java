package net.loserver.oreradar.forge;

import java.util.function.Consumer;

import io.netty.buffer.Unpooled;
import net.loserver.oreradar.OreRadar;
import net.loserver.oreradar.client.ClientBridge;
import net.loserver.oreradar.core.net.RadarProtocol;
import net.loserver.oreradar.server.RadarServer;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.game.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.game.ServerboundCustomPayloadPacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.event.EventNetworkChannel;

/**
 * DESIGN §7 on Forge: one {@link EventNetworkChannel} per channel id, carrying core's bytes as the
 * whole payload. Forge's SimpleChannel would put a discriminator byte in front, which the Fabric
 * mod would not expect.
 *
 * <p>A client with Ore Radar may join a server without it. A server with Ore Radar refuses, at
 * login and with Forge's own message, a client without it: the charging station is a block such a
 * client does not know, and a chunk holding one would fail to load there, leaving a hole in the
 * world (DESIGN §3).
 */
final class ForgeNetworking {
    /** Forge's channel version, for its handshake only; core's protocol version travels in hello and config. */
    private static final String CHANNEL_VERSION = "1";

    private ForgeNetworking() {}

    static void register() {
        for (RadarProtocol.Channel channel : RadarProtocol.Channel.values()) {
            // Client side: any server, with or without Ore Radar. Server side: only clients with it.
            EventNetworkChannel network = NetworkRegistry.newEventChannel(id(channel), () -> CHANNEL_VERSION,
                    NetworkRegistry.acceptMissingOr(CHANNEL_VERSION), CHANNEL_VERSION::equals);
            if (channel.direction() == RadarProtocol.Direction.CLIENT_TO_SERVER) {
                network.addListener(new FromClient(channel));
            } else {
                network.addListener(new FromServer(channel));
            }
        }
    }

    static void sendToPlayer(ServerPlayer player, RadarProtocol.Channel channel, byte[] payload) {
        // A player that never joined (a GameTest stand-in) has no connection to send on.
        if (player.connection == null) return;
        player.connection.send(new ClientboundCustomPayloadPacket(id(channel), wrap(payload)));
    }

    static void sendToServer(RadarProtocol.Channel channel, byte[] payload) {
        PacketDistributor.SERVER.noArg().send(new ServerboundCustomPayloadPacket(id(channel), wrap(payload)));
    }

    /**
     * A client-to-server payload, which Forge delivers on the network thread. A named class rather
     * than a lambda: Forge's event bus reads the event type from the listener's declared type.
     */
    private record FromClient(RadarProtocol.Channel channel) implements Consumer<NetworkEvent.ClientCustomPayloadEvent> {
        @Override
        public void accept(NetworkEvent.ClientCustomPayloadEvent event) {
            NetworkEvent.Context context = event.getSource().get();
            context.setPacketHandled(true);
            ServerPlayer player = context.getSender();
            if (player == null || event.getPayload() == null) return;
            byte[] payload = bytes(event.getPayload());
            context.enqueueWork(logged(channel, () -> {
                RadarServer server = RadarServer.get();
                if (server != null) server.receive(player, channel, payload);
            }));
        }
    }

    /** A server-to-client payload, handed to the client's bridge on the client thread. */
    private record FromServer(RadarProtocol.Channel channel) implements Consumer<NetworkEvent.ServerCustomPayloadEvent> {
        @Override
        public void accept(NetworkEvent.ServerCustomPayloadEvent event) {
            NetworkEvent.Context context = event.getSource().get();
            context.setPacketHandled(true);
            if (event.getPayload() == null) return;
            byte[] payload = bytes(event.getPayload());
            context.enqueueWork(logged(channel, () -> ClientBridge.get().receive(channel, payload)));
        }
    }

    /**
     * Logs what the work throws. Forge runs enqueued work as a future that nothing reads, so an
     * exception would vanish without a trace; Fabric's executor logs it, and so does this.
     */
    private static Runnable logged(RadarProtocol.Channel channel, Runnable work) {
        return () -> {
            try {
                work.run();
            } catch (RuntimeException e) {
                OreRadar.LOGGER.error("Ore Radar: handling {} failed", channel.id(), e);
            }
        };
    }

    /** Copies the payload out: the buffer is released once the handler returns. */
    private static byte[] bytes(FriendlyByteBuf buffer) {
        byte[] payload = new byte[buffer.readableBytes()];
        buffer.readBytes(payload);
        return payload;
    }

    private static FriendlyByteBuf wrap(byte[] payload) {
        return new FriendlyByteBuf(Unpooled.wrappedBuffer(payload));
    }

    private static ResourceLocation id(RadarProtocol.Channel channel) {
        return new ResourceLocation(RadarProtocol.NAMESPACE, channel.path());
    }
}
