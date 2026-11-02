package net.loserver.oreradar.core.net;

import java.util.Objects;

import net.loserver.oreradar.core.ActiveFrom;

/**
 * What a client asks for in hello and prefs: the server clamps it to its own limits.
 *
 * @param probeChunks the client's outline reach in chunks, -1 for the server's maximum
 */
public record ClientPrefs(ActiveFrom activeFrom, int probeChunks, boolean wantsOutlines, boolean wantsArrow, boolean wantsBeep) {
    /** Assumed for a player whose client never said hello, and for every player on Paper. */
    public static final ClientPrefs DEFAULT = new ClientPrefs(ActiveFrom.HANDS, -1, true, true, true);

    private static final int OUTLINES = 1, ARROW = 2, BEEP = 4;

    public ClientPrefs {
        Objects.requireNonNull(activeFrom, "activeFrom");
        if (probeChunks < -1 || probeChunks > RadarProtocol.MAX_PROBE_CHUNKS) throw new IllegalArgumentException("probeChunks " + probeChunks);
    }

    void encode(RadarBuf.Writer w) {
        w.writeByte(activeFrom.ordinal());
        w.writeVarInt(probeChunks);
        w.writeByte((wantsOutlines ? OUTLINES : 0) | (wantsArrow ? ARROW : 0) | (wantsBeep ? BEEP : 0));
    }

    static ClientPrefs decode(RadarBuf.Reader r) {
        ActiveFrom from = ActiveFrom.byOrdinal(r.readUnsignedByte());
        if (from == null) throw new PacketException("invalid activeFrom");
        int probe = r.readVarInt(-1, RadarProtocol.MAX_PROBE_CHUNKS, "probeChunks");
        int flags = r.readUnsignedByte();
        return new ClientPrefs(from, probe, (flags & OUTLINES) != 0, (flags & ARROW) != 0, (flags & BEEP) != 0);
    }
}
