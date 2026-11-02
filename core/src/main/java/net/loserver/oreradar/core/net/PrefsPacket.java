package net.loserver.oreradar.core.net;

import java.util.Objects;

/** C→S {@code oreradar:prefs}: the hello body again, sent whenever the client changes these settings. */
public record PrefsPacket(ClientPrefs prefs) implements RadarPacket {
    public PrefsPacket {
        Objects.requireNonNull(prefs, "prefs");
    }

    @Override
    public RadarProtocol.Channel channel() {
        return RadarProtocol.Channel.PREFS;
    }

    @Override
    public void encode(RadarBuf.Writer w) {
        w.writeVarInt(RadarProtocol.VERSION);
        prefs.encode(w);
    }

    /** Throws {@link ProtocolMismatchException} for another protocol version. */
    public static PrefsPacket decode(RadarBuf.Reader r) {
        RadarProtocol.readVersion(r);
        return new PrefsPacket(ClientPrefs.decode(r));
    }

    public static PrefsPacket decode(byte[] payload) {
        return RadarBuf.decode(payload, PrefsPacket::decode);
    }
}
