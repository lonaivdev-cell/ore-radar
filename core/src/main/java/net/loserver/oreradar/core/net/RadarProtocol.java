package net.loserver.oreradar.core.net;

/** Protocol constants shared by every platform (DESIGN §7). */
public final class RadarProtocol {
    public static final int VERSION = 1;
    public static final String NAMESPACE = "oreradar";

    /** Inventory slots as the protocol numbers them: 0-8 hotbar, 9-35 main inventory, 40 off hand. */
    public static final int HOTBAR_SIZE = 9;
    public static final int INVENTORY_SIZE = 36;
    public static final int OFFHAND_SLOT = 40;

    public static final int MAX_SPEC_LENGTH = 256;
    public static final int MAX_ID_LENGTH = 256;
    public static final int MAX_NAME_LENGTH = 256;
    /** Hard caps a decoder enforces, whatever the peer claims. */
    public static final int MAX_MARKERS = 1024;
    public static final int MAX_CATEGORIES = 64;
    public static final int MAX_CATALOG = 65536;
    public static final int MAX_PROBE_CHUNKS = 64;

    public enum Direction {
        CLIENT_TO_SERVER, SERVER_TO_CLIENT
    }

    /** One channel (payload type) per packet, each {@code oreradar:<path>}. */
    public enum Channel {
        HELLO("hello", Direction.CLIENT_TO_SERVER),
        PREFS("prefs", Direction.CLIENT_TO_SERVER),
        SELECT("select", Direction.CLIENT_TO_SERVER),
        POWER("power", Direction.CLIENT_TO_SERVER),
        CONFIG("config", Direction.SERVER_TO_CLIENT),
        SCAN("scan", Direction.SERVER_TO_CLIENT);

        private final String path;
        private final Direction direction;

        Channel(String path, Direction direction) {
            this.path = path;
            this.direction = direction;
        }

        public String path() {
            return path;
        }

        /** {@code oreradar:<path>} */
        public String id() {
            return NAMESPACE + ":" + path;
        }

        public Direction direction() {
            return direction;
        }
    }

    private RadarProtocol() {}

    /** A slot a client may name: 0-35 or the off hand (40). */
    public static boolean isValidSlot(int slot) {
        return (slot >= 0 && slot < INVENTORY_SIZE) || slot == OFFHAND_SLOT;
    }

    static int readSlot(RadarBuf.Reader r) {
        int slot = r.readVarInt();
        if (!isValidSlot(slot)) throw new PacketException("invalid slot " + slot);
        return slot;
    }

    /** Reads the protocol VarInt and fails with {@link ProtocolMismatchException} unless it is ours. */
    static int readVersion(RadarBuf.Reader r) {
        int v = r.readVarInt();
        if (v != VERSION) throw new ProtocolMismatchException(v);
        return v;
    }
}
