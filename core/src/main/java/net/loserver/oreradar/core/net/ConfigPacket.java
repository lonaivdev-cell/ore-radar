package net.loserver.oreradar.core.net;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import net.loserver.oreradar.core.ActiveFrom;

/**
 * S→C {@code oreradar:config}: the server's limits, its category tabs and the catalog of selectable
 * targets. Sent after hello and again after a reload.
 */
public record ConfigPacket(Limits limits, List<Category> categories, List<CatalogEntry> catalog) implements RadarPacket {

    public ConfigPacket {
        Objects.requireNonNull(limits, "limits");
        categories = List.copyOf(categories);
        catalog = List.copyOf(catalog);
        if (categories.size() > RadarProtocol.MAX_CATEGORIES) throw new IllegalArgumentException("too many categories");
        if (catalog.size() > RadarProtocol.MAX_CATALOG) throw new IllegalArgumentException("catalog too large");
        long allowed = categories.size() == 64 ? -1L : (1L << categories.size()) - 1;
        for (CatalogEntry e : catalog) {
            if ((e.categoryMask() & ~allowed) != 0) throw new IllegalArgumentException("category mask names a missing category: " + e.spec());
        }
    }

    /**
     * The server's rules. Feature flags say what clients may show; a client's own settings can
     * only switch things off.
     */
    public record Limits(int maxRadius, int probeChunks, int maxMarkers, int scanIntervalTicks, ActiveFrom activeFromMax,
                         boolean outlines, boolean outlinesThroughWalls, boolean arrow, boolean distance, boolean beep,
                         boolean energy, boolean externalChargers,
                         int arrowPrecisionBlocks, int beepMinIntervalTicks, int beepMaxRange,
                         int chargeCapacity, int drainPerSecond) {
        private static final int OUTLINES = 1, THROUGH_WALLS = 1 << 1, ARROW = 1 << 2, DISTANCE = 1 << 3,
                BEEP = 1 << 4, ENERGY = 1 << 5, EXTERNAL_CHARGERS = 1 << 6;

        public Limits {
            Objects.requireNonNull(activeFromMax, "activeFromMax");
            if (maxRadius < 0 || probeChunks < 0 || probeChunks > RadarProtocol.MAX_PROBE_CHUNKS
                    || maxMarkers < 0 || maxMarkers > RadarProtocol.MAX_MARKERS || scanIntervalTicks < 1
                    || arrowPrecisionBlocks < 1 || beepMinIntervalTicks < 1 || beepMaxRange < 0
                    || chargeCapacity < 1 || drainPerSecond < 0) {
                throw new IllegalArgumentException("limits out of range: " + this);
            }
        }

        public int featureFlags() {
            return (outlines ? OUTLINES : 0) | (outlinesThroughWalls ? THROUGH_WALLS : 0) | (arrow ? ARROW : 0)
                    | (distance ? DISTANCE : 0) | (beep ? BEEP : 0) | (energy ? ENERGY : 0)
                    | (externalChargers ? EXTERNAL_CHARGERS : 0);
        }

        void encode(RadarBuf.Writer w) {
            w.writeVarInt(maxRadius).writeVarInt(probeChunks).writeVarInt(maxMarkers).writeVarInt(scanIntervalTicks);
            w.writeByte(activeFromMax.ordinal());
            w.writeVarInt(featureFlags());
            w.writeVarInt(arrowPrecisionBlocks).writeVarInt(beepMinIntervalTicks).writeVarInt(beepMaxRange);
            w.writeVarInt(chargeCapacity).writeVarInt(drainPerSecond);
        }

        static Limits decode(RadarBuf.Reader r) {
            int maxRadius = r.readVarInt(0, Integer.MAX_VALUE, "maxRadius");
            int probeChunks = r.readVarInt(0, RadarProtocol.MAX_PROBE_CHUNKS, "probeChunks");
            int maxMarkers = r.readVarInt(0, RadarProtocol.MAX_MARKERS, "maxMarkers");
            int scanInterval = r.readVarInt(1, Integer.MAX_VALUE, "scanIntervalTicks");
            ActiveFrom from = ActiveFrom.byOrdinal(r.readUnsignedByte());
            if (from == null) throw new PacketException("invalid activeFromMax");
            int f = r.readVarInt(); // bits this version does not know are ignored
            int precision = r.readVarInt(1, Integer.MAX_VALUE, "arrowPrecisionBlocks");
            int beepMin = r.readVarInt(1, Integer.MAX_VALUE, "beepMinIntervalTicks");
            int beepRange = r.readVarInt(0, Integer.MAX_VALUE, "beepMaxRange");
            int capacity = r.readVarInt(1, Integer.MAX_VALUE, "chargeCapacity");
            int drain = r.readVarInt(0, Integer.MAX_VALUE, "drainPerSecond");
            return new Limits(maxRadius, probeChunks, maxMarkers, scanInterval, from,
                    (f & OUTLINES) != 0, (f & THROUGH_WALLS) != 0, (f & ARROW) != 0, (f & DISTANCE) != 0,
                    (f & BEEP) != 0, (f & ENERGY) != 0, (f & EXTERNAL_CHARGERS) != 0,
                    precision, beepMin, beepRange, capacity, drain);
        }
    }

    /** A GUI tab. Bit i of a catalog entry's category mask means "in categories.get(i)". */
    public record Category(String id, String name, boolean nameIsTranslationKey, String iconBlockId) {
        public Category {
            checkString(id, RadarProtocol.MAX_ID_LENGTH, "id");
            checkString(name, RadarProtocol.MAX_NAME_LENGTH, "name");
            checkString(iconBlockId, RadarProtocol.MAX_ID_LENGTH, "iconBlockId");
        }
    }

    /**
     * One selectable target.
     *
     * @param spec        what to send in {@link SelectPacket}: {@code @group}, {@code #tag} or a block id
     * @param memberCount how many blocks the entry finds (1 for a single block)
     */
    public record CatalogEntry(String spec, String iconBlockId, String name, boolean nameIsTranslationKey,
                               long categoryMask, int memberCount) {
        public CatalogEntry {
            checkString(spec, RadarProtocol.MAX_SPEC_LENGTH, "spec");
            checkString(iconBlockId, RadarProtocol.MAX_ID_LENGTH, "iconBlockId");
            checkString(name, RadarProtocol.MAX_NAME_LENGTH, "name");
            if (memberCount < 0) throw new IllegalArgumentException("memberCount " + memberCount);
        }

        public boolean inCategory(int index) {
            return index >= 0 && index < 64 && (categoryMask & (1L << index)) != 0;
        }
    }

    @Override
    public RadarProtocol.Channel channel() {
        return RadarProtocol.Channel.CONFIG;
    }

    @Override
    public void encode(RadarBuf.Writer w) {
        w.writeVarInt(RadarProtocol.VERSION);
        limits.encode(w);
        w.writeVarInt(categories.size());
        for (Category c : categories) {
            w.writeString(c.id(), RadarProtocol.MAX_ID_LENGTH);
            w.writeString(c.name(), RadarProtocol.MAX_NAME_LENGTH);
            w.writeBoolean(c.nameIsTranslationKey());
            w.writeString(c.iconBlockId(), RadarProtocol.MAX_ID_LENGTH);
        }
        w.writeVarInt(catalog.size());
        for (CatalogEntry e : catalog) {
            w.writeString(e.spec(), RadarProtocol.MAX_SPEC_LENGTH);
            w.writeString(e.iconBlockId(), RadarProtocol.MAX_ID_LENGTH);
            w.writeString(e.name(), RadarProtocol.MAX_NAME_LENGTH);
            w.writeBoolean(e.nameIsTranslationKey());
            w.writeVarLong(e.categoryMask());
            w.writeVarInt(e.memberCount());
        }
    }

    /** Throws {@link ProtocolMismatchException} for another protocol version. */
    public static ConfigPacket decode(RadarBuf.Reader r) {
        RadarProtocol.readVersion(r);
        Limits limits = Limits.decode(r);
        // Minimum encoded sizes: a category is 3 strings + a bool, an entry 3 strings + bool + varlong + varint.
        int nc = r.readCount(RadarProtocol.MAX_CATEGORIES, 4, "category count");
        List<Category> categories = new ArrayList<>(nc);
        for (int i = 0; i < nc; i++) {
            String id = r.readString(RadarProtocol.MAX_ID_LENGTH);
            String name = r.readString(RadarProtocol.MAX_NAME_LENGTH);
            boolean key = r.readBoolean();
            categories.add(new Category(id, name, key, r.readString(RadarProtocol.MAX_ID_LENGTH)));
        }
        long allowed = nc == 64 ? -1L : (1L << nc) - 1;
        int ne = r.readCount(RadarProtocol.MAX_CATALOG, 6, "catalog size");
        List<CatalogEntry> catalog = new ArrayList<>(ne);
        for (int i = 0; i < ne; i++) {
            String spec = r.readString(RadarProtocol.MAX_SPEC_LENGTH);
            String icon = r.readString(RadarProtocol.MAX_ID_LENGTH);
            String name = r.readString(RadarProtocol.MAX_NAME_LENGTH);
            boolean key = r.readBoolean();
            long mask = r.readVarLong();
            if ((mask & ~allowed) != 0) throw new PacketException("category mask names a missing category");
            int members = r.readVarInt(0, Integer.MAX_VALUE, "memberCount");
            catalog.add(new CatalogEntry(spec, icon, name, key, mask, members));
        }
        return new ConfigPacket(limits, categories, catalog);
    }

    public static ConfigPacket decode(byte[] payload) {
        return RadarBuf.decode(payload, ConfigPacket::decode);
    }

    private static void checkString(String s, int max, String what) {
        Objects.requireNonNull(s, what);
        if (s.length() > max) throw new IllegalArgumentException(what + " longer than " + max);
    }
}
