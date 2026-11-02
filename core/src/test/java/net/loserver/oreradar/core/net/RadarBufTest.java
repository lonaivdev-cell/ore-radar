package net.loserver.oreradar.core.net;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Random;

import org.junit.jupiter.api.Test;

class RadarBufTest {

    private static byte[] bytes(int... values) {
        byte[] out = new byte[values.length];
        for (int i = 0; i < values.length; i++) out[i] = (byte) values[i];
        return out;
    }

    @Test
    void varIntMatchesMinecraft() {
        // Known encodings from the Minecraft protocol documentation.
        int[][] cases = {
                {0, 0x00}, {1, 0x01}, {127, 0x7f}, {128, 0x80, 0x01}, {255, 0xff, 0x01}, {25565, 0xdd, 0xc7, 0x01},
                {2097151, 0xff, 0xff, 0x7f}, {2147483647, 0xff, 0xff, 0xff, 0xff, 0x07}, {-1, 0xff, 0xff, 0xff, 0xff, 0x0f},
                {-2147483648, 0x80, 0x80, 0x80, 0x80, 0x08}};
        for (int[] c : cases) {
            int[] enc = java.util.Arrays.copyOfRange(c, 1, c.length);
            assertArrayEquals(bytes(enc), new RadarBuf.Writer().writeVarInt(c[0]).toByteArray(), "encode " + c[0]);
            assertEquals(c[0], new RadarBuf.Reader(bytes(enc)).readVarInt(), "decode " + c[0]);
        }
    }

    @Test
    void varLongMatchesMinecraft() {
        assertArrayEquals(bytes(0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0x7f), new RadarBuf.Writer().writeVarLong(Long.MAX_VALUE).toByteArray());
        assertArrayEquals(bytes(0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0x01), new RadarBuf.Writer().writeVarLong(-1).toByteArray());
        assertArrayEquals(bytes(0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x01),
                new RadarBuf.Writer().writeVarLong(Long.MIN_VALUE).toByteArray());
        Random rnd = new Random(7);
        for (int i = 0; i < 10_000; i++) {
            long v = rnd.nextLong() >> rnd.nextInt(64);
            assertEquals(v, new RadarBuf.Reader(new RadarBuf.Writer().writeVarLong(v).toByteArray()).readVarLong());
            int x = (int) v;
            assertEquals(x, new RadarBuf.Reader(new RadarBuf.Writer().writeVarInt(x).toByteArray()).readVarInt());
        }
    }

    @Test
    void longIsBigEndian() {
        assertArrayEquals(bytes(0x01, 0x23, 0x45, 0x67, 0x89, 0xab, 0xcd, 0xef), new RadarBuf.Writer().writeLong(0x0123456789ABCDEFL).toByteArray());
        assertEquals(-2L, new RadarBuf.Reader(bytes(0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xfe)).readLong());
    }

    @Test
    void stringsAreLengthPrefixedUtf8() {
        byte[] enc = new RadarBuf.Writer().writeString("é⛏😀", 10).toByteArray();
        assertArrayEquals(bytes(9, 0xc3, 0xa9, 0xe2, 0x9b, 0x8f, 0xf0, 0x9f, 0x98, 0x80), enc);
        assertEquals("é⛏😀", new RadarBuf.Reader(enc).readString(10));
        assertEquals("", new RadarBuf.Reader(bytes(0)).readString(0));
        String max = "x".repeat(RadarBuf.MAX_STRING_CHARS);
        assertEquals(max, new RadarBuf.Reader(new RadarBuf.Writer().writeString(max, RadarBuf.MAX_STRING_CHARS).toByteArray())
                .readString(RadarBuf.MAX_STRING_CHARS));
        assertThrows(IllegalArgumentException.class, () -> new RadarBuf.Writer().writeString("abc", 2));
    }

    @Test
    void readerRejectsMalformedInput() {
        assertThrows(PacketException.class, () -> new RadarBuf.Reader(bytes()).readByte());
        assertThrows(PacketException.class, () -> new RadarBuf.Reader(bytes(0x80)).readVarInt(), "unterminated");
        assertThrows(PacketException.class, () -> new RadarBuf.Reader(bytes(0x80, 0x80, 0x80, 0x80, 0x80, 0x01)).readVarInt(), "6 bytes");
        assertThrows(PacketException.class, () -> new RadarBuf.Reader(bytes(0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0x01))
                .readVarLong(), "11 bytes");
        assertThrows(PacketException.class, () -> new RadarBuf.Reader(bytes(1, 2, 3)).readLong());
        assertThrows(PacketException.class, () -> new RadarBuf.Reader(bytes(2)).readBoolean());
        assertThrows(PacketException.class, () -> new RadarBuf.Reader(bytes(4, 'a', 'b', 'c', 'd')).readString(1), "over 3 bytes per char");
        assertThrows(PacketException.class, () -> new RadarBuf.Reader(bytes(3, 'a', 'b', 'c')).readString(2), "over the char limit");
        assertThrows(PacketException.class, () -> new RadarBuf.Reader(bytes(5, 'a')).readString(10), "length past the end");
        assertThrows(PacketException.class, () -> new RadarBuf.Reader(bytes(0xff, 0xff, 0xff, 0xff, 0x0f)).readString(10), "negative length");
        assertThrows(PacketException.class, () -> new RadarBuf.Reader(bytes(2, 0xc3, 0x28)).readString(10), "invalid UTF-8");
        assertThrows(PacketException.class, () -> new RadarBuf.Reader(bytes(2, 0xc0, 0x80)).readString(10), "overlong UTF-8");
        assertThrows(PacketException.class, () -> new RadarBuf.Reader(bytes(1, 2)).expectEnd());
        assertThrows(PacketException.class, () -> new RadarBuf.Reader(bytes(5)).readVarInt(0, 4, "x"));
        PacketException forged = assertThrows(PacketException.class, () -> new RadarBuf.Reader(bytes(0xe8, 0x07, 0, 0)).readCount(5000, 8, "markers"));
        assertTrue(forged.getMessage().contains("exceeds the payload"));
    }

    @Test
    void readerOverASlice() {
        byte[] data = bytes(9, 9, 0x05, 0x01, 9);
        RadarBuf.Reader r = new RadarBuf.Reader(data, 2, 2);
        assertEquals(5, r.readVarInt());
        assertTrue(r.readBoolean());
        assertEquals(0, r.remaining());
        assertThrows(PacketException.class, r::readByte);
        assertThrows(IllegalArgumentException.class, () -> new RadarBuf.Reader(data, 4, 2));
    }

    @Test
    void writerGrows() {
        RadarBuf.Writer w = new RadarBuf.Writer();
        for (int i = 0; i < 10_000; i++) w.writeLong(i);
        assertEquals(80_000, w.size());
        RadarBuf.Reader r = new RadarBuf.Reader(w.toByteArray());
        for (int i = 0; i < 10_000; i++) assertEquals(i, r.readLong());
        assertFalse(r.remaining() > 0);
    }
}
