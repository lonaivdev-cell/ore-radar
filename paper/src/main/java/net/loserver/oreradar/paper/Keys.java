package net.loserver.oreradar.paper;

import org.bukkit.NamespacedKey;

/**
 * Persistent-data keys on items, chunks and entities, and the recipe keys, all in the
 * {@code oreradar} namespace (DESIGN §3 "Item data").
 */
final class Keys {
    /** Item: byte 1 on a {@code minecraft:compass} that is a radar. */
    static final NamespacedKey RADAR = key("radar");
    /** Item: the target spec string. */
    static final NamespacedKey TARGET = key("target");
    /** Item: byte 1 while the radar is switched off; absent when on. */
    static final NamespacedKey OFF = key("off");
    /** Item: int charge; absent means full. */
    static final NamespacedKey CHARGE = key("charge");
    /**
     * Item: a random long that makes each radar unlike every other, so radars never stack and a
     * radar that moved is found again (see {@link RadarItems#newRadar}).
     */
    static final NamespacedKey ID = key("id");
    /** Item: byte 1 on a {@code minecraft:lodestone} that places a charging station. */
    static final NamespacedKey STATION = key("station");
    /** Item and station entry: the station's charge buffer. */
    static final NamespacedKey BUFFER = key("buffer");
    /** Station entry: the serialized item in the radar slot, absent when empty. */
    static final NamespacedKey RADAR_SLOT = key("radar_slot");
    /** Station entry: the serialized item in the fuel slot, absent when empty. */
    static final NamespacedKey FUEL_SLOT = key("fuel_slot");
    /** Entity: byte 1 on our outline displays, so leftovers can be found and removed. */
    static final NamespacedKey OUTLINE = key("outline");

    /** Chunk: one entry per station, {@code station/<x>/<y>/<z>} in block coordinates. */
    static final String STATION_ENTRY_PREFIX = "station/";

    static final NamespacedKey RECIPE_RADAR = key("ore_radar");
    static final NamespacedKey RECIPE_STATION = key("charging_station");

    private Keys() {}

    static NamespacedKey stationEntry(int x, int y, int z) {
        return key(STATION_ENTRY_PREFIX + x + "/" + y + "/" + z);
    }

    /** The block position of a station entry key, or null if the key is not one. */
    static int[] parseStationEntry(NamespacedKey key) {
        if (!key.getNamespace().equals(RADAR.getNamespace()) || !key.getKey().startsWith(STATION_ENTRY_PREFIX)) return null;
        String[] parts = key.getKey().substring(STATION_ENTRY_PREFIX.length()).split("/");
        if (parts.length != 3) return null;
        try {
            return new int[] {Integer.parseInt(parts[0]), Integer.parseInt(parts[1]), Integer.parseInt(parts[2])};
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static NamespacedKey key(String path) {
        return NamespacedKey.fromString("oreradar:" + path);
    }
}
