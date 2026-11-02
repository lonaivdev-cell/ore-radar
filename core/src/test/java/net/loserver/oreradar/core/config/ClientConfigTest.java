package net.loserver.oreradar.core.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import net.loserver.oreradar.core.ActiveFrom;
import net.loserver.oreradar.core.net.ClientPrefs;
import net.loserver.oreradar.core.net.ConfigPacket;

class ClientConfigTest {
    @TempDir
    Path dir;

    /** What Ore Radar 1.0's ForgeConfigSpec wrote (NightConfig style: tab-indented, #comments). */
    static final String LEGACY_FILE = """
            #What the radar looks for and how far.
            [detection]
            \t#Outlines are drawn for every match closer than (distance to the nearest match + extraChunks * 16 blocks).
            \t#Range: 0 ~ 16
            \textraChunks = 3
            \t#Range: 16 ~ 512
            \tmaxRadius = 128
            \t#Range: 1 ~ 4096
            \tmaxOutlines = 2000
            \t#Range: 1 ~ 200
            \tscanIntervalTicks = 10
            \t#Range: 200 ~ 20000
            \tscanBudgetMicros = 2000
            \t#Allowed Values: HANDS, HOTBAR, INVENTORY
            \tactiveFrom = "HOTBAR"

            #How matches are drawn.
            [outline]
            \t#Outline colour as #RRGGBB.
            \tcolor = "#E4EEF7"
            \tnearestColor = "FFAA00"
            \t#Range: 0.5 ~ 8.0
            \tlineWidth = 3.0
            \tlineAlpha = 0.9
            \tglow = 0.0
            \tfillAlpha = 0.1
            \tdistanceFade = true

            #The direction indicator and sound.
            [hud]
            \tanchor = "TOP_LEFT"
            \tping = false
            \tpingVolume = 0.35
            """;

    private Path file() {
        return dir.resolve(ClientConfig.FILE_NAME);
    }

    @Test
    void defaultsMatchTheDesignTable() {
        ClientConfig c = ClientConfig.defaults();
        assertEquals(ActiveFrom.HANDS, c.activeFrom());
        assertEquals(-1, c.probeChunks());
        assertTrue(c.outlinesEnabled());
        assertEquals(0xE8F2FF, c.outlinesColor());
        assertEquals(0xFFFFFF, c.outlinesNearestColor());
        assertEquals(2.0, c.outlinesLineWidth());
        assertEquals(0.12, c.outlinesFillAlpha());
        assertTrue(c.outlinesGlow());
        assertEquals(256, c.maxOutlines());
        assertTrue(c.arrowEnabled() && c.arrowShowDistance() && c.arrowShowHeight());
        assertEquals(HudAnchor.CROSSHAIR, c.hudAnchor());
        assertEquals(1.0, c.hudScale());
        assertTrue(c.hudShowCharge());
        assertTrue(c.beepEnabled());
        assertEquals("minecraft:block.note_block.pling", c.beepSound());
        assertEquals(0.6, c.beepVolume());
        assertEquals(1.4, c.beepPitch());
    }

    @Test
    void legacyFileIsReplacedOnceWithABackup() throws Exception {
        Files.writeString(file(), LEGACY_FILE);
        LoadResult<ClientConfig> r = ClientConfig.load(file(), null);
        assertTrue(r.fileWritten());
        assertFalse(r.failed());
        assertEquals(ClientConfig.defaults().prefs(), r.config().prefs(), "1.0's settings are not carried over");
        assertEquals(ClientConfig.defaults().outlinesColor(), r.config().outlinesColor());
        assertEquals(List.of("replaced the Ore Radar 1.0 client settings with 2.0's defaults; the old file is kept as oreradar-client.toml.bak"),
                r.warnings());

        Path bak = dir.resolve(ClientConfig.FILE_NAME + ".bak");
        assertEquals(LEGACY_FILE, Files.readString(bak));
        ClientConfig.load(dir.resolve("fresh.toml"), null);
        assertEquals(Files.readString(dir.resolve("fresh.toml")), Files.readString(file()), "exactly a new file");

        LoadResult<ClientConfig> again = ClientConfig.load(file(), null);
        assertFalse(again.fileWritten(), "this happens once");
        assertEquals(List.of(), again.warnings());

        // Another 1.0 file later never overwrites the first backup.
        Files.writeString(file(), "[detection]\nextraChunks = 1\n");
        ClientConfig.load(file(), null);
        assertEquals(LEGACY_FILE, Files.readString(bak));
        assertEquals("[detection]\nextraChunks = 1\n", Files.readString(dir.resolve(ClientConfig.FILE_NAME + ".bak.1")));
    }

    @Test
    void aTwoPointOhFileWithAStrayDetectionTableIsNotReplaced() throws Exception {
        Files.writeString(file(), "[radar]\nprobeChunks = 4\n[detection]\nextraChunks = 1\n");
        LoadResult<ClientConfig> r = ClientConfig.load(file(), null);
        assertEquals(4, r.config().probeChunks());
        assertFalse(Files.exists(dir.resolve(ClientConfig.FILE_NAME + ".bak")));
        assertTrue(r.warnings().contains("unknown key detection is kept in the file but ignored"));
    }

    @Test
    void withClampsAndSaveRoundTripsKeepingComments() throws Exception {
        ClientConfig.load(file(), null);
        String original = Files.readString(file());
        Files.writeString(file(), original.replace("[beep]", "# my sound notes\n[beep]"));
        ClientConfig c = ClientConfig.load(file(), null).config()
                .with(ClientConfig.BEEP_VOLUME, 5.0)
                .with(ClientConfig.OUTLINES_COLOR, "not a colour")
                .with(ClientConfig.HUD_ANCHOR, HudAnchor.HIDDEN);
        assertEquals(1.0, c.beepVolume());
        assertEquals(0xE8F2FF, c.outlinesColor(), "an invalid colour falls back to the default");
        c.save(file());
        String text = Files.readString(file());
        assertTrue(text.contains("# my sound notes\n[beep]"), text);
        ClientConfig back = ClientConfig.load(file(), null).config();
        assertEquals(1.0, back.beepVolume());
        assertEquals(HudAnchor.HIDDEN, back.hudAnchor());
        assertThrows(IllegalArgumentException.class, () -> c.with(ServerConfig.MAX_RADIUS, 10));
    }

    @Test
    void saveReplacesAnUnparsableFileButKeepsItAsBackup() throws Exception {
        Files.writeString(file(), "this is = = broken");
        ClientConfig.defaults().save(file());
        assertEquals("this is = = broken", Files.readString(dir.resolve(ClientConfig.FILE_NAME + ".bak")));
        assertEquals(ClientConfig.defaults().probeChunks(), ClientConfig.load(file(), null).config().probeChunks());
    }

    @Test
    void anUnknownKeyIsKeptAndWarnedAbout() throws Exception {
        ClientConfig.load(file(), null);
        String extra = "mine = [\"@ores/iron\", \"#c:ores\"]";
        Files.writeString(file(), Files.readString(file()).replace("[beep]\n", "[beep]\n" + extra + "\n"));
        LoadResult<ClientConfig> r = ClientConfig.load(file(), null);
        assertEquals(List.of("unknown key beep.mine is kept in the file but ignored"), r.warnings());
        assertFalse(r.fileWritten(), "nothing else is missing, so the file is left alone");
        r.config().with(ClientConfig.BEEP_VOLUME, 0.3).save(file());
        assertTrue(Files.readString(file()).contains(extra), "a save keeps it too");
    }

    @Test
    void prefsAndEffectiveSettingsAreCappedByTheServer() {
        ClientConfig client = ClientConfig.defaults()
                .with(ClientConfig.ACTIVE_FROM, ActiveFrom.INVENTORY)
                .with(ClientConfig.PROBE_CHUNKS, 6)
                .with(ClientConfig.OUTLINES_MAX, 1000);
        assertEquals(new ClientPrefs(ActiveFrom.INVENTORY, 6, true, true, true), client.prefs());

        ConfigPacket.Limits server = new ConfigPacket.Limits(96, 2, 128, 20, ActiveFrom.HOTBAR,
                true, false, false, true, false, true, true, 1, 4, 64, 36000, 10);
        EffectiveClientSettings e = EffectiveClientSettings.of(client, server);
        assertEquals(ActiveFrom.HOTBAR, e.activeFrom());
        assertEquals(2, e.probeChunks());
        assertEquals(128, e.maxOutlines());
        assertTrue(e.outlines());
        assertFalse(e.outlinesThroughWalls());
        assertFalse(e.arrow(), "the server forbids the arrow");
        assertTrue(e.distance(), "distance alone is still allowed");
        assertTrue(e.height());
        assertFalse(e.beep());
        assertTrue(e.energy());

        EffectiveClientSettings quiet = EffectiveClientSettings.of(
                client.with(ClientConfig.PROBE_CHUNKS, -1).with(ClientConfig.OUTLINES_ENABLED, false)
                        .with(ClientConfig.ARROW_SHOW_HEIGHT, false).with(ClientConfig.ACTIVE_FROM, ActiveFrom.HANDS), server);
        assertEquals(2, quiet.probeChunks(), "-1 means the server's maximum");
        assertEquals(ActiveFrom.HANDS, quiet.activeFrom());
        assertFalse(quiet.outlines());
        assertFalse(quiet.height());
        assertEquals(1, EffectiveClientSettings.clampProbe(1, 2));
        assertEquals(0, EffectiveClientSettings.clampProbe(3, 0));
    }

    @Test
    void schemaExposesConstraintsForASettingsScreen() {
        assertEquals(19, ClientConfig.SCHEMA.settings().size());
        assertEquals("-1..8", ClientConfig.PROBE_CHUNKS.constraint());
        assertEquals("0.5..8.0", ClientConfig.OUTLINES_LINE_WIDTH.constraint());
        assertEquals("CROSSHAIR, TOP_LEFT, TOP_CENTER, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT or HIDDEN", ClientConfig.HUD_ANCHOR.constraint());
        assertEquals("Outline colour (#RRGGBB; default \"#E8F2FF\")", ClientConfig.OUTLINES_COLOR.comment());
        assertEquals("Outline matched blocks (default true)", ClientConfig.OUTLINES_ENABLED.comment());
        assertEquals(List.of(HudAnchor.values()), ClientConfig.HUD_ANCHOR.values());
    }
}
