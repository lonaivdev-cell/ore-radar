package net.loserver.oreradar.core.config;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

import net.loserver.oreradar.core.ActiveFrom;
import net.loserver.oreradar.core.designation.Designation;
import net.loserver.oreradar.core.designation.FuelEntry;
import net.loserver.oreradar.core.net.ConfigPacket;
import net.loserver.oreradar.core.net.RadarProtocol;
import net.loserver.oreradar.core.toml.TomlTable;
import net.loserver.oreradar.core.toml.TomlWriter;

/**
 * {@code oreradar-server.toml} (DESIGN §10): what radars may find and show, energy and the charging
 * station. Immutable; {@link #load} returns a new instance. Mods use {@link #SCHEMA}; the Paper
 * plugin uses {@link #PAPER_SCHEMA}, which adds the {@code [paper]} table and leaves out the keys
 * Paper cannot act on: Vein Mining's datapack groups and energy from other mods.
 */
public final class ServerConfig {
    public static final String FILE_NAME = "oreradar-server.toml";

    // [radar]
    public static final Setting.Int MAX_RADIUS = new Setting.Int("radar", "maxRadius", 96, 16, 256,
            "Hard cap on any scan, in blocks from the player, horizontally and vertically");
    public static final Setting.Int PROBE_CHUNKS = new Setting.Int("radar", "probeChunks", 2, 0, 8,
            "How far past the nearest match outlines reach, in chunks; clients may choose less");
    public static final Setting.Int SCAN_INTERVAL_TICKS = new Setting.Int("radar", "scanIntervalTicks", 20, 5, 200,
            "Ticks between two scans for the same player; 20 ticks = 1 second");
    public static final Setting.Int SCAN_BUDGET_MICROS = new Setting.Int("radar", "scanBudgetMicros", 2000, 200, 20000,
            "CPU time all players' scans together may use per server tick, in microseconds; a scan that runs out continues on the next tick");
    public static final Setting.Int MAX_MARKERS = new Setting.Int("radar", "maxMarkers", 256, 0, RadarProtocol.MAX_MARKERS,
            "Most blocks outlined for a player, nearest first");
    public static final Setting.Choice<ActiveFrom> ACTIVE_FROM_MAX = new Setting.Choice<>("radar", "activeFromMax", ActiveFrom.INVENTORY,
            "Farthest from the hands a radar may be and still work; clients may choose less");
    public static final Setting.ListOf<String> DIMENSIONS_DENY = new Setting.ListOf<>("radar", "dimensionsDeny", List.of(),
            ServerConfig::normalizeId, s -> s, "Dimensions where radars do nothing, e.g. [\"minecraft:the_end\"]");

    // [features.*]
    public static final Setting.Bool OUTLINES_ENABLED = new Setting.Bool("features.outlines", "enabled", true,
            "Let clients outline matched blocks");
    public static final Setting.Bool OUTLINES_THROUGH_WALLS = new Setting.Bool("features.outlines", "throughWalls", true,
            "Outlines show through other blocks; false hides them behind walls");
    public static final Setting.Bool ARROW_ENABLED = new Setting.Bool("features.arrow", "enabled", true,
            "Let clients show an arrow to the nearest match");
    public static final Setting.Int ARROW_PRECISION_BLOCKS = new Setting.Int("features.arrow", "precisionBlocks", 1, 1, 64,
            "Round the nearest match to the centre of a grid cell this many blocks wide: 1 = exact, 16 = chunk centre");
    public static final Setting.Bool ARROW_SHOW_DISTANCE = new Setting.Bool("features.arrow", "showDistance", true,
            "Let clients show the distance to the nearest match");
    public static final Setting.Bool BEEP_ENABLED = new Setting.Bool("features.beep", "enabled", true,
            "Let clients beep faster as the nearest match gets closer");
    public static final Setting.Int BEEP_MIN_INTERVAL_TICKS = new Setting.Int("features.beep", "minIntervalTicks", 4, 1, 100,
            "Fastest beep, in ticks between beeps");
    public static final Setting.Int BEEP_MAX_RANGE = new Setting.Int("features.beep", "maxRange", 64, 1, 256,
            "Silent when the nearest match is farther than this, in blocks");

    // [targets]
    public static final Setting.Bool ALLOW_ALL = new Setting.Bool("targets", "allowAll", false,
            "Let radars track every block, not only those the categories list. In a big modpack each player with the mod"
                    + " is then sent about 1 MB at join and after every reload");
    public static final Setting.ListOf<Designation> DENY_LIST = new Setting.ListOf<>("targets", "denyList", List.of(),
            Designation::parse, Designation::toString,
            "Block ids and #tags never trackable, even if a category lists them (so is the #oreradar:untrackable tag)");
    public static final Setting.Groups GROUPS = new Setting.Groups("targets", "groups",
            "Blocks found together as one target, e.g. [[\"#c:ores/zinc\", \"create:zinc_ore\"]]; id custom/<first entry>, e.g. custom/c.ores/zinc");
    public static final Setting.Bool READ_VEIN_MINING_GROUPS = new Setting.Bool("targets", "readVeinMiningGroups", true,
            "Also read datapack groups from data/<namespace>/veinmining/groups.json");

    // [energy]
    public static final Setting.Bool ENERGY_ENABLED = new Setting.Bool("energy", "enabled", true,
            "Radars use charge; false makes them free and hides the charge bar");
    public static final Setting.Int CAPACITY = new Setting.Int("energy", "capacity", 36000, 1, 10_000_000,
            "Charge a full radar holds");
    public static final Setting.Int DRAIN_PER_SECOND = new Setting.Int("energy", "drainPerSecond", 10, 0, 1_000_000,
            "Charge an active radar uses per second; the defaults last one hour");
    public static final Setting.Bool ACCEPT_EXTERNAL_CHARGERS = new Setting.Bool("energy", "acceptExternalChargers", true,
            "Other mods' chargers can fill radars directly: FE on Forge/NeoForge, Team Reborn Energy on Fabric");
    public static final Setting.Int FE_PER_CHARGE = new Setting.Int("energy", "fePerCharge", 10, 1, 1_000_000,
            "Forge Energy (FE) per unit of charge");
    public static final Setting.Int TR_ENERGY_PER_CHARGE = new Setting.Int("energy", "trEnergyPerCharge", 4, 1, 1_000_000,
            "Team Reborn Energy per unit of charge");

    // [station]
    public static final Setting.Int BUFFER_CAPACITY = new Setting.Int("station", "bufferCapacity", 200000, 1, 100_000_000,
            "Charge a charging station stores");
    public static final Setting.Int CHARGE_RATE_PER_TICK = new Setting.Int("station", "chargeRatePerTick", 200, 1, 10_000_000,
            "Charge moved from the station into a radar per tick");
    public static final Setting.Int MAX_INPUT_PER_TICK = new Setting.Int("station", "maxInputPerTick", 2000, 0, 10_000_000,
            "Most charge a station accepts from energy cables per tick, after conversion");
    public static final Setting.ListOf<FuelEntry> FUELS = new Setting.ListOf<>("station", "fuels", List.of(
            FuelEntry.parse("minecraft:redstone=4000"), FuelEntry.parse("minecraft:redstone_block=36000"),
            FuelEntry.parse("minecraft:coal=1600"), FuelEntry.parse("minecraft:charcoal=1600"),
            FuelEntry.parse("minecraft:coal_block=16000"), FuelEntry.parse("minecraft:glowstone_dust=2000"),
            FuelEntry.parse("#c:dusts/redstone=4000")), FuelEntry::parse, FuelEntry::toString,
            "Items the station burns into its buffer, as \"item=charge\" or \"#item_tag=charge\"");

    // [paper], Paper only
    public static final Setting.Int PAPER_MAX_DISPLAY_OUTLINES = new Setting.Int("paper", "maxDisplayOutlines", 48, 0, RadarProtocol.MAX_MARKERS,
            "Most glowing block displays shown to a vanilla client, nearest first");
    public static final Setting.Text PAPER_GLOW_COLOR = new Setting.Text("paper", "glowColor", "#E8F2FF", Colors::isColor, "#RRGGBB",
            "Glow colour of those block displays");

    private static final List<String> CATEGORY_KEYS = List.of("name", "icon", "blocks");

    public static final ConfigSchema SCHEMA = schema(false);
    public static final ConfigSchema PAPER_SCHEMA = schema(true);

    private static final ServerConfig DEFAULTS = new ServerConfig(false, ConfigValues.defaults(SCHEMA), CategoryDef.DEFAULTS);
    private static final ServerConfig PAPER_DEFAULTS = new ServerConfig(true, ConfigValues.defaults(PAPER_SCHEMA), CategoryDef.DEFAULTS);

    private final boolean paper;
    private final ConfigValues values;
    private final List<CategoryDef> categories;
    private final Set<String> deniedDimensions;

    private ServerConfig(boolean paper, ConfigValues values, List<CategoryDef> categories) {
        this.paper = paper;
        this.values = values;
        this.categories = List.copyOf(categories);
        this.deniedDimensions = Set.copyOf(values.get(DIMENSIONS_DENY));
    }

    public static ServerConfig defaults(boolean paper) {
        return paper ? PAPER_DEFAULTS : DEFAULTS;
    }

    /**
     * Reads the file, writing it first if missing and completing it if keys are missing. On a
     * syntax error the result carries {@code previous} (defaults if null) and a line:column warning.
     */
    public static LoadResult<ServerConfig> load(Path file, ServerConfig previous, boolean paper) {
        ConfigSchema schema = paper ? PAPER_SCHEMA : SCHEMA;
        ServerConfig prev = previous != null ? previous : defaults(paper);
        return ConfigFile.load(file, schema, prev,
                (root, warnings) -> new ServerConfig(paper, ConfigValues.read(root, schema, warnings), readCategories(root, warnings)),
                null);
    }

    /** A copy with one setting changed (checked like a file value). */
    public <T> ServerConfig with(Setting<T> setting, T value) {
        return new ServerConfig(paper, values.with(setting, value), categories);
    }

    public ServerConfig withCategories(List<CategoryDef> newCategories) {
        if (newCategories.size() > RadarProtocol.MAX_CATEGORIES) throw new IllegalArgumentException("too many categories");
        return new ServerConfig(paper, values, newCategories);
    }

    public <T> T get(Setting<T> setting) {
        return values.get(setting);
    }

    /** What the config packet tells clients. */
    public ConfigPacket.Limits limits() {
        boolean energy = energyEnabled();
        return new ConfigPacket.Limits(maxRadius(), probeChunks(), maxMarkers(), scanIntervalTicks(), activeFromMax(),
                outlinesEnabled(), outlinesThroughWalls(), arrowEnabled(), arrowShowDistance(), beepEnabled(),
                energy, energy && acceptExternalChargers(),
                arrowPrecisionBlocks(), beepMinIntervalTicks(), beepMaxRange(), capacity(), drainPerSecond());
    }

    public boolean isPaper() {
        return paper;
    }

    public int maxRadius() {
        return values.get(MAX_RADIUS);
    }

    public int probeChunks() {
        return values.get(PROBE_CHUNKS);
    }

    public int scanIntervalTicks() {
        return values.get(SCAN_INTERVAL_TICKS);
    }

    public int scanBudgetMicros() {
        return values.get(SCAN_BUDGET_MICROS);
    }

    public int maxMarkers() {
        return values.get(MAX_MARKERS);
    }

    public ActiveFrom activeFromMax() {
        return values.get(ACTIVE_FROM_MAX);
    }

    public List<String> dimensionsDeny() {
        return values.get(DIMENSIONS_DENY);
    }

    /** @param dimensionId e.g. {@code minecraft:the_end} */
    public boolean isDimensionDenied(String dimensionId) {
        return deniedDimensions.contains(dimensionId);
    }

    public boolean outlinesEnabled() {
        return values.get(OUTLINES_ENABLED);
    }

    public boolean outlinesThroughWalls() {
        return values.get(OUTLINES_THROUGH_WALLS);
    }

    public boolean arrowEnabled() {
        return values.get(ARROW_ENABLED);
    }

    public int arrowPrecisionBlocks() {
        return values.get(ARROW_PRECISION_BLOCKS);
    }

    public boolean arrowShowDistance() {
        return values.get(ARROW_SHOW_DISTANCE);
    }

    public boolean beepEnabled() {
        return values.get(BEEP_ENABLED);
    }

    public int beepMinIntervalTicks() {
        return values.get(BEEP_MIN_INTERVAL_TICKS);
    }

    public int beepMaxRange() {
        return values.get(BEEP_MAX_RANGE);
    }

    /** Every block is trackable, not only those the categories list. */
    public boolean allowAll() {
        return values.get(ALLOW_ALL);
    }

    public List<Designation> denyList() {
        return values.get(DENY_LIST);
    }

    /** The config's groups in file order; an entry may be empty (its id number is still taken). */
    public List<List<Designation>> groups() {
        return values.get(GROUPS);
    }

    /** Mods only; Paper reads no datapack groups. */
    public boolean readVeinMiningGroups() {
        return modsOnly(READ_VEIN_MINING_GROUPS, false);
    }

    /** Category tabs in file order, at most {@link RadarProtocol#MAX_CATEGORIES}; also what radars may track (§4). */
    public List<CategoryDef> categories() {
        return categories;
    }

    public boolean energyEnabled() {
        return values.get(ENERGY_ENABLED);
    }

    public int capacity() {
        return values.get(CAPACITY);
    }

    public int drainPerSecond() {
        return values.get(DRAIN_PER_SECOND);
    }

    /** Mods only; Paper has no energy API. */
    public boolean acceptExternalChargers() {
        return modsOnly(ACCEPT_EXTERNAL_CHARGERS, false);
    }

    public int fePerCharge() {
        return modsOnly(FE_PER_CHARGE, FE_PER_CHARGE.defaultValue());
    }

    public int trEnergyPerCharge() {
        return modsOnly(TR_ENERGY_PER_CHARGE, TR_ENERGY_PER_CHARGE.defaultValue());
    }

    public int bufferCapacity() {
        return values.get(BUFFER_CAPACITY);
    }

    public int chargeRatePerTick() {
        return values.get(CHARGE_RATE_PER_TICK);
    }

    /** Mods only; on Paper no cable feeds a station. */
    public int maxInputPerTick() {
        return modsOnly(MAX_INPUT_PER_TICK, 0);
    }

    public List<FuelEntry> fuels() {
        return values.get(FUELS);
    }

    /** Paper only; the default elsewhere. */
    public int paperMaxDisplayOutlines() {
        return paper ? values.get(PAPER_MAX_DISPLAY_OUTLINES) : PAPER_MAX_DISPLAY_OUTLINES.defaultValue();
    }

    /** Paper only, as 0xRRGGBB. */
    public int paperGlowColor() {
        return Colors.parse(paper ? values.get(PAPER_GLOW_COLOR) : PAPER_GLOW_COLOR.defaultValue(), 0xE8F2FF);
    }

    /** A setting Paper's file leaves out: its value on the mods, {@code onPaper} on Paper. */
    private <T> T modsOnly(Setting<T> setting, T onPaper) {
        return paper ? onPaper : values.get(setting);
    }

    private static ConfigSchema schema(boolean paper) {
        // Paper reads no datapack groups and has no energy API, so its file leaves those keys out.
        List<Setting<?>> targets = new ArrayList<>(List.of(ALLOW_ALL, DENY_LIST, GROUPS));
        List<Setting<?>> energy = new ArrayList<>(List.of(ENERGY_ENABLED, CAPACITY, DRAIN_PER_SECOND));
        List<Setting<?>> station = new ArrayList<>(List.of(BUFFER_CAPACITY, CHARGE_RATE_PER_TICK));
        if (!paper) {
            targets.add(READ_VEIN_MINING_GROUPS);
            energy.addAll(List.of(ACCEPT_EXTERNAL_CHARGERS, FE_PER_CHARGE, TR_ENERGY_PER_CHARGE));
            station.add(MAX_INPUT_PER_TICK);
        }
        station.add(FUELS);
        List<ConfigSchema.Node> nodes = new ArrayList<>();
        nodes.add(new ConfigSchema.Table("radar", List.of("Scanning. The server scans; clients only draw what it sends."),
                List.of(MAX_RADIUS, PROBE_CHUNKS, SCAN_INTERVAL_TICKS, SCAN_BUDGET_MICROS, MAX_MARKERS, ACTIVE_FROM_MAX, DIMENSIONS_DENY)));
        nodes.add(new ConfigSchema.Table("features", List.of("What clients may show. A client can turn each one off for itself, never on."),
                List.of()));
        nodes.add(new ConfigSchema.Table("features.outlines", List.of(), List.of(OUTLINES_ENABLED, OUTLINES_THROUGH_WALLS)));
        nodes.add(new ConfigSchema.Table("features.arrow", List.of(), List.of(ARROW_ENABLED, ARROW_PRECISION_BLOCKS, ARROW_SHOW_DISTANCE)));
        nodes.add(new ConfigSchema.Table("features.beep", List.of(), List.of(BEEP_ENABLED, BEEP_MIN_INTERVAL_TICKS, BEEP_MAX_RANGE)));
        nodes.add(new ConfigSchema.Table("targets", List.of(
                "What radars can track is set by the [categories] below. Entries are block ids (\"minecraft:stone\")",
                "or block tags (\"#c:ores\"), as in Vein Mining. Ore tags such as c:ores/iron become one target per ore."),
                targets));
        nodes.add(new ConfigSchema.NamedTables("categories", List.of(
                "The tabs of the radar screen, in this order: [categories.<id>] with name, icon (a block id) and",
                "blocks (ids and #tags). Radars can track a block only if a tab lists it (or allowAll is on).",
                "Tabs with nothing trackable are hidden. At most 64."),
                CATEGORY_KEYS, defaultCategoriesTable(), ServerConfig::categoryDefaults, categoryExamples()));
        nodes.add(new ConfigSchema.Table("energy", List.of("Radar charge."), energy));
        nodes.add(new ConfigSchema.Table("station", List.of("Charging station."), station));
        if (paper) {
            nodes.add(new ConfigSchema.Table("paper", List.of("Paper plugin: how vanilla clients see the radar."),
                    List.of(PAPER_MAX_DISPLAY_OUTLINES, PAPER_GLOW_COLOR)));
        }
        return new ConfigSchema(List.of("Ore Radar server settings. Read at start and by /oreradar reload."), nodes);
    }

    private static TomlTable defaultCategoriesTable() {
        TomlTable t = new TomlTable();
        for (CategoryDef c : CategoryDef.DEFAULTS) t.put(c.id(), categoryTable(c));
        return t;
    }

    /** The built-in tabs a new file does not use, as TOML for the owner to uncomment. */
    private static List<String> categoryExamples() {
        List<String> lines = new ArrayList<>(List.of("More tabs: uncomment one (delete the \"# \" at the start of its lines) to make",
                "those blocks trackable too.", ""));
        for (CategoryDef c : CategoryDef.BUILT_IN) {
            if (CategoryDef.DEFAULTS.contains(c)) continue;
            TomlTable t = categoryTable(c);
            lines.add("[categories." + TomlWriter.key(c.id()) + "]");
            for (String k : t.keySet()) lines.add(k + " = " + TomlWriter.inline(t.get(k)));
            lines.add("");
        }
        return lines;
    }

    private static TomlTable categoryTable(CategoryDef c) {
        TomlTable t = new TomlTable();
        writeCategory(t, c);
        return t;
    }

    /** What a category's missing keys default to: the built-in one's values, else its id as name. */
    private static TomlTable categoryDefaults(String id) {
        CategoryDef builtIn = CategoryDef.builtIn(id);
        if (builtIn != null) return categoryTable(builtIn);
        TomlTable t = new TomlTable();
        t.put("name", id);
        t.put("icon", CategoryDef.FALLBACK_ICON);
        t.put("blocks", List.of());
        return t;
    }

    private static void writeCategory(TomlTable t, CategoryDef c) {
        List<Object> blocks = new ArrayList<>();
        for (Designation d : c.blocks()) blocks.add(d.toString());
        t.put("name", c.name());
        t.put("icon", c.icon());
        t.put("blocks", blocks);
    }

    private static List<CategoryDef> readCategories(TomlTable root, List<String> warnings) {
        TomlTable cats = root.table("categories");
        if (cats == null) return CategoryDef.DEFAULTS;
        List<CategoryDef> out = new ArrayList<>();
        for (String id : cats.keySet()) {
            if (!(cats.get(id) instanceof TomlTable t)) continue;
            String where = "categories." + id;
            if (!CategoryDef.isValidId(id)) {
                warnings.add(where + ": a category id uses lowercase letters, digits, '_', '-' and '.' (at most "
                        + CategoryDef.MAX_ID_LENGTH + "); ignoring it");
                continue;
            }
            if (out.size() == RadarProtocol.MAX_CATEGORIES) {
                warnings.add(where + ": more than " + RadarProtocol.MAX_CATEGORIES + " categories; ignoring it");
                continue;
            }
            TomlTable def = categoryDefaults(id);
            Object name = t.get("name");
            if (!(name instanceof String s) || s.isBlank() || s.length() > RadarProtocol.MAX_NAME_LENGTH) {
                warnings.add(where + ".name: expected a non-empty string of at most " + RadarProtocol.MAX_NAME_LENGTH + " chars; using \""
                        + def.get("name") + "\"");
                name = def.get("name");
            }
            Object icon = t.get("icon");
            Designation iconId = icon instanceof String s ? Designation.parse(s) : null;
            if (iconId == null || iconId.tag()) {
                warnings.add(where + ".icon: expected a block id; using " + def.get("icon"));
                iconId = Designation.block((String) def.get("icon"));
            }
            List<Designation> blocks = new Setting.ListOf<>(where, "blocks", List.of(), Designation::parse, Designation::toString, "")
                    .read(t.get("blocks"), warnings);
            out.add(new CategoryDef(id, (String) name, iconId.id(), blocks));
        }
        return Collections.unmodifiableList(out);
    }

    /** A resource location normalised to {@code ns:path}, or null if invalid. */
    private static String normalizeId(String s) {
        String[] id = Designation.splitId(s.trim());
        return id == null ? null : id[0] + ":" + id[1];
    }
}
