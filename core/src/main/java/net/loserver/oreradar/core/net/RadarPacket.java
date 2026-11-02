package net.loserver.oreradar.core.net;

/** A packet body. Each implementation also has {@code static decode(Reader)} and {@code decode(byte[])}. */
public interface RadarPacket {
    RadarProtocol.Channel channel();

    void encode(RadarBuf.Writer w);

    default byte[] toBytes() {
        RadarBuf.Writer w = new RadarBuf.Writer();
        encode(w);
        return w.toByteArray();
    }
}
