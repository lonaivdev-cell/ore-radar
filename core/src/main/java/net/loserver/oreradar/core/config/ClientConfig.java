package net.loserver.oreradar.core.config;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import net.loserver.oreradar.core.ActiveFrom;
import net.loserver.oreradar.core.designation.Designation;
import net.loserver.oreradar.core.net.ClientPrefs;
import net.loserver.oreradar.core.toml.TomlDocument;
import net.loserver.oreradar.core.toml.TomlTable;
import net.loserver.oreradar.core.util.AtomicFiles;

/**
 * {@code oreradar-client.toml} (DESIGN §10): how this player wants the radar to look and sound.
 * Anything the server forbids stays off whatever this says ({@link EffectiveClientSettings}).
 * Immutable; the settings screen edits it with {@link #with} and {@link #save}. A 1.0 file is set
 * aside as a .bak and replaced with the defaults (DESIGN §9). Favourites and recent targets are not
 * settings: they live in {@link ClientData}.
 */
public final class ClientConfig {
    public static final String FILE_NAME = "oreradar-client.toml";

    // [radar]
    public static final Setting.Choice<ActiveFrom> ACTIVE_FROM = new Setting.Choice<>("radar", "activeFrom", ActiveFrom.HANDS,
            "Where your radar must be to work; the server may allow less");
    public static final Setting.Int PROBE_CHUNKS = new Setting.Int("radar", "probeChunks", -1, -1, 8,
            "How far past the nearest match to outline, in chunks; -1 = as far as the server allows");

    // [outlines]
    public static final Setting.Bool OUTLINES_ENABLED = new Setting.Bool("outlines", "enabled", true,
            "Outline matched blocks");
    public static final Setting.Text OUTLINES_COLOR = new Setting.Text("outlines", "color", "#E8F2FF", Colors::isColor, "#RRGGBB",
            "Outline colour");
    public static final Setting.Text OUTLINES_NEAREST_COLOR = new Setting.Text("outlines", "nearestColor", "#FFFFFF", Colors::isColor,
            "#RRGGBB", "Colour of the nearest match");
    public static final Setting.Real OUTLINES_LINE_WIDTH = new Setting.Real("outlines", "lineWidth", 2.0, 0.5, 8.0,
            "Line width in pixels at 1080p");
    public static final Setting.Real OUTLINES_FILL_ALPHA = new Setting.Real("outlines", "fillAlpha", 0.12, 0.0, 1.0,
            "Opacity of the faint fill on matched faces; 0 turns it off");
    public static final Setting.Bool OUTLINES_GLOW = new Setting.Bool("outlines", "glow", true,
            "Soft glow around the lines");
    public static final Setting.Int OUTLINES_MAX = new Setting.Int("outlines", "maxOutlines", 256, 1, 1024,
            "Most blocks outlined, nearest first; the server may allow fewer");

    // [arrow]
    public static final Setting.Bool ARROW_ENABLED = new Setting.Bool("arrow", "enabled", true,
            "Show the arrow to the nearest match");
    public static final Setting.Bool ARROW_SHOW_DISTANCE = new Setting.Bool("arrow", "showDistance", true,
            "Show the distance next to the arrow");
    public static final Setting.Bool ARROW_SHOW_HEIGHT = new Setting.Bool("arrow", "showHeight", true,
            "Show how far up or down the match is");

    // [hud]
    public static final Setting.Choice<HudAnchor> HUD_ANCHOR = new Setting.Choice<>("hud", "anchor", HudAnchor.CROSSHAIR,
            "Where the arrow and distance are drawn");
    public static final Setting.Real HUD_SCALE = new Setting.Real("hud", "scale", 1.0, 0.5, 4.0,
            "Size of the HUD");
    public static final Setting.Bool HUD_SHOW_CHARGE = new Setting.Bool("hud", "showCharge", true,
            "Show the active radar's charge on the HUD");

    // [beep]
    public static final Setting.Bool BEEP_ENABLED = new Setting.Bool("beep", "enabled", true,
            "Beep faster as the nearest match gets closer");
    public static final Setting.Text BEEP_SOUND = new Setting.Text("beep", "sound", "minecraft:block.note_block.pling",
            Designation::isValidId, "a sound id", "Sound played for each beep");
    public static final Setting.Real BEEP_VOLUME = new Setting.Real("beep", "volume", 0.6, 0.0, 1.0,
            "Beep volume");
    public static final Setting.Real BEEP_PITCH = new Setting.Real("beep", "pitch", 1.4, 0.5, 2.0,
            "Beep pitch; it rises a little as you get closer");

    public static final ConfigSchema SCHEMA = new ConfigSchema(
            List.of("Ore Radar client settings. Anything the server forbids stays off whatever this file says."),
            List.of(
                    new ConfigSchema.Table("radar", List.of(), List.of(ACTIVE_FROM, PROBE_CHUNKS)),
                    new ConfigSchema.Table("outlines", List.of(), List.of(OUTLINES_ENABLED, OUTLINES_COLOR, OUTLINES_NEAREST_COLOR,
                            OUTLINES_LINE_WIDTH, OUTLINES_FILL_ALPHA, OUTLINES_GLOW, OUTLINES_MAX)),
                    new ConfigSchema.Table("arrow", List.of(), List.of(ARROW_ENABLED, ARROW_SHOW_DISTANCE, ARROW_SHOW_HEIGHT)),
                    new ConfigSchema.Table("hud", List.of(), List.of(HUD_ANCHOR, HUD_SCALE, HUD_SHOW_CHARGE)),
                    new ConfigSchema.Table("beep", List.of(), List.of(BEEP_ENABLED, BEEP_SOUND, BEEP_VOLUME, BEEP_PITCH))));

    private static final ClientConfig DEFAULTS = new ClientConfig(ConfigValues.defaults(SCHEMA));

    private final ConfigValues values;

    private ClientConfig(ConfigValues values) {
        this.values = values;
    }

    public static ClientConfig defaults() {
        return DEFAULTS;
    }

    /**
     * Reads the file: creates it if missing, completes missing keys, replaces a 1.0 file with the
     * defaults (keeping a .bak). On a syntax error the result carries {@code previous} (defaults if null).
     */
    public static LoadResult<ClientConfig> load(Path file, ClientConfig previous) {
        return ConfigFile.load(file, SCHEMA, previous != null ? previous : DEFAULTS,
                (root, warnings) -> new ClientConfig(ConfigValues.read(root, SCHEMA, warnings)),
                ClientConfig::replaceLegacy);
    }

    /**
     * Ore Radar 1.0's file (ForgeConfigSpec: [detection], [outline], [hud]; it has none of 2.0's
     * [radar] or [outlines]) is set aside as a .bak and replaced with a fresh file. Its settings are
     * not carried over, since only a few players ever had it; merged as-is, its old tables would stay
     * in the file and be warned about on every load. Returns null for any other file.
     */
    private static TomlDocument replaceLegacy(TomlDocument doc, Path file, List<String> warnings) throws IOException {
        TomlTable r = doc.root();
        boolean legacy = (r.table("detection") != null || r.table("outline") != null) && !r.contains("radar") && !r.contains("outlines");
        if (!legacy) return null;
        Path backup = AtomicFiles.backup(file);
        warnings.add("replaced the Ore Radar 1.0 client settings with 2.0's defaults; the old file is kept as " + backup.getFileName());
        return new TomlDocument();
    }

    /** Writes these values into the file, keeping its comments and unknown keys. */
    public void save(Path file) throws IOException {
        ConfigFile.save(file, SCHEMA, values::writeTo);
    }

    /** A copy with one setting changed, clamped like a value read from the file. */
    public <T> ClientConfig with(Setting<T> setting, T value) {
        return new ClientConfig(values.with(setting, value));
    }

    public <T> T get(Setting<T> setting) {
        return values.get(setting);
    }

    /** What this client asks the server for, in hello and prefs. */
    public ClientPrefs prefs() {
        return new ClientPrefs(activeFrom(), probeChunks(), outlinesEnabled(), arrowEnabled(), beepEnabled());
    }

    public ActiveFrom activeFrom() {
        return values.get(ACTIVE_FROM);
    }

    /** -1 means the server's maximum. */
    public int probeChunks() {
        return values.get(PROBE_CHUNKS);
    }

    public boolean outlinesEnabled() {
        return values.get(OUTLINES_ENABLED);
    }

    /** 0xRRGGBB */
    public int outlinesColor() {
        return Colors.parse(values.get(OUTLINES_COLOR), 0xE8F2FF);
    }

    /** 0xRRGGBB */
    public int outlinesNearestColor() {
        return Colors.parse(values.get(OUTLINES_NEAREST_COLOR), 0xFFFFFF);
    }

    public double outlinesLineWidth() {
        return values.get(OUTLINES_LINE_WIDTH);
    }

    public double outlinesFillAlpha() {
        return values.get(OUTLINES_FILL_ALPHA);
    }

    public boolean outlinesGlow() {
        return values.get(OUTLINES_GLOW);
    }

    public int maxOutlines() {
        return values.get(OUTLINES_MAX);
    }

    public boolean arrowEnabled() {
        return values.get(ARROW_ENABLED);
    }

    public boolean arrowShowDistance() {
        return values.get(ARROW_SHOW_DISTANCE);
    }

    public boolean arrowShowHeight() {
        return values.get(ARROW_SHOW_HEIGHT);
    }

    public HudAnchor hudAnchor() {
        return values.get(HUD_ANCHOR);
    }

    public double hudScale() {
        return values.get(HUD_SCALE);
    }

    public boolean hudShowCharge() {
        return values.get(HUD_SHOW_CHARGE);
    }

    public boolean beepEnabled() {
        return values.get(BEEP_ENABLED);
    }

    public String beepSound() {
        return values.get(BEEP_SOUND);
    }

    public double beepVolume() {
        return values.get(BEEP_VOLUME);
    }

    public double beepPitch() {
        return values.get(BEEP_PITCH);
    }
}
