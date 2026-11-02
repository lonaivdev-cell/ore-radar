package net.loserver.oreradar.core.net;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.function.Function;

/**
 * The single codec for every Ore Radar payload (DESIGN §7), so the mods and the Paper plugin are
 * wire-compatible: VarInt/VarLong as in Minecraft, strings as VarInt byte length + UTF-8, longs as
 * 8 bytes big-endian. The reader never allocates more than the payload's own size and reports any
 * malformed input as a {@link PacketException}.
 */
public final class RadarBuf {
    /** Minecraft's string limit, in UTF-16 chars. */
    public static final int MAX_STRING_CHARS = 32767;

    private RadarBuf() {}

    /**
     * Decodes a whole payload: the decoder must consume every byte. Values a record constructor
     * rejects surface as {@link PacketException} too, so callers catch one type.
     */
    public static <T> T decode(byte[] payload, Function<Reader, T> decoder) {
        Reader r = new Reader(payload);
        T value;
        try {
            value = decoder.apply(r);
        } catch (IllegalArgumentException e) {
            throw new PacketException(e.getMessage());
        }
        r.expectEnd();
        return value;
    }

    public static final class Writer {
        private byte[] buf = new byte[64];
        private int size;

        public Writer writeByte(int b) {
            if (size == buf.length) buf = Arrays.copyOf(buf, buf.length * 2);
            buf[size++] = (byte) b;
            return this;
        }

        public Writer writeBoolean(boolean b) {
            return writeByte(b ? 1 : 0);
        }

        public Writer writeVarInt(int value) {
            int v = value;
            while ((v & ~0x7F) != 0) {
                writeByte((v & 0x7F) | 0x80);
                v >>>= 7;
            }
            return writeByte(v);
        }

        public Writer writeVarLong(long value) {
            long v = value;
            while ((v & ~0x7FL) != 0) {
                writeByte((int) (v & 0x7F) | 0x80);
                v >>>= 7;
            }
            return writeByte((int) v);
        }

        public Writer writeLong(long v) {
            for (int shift = 56; shift >= 0; shift -= 8) writeByte((int) (v >>> shift));
            return this;
        }

        /** Writes a string of at most maxChars UTF-16 chars; a longer one is a bug on the sending side. */
        public Writer writeString(String s, int maxChars) {
            if (s.length() > maxChars) throw new IllegalArgumentException("string longer than " + maxChars + " chars");
            byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
            writeVarInt(bytes.length);
            for (byte b : bytes) writeByte(b);
            return this;
        }

        public int size() {
            return size;
        }

        public byte[] toByteArray() {
            return Arrays.copyOf(buf, size);
        }
    }

    public static final class Reader {
        private final byte[] data;
        private final int end;
        private int pos;

        public Reader(byte[] data) {
            this(data, 0, data.length);
        }

        public Reader(byte[] data, int offset, int length) {
            if (offset < 0 || length < 0 || offset + length > data.length) throw new IllegalArgumentException("bad slice");
            this.data = data;
            this.pos = offset;
            this.end = offset + length;
        }

        public int remaining() {
            return end - pos;
        }

        public void expectEnd() {
            if (pos != end) throw new PacketException(remaining() + " unexpected trailing bytes");
        }

        public byte readByte() {
            if (pos >= end) throw new PacketException("payload ends early");
            return data[pos++];
        }

        public int readUnsignedByte() {
            return readByte() & 0xFF;
        }

        /** A boolean must be 0 or 1. */
        public boolean readBoolean() {
            int b = readByte();
            if (b != 0 && b != 1) throw new PacketException("invalid boolean " + b);
            return b == 1;
        }

        public int readVarInt() {
            int value = 0;
            for (int shift = 0; shift < 35; shift += 7) {
                byte b = readByte();
                value |= (b & 0x7F) << shift;
                if ((b & 0x80) == 0) return value;
            }
            throw new PacketException("VarInt longer than 5 bytes");
        }

        /** A VarInt that must lie in [min, max]; what names the field in the error. */
        public int readVarInt(int min, int max, String what) {
            int v = readVarInt();
            if (v < min || v > max) throw new PacketException(what + " out of range: " + v);
            return v;
        }

        public long readVarLong() {
            long value = 0;
            for (int shift = 0; shift < 70; shift += 7) {
                byte b = readByte();
                value |= (long) (b & 0x7F) << shift;
                if ((b & 0x80) == 0) return value;
            }
            throw new PacketException("VarLong longer than 10 bytes");
        }

        public long readLong() {
            if (remaining() < 8) throw new PacketException("payload ends early");
            long v = 0;
            for (int i = 0; i < 8; i++) v = (v << 8) | (data[pos++] & 0xFF);
            return v;
        }

        /**
         * A list size, at most max. Also bounded by the bytes left (each element takes at least
         * minBytesEach), so a forged count cannot make the caller pre-size a huge list.
         */
        public int readCount(int max, int minBytesEach, String what) {
            int n = readVarInt(0, max, what);
            if ((long) n * minBytesEach > remaining()) throw new PacketException(what + " " + n + " exceeds the payload");
            return n;
        }

        public String readString(int maxChars) {
            int len = readVarInt();
            if (len < 0 || len > maxChars * 3L) throw new PacketException("string of " + len + " bytes is too long");
            if (len > remaining()) throw new PacketException("payload ends early");
            String s;
            try {
                s = StandardCharsets.UTF_8.newDecoder()
                        .onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT)
                        .decode(ByteBuffer.wrap(data, pos, len))
                        .toString();
            } catch (CharacterCodingException e) {
                throw new PacketException("string is not valid UTF-8");
            }
            if (s.length() > maxChars) throw new PacketException("string longer than " + maxChars + " chars");
            pos += len;
            return s;
        }
    }
}
