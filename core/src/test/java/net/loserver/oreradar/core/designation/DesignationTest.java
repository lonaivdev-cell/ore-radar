package net.loserver.oreradar.core.designation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import net.loserver.oreradar.core.config.CategoryDef;

class DesignationTest {

    @Test
    void parsesBlocksAndTags() {
        assertEquals(new Designation(false, "minecraft", "stone"), Designation.parse("minecraft:stone"));
        assertEquals(new Designation(true, "c", "ores/iron"), Designation.parse("#c:ores/iron"));
        assertEquals(new Designation(false, "minecraft", "stone"), Designation.parse("stone"), "a missing namespace means minecraft");
        assertEquals(new Designation(true, "minecraft", "logs"), Designation.parse("#logs"));
        assertEquals(new Designation(false, "create", "zinc_ore"), Designation.parse("  create:zinc_ore "));
        assertEquals(new Designation(false, "my-mod.x", "a/b_c-d.e"), Designation.parse("my-mod.x:a/b_c-d.e"));
        assertEquals("#c:ores", Designation.parse("#c:ores").toString());
        assertEquals("c:ores", Designation.parse("#c:ores").id());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "#", ":", "minecraft:", ":stone", "Minecraft:stone", "minecraft:Stone", "a b:c", "a:b c",
            "##c:ores", "a:b:c", "a/b:c", "minecraft:stone!", "@ores/iron", "é:x"})
    void rejectsMalformed(String s) {
        assertNull(Designation.parse(s), s);
    }

    @Test
    void lengthLimit() {
        String ok = "a:" + "x".repeat(254);
        assertEquals(256, ok.length());
        assertEquals(ok, Designation.parse(ok).toString());
        assertNull(Designation.parse(ok + "x"));
        assertNull(Designation.parse(null));
        assertThrows(IllegalArgumentException.class, () -> Designation.block("Bad"));
    }

    @Test
    void targetSpecsParseAndFormat() {
        for (String s : List.of("@ores/iron", "@custom/c.ores/zinc", "@pack/minecraft.clay~2", "@custom/3", "#forge:ores/iron", "#c:ores", "minecraft:spawner", "create:zinc_ore")) {
            TargetSpec spec = TargetSpec.parse(s);
            assertEquals(s, spec.toString(), s);
            assertEquals(spec, TargetSpec.parse(spec.toString()));
        }
        assertEquals(TargetSpec.Kind.GROUP, TargetSpec.parse("@ores/iron").kind());
        assertEquals("ores/iron", TargetSpec.parse("@ores/iron").id());
        assertEquals(TargetSpec.Kind.TAG, TargetSpec.parse("#forge:ores/iron").kind());
        assertEquals(Designation.tag("forge:ores/iron"), TargetSpec.parse("#forge:ores/iron").designation());
        assertEquals(TargetSpec.Kind.BLOCK, TargetSpec.parse("minecraft:spawner").kind());
        assertNull(TargetSpec.parse("@ores/iron").designation());
        assertEquals("minecraft:iron_ore", TargetSpec.parse("iron_ore").toString(), "1.0 accepted a bare path too");
        for (String bad : List.of("@", "@~2", "@pack/a~", "@pack/a~1", "@pack/a~02", "@pack/a~x", "@pack/a~2~3", "@pack/a~12345678",
                "@Pack/a", "@pack/a b")) {
            assertNull(TargetSpec.parse(bad), bad);
        }
        assertEquals("pack/a~1234567", TargetSpec.parse("@pack/a~1234567").id());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "@", "@Ores", "@ores iron", "@@x", " minecraft:stone", "minecraft:stone ", "#", "#C:ores", "Stone"})
    void targetSpecRejectsMalformed(String s) {
        assertNull(TargetSpec.parse(s), s);
    }

    @Test
    void targetSpecLengthLimit() {
        String max = "@" + "a".repeat(255);
        assertEquals(256, max.length());
        assertEquals(max, TargetSpec.parse(max).toString());
        assertNull(TargetSpec.parse("@" + "a".repeat(256)));
        assertNull(TargetSpec.parse("minecraft:" + "a".repeat(247)));
        assertNull(TargetSpec.parse(null));
    }

    @Test
    void builtInCategoriesListWhatTheDesignSays() {
        assertEquals(List.of("#c:ores", "#forge:ores", "#minecraft:coal_ores", "#minecraft:copper_ores", "#minecraft:iron_ores",
                "#minecraft:gold_ores", "#minecraft:redstone_ores", "#minecraft:lapis_ores", "#minecraft:emerald_ores",
                "#minecraft:diamond_ores", "minecraft:nether_quartz_ore", "minecraft:ancient_debris"), strings(CategoryDef.ORE_DESIGNATIONS));
        assertEquals(List.of("ores", "stone", "wood", "nature", "fluids", "utility"), CategoryDef.BUILT_IN.stream().map(CategoryDef::id).toList());
        assertEquals(List.of("#minecraft:base_stone_overworld", "#minecraft:base_stone_nether", "#c:stones", "#forge:stone"),
                strings(CategoryDef.builtIn("stone").blocks()));
        assertEquals(List.of("#minecraft:logs"), strings(CategoryDef.builtIn("wood").blocks()));
        assertEquals(List.of("#minecraft:flowers", "#minecraft:leaves", "minecraft:amethyst_cluster", "minecraft:budding_amethyst",
                "#minecraft:crystal_sound_blocks"), strings(CategoryDef.builtIn("nature").blocks()));
        assertEquals(List.of("minecraft:water", "minecraft:lava"), strings(CategoryDef.builtIn("fluids").blocks()));
        assertEquals(List.of("minecraft:spawner", "#minecraft:beds", "minecraft:chest", "minecraft:barrel"),
                strings(CategoryDef.builtIn("utility").blocks()));
        assertEquals(List.of(CategoryDef.BUILT_IN.get(0)), CategoryDef.DEFAULTS, "a new server tracks ores only");
    }

    @Test
    void fuelEntries() {
        assertEquals(new FuelEntry(Designation.block("minecraft:redstone"), 4000), FuelEntry.parse("minecraft:redstone=4000"));
        assertEquals(new FuelEntry(Designation.tag("c:dusts/redstone"), 4000), FuelEntry.parse("#c:dusts/redstone = 4000"));
        assertEquals("#c:dusts/redstone=4000", FuelEntry.parse("#c:dusts/redstone = 4000").toString());
        assertEquals(Integer.MAX_VALUE, FuelEntry.parse("a:b=2147483647").value());
        for (String bad : List.of("minecraft:redstone", "=5", "a:b=", "a:b=0", "a:b=-1", "a:b=1.5", "a:b=2147483648", "a:b=99999999999", "A:b=1",
                "a:b=1=2")) {
            assertNull(FuelEntry.parse(bad), bad);
        }
    }

    @Test
    void autoOreGroupNames() {
        assertEquals("ores/iron", GroupSources.autoGroupId("c:ores/iron"));
        assertEquals("ores/iron", GroupSources.autoGroupId("forge:ores/iron"));
        assertEquals("ores/iron", GroupSources.autoGroupId("c:iron_ores"));
        assertEquals("ores/iron", GroupSources.autoGroupId("minecraft:iron_ores"));
        assertEquals("ores/netherite_scrap", GroupSources.autoGroupId("c:netherite_scrap_ores"));
        for (String no : List.of("c:ores", "forge:ores", "c:ores/", "c:ores/a/b", "minecraft:_ores", "minecraft:logs", "forge:iron_ores",
                "mymod:ores/iron", "c:ores_in_ground/stone", "minecraft:ores/iron", "ores/iron", "bad tag")) {
            assertNull(GroupSources.autoGroupId(no), no);
        }
    }

    @Test
    void autoGroupsMergeEveryNamingOfAMaterial() {
        List<GroupDef> groups = GroupSources.auto(List.of("minecraft:iron_ores", "c:ores/iron", "forge:ores/iron", "c:ores", "c:gold_ores",
                "minecraft:logs"));
        assertEquals(2, groups.size());
        assertEquals("ores/gold", groups.get(0).id());
        assertEquals(List.of("#c:gold_ores"), strings(groups.get(0).designations()));
        assertEquals("ores/iron", groups.get(1).id());
        assertEquals(List.of("#c:ores/iron", "#forge:ores/iron", "#minecraft:iron_ores"), strings(groups.get(1).designations()));
    }

    @Test
    void customGroupsAreKeyedByTheirFirstEntry() {
        List<String> w = new ArrayList<>();
        List<GroupDef> g = GroupSources.custom(List.of(List.of(Designation.block("a:b")), List.of(), List.of(Designation.tag("c:ores/zinc"),
                Designation.block("create:zinc_ore")), List.of(Designation.block("stone"))), w);
        assertEquals(List.of("custom/a.b", "custom/c.ores/zinc", "custom/minecraft.stone"), g.stream().map(GroupDef::id).toList());
        assertEquals(List.of(), w);
    }

    @Test
    void collidingKeysGetASuffixAndAWarningButExactRepeatsAreDropped() {
        List<String> w = new ArrayList<>();
        List<GroupDef> g = GroupSources.custom(List.of(
                List.of(Designation.tag("c:ores/zinc"), Designation.block("create:zinc_ore")),
                List.of(Designation.tag("c:ores/zinc")),
                List.of(Designation.tag("c:ores/zinc"), Designation.block("create:zinc_ore")),
                List.of(Designation.tag("c:ores/zinc"), Designation.block("mod:other_zinc"))), w);
        assertEquals(List.of("custom/c.ores/zinc", "custom/c.ores/zinc~2", "custom/c.ores/zinc~3"), g.stream().map(GroupDef::id).toList());
        assertEquals(List.of("#c:ores/zinc", "mod:other_zinc"), strings(g.get(2).designations()));
        assertEquals(2, w.size(), w.toString());
        assertTrue(w.get(0).startsWith("[targets] groups: another group also starts with #c:ores/zinc, so this one is @custom/c.ores/zinc~2"),
                w.get(0));
        for (GroupDef d : g) assertEquals(d, new GroupDef(TargetSpec.parse("@" + d.id()).id(), d.designations()), "ids are valid specs");
    }

    @Test
    void overlongKeysAreCutToFitASpecWithItsSuffix() {
        Designation longest = Designation.block("a:" + "p".repeat(Designation.MAX_LENGTH - 2));
        String key = GroupSources.key(GroupSources.CUSTOM_PREFIX, longest);
        assertTrue(key.startsWith("a.ppp"));
        List<GroupDef> g = GroupSources.custom(List.of(List.of(longest), List.of(longest, Designation.block("b:c"))), new ArrayList<>());
        assertEquals(GroupSources.CUSTOM_PREFIX + key + "~2", g.get(1).id());
        assertEquals(TargetSpec.MAX_LENGTH - "~1234567".length(), TargetSpec.group(g.get(0).id()).toString().length());
        assertEquals(g.get(1).id(), TargetSpec.parse(TargetSpec.group(g.get(1).id()).toString()).id());
    }

    @Test
    void packGroupsHonourReplaceAndTheVeinMiningSwitch() {
        List<String> w = new ArrayList<>();
        PackGroupFile low = PackGroupFile.parse("pack:veinmining/groups.json", PackGroupFile.Source.VEIN_MINING,
                "{\"groups\": [[\"minecraft:obsidian\", \"minecraft:crying_obsidian\"]]}", w);
        PackGroupFile high = PackGroupFile.parse("pack:veinmining/groups.json", PackGroupFile.Source.VEIN_MINING,
                "{\"replace\": true, \"groups\": [[\"#c:ores/zinc\", \"create:zinc_ore\"]]}", w);
        PackGroupFile ours = PackGroupFile.parse("another:oreradar/groups.json", PackGroupFile.Source.ORE_RADAR,
                "{\"groups\": [\"minecraft:clay, minecraft:mud\", [], [\"not valid!\", \"a:b\"]]}", w);
        PackGroupFile add = PackGroupFile.parse("pack:veinmining/groups.json", PackGroupFile.Source.VEIN_MINING,
                "{\"replace\": false, \"groups\": [[\"x:y\"]]}", w);
        assertEquals(List.of("another:oreradar/groups.json group 3: ignoring invalid entry not valid!"), w);

        List<GroupDef> all = GroupSources.pack(List.of(low, high, ours, add), true, w);
        // Ordered by location: another:... first, then pack:..., whose low file was replaced.
        assertEquals(List.of("pack/minecraft.clay", "pack/a.b", "pack/c.ores/zinc", "pack/x.y"), all.stream().map(GroupDef::id).toList());
        assertEquals(List.of("minecraft:clay", "minecraft:mud"), strings(all.get(0).designations()));
        assertEquals(List.of("a:b"), strings(all.get(1).designations()));
        assertEquals(List.of("#c:ores/zinc", "create:zinc_ore"), strings(all.get(2).designations()));
        assertEquals(List.of("x:y"), strings(all.get(3).designations()));
        assertEquals(1, w.size(), "no collisions");

        List<GroupDef> noVein = GroupSources.pack(List.of(low, high, ours, add), false, w);
        assertEquals(2, noVein.size());
    }

    @Test
    void packCollisionSuffixesFollowLocationOrderNotLoadOrder() {
        PackGroupFile b = new PackGroupFile("b:oreradar/groups.json", PackGroupFile.Source.ORE_RADAR, false,
                List.of(List.of(Designation.block("x:y"), Designation.block("b:only"))));
        PackGroupFile a = new PackGroupFile("a:oreradar/groups.json", PackGroupFile.Source.ORE_RADAR, false,
                List.of(List.of(Designation.block("x:y"), Designation.block("a:only"))));
        List<String> w = new ArrayList<>();
        List<GroupDef> ab = GroupSources.pack(List.of(a, b), true, w);
        List<GroupDef> ba = GroupSources.pack(List.of(b, a), true, w);
        assertEquals(ab, ba);
        assertEquals("pack/x.y~2", ab.get(1).id());
        assertEquals(List.of("x:y", "b:only"), strings(ab.get(1).designations()));
        assertEquals(List.of("b:oreradar/groups.json: another group also starts with x:y, so this one is @pack/x.y~2; radars tracking it "
                + "lose it if the groups are reordered (start it with a different entry)"), w.subList(0, 1));
    }

    @Test
    void badGroupFilesAreReportedNotThrown() {
        List<String> w = new ArrayList<>();
        assertNull(PackGroupFile.parse("p:veinmining/groups.json", PackGroupFile.Source.VEIN_MINING, "{\"groups\": [[\"a:b\"]", w));
        assertTrue(w.get(0).startsWith("p:veinmining/groups.json: line 1, column "), w.toString());
        assertNull(PackGroupFile.parse("p:x", PackGroupFile.Source.ORE_RADAR, "[1, 2]", w));
        assertNull(PackGroupFile.parse("p:x", PackGroupFile.Source.ORE_RADAR, "{\"groups\": 5}", w));
        PackGroupFile odd = PackGroupFile.parse("p:x", PackGroupFile.Source.ORE_RADAR, "{\"replace\": \"yes\", \"groups\": [5, [\"a:b\"]]}", w);
        assertEquals(List.of(List.of(Designation.block("a:b"))), odd.groups());
        assertTrue(w.contains("p:x: \"replace\" must be true or false"), w.toString());
        assertTrue(w.contains("p:x group 1: expected an array of block ids and #tags"), w.toString());
        PackGroupFile empty = PackGroupFile.parse("p:y", PackGroupFile.Source.ORE_RADAR, "{}", w);
        assertEquals(List.of(), empty.groups());
    }

    @Test
    void allMergesSourcesInOrder() {
        List<PackGroupFile> pack = List.of(new PackGroupFile("p:oreradar/groups.json", PackGroupFile.Source.ORE_RADAR, false,
                List.of(List.of(Designation.block("c:d")))));
        List<String> w = new ArrayList<>();
        List<GroupDef> g = GroupSources.all(List.of("c:ores/tin"), List.of(List.of(Designation.block("a:b"))), pack, true, w);
        assertEquals(List.of("ores/tin", "custom/a.b", "pack/c.d"), g.stream().map(GroupDef::id).toList());
        PackGroupFile veinMining = new PackGroupFile("p:veinmining/groups.json", PackGroupFile.Source.VEIN_MINING, false,
                List.of(List.of(Designation.block("e:f"))));
        assertEquals(List.of("ores/tin", "custom/a.b"),
                GroupSources.all(List.of("c:ores/tin"), List.of(List.of(Designation.block("a:b"))), List.of(veinMining), false, w).stream()
                        .map(GroupDef::id).toList(), "readVeinMiningGroups = false skips Vein Mining's files");
        assertEquals(List.of(), w);
    }

    private static List<String> strings(List<Designation> ds) {
        return ds.stream().map(Designation::toString).toList();
    }
}
