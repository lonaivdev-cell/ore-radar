package net.loserver.oreradar.core.config;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import net.loserver.oreradar.core.designation.Designation;
import net.loserver.oreradar.core.net.ConfigPacket;

/**
 * A {@code [categories.<id>]} table: a GUI tab with its name, icon block and the designations it
 * lists. The categories are also the allow-list: radars track only blocks a category lists, unless
 * {@code allowAll} is on (DESIGN §4).
 */
public record CategoryDef(String id, String name, String icon, List<Designation> blocks) {
    public static final int MAX_ID_LENGTH = 64;
    public static final String FALLBACK_ICON = "minecraft:stone";

    /** The §4 ore designations: the {@code ores} tab, the only one a new server file has. */
    public static final List<Designation> ORE_DESIGNATIONS = designations(
            "#c:ores", "#forge:ores",
            "#minecraft:coal_ores", "#minecraft:copper_ores", "#minecraft:iron_ores", "#minecraft:gold_ores",
            "#minecraft:redstone_ores", "#minecraft:lapis_ores", "#minecraft:emerald_ores", "#minecraft:diamond_ores",
            "minecraft:nether_quartz_ore", "minecraft:ancient_debris");

    /**
     * The §4 tabs, in order. Their names reach players translated ({@link #toPacket}), and a table
     * with one of these ids that lacks a key takes it from here. A new server file has the first
     * ({@link #DEFAULTS}); the others are written below it as commented-out examples.
     */
    public static final List<CategoryDef> BUILT_IN = List.of(
            new CategoryDef("ores", "Ores", "minecraft:diamond_ore", ORE_DESIGNATIONS),
            new CategoryDef("stone", "Stone", "minecraft:stone", designations("#minecraft:base_stone_overworld",
                    "#minecraft:base_stone_nether", "#c:stones", "#forge:stone")),
            new CategoryDef("wood", "Wood", "minecraft:oak_log", designations("#minecraft:logs")),
            new CategoryDef("nature", "Nature", "minecraft:poppy", designations("#minecraft:flowers", "#minecraft:leaves",
                    "minecraft:amethyst_cluster", "minecraft:budding_amethyst", "#minecraft:crystal_sound_blocks")),
            new CategoryDef("fluids", "Fluids", "minecraft:water", designations("minecraft:water", "minecraft:lava")),
            new CategoryDef("utility", "Utility", "minecraft:chest", designations("minecraft:spawner", "#minecraft:beds",
                    "minecraft:chest", "minecraft:barrel")));

    /** What a server tracks out of the box: ores only. */
    public static final List<CategoryDef> DEFAULTS = List.of(BUILT_IN.get(0));

    public CategoryDef {
        if (!isValidId(id)) throw new IllegalArgumentException("invalid category id " + id);
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(icon, "icon");
        blocks = List.copyOf(blocks);
    }

    /** Lowercase letters, digits, {@code _ - .}; at most {@value #MAX_ID_LENGTH} chars. */
    public static boolean isValidId(String id) {
        return id != null && id.length() <= MAX_ID_LENGTH && id.indexOf('/') < 0 && Designation.isValidPath(id);
    }

    /** The built-in category with this id, or null. */
    public static CategoryDef builtIn(String id) {
        for (CategoryDef d : BUILT_IN) {
            if (d.id.equals(id)) return d;
        }
        return null;
    }

    public String translationKey() {
        return "oreradar.category." + id;
    }

    /**
     * The tab as the config packet carries it. A built-in category that keeps its default English
     * name is sent as a translation key, so players see it in their own language; a renamed one is
     * sent as written.
     */
    public ConfigPacket.Category toPacket() {
        CategoryDef d = builtIn(id);
        if (d != null && d.name.equals(name)) return new ConfigPacket.Category(id, translationKey(), true, icon);
        return new ConfigPacket.Category(id, name, false, icon);
    }

    private static List<Designation> designations(String... entries) {
        List<Designation> out = new ArrayList<>(entries.length);
        for (String e : entries) out.add(Designation.parse(e));
        return List.copyOf(out);
    }
}
