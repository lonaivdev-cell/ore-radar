package net.loserver.oreradar.core.net;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import org.junit.jupiter.api.Test;

import net.loserver.oreradar.core.ActiveFrom;

class PacketsTest {

    static final ConfigPacket.Limits LIMITS = new ConfigPacket.Limits(96, 2, 256, 20, ActiveFrom.INVENTORY,
            true, true, true, true, true, true, false, 16, 4, 64, 36000, 10);

    static ConfigPacket config() {
        List<ConfigPacket.Category> cats = List.of(
                new ConfigPacket.Category("ores", "oreradar.category.ores", true, "minecraft:diamond_ore"),
                new ConfigPacket.Category("gems", "Gemas ✦", false, "minecraft:emerald_ore"));
        List<ConfigPacket.CatalogEntry> entries = List.of(
                new ConfigPacket.CatalogEntry("@ores/iron", "minecraft:iron_ore", "block.minecraft.iron_ore", true, 0b01, 3),
                new ConfigPacket.CatalogEntry("minecraft:emerald_ore", "minecraft:emerald_ore", "block.minecraft.emerald_ore", true, 0b11, 1),
                new ConfigPacket.CatalogEntry("minecraft:water", "minecraft:water", "Água", false, 0, 1));
        return new ConfigPacket(LIMITS, cats, entries);
    }

    static List<RadarPacket> samples() {
        long[] markers = {BlockPosPacking.asLong(1, -64, 1), BlockPosPacking.asLong(-30_000_000, 319, 29_999_999)};
        return List.of(
                new HelloPacket(new ClientPrefs(ActiveFrom.HOTBAR, -1, true, false, true)),
                new HelloPacket(ClientPrefs.DEFAULT),
                new PrefsPacket(new ClientPrefs(ActiveFrom.INVENTORY, 8, false, true, false)),
                new SelectPacket(40, "@ores/iron"),
                new SelectPacket(0, ""),
                new SelectPacket(35, "#forge:ores/iron"),
                new PowerPacket(7, true),
                new PowerPacket(40, false),
                config(),
                new ConfigPacket(LIMITS, List.of(), List.of()),
                new ScanPacket(ScanPacket.Status.FOUND, "@ores/iron", 3, 31200,
                        new ScanPacket.Found(true, BlockPosPacking.asLong(-8, -56, 24), 38, 14, false, markers)),
                new ScanPacket(ScanPacket.Status.FOUND, "#c:ores", 40, 0, new ScanPacket.Found(false, 0, 96, 2000, true, new long[0])),
                new ScanPacket(ScanPacket.Status.NONE_FOUND, "minecraft:spawner", 0, 100, null),
                new ScanPacket(ScanPacket.Status.OFF, "x:y", 1, 5, null),
                new ScanPacket(ScanPacket.Status.NO_CHARGE, "x:y", 1, 0, null),
                new ScanPacket(ScanPacket.Status.DIMENSION_DISABLED, "x:y", 1, 5, null),
                ScanPacket.idle());
    }

    static Function<byte[], ? extends RadarPacket> decoder(RadarProtocol.Channel channel) {
        return switch (channel) {
            case HELLO -> HelloPacket::decode;
            case PREFS -> PrefsPacket::decode;
            case SELECT -> SelectPacket::decode;
            case POWER -> PowerPacket::decode;
            case CONFIG -> ConfigPacket::decode;
            case SCAN -> ScanPacket::decode;
        };
    }

    @Test
    void everyPacketRoundTrips() {
        for (RadarPacket p : samples()) {
            byte[] bytes = p.toBytes();
            RadarPacket back = decoder(p.channel()).apply(bytes);
            assertEquals(p, back, p.channel().id());
            assertArrayEquals(bytes, back.toBytes());
        }
    }

    @Test
    void wireLayoutOfHelloIsAsDesigned() {
        // varint protocol, byte activeFrom, varint probeChunks (-1), byte flags (outlines | beep)
        byte[] hello = new HelloPacket(new ClientPrefs(ActiveFrom.HOTBAR, -1, true, false, true)).toBytes();
        assertArrayEquals(new byte[] {1, 1, -1, -1, -1, -1, 0x0f, 0b101}, hello);
        assertArrayEquals(new byte[] {40, 1}, new PowerPacket(40, true).toBytes());
        // byte status IDLE, empty spec, varint slot -1, varint charge 0
        assertArrayEquals(new byte[] {0, 0, -1, -1, -1, -1, 0x0f, 0}, ScanPacket.idle().toBytes());
    }

    @Test
    void scanLayoutIsAsDesigned() {
        long pos = BlockPosPacking.asLong(1, 2, 3);
        byte[] b = new ScanPacket(ScanPacket.Status.FOUND, "a:b", 5, 300, new ScanPacket.Found(true, pos, 40, 2, true, new long[] {pos})).toBytes();
        RadarBuf.Reader r = new RadarBuf.Reader(b);
        assertEquals(4, r.readUnsignedByte());
        assertEquals("a:b", r.readString(256));
        assertEquals(5, r.readVarInt());
        assertEquals(300, r.readVarInt());
        assertEquals(true, r.readBoolean());
        assertEquals(pos, r.readLong());
        assertEquals(40, r.readVarInt());
        assertEquals(2, r.readVarInt());
        assertEquals(true, r.readBoolean());
        assertEquals(1, r.readVarInt());
        assertEquals(pos, r.readLong());
        r.expectEnd();
    }

    @Test
    void protocolMismatchIsItsOwnException() {
        RadarBuf.Writer w = new RadarBuf.Writer().writeVarInt(2).writeByte(0).writeVarInt(0).writeByte(0);
        byte[] future = w.toByteArray();
        ProtocolMismatchException e = assertThrows(ProtocolMismatchException.class, () -> HelloPacket.decode(future));
        assertEquals(2, e.remoteVersion());
        assertThrows(ProtocolMismatchException.class, () -> PrefsPacket.decode(future));
        assertThrows(ProtocolMismatchException.class, () -> ConfigPacket.decode(new byte[] {2, 9, 9, 9}));
    }

    @Test
    void semanticallyInvalidValuesAreRejected() {
        assertThrows(PacketException.class, () -> SelectPacket.decode(new byte[] {36, 0}), "slot 36");
        assertThrows(PacketException.class, () -> PowerPacket.decode(new byte[] {41, 1}), "slot 41");
        assertThrows(PacketException.class, () -> PowerPacket.decode(new byte[] {-1, -1, -1, -1, 0x0f, 1}), "slot -1");
        assertThrows(PacketException.class, () -> HelloPacket.decode(new byte[] {1, 3, 0, 0}), "activeFrom 3");
        assertThrows(PacketException.class, () -> HelloPacket.decode(new byte[] {1, 0, 65, 0}), "probeChunks 65");
        assertThrows(PacketException.class, () -> ScanPacket.decode(new byte[] {6, 0, 0, 0}), "status 6");
        assertThrows(PacketException.class, () -> ScanPacket.decode(new byte[] {0, 0, 36, 0}), "slot 36");
        RadarBuf.Writer longSpec = new RadarBuf.Writer().writeVarInt(0).writeString("a".repeat(257), 300);
        assertThrows(PacketException.class, () -> SelectPacket.decode(longSpec.toByteArray()), "spec over 256");
        byte[] trailing = new byte[] {0, 1, 0};
        assertThrows(PacketException.class, () -> PowerPacket.decode(trailing), "trailing bytes");
    }

    @Test
    void categoryMaskMustNameSentCategories() {
        assertThrows(IllegalArgumentException.class, () -> new ConfigPacket(LIMITS, config().categories(),
                List.of(new ConfigPacket.CatalogEntry("a:b", "a:b", "n", false, 0b100, 1))));
        // A peer that sends a mask bit for a category it never sent is rejected on decode.
        RadarBuf.Writer forged = new RadarBuf.Writer().writeVarInt(RadarProtocol.VERSION);
        forged.writeVarInt(96).writeVarInt(2).writeVarInt(256).writeVarInt(20).writeByte(2).writeVarInt(0)
                .writeVarInt(1).writeVarInt(4).writeVarInt(64).writeVarInt(36000).writeVarInt(10);
        forged.writeVarInt(0);
        forged.writeVarInt(1).writeString("a:b", 256).writeString("a:b", 256).writeString("n", 256).writeBoolean(false)
                .writeVarLong(1).writeVarInt(1);
        assertThrows(PacketException.class, () -> ConfigPacket.decode(forged.toByteArray()));
    }

    @Test
    void sixtyFourCategoriesUseEveryMaskBit() {
        List<ConfigPacket.Category> cats = new ArrayList<>();
        for (int i = 0; i < 64; i++) cats.add(new ConfigPacket.Category("c" + i, "C" + i, false, "minecraft:stone"));
        ConfigPacket p = new ConfigPacket(LIMITS, cats,
                List.of(new ConfigPacket.CatalogEntry("a:b", "a:b", "n", false, -1L, 1)));
        assertEquals(p, ConfigPacket.decode(p.toBytes()));
        assertEquals(true, p.catalog().get(0).inCategory(63));
        cats.add(new ConfigPacket.Category("c64", "C64", false, "minecraft:stone"));
        assertThrows(IllegalArgumentException.class, () -> new ConfigPacket(LIMITS, cats, List.of()));
    }

    @Test
    void limitsAndFoundRejectNonsense() {
        assertThrows(IllegalArgumentException.class, () -> new ConfigPacket.Limits(96, 2, 2000, 20, ActiveFrom.HANDS,
                true, true, true, true, true, true, true, 1, 4, 64, 36000, 10), "maxMarkers over 1024");
        assertThrows(IllegalArgumentException.class, () -> new ScanPacket.Found(true, 0, 1, 1, false, new long[1025]));
        assertThrows(IllegalArgumentException.class, () -> new ScanPacket(ScanPacket.Status.FOUND, "", -1, 0, null));
        assertThrows(IllegalArgumentException.class, () -> new ScanPacket(ScanPacket.Status.IDLE, "", -1, 0,
                new ScanPacket.Found(false, 0, 0, 0, false, new long[0])));
        assertThrows(IllegalArgumentException.class, () -> new SelectPacket(36, ""));
        assertEquals(0L, new ScanPacket.Found(false, 1234, 0, 0, false, new long[0]).nearestPos(), "no nearest, no position");
    }

    @Test
    void channelsAreNamespaced() {
        assertEquals("oreradar:hello", RadarProtocol.Channel.HELLO.id());
        assertEquals("oreradar:scan", RadarProtocol.Channel.SCAN.id());
        assertEquals(RadarProtocol.Direction.SERVER_TO_CLIENT, RadarProtocol.Channel.SCAN.direction());
        assertEquals(1, RadarProtocol.VERSION);
    }
}
