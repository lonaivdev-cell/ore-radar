package net.loserver.oreradar.core.net;

/** A payload that does not decode: truncated, oversized, or holding values outside the protocol. */
public class PacketException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public PacketException(String message) {
        super(message);
    }
}
