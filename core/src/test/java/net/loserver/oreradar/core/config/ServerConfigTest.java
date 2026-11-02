package net.loserver.oreradar.core.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import net.loserver.oreradar.core.ActiveFrom;
import net.loserver.oreradar.core.designation.Designation;
import net.loserver.oreradar.core.designation.FuelEntry;
import net.loserver.oreradar.core.net.ConfigPacket;
import net.loserver.oreradar.core.toml.TomlReader;
import net.loserver.oreradar.core.toml.TomlTable;

class ServerConfigTest {
    @TempDir
    Path dir;

    private Path file() {
        return dir.resolve(ServerConfig.FILE_NAME);
    }

    private LoadResult<ServerConfig> load(String text) throws Exception {
        Files.writeString(file(), text);
        return ServerConfig.load(file(), null, false);
    }

    @Test
    void defaultsMatchTheDesignTable() {
        ServerConfig c = ServerConfig.defaults(false);
        assertEquals(96, c.maxRadius());
        assertEquals(2, c.probeChunks());
        assertEquals(20, c.scanIntervalTicks());
        assertEquals(2000, c.scanBudgetMicros());
        assertEquals(256, c.maxMarkers());
        assertEquals(ActiveFrom.INVENTORY, c.activeFromMax());
        assertEquals(List.of(), c.dimensionsDeny());
        assertTrue(c.outlinesEnabled());
        assertTrue(c.outlinesThroughWalls());
        assertTrue(c.arrowEnabled());
        assertEquals(1, c.arrowPrecisionBlocks());
        assertTrue(c.arrowShowDistance());
        assertTrue(c.beepEnabled());
        assertEquals(4, c.beepMinIntervalTicks());
        assertEquals(64, c.beepMaxRange());
        assertFalse(c.allowAll());
        assertEquals(List.of(), c.denyList());
        assertEquals(List.of(), c.groups());
        assertTrue(c.readVeinMiningGroups());
        assertEquals(List.of("ores"), c.categories().stream().map(CategoryDef::id).toList());
        assertEquals(CategoryDef.ORE_DESIGNATIONS, c.categories().get(0).blocks());
        assertTrue(c.energyEnabled());
        assertEquals(36000, c.capacity());
        assertEquals(10, c.drainPerSecond());
        assertTrue(c.acceptExternalChargers());
        assertEquals(10, c.fePerCharge());
        assertEquals(4, c.trEnergyPerCharge());
        assertEquals(200000, c.bufferCapacity());
        assertEquals(200, c.chargeRatePerTick());
        assertEquals(2000, c.maxInputPerTick());
        assertEquals(List.of("minecraft:redstone=4000", "minecraft:redstone_block=36000", "minecraft:coal=1600",
                "minecraft:charcoal=1600", "minecraft:coal_block=16000", "minecraft:glowstone_dust=2000", "#c:dusts/redstone=4000"),
                c.fuels().stream().map(FuelEntry::toString).toList());
        ServerConfig p = ServerConfig.defaults(true);
        assertEquals(48, p.paperMaxDisplayOutlines());
        assertEquals(0xE8F2FF, p.paperGlowColor());
    }

    @Test
    void missingFileIsWrittenWithEveryKeyCommented() throws Exception {
        LoadResult<ServerConfig> r = ServerConfig.load(file(), null, false);
        assertTrue(r.fileWritten());
        assertFalse(r.failed());
        assertEquals(List.of(), r.warnings());
        String text = Files.readString(file());
        assertTrue(text.startsWith("# Ore Radar server settings."), text);
        for (Setting<?> s : ServerConfig.SCHEMA.settings()) {
            assertTrue(text.contains("# " + s.comment() + "\n" + s.key() + " = "), "comment for " + s.path());
        }
        assertTrue(text.contains("[categories.ores]\nname = \"Ores\"\nicon = \"minecraft:diamond_ore\"\nblocks = ["), text);
        assertTrue(text.contains("\nmaxRadius = 96\n"), text);
        assertFalse(text.contains("[paper]"));
        assertFalse(text.contains("\n[categories.stone]"), "only ores is active");
        assertTrue(text.contains("# [categories.utility]\n# name = \"Utility\"\n# icon = \"minecraft:chest\"\n"
                + "# blocks = [\"minecraft:spawner\", \"#minecraft:beds\", \"minecraft:chest\", \"minecraft:barrel\"]\n"), text);

        LoadResult<ServerConfig> again = ServerConfig.load(file(), null, false);
        assertFalse(again.fileWritten(), "a complete file is not rewritten");
        assertEquals(List.of(), again.warnings());
        assertEquals(text, Files.readString(file()));
    }

    @Test
    void theCommentedOutCategoriesWorkOnceUncommented() throws Exception {
        ServerConfig.load(file(), null, false);
        StringBuilder text = new StringBuilder();
        boolean inUtility = false;
        for (String line : Files.readString(file()).split("\n", -1)) {
            if (line.equals("# [categories.utility]")) inUtility = true;
            else if (!line.startsWith("# ")) inUtility = false;
            text.append(inUtility ? line.substring(2) : line).append('\n');
        }
        LoadResult<ServerConfig> r = load(text.toString());
        assertEquals(List.of(), r.warnings());
        assertEquals(List.of(CategoryDef.builtIn("ores"), CategoryDef.builtIn("utility")), r.config().categories());
    }

    @Test
    void outOfRangeIsClampedAndWrongTypeFallsBackWithWarnings() throws Exception {
        ServerConfig.load(file(), null, false);
        String text = Files.readString(file())
                .replace("maxRadius = 96", "maxRadius = 999")
                .replace("probeChunks = 2", "probeChunks = -3")
                .replace("scanIntervalTicks = 20", "scanIntervalTicks = \"fast\"")
                .replace("activeFromMax = \"INVENTORY\"", "activeFromMax = \"hotbar\"")
                .replace("allowAll = false", "allowAll = \"yes\"")
                .replace("maxMarkers = 256", "maxMarkers = 128.0");
        LoadResult<ServerConfig> r = load(text);
        ServerConfig c = r.config();
        assertEquals(256, c.maxRadius());
        assertEquals(0, c.probeChunks());
        assertEquals(20, c.scanIntervalTicks());
        assertEquals(ActiveFrom.HOTBAR, c.activeFromMax(), "enum names match ignoring case");
        assertFalse(c.allowAll());
        assertEquals(128, c.maxMarkers(), "an integral float is accepted for an int");
        assertEquals(4, r.warnings().size(), r.warnings().toString());
        assertTrue(r.warnings().contains("radar.maxRadius = 999 is outside 16..256; using 256"), r.warnings().toString());
        assertTrue(r.warnings().contains("radar.scanIntervalTicks: expected a whole number, found \"fast\"; using 20"));
        assertFalse(r.fileWritten(), "nothing missing, so the user's text stays as written");
        assertTrue(Files.readString(file()).contains("maxRadius = 999"));
    }

    @Test
    void missingKeysAreAddedKeepingUserValuesCommentsAndUnknownKeys() throws Exception {
        LoadResult<ServerConfig> r = load("""
                # my server notes
                [radar]
                # we like it big
                maxRadius = 200   # was 96
                myPluginKey = "keep me"

                [features.beep]
                enabled = false

                [targets]
                allowAll = true
                denyList = ["minecraft:spawner", "Bad Entry", "#minecraft:beds"]
                groups = [["#c:ores/zinc", "create:zinc_ore"], "minecraft:obsidian, minecraft:crying_obsidian", []]

                [mystuff]
                x = 1
                """);
        ServerConfig c = r.config();
        assertTrue(r.fileWritten());
        assertEquals(200, c.maxRadius());
        assertFalse(c.beepEnabled());
        assertTrue(c.allowAll());
        assertEquals(List.of(Designation.block("minecraft:spawner"), Designation.tag("minecraft:beds")), c.denyList());
        assertEquals(3, c.groups().size());
        assertEquals(List.of(Designation.block("minecraft:obsidian"), Designation.block("minecraft:crying_obsidian")), c.groups().get(1));
        assertEquals(List.of(), c.groups().get(2));
        assertTrue(r.warnings().contains("targets.denyList: ignoring invalid entry \"Bad Entry\""), r.warnings().toString());
        assertTrue(r.warnings().contains("unknown key radar.myPluginKey is kept in the file but ignored"), r.warnings().toString());
        assertTrue(r.warnings().contains("unknown key mystuff is kept in the file but ignored"), r.warnings().toString());

        String text = Files.readString(file());
        TomlTable root = TomlReader.parse(text).root();
        assertEquals(200L, root.get("radar", "maxRadius"));
        assertEquals("keep me", root.get("radar", "myPluginKey"));
        assertEquals(1L, root.get("mystuff", "x"));
        assertEquals(2L, root.get("radar", "probeChunks"));
        assertTrue(text.startsWith("# my server notes\n[radar]\n# we like it big\nmaxRadius = 200 # was 96\n"), text);
        assertTrue(text.contains("# " + ServerConfig.PROBE_CHUNKS.comment() + "\nprobeChunks = 2\n"), "added keys get the schema comment");
        assertTrue(text.contains("\n[categories.ores]\n"), "a missing categories table is written with the defaults");
        assertTrue(text.contains("\n# [categories.utility]\n"), "and the examples");

        LoadResult<ServerConfig> again = ServerConfig.load(file(), null, false);
        assertFalse(again.fileWritten());
        assertEquals(200, again.config().maxRadius());
    }

    @Test
    void parseErrorKeepsThePreviousConfigAndTheFile() throws Exception {
        ServerConfig previous = ServerConfig.defaults(false).with(ServerConfig.MAX_RADIUS, 128);
        String broken = "[radar]\nmaxRadius = 100\nprobeChunks = = 2\n";
        Files.writeString(file(), broken);
        LoadResult<ServerConfig> r = ServerConfig.load(file(), previous, false);
        assertTrue(r.failed());
        assertSame(previous, r.config());
        assertFalse(r.fileWritten());
        assertEquals(1, r.warnings().size());
        assertTrue(r.warnings().get(0).contains("line 3, column 15"), r.warnings().get(0));
        assertEquals(broken, Files.readString(file()));
        // With no previous config, the defaults stand in.
        assertEquals(96, ServerConfig.load(file(), null, false).config().maxRadius());
    }

    @Test
    void crlfFileWithUnicodeLoads() throws Exception {
        LoadResult<ServerConfig> r = load("[radar]\r\nmaxRadius = 64\r\n[categories.minerios]\r\nname = \"Minérios ⛏\"\r\n"
                + "icon = \"minecraft:iron_ore\"\r\nblocks = [\r\n  \"#c:ores\", # all\r\n]\r\n");
        assertEquals(64, r.config().maxRadius());
        assertEquals(List.of(new CategoryDef("minerios", "Minérios ⛏", "minecraft:iron_ore", List.of(Designation.tag("c:ores")))),
                r.config().categories());
        assertEquals(List.of(), r.warnings());
    }

    @Test
    void categoriesTheUserRemovedStayRemovedAndBadOnesAreReported() throws Exception {
        StringBuilder many = new StringBuilder();
        for (int i = 0; i < 70; i++) many.append("[categories.c").append(i).append("]\nname = \"C").append(i).append("\"\nicon = \"minecraft:stone\"\nblocks = []\n");
        LoadResult<ServerConfig> r = load("""
                [categories.ores]
                name = "Ores"
                blocks = ["#c:ores"]
                [categories."Bad Id"]
                name = "x"
                [categories.weird]
                name = ""
                icon = "#minecraft:logs"
                blocks = "nope"
                extra = 1
                """ + many);
        List<CategoryDef> cats = r.config().categories();
        assertEquals(64, cats.size());
        assertEquals("ores", cats.get(0).id());
        assertEquals("minecraft:diamond_ore", cats.get(0).icon(), "a missing icon of a built-in category takes its default");
        CategoryDef weird = cats.get(1);
        assertEquals("weird", weird.id());
        assertEquals("weird", weird.name());
        assertEquals(CategoryDef.FALLBACK_ICON, weird.icon());
        assertEquals(List.of(), weird.blocks());
        List<String> w = r.warnings();
        assertTrue(w.stream().anyMatch(s -> s.startsWith("categories.Bad Id: a category id uses")), w.toString());
        assertTrue(w.contains("categories.weird.icon: expected a block id; using minecraft:stone"), w.toString());
        assertTrue(w.contains("categories.weird.blocks: expected an array of strings, found \"nope\"; using []"), w.toString());
        assertTrue(w.contains("unknown key categories.weird.extra is kept in the file but ignored"), w.toString());
        assertTrue(w.stream().anyMatch(s -> s.startsWith("categories.c62: more than 64 categories")), w.toString());
        String text = Files.readString(file());
        assertFalse(text.contains("[categories.stone]"), "deleted categories are not brought back, not even as examples");
        assertTrue(text.contains("[categories.ores]\nname = \"Ores\"\nicon = \"minecraft:diamond_ore\"\nblocks = [\"#c:ores\"]\n"), text);
    }

    @Test
    void fuelsParseAndBadOnesAreDropped() throws Exception {
        LoadResult<ServerConfig> r = load("[station]\nfuels = [\"minecraft:redstone = 10\", \"#c:dusts/glowstone=5\", \"x=0\", \"nonumber\", 7]\n");
        assertEquals(List.of(new FuelEntry(Designation.block("minecraft:redstone"), 10), new FuelEntry(Designation.tag("c:dusts/glowstone"), 5)),
                r.config().fuels());
        assertEquals(3, r.warnings().stream().filter(s -> s.startsWith("station.fuels: ignoring invalid entry")).count(), r.warnings().toString());
    }

    @Test
    void limitsCarryTheFeatureFlags() {
        ServerConfig c = ServerConfig.defaults(false)
                .with(ServerConfig.BEEP_ENABLED, false)
                .with(ServerConfig.ACCEPT_EXTERNAL_CHARGERS, true)
                .with(ServerConfig.ENERGY_ENABLED, false)
                .with(ServerConfig.ARROW_PRECISION_BLOCKS, 16)
                .with(ServerConfig.MAX_RADIUS, 5000);
        ConfigPacket.Limits l = c.limits();
        assertEquals(256, l.maxRadius(), "with() clamps like the file does");
        assertTrue(l.outlines() && l.outlinesThroughWalls() && l.arrow() && l.distance());
        assertFalse(l.beep());
        assertFalse(l.energy());
        assertFalse(l.externalChargers(), "no external chargers without energy");
        assertEquals(16, l.arrowPrecisionBlocks());
        assertEquals(0b0001111, l.featureFlags());
    }

    @Test
    void paperSchemaAddsThePaperTableAndModsKeepItAsUnknown() throws Exception {
        Files.writeString(file(), "[paper]\nmaxDisplayOutlines = 12\nglowColor = \"#00ff00\"\n");
        LoadResult<ServerConfig> paper = ServerConfig.load(file(), null, true);
        assertEquals(12, paper.config().paperMaxDisplayOutlines());
        assertEquals(0x00FF00, paper.config().paperGlowColor());
        assertTrue(paper.config().isPaper());

        Path mods = dir.resolve("mods.toml");
        Files.writeString(mods, "[paper]\nmaxDisplayOutlines = 12\n");
        LoadResult<ServerConfig> mod = ServerConfig.load(mods, null, false);
        assertEquals(48, mod.config().paperMaxDisplayOutlines());
        assertTrue(mod.warnings().contains("unknown key paper is kept in the file but ignored"), mod.warnings().toString());
        assertEquals(12L, TomlReader.read(mods).root().get("paper", "maxDisplayOutlines"));
    }

    @Test
    void paperFileLeavesOutWhatPaperCannotUse() throws Exception {
        LoadResult<ServerConfig> r = ServerConfig.load(file(), null, true);
        assertEquals(List.of(), r.warnings());
        String text = Files.readString(file());
        for (Setting<?> s : ServerConfig.PAPER_SCHEMA.settings()) {
            assertTrue(text.contains("# " + s.comment() + "\n" + s.key() + " = "), "comment for " + s.path());
        }
        assertTrue(text.contains("\n[paper]\n"), text);
        for (String modsOnly : List.of("readVeinMiningGroups", "acceptExternalChargers", "fePerCharge", "trEnergyPerCharge",
                "maxInputPerTick")) {
            assertFalse(text.contains(modsOnly), modsOnly);
        }
        ServerConfig p = r.config();
        assertFalse(p.readVeinMiningGroups());
        assertFalse(p.acceptExternalChargers());
        assertEquals(0, p.maxInputPerTick());
        assertFalse(p.limits().externalChargers());
    }

    @Test
    void dimensionsAreNormalised() throws Exception {
        ServerConfig c = load("[radar]\ndimensionsDeny = [\"minecraft:the_end\", \"the_nether\", \"Bad:Dim\"]\n").config();
        assertEquals(List.of("minecraft:the_end", "minecraft:the_nether"), c.dimensionsDeny());
        assertTrue(c.isDimensionDenied("minecraft:the_nether"));
        assertFalse(c.isDimensionDenied("minecraft:overworld"));
    }

    @Test
    void categoryNamesAreTranslatedOnlyWhileUnchanged() {
        CategoryDef ores = CategoryDef.DEFAULTS.get(0);
        assertEquals(new ConfigPacket.Category("ores", "oreradar.category.ores", true, "minecraft:diamond_ore"), ores.toPacket());
        assertEquals(new ConfigPacket.Category("stone", "oreradar.category.stone", true, "minecraft:stone"),
                CategoryDef.builtIn("stone").toPacket(), "an uncommented example too");
        CategoryDef renamed = new CategoryDef("ores", "Minérios", "minecraft:iron_ore", ores.blocks());
        assertEquals(new ConfigPacket.Category("ores", "Minérios", false, "minecraft:iron_ore"), renamed.toPacket());
    }

    @Test
    void unreadableFileKeepsPrevious() throws Exception {
        Files.createDirectory(file());
        LoadResult<ServerConfig> r = ServerConfig.load(file(), null, false);
        assertTrue(r.failed());
        assertEquals(96, r.config().maxRadius());
        assertTrue(r.warnings().get(0).startsWith("could not read"), r.warnings().toString());
    }
}
