package net.loserver.oreradar.core.net;

import java.util.Objects;

/** C→S {@code oreradar:hello}: the client runs Ore Radar; the server answers with config. */
public record HelloPacket(ClientPrefs prefs) implements RadarPacket {
    public HelloPacket {
        Objects.requireNonNull(prefs, "prefs");
    }

    @Override
    public RadarProtocol.Channel channel() {
        return RadarProtocol.Channel.HELLO;
    }

    @Override
    public void encode(RadarBuf.Writer w) {
        w.writeVarInt(RadarProtocol.VERSION);
        prefs.encode(w);
    }

    /** Throws {@link ProtocolMismatchException} for another protocol version. */
    public static HelloPacket decode(RadarBuf.Reader r) {
        RadarProtocol.readVersion(r);
        return new HelloPacket(ClientPrefs.decode(r));
    }

    public static HelloPacket decode(byte[] payload) {
        return RadarBuf.decode(payload, HelloPacket::decode);
    }
}
