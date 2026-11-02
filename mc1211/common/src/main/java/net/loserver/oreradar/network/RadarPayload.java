package net.loserver.oreradar.network;

import java.util.EnumMap;
import java.util.Map;

import net.loserver.oreradar.core.net.RadarPacket;
import net.loserver.oreradar.core.net.RadarProtocol;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * One Ore Radar packet on a 1.21.1 connection (DESIGN §7): a custom payload on the channel's id
 * whose body is exactly core's bytes, the bytes the 1.20.1 mods speak too. Each channel
 * is a payload type of its own, and its codec writes the bytes as they are and reads whatever is
 * left of the packet: no length prefix (which {@code ByteBufCodecs.BYTE_ARRAY} would add), no
 * discriminator. Reading never fails; core decodes the bytes on the game thread and reports garbage
 * as a {@code PacketException}, which is logged and ignored, so a malformed packet never reaches
 * the codec stage that would disconnect the player.
 *
 * @param bytes the encoded packet; not copied, so don't modify it
 */
public record RadarPayload(RadarProtocol.Channel channel, byte[] bytes) implements CustomPacketPayload {
    private static final Map<RadarProtocol.Channel, Type<RadarPayload>> TYPES = new EnumMap<>(RadarProtocol.Channel.class);
    private static final Map<RadarProtocol.Channel, StreamCodec<FriendlyByteBuf, RadarPayload>> CODECS =
            new EnumMap<>(RadarProtocol.Channel.class);

    static {
        for (RadarProtocol.Channel channel : RadarProtocol.Channel.values()) {
            TYPES.put(channel, new Type<>(ResourceLocation.fromNamespaceAndPath(RadarProtocol.NAMESPACE, channel.path())));
            CODECS.put(channel, StreamCodec.of(
                    (buffer, payload) -> buffer.writeBytes(payload.bytes()),
                    buffer -> {
                        byte[] bytes = new byte[buffer.readableBytes()];
                        buffer.readBytes(bytes);
                        return new RadarPayload(channel, bytes);
                    }));
        }
    }

    public static RadarPayload of(RadarPacket packet) {
        return new RadarPayload(packet.channel(), packet.toBytes());
    }

    /** The payload type of a channel, {@code oreradar:<path>}. */
    public static Type<RadarPayload> type(RadarProtocol.Channel channel) {
        return TYPES.get(channel);
    }

    /** The codec each loader registers for the channel. */
    public static StreamCodec<FriendlyByteBuf, RadarPayload> codec(RadarProtocol.Channel channel) {
        return CODECS.get(channel);
    }

    @Override
    public Type<RadarPayload> type() {
        return TYPES.get(channel);
    }
}
