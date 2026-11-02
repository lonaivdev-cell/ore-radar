package net.loserver.oreradar.core.net;

import java.util.Objects;

/**
 * C→S {@code oreradar:select}: track this spec with the radar in that slot ("" clears the target).
 * The spec is only length-checked here; the server decides whether it is allowed.
 */
public record SelectPacket(int slot, String spec) implements RadarPacket {
    public SelectPacket {
        Objects.requireNonNull(spec, "spec");
        if (!RadarProtocol.isValidSlot(slot)) throw new IllegalArgumentException("slot " + slot);
        if (spec.length() > RadarProtocol.MAX_SPEC_LENGTH) throw new IllegalArgumentException("spec too long");
    }

    @Override
    public RadarProtocol.Channel channel() {
        return RadarProtocol.Channel.SELECT;
    }

    @Override
    public void encode(RadarBuf.Writer w) {
        w.writeVarInt(slot);
        w.writeString(spec, RadarProtocol.MAX_SPEC_LENGTH);
    }

    public static SelectPacket decode(RadarBuf.Reader r) {
        return new SelectPacket(RadarProtocol.readSlot(r), r.readString(RadarProtocol.MAX_SPEC_LENGTH));
    }

    public static SelectPacket decode(byte[] payload) {
        return RadarBuf.decode(payload, SelectPacket::decode);
    }
}
