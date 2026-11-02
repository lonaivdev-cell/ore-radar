package net.loserver.oreradar.core.net;

import java.util.Arrays;
import java.util.Objects;

/**
 * S→C {@code oreradar:scan}: the result of one scan of the player's active radar, or {@code IDLE}
 * once when no radar is active any more.
 *
 * @param slot   the active radar's slot, -1 for none
 * @param found  present exactly when the status is {@link Status#FOUND}
 */
public record ScanPacket(Status status, String spec, int slot, int charge, Found found) implements RadarPacket {

    /** The ordinal is the wire byte. */
    public enum Status {
        IDLE, OFF, NO_CHARGE, NONE_FOUND, FOUND, DIMENSION_DISABLED;

        private static final Status[] VALUES = values();

        static Status byWire(int b) {
            if (b < 0 || b >= VALUES.length) throw new PacketException("invalid scan status " + b);
            return VALUES[b];
        }
    }

    /**
     * What a {@code FOUND} scan saw. Markers are packed block positions, nearest first; the server
     * sends none when outlines are off, and no nearest position when arrow, beep and distance are.
     * The array is owned by this record: don't modify it.
     *
     * @param nearestPos    valid when hasNearest; already rounded to the server's precision
     * @param outlineRadius how far the scan counted and outlined; without markers, measured to the
     *                      rounded nearest, so it gives away no more than the rounding does
     * @param totalMatches  matches within the scan's outline radius, which may exceed the markers sent
     * @param truncated     true when more matches exist than markers were sent
     */
    public record Found(boolean hasNearest, long nearestPos, int outlineRadius, int totalMatches, boolean truncated, long[] markers) {
        public Found {
            Objects.requireNonNull(markers, "markers");
            if (markers.length > RadarProtocol.MAX_MARKERS) throw new IllegalArgumentException("too many markers");
            if (outlineRadius < 0 || totalMatches < 0) throw new IllegalArgumentException("negative radius or count");
            if (!hasNearest) nearestPos = 0;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Found f && f.hasNearest == hasNearest && f.nearestPos == nearestPos
                    && f.outlineRadius == outlineRadius && f.totalMatches == totalMatches
                    && f.truncated == truncated && Arrays.equals(f.markers, markers);
        }

        @Override
        public int hashCode() {
            return Objects.hash(hasNearest, nearestPos, outlineRadius, totalMatches, truncated) * 31 + Arrays.hashCode(markers);
        }

        @Override
        public String toString() {
            return "Found[hasNearest=" + hasNearest + ", nearestPos=" + nearestPos + ", outlineRadius=" + outlineRadius
                    + ", totalMatches=" + totalMatches + ", truncated=" + truncated + ", markers=" + markers.length + "]";
        }
    }

    public ScanPacket {
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(spec, "spec");
        if (spec.length() > RadarProtocol.MAX_SPEC_LENGTH) throw new IllegalArgumentException("spec too long");
        if (slot != -1 && !RadarProtocol.isValidSlot(slot)) throw new IllegalArgumentException("slot " + slot);
        if (charge < 0) throw new IllegalArgumentException("charge " + charge);
        if ((status == Status.FOUND) != (found != null)) throw new IllegalArgumentException("found must be set exactly for FOUND");
    }

    /** The packet sent once when a player's radar stops being active. */
    public static ScanPacket idle() {
        return new ScanPacket(Status.IDLE, "", -1, 0, null);
    }

    @Override
    public RadarProtocol.Channel channel() {
        return RadarProtocol.Channel.SCAN;
    }

    @Override
    public void encode(RadarBuf.Writer w) {
        w.writeByte(status.ordinal());
        w.writeString(spec, RadarProtocol.MAX_SPEC_LENGTH);
        w.writeVarInt(slot);
        w.writeVarInt(charge);
        if (found == null) return;
        w.writeBoolean(found.hasNearest());
        if (found.hasNearest()) w.writeLong(found.nearestPos());
        w.writeVarInt(found.outlineRadius()).writeVarInt(found.totalMatches()).writeBoolean(found.truncated());
        w.writeVarInt(found.markers().length);
        for (long p : found.markers()) w.writeLong(p);
    }

    public static ScanPacket decode(RadarBuf.Reader r) {
        Status status = Status.byWire(r.readUnsignedByte());
        String spec = r.readString(RadarProtocol.MAX_SPEC_LENGTH);
        int slot = r.readVarInt();
        if (slot != -1 && !RadarProtocol.isValidSlot(slot)) throw new PacketException("invalid slot " + slot);
        int charge = r.readVarInt(0, Integer.MAX_VALUE, "charge");
        Found found = null;
        if (status == Status.FOUND) {
            boolean hasNearest = r.readBoolean();
            long nearest = hasNearest ? r.readLong() : 0;
            int radius = r.readVarInt(0, Integer.MAX_VALUE, "outlineRadius");
            int total = r.readVarInt(0, Integer.MAX_VALUE, "totalMatches");
            boolean truncated = r.readBoolean();
            int n = r.readCount(RadarProtocol.MAX_MARKERS, 8, "marker count");
            long[] markers = new long[n];
            for (int i = 0; i < n; i++) markers[i] = r.readLong();
            found = new Found(hasNearest, nearest, radius, total, truncated, markers);
        }
        return new ScanPacket(status, spec, slot, charge, found);
    }

    public static ScanPacket decode(byte[] payload) {
        return RadarBuf.decode(payload, ScanPacket::decode);
    }
}
