package net.loserver.oreradar.core.net;

/**
 * The peer speaks another protocol version. Not an attack: show "incompatible server version"
 * (client) or ignore the player (server) instead of disconnecting.
 */
public final class ProtocolMismatchException extends PacketException {
    private static final long serialVersionUID = 1L;

    private final int remoteVersion;

    public ProtocolMismatchException(int remoteVersion) {
        super("protocol " + remoteVersion + ", expected " + RadarProtocol.VERSION);
        this.remoteVersion = remoteVersion;
    }

    public int remoteVersion() {
        return remoteVersion;
    }
}
