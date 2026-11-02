package net.loserver.oreradar.core.net;

/** C→S {@code oreradar:power}: switch the radar in that slot on or off. */
public record PowerPacket(int slot, boolean on) implements RadarPacket {
    public PowerPacket {
        if (!RadarProtocol.isValidSlot(slot)) throw new IllegalArgumentException("slot " + slot);
    }

    @Override
    public RadarProtocol.Channel channel() {
        return RadarProtocol.Channel.POWER;
    }

    @Override
    public void encode(RadarBuf.Writer w) {
        w.writeVarInt(slot);
        w.writeBoolean(on);
    }

    public static PowerPacket decode(RadarBuf.Reader r) {
        return new PowerPacket(RadarProtocol.readSlot(r), r.readBoolean());
    }

    public static PowerPacket decode(byte[] payload) {
        return RadarBuf.decode(payload, PowerPacket::decode);
    }
}
