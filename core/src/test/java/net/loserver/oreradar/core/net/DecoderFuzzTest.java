package net.loserver.oreradar.core.net;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.fail;

import java.util.Arrays;
import java.util.Random;
import java.util.function.Function;

import org.junit.jupiter.api.Test;

/**
 * Garbage from a peer must only ever produce a {@link PacketException}: no other exception, no
 * huge allocation, no hang.
 */
class DecoderFuzzTest {

    private static void decodeOrPacketException(RadarProtocol.Channel channel, byte[] payload) {
        Function<byte[], ? extends RadarPacket> decoder = PacketsTest.decoder(channel);
        try {
            decoder.apply(payload);
        } catch (PacketException expected) {
            // fine: rejected cleanly
        } catch (RuntimeException | OutOfMemoryError e) {
            fail(channel + " threw " + e + " for " + Arrays.toString(payload), e);
        }
    }

    @Test
    void randomBytes() {
        Random rnd = new Random(20261029);
        for (RadarProtocol.Channel channel : RadarProtocol.Channel.values()) {
            for (int i = 0; i < 20_000; i++) {
                byte[] b = new byte[rnd.nextInt(64)];
                rnd.nextBytes(b);
                // Half the time start with a valid protocol version so the decoders get past it.
                if (b.length > 0 && rnd.nextBoolean() && (channel == RadarProtocol.Channel.HELLO
                        || channel == RadarProtocol.Channel.PREFS || channel == RadarProtocol.Channel.CONFIG)) {
                    b[0] = 1;
                }
                decodeOrPacketException(channel, b);
            }
        }
    }

    @Test
    void everyTruncationAndBitFlipOfValidPayloads() {
        Random rnd = new Random(5);
        for (RadarPacket p : PacketsTest.samples()) {
            byte[] full = p.toBytes();
            for (int len = 0; len < full.length; len++) {
                byte[] cut = Arrays.copyOf(full, len);
                assertThrows(PacketException.class, () -> PacketsTest.decoder(p.channel()).apply(cut), p.channel() + " cut at " + len);
            }
            byte[] longer = Arrays.copyOf(full, full.length + 1);
            assertThrows(PacketException.class, () -> PacketsTest.decoder(p.channel()).apply(longer), p.channel() + " with a trailing byte");
            for (int i = 0; i < 2_000; i++) {
                byte[] flipped = full.clone();
                flipped[rnd.nextInt(flipped.length)] ^= (byte) (1 << rnd.nextInt(8));
                decodeOrPacketException(p.channel(), flipped);
            }
        }
    }

    @Test
    void forgedHugeCountsAllocateNothing() {
        // A scan claiming 1024 markers in a 12-byte payload, and a config claiming 65536 entries.
        RadarBuf.Writer scan = new RadarBuf.Writer().writeByte(4).writeString("", 1).writeVarInt(0).writeVarInt(0)
                .writeBoolean(false).writeVarInt(0).writeVarInt(0).writeBoolean(false).writeVarInt(1024);
        assertThrows(PacketException.class, () -> ScanPacket.decode(scan.toByteArray()));
        RadarBuf.Writer config = new RadarBuf.Writer().writeVarInt(1).writeVarInt(96).writeVarInt(2).writeVarInt(256).writeVarInt(20)
                .writeByte(2).writeVarInt(0).writeVarInt(1).writeVarInt(4).writeVarInt(64).writeVarInt(36000).writeVarInt(10)
                .writeVarInt(0).writeVarInt(65536);
        assertThrows(PacketException.class, () -> ConfigPacket.decode(config.toByteArray()));
        RadarBuf.Writer tooMany = new RadarBuf.Writer().writeVarInt(1).writeVarInt(96).writeVarInt(2).writeVarInt(256).writeVarInt(20)
                .writeByte(2).writeVarInt(0).writeVarInt(1).writeVarInt(4).writeVarInt(64).writeVarInt(36000).writeVarInt(10)
                .writeVarInt(65);
        assertThrows(PacketException.class, () -> ConfigPacket.decode(tooMany.toByteArray()), "over 64 categories");
        RadarBuf.Writer hugeString = new RadarBuf.Writer().writeVarInt(0).writeVarInt(Integer.MAX_VALUE);
        assertThrows(PacketException.class, () -> SelectPacket.decode(hugeString.toByteArray()));
    }
}
