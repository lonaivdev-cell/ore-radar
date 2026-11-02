package net.loserver.oreradar.core.designation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import net.loserver.oreradar.core.config.CategoryDef;
import net.loserver.oreradar.core.config.ServerConfig;
import net.loserver.oreradar.core.net.ConfigPacket;

class TargetRulesTest {

    /** A tiny registry where a block is its id string. */
    static final class FakeLookup implements BlockLookup<String> {
        final List<String> blocks = new ArrayList<>();
        final Map<String, List<String>> tags = new LinkedHashMap<>();
        final Set<String> air = Set.of("minecraft:air", "minecraft:cave_air");
        final Set<String> noItem = Set.of("minecraft:water", "minecraft:lava", "minecraft:air", "minecraft:cave_air", "mod:ghost_ore",
                "mod:invisible");

        FakeLookup withBlocks(String... ids) {
            blocks.addAll(List.of(ids));
            return this;
        }

        FakeLookup withTag(String tag, String... members) {
            tags.put(tag, List.of(members));
            return this;
        }

        @Override
        public Iterable<String> blocks() {
            return blocks;
        }

        @Override
        public String block(String id) {
            return blocks.contains(id) ? id : null;
        }

        @Override
        public String id(String block) {
            return block;
        }

        @Override
        public Iterable<String> tag(String tagId) {
            return tags.getOrDefault(tagId, List.of());
        }

        @Override
        public Iterable<String> tagIds() {
            return tags.keySet();
        }

        @Override
        public boolean isAir(String block) {
            return air.contains(block);
        }

        @Override
        public boolean hasItem(String block) {
            return !noItem.contains(block);
        }

        @Override
        public String nameKey(String block) {
            return "block." + block.replace(':', '.');
        }
    }

    static FakeLookup world() {
        return new FakeLookup()
                .withBlocks("minecraft:air", "minecraft:cave_air", "minecraft:stone", "minecraft:deepslate", "minecraft:iron_ore",
                        "minecraft:deepslate_iron_ore", "mod:iron_ore_nether", "minecraft:gold_ore", "minecraft:nether_gold_ore",
                        "minecraft:diamond_ore", "minecraft:oak_log", "minecraft:water", "minecraft:lava", "minecraft:spawner",
                        "minecraft:chest", "minecraft:ancient_debris", "mod:ghost_ore", "create:zinc_ore", "minecraft:dirt", "mod:invisible")
                .withTag("minecraft:iron_ores", "minecraft:iron_ore", "minecraft:deepslate_iron_ore")
                .withTag("c:ores/iron", "minecraft:iron_ore", "minecraft:deepslate_iron_ore", "mod:iron_ore_nether")
                .withTag("minecraft:gold_ores", "minecraft:gold_ore", "minecraft:nether_gold_ore")
                .withTag("minecraft:diamond_ores", "minecraft:diamond_ore")
                .withTag("c:ores/zinc", "create:zinc_ore")
                .withTag("c:ores", "minecraft:iron_ore", "minecraft:deepslate_iron_ore", "mod:iron_ore_nether", "minecraft:gold_ore",
                        "minecraft:nether_gold_ore", "minecraft:diamond_ore", "create:zinc_ore", "mod:ghost_ore")
                .withTag("minecraft:base_stone_overworld", "minecraft:stone", "minecraft:deepslate")
                .withTag("minecraft:logs", "minecraft:oak_log")
                .withTag("oreradar:untrackable");
    }

    private static TargetRules<String> rules(ServerConfig config, FakeLookup lookup, List<String> warnings) {
        return TargetRules.build(config, lookup, List.of(), warnings);
    }

    @Test
    void theDefaultOresCategoryGroupsAndCatalog() {
        List<String> w = new ArrayList<>();
        TargetRules<String> r = rules(ServerConfig.defaults(false), world(), w);
        assertEquals(List.of(), w);
        assertEquals(Set.of("minecraft:iron_ore", "minecraft:deepslate_iron_ore", "mod:iron_ore_nether", "minecraft:gold_ore",
                "minecraft:nether_gold_ore", "minecraft:diamond_ore", "create:zinc_ore", "mod:ghost_ore", "minecraft:ancient_debris"),
                r.allowed());
        assertFalse(r.isAllowed("minecraft:stone"));

        // ores/diamond, ores/gold, ores/iron, ores/zinc from the tags, sorted.
        assertEquals(List.of("ores/diamond", "ores/gold", "ores/iron", "ores/zinc"), r.groups().stream().map(TargetRules.Group::id).toList());
        TargetRules.Group<String> iron = r.group("ores/iron");
        assertEquals(Set.of("minecraft:iron_ore", "minecraft:deepslate_iron_ore", "mod:iron_ore_nether"), iron.members());
        assertEquals("minecraft:iron_ore", iron.representative(), "plain vanilla ore beats deepslate and modded nether variants");
        assertEquals("minecraft:gold_ore", r.group("ores/gold").representative());

        List<ConfigPacket.CatalogEntry> catalog = r.catalog();
        assertEquals(List.of("@ores/diamond", "@ores/gold", "@ores/iron", "@ores/zinc", "minecraft:ancient_debris", "mod:ghost_ore"),
                catalog.stream().map(ConfigPacket.CatalogEntry::spec).toList(),
                "groups first, then singles not in a group; the itemless ghost ore stays because it is in the ores tab");
        ConfigPacket.CatalogEntry ironEntry = catalog.get(2);
        assertEquals("minecraft:iron_ore", ironEntry.iconBlockId());
        assertEquals("block.minecraft.iron_ore", ironEntry.name());
        assertTrue(ironEntry.nameIsTranslationKey());
        assertEquals(3, ironEntry.memberCount());

        // The default file has only the ores tab, so it is bit 0.
        assertEquals(List.of("ores"), r.categories().stream().map(ConfigPacket.Category::id).toList());
        for (ConfigPacket.CatalogEntry e : catalog) assertEquals(1L, e.categoryMask(), e.spec());
    }

    @Test
    void untrackableTagAndDenyListAlwaysWin() {
        FakeLookup lookup = world().withTag("oreradar:untrackable", "minecraft:diamond_ore");
        ServerConfig c = ServerConfig.defaults(false).with(ServerConfig.DENY_LIST, List.of(Designation.tag("minecraft:gold_ores")));
        TargetRules<String> r = rules(c, lookup, new ArrayList<>());
        assertFalse(r.isAllowed("minecraft:diamond_ore"));
        assertFalse(r.isAllowed("minecraft:gold_ore"));
        assertNull(r.group("ores/diamond"));
        assertNull(r.group("ores/gold"));
        assertEquals(TargetRules.Status.NOT_ALLOWED, r.resolve("minecraft:diamond_ore").status());
        assertEquals(TargetRules.Status.NOT_ALLOWED, r.resolve("#minecraft:gold_ores").status());
        assertNull(r.specFor("minecraft:diamond_ore"), "untrackable");
    }

    @Test
    void allowAllSkipsAirAndItemlessBlocksOutsideCategories() {
        ServerConfig c = ServerConfig.defaults(false).with(ServerConfig.ALLOW_ALL, true).withCategories(CategoryDef.BUILT_IN);
        TargetRules<String> r = rules(c, world(), new ArrayList<>());
        assertFalse(r.isAllowed("minecraft:air"));
        assertTrue(r.isAllowed("minecraft:dirt"));
        List<String> specs = r.catalog().stream().map(ConfigPacket.CatalogEntry::spec).toList();
        assertTrue(specs.contains("minecraft:water"), "no item, but in the fluids category");
        assertFalse(specs.contains("mod:invisible"), "no item and in no category");
        assertFalse(specs.contains("minecraft:iron_ore"), "covered by its group");
        assertTrue(specs.contains("minecraft:dirt"));
        // Visible tabs: ores, stone, wood, fluids, utility (nature has nothing in this registry).
        assertEquals(List.of("ores", "stone", "wood", "fluids", "utility"), r.categories().stream().map(ConfigPacket.Category::id).toList());
        ConfigPacket.CatalogEntry water = r.catalog().get(specs.indexOf("minecraft:water"));
        assertEquals(1L << 3, water.categoryMask(), "fluids is the 4th visible tab");
        ConfigPacket.CatalogEntry stone = r.catalog().get(specs.indexOf("minecraft:stone"));
        assertEquals(1L << 1, stone.categoryMask());
        ConfigPacket.CatalogEntry dirt = r.catalog().get(specs.indexOf("minecraft:dirt"));
        assertEquals(0L, dirt.categoryMask(), "only under All");
    }

    @Test
    void theCategoriesAreTheAllowList() {
        ServerConfig c = ServerConfig.defaults(false).withCategories(List.of(CategoryDef.builtIn("ores"), CategoryDef.builtIn("utility")));
        TargetRules<String> r = rules(c, world(), new ArrayList<>());
        assertTrue(r.isAllowed("minecraft:spawner") && r.isAllowed("minecraft:chest"), "ores plus utility takes one more table");
        assertTrue(r.resolve("@ores/iron").ok());
        assertFalse(r.isAllowed("minecraft:stone"), "no category lists it");
        assertEquals(List.of("ores", "utility"), r.categories().stream().map(ConfigPacket.Category::id).toList());

        ServerConfig own = ServerConfig.defaults(false).withCategories(List.of(new CategoryDef("mine", "Mine", "minecraft:oak_log",
                List.of(Designation.tag("minecraft:logs"), Designation.block("minecraft:spawner"), Designation.block("minecraft:air")))));
        TargetRules<String> o = rules(own, world(), new ArrayList<>());
        assertEquals(Set.of("minecraft:oak_log", "minecraft:spawner"), o.allowed(), "never air");
        assertEquals(TargetRules.Status.NOT_ALLOWED, o.resolve("minecraft:iron_ore").status(), "ores are not listed here");
        assertEquals(TargetRules.Status.UNKNOWN, o.resolve("@ores/iron").status(), "so their group has nothing to track");

        ServerConfig all = own.with(ServerConfig.ALLOW_ALL, true).with(ServerConfig.DENY_LIST, List.of(Designation.block("minecraft:dirt")));
        TargetRules<String> a = rules(all, world(), new ArrayList<>());
        assertTrue(a.isAllowed("minecraft:stone"), "allowAll: no category needed");
        assertFalse(a.isAllowed("minecraft:dirt"), "but the deny list still wins");
        assertFalse(a.isAllowed("minecraft:cave_air"));
    }

    @Test
    void nothingAllowedIsWarned() {
        List<String> w = new ArrayList<>();
        ServerConfig c = ServerConfig.defaults(false).withCategories(List.of(
                new CategoryDef("x", "X", "minecraft:stone", List.of(Designation.tag("nope:nothing")))));
        TargetRules<String> r = rules(c, world(), w);
        assertTrue(r.allowed().isEmpty());
        assertEquals(List.of("no block is trackable (see [categories] and denyList): radars will find nothing"), w);
        assertTrue(r.catalog().isEmpty());
        assertTrue(r.categories().isEmpty());
    }

    @Test
    void customAndPackGroupsMergeAfterAutoGroupsAndDuplicatesCollapse() {
        ServerConfig c = ServerConfig.defaults(false).with(ServerConfig.ALLOW_ALL, true)
                .with(ServerConfig.GROUPS, List.of(
                        List.of(Designation.block("minecraft:iron_ore"), Designation.block("minecraft:deepslate_iron_ore"),
                                Designation.block("mod:iron_ore_nether")),
                        List.of(Designation.block("minecraft:stone"), Designation.block("minecraft:dirt"))));
        List<PackGroupFile> pack = List.of(new PackGroupFile("p:veinmining/groups.json", PackGroupFile.Source.VEIN_MINING, false,
                List.of(List.of(Designation.block("minecraft:dirt"), Designation.block("minecraft:chest")))));
        TargetRules<String> r = TargetRules.build(c, world(), pack, new ArrayList<>());
        List<String> ids = r.groups().stream().map(TargetRules.Group::id).toList();
        assertFalse(ids.contains("custom/minecraft.iron_ore"), "same blocks as ores/iron");
        assertTrue(ids.containsAll(List.of("ores/iron", "custom/minecraft.stone", "pack/minecraft.dirt")), ids.toString());
        assertEquals("custom/minecraft.stone", r.groupOf("minecraft:dirt").id(), "the first group wins for sneak-use");
        assertEquals(TargetSpec.parse("@custom/minecraft.stone"), r.specFor("minecraft:stone"));
        assertEquals(TargetSpec.parse("@ores/iron"), r.specFor("minecraft:deepslate_iron_ore"));
        assertEquals(TargetSpec.parse("minecraft:oak_log"), r.specFor("minecraft:oak_log"));
    }

    @Test
    void storedGroupSpecsSurviveReorderingTheConfigAndTheDatapacks() {
        List<Designation> zinc = List.of(Designation.tag("c:ores/zinc"), Designation.block("minecraft:stone"));
        List<Designation> dirt = List.of(Designation.block("minecraft:dirt"), Designation.block("minecraft:chest"));
        List<Designation> logs = List.of(Designation.tag("minecraft:logs"), Designation.block("minecraft:spawner"));
        PackGroupFile first = new PackGroupFile("a:oreradar/groups.json", PackGroupFile.Source.ORE_RADAR, false,
                List.of(List.of(Designation.block("minecraft:water"), Designation.block("minecraft:lava")), dirt));
        PackGroupFile second = new PackGroupFile("b:veinmining/groups.json", PackGroupFile.Source.VEIN_MINING, false,
                List.of(List.of(Designation.block("minecraft:gold_ore"), Designation.block("minecraft:diamond_ore"))));
        PackGroupFile firstReordered = new PackGroupFile("a:oreradar/groups.json", PackGroupFile.Source.ORE_RADAR, false,
                List.of(dirt, List.of(Designation.block("minecraft:water"), Designation.block("minecraft:lava"))));
        ServerConfig c = ServerConfig.defaults(false).with(ServerConfig.ALLOW_ALL, true);

        List<String> w = new ArrayList<>();
        TargetRules<String> before = TargetRules.build(c.with(ServerConfig.GROUPS, List.of(zinc, logs)), world(), List.of(first, second), w);
        TargetRules<String> after = TargetRules.build(c.with(ServerConfig.GROUPS, List.of(logs, zinc)), world(),
                List.of(second, firstReordered), w);
        assertEquals(List.of(), w);
        for (String spec : List.of("@custom/c.ores/zinc", "@custom/minecraft.logs", "@pack/minecraft.dirt", "@pack/minecraft.water",
                "@pack/minecraft.gold_ore")) {
            TargetRules.Resolution<String> a = before.resolve(spec), b = after.resolve(spec);
            assertTrue(a.ok(), spec);
            assertEquals(a.blocks(), b.blocks(), spec);
        }
        assertEquals(Set.of("create:zinc_ore", "minecraft:stone"), after.resolve("@custom/c.ores/zinc").blocks());
        assertEquals(Set.of("minecraft:dirt", "minecraft:chest"), after.resolve("@pack/minecraft.dirt").blocks());
        assertEquals(TargetSpec.parse("@custom/c.ores/zinc"), after.specFor("minecraft:stone"));
    }

    @Test
    void resolveSpecs() {
        TargetRules<String> r = rules(ServerConfig.defaults(false), world(), new ArrayList<>());
        TargetRules.Resolution<String> group = r.resolve("@ores/iron");
        assertTrue(group.ok());
        assertEquals(3, group.blocks().size());
        TargetRules.Resolution<String> tag = r.resolve("#c:ores/iron");
        assertTrue(tag.ok());
        assertEquals("minecraft:iron_ore", tag.representative());
        TargetRules.Resolution<String> legacy = r.resolve("#minecraft:iron_ores");
        assertEquals(Set.of("minecraft:iron_ore", "minecraft:deepslate_iron_ore"), legacy.blocks(), "1.0 tag specs still resolve");
        TargetRules.Resolution<String> forge = r.resolve("#forge:ores/iron");
        assertEquals(group.blocks(), forge.blocks(), "1.0's tag, absent here (as on 1.21.1), finds its auto ore group");
        assertEquals("#forge:ores/iron", forge.spec().toString());
        assertEquals(Set.of("minecraft:ancient_debris"), r.resolve("minecraft:ancient_debris").blocks());
        assertEquals(TargetRules.Status.UNKNOWN, r.resolve("@ores/unobtainium").status());
        assertEquals(TargetRules.Status.UNKNOWN, r.resolve("#c:ores/unobtainium").status());
        assertEquals(TargetRules.Status.UNKNOWN, r.resolve("mod:missing").status());
        assertEquals(TargetRules.Status.UNKNOWN, r.resolve("minecraft:air").status());
        assertEquals(TargetRules.Status.NOT_ALLOWED, r.resolve("minecraft:stone").status());
        assertEquals("minecraft:stone", r.resolve("minecraft:stone").representative(), "for the 'cannot track' message");
        assertEquals(TargetRules.Status.NOT_ALLOWED, r.resolve("#minecraft:logs").status());
        assertEquals(TargetRules.Status.INVALID, r.resolve("not a spec").status());
        assertEquals(TargetRules.Status.INVALID, r.resolve("").status());
        assertSame(r.resolve("@ores/iron"), r.resolve("@ores/iron"), "cached");
    }

    @Test
    void theResolutionCacheStaysBounded() {
        TargetRules<String> r = rules(ServerConfig.defaults(false), world(), new ArrayList<>());
        for (int i = 0; i < 20_000; i++) assertEquals(TargetRules.Status.UNKNOWN, r.resolve("#junk:tag_" + i).status());
        assertTrue(r.resolve("@ores/iron").ok());
    }

    @Test
    void anOversizedCatalogIsTrimmedToOnePacket() {
        FakeLookup big = world();
        for (int i = 0; i < 20_000; i++) big.withBlocks("somemod:a_rather_long_block_name_to_fill_the_packet_" + i);
        List<String> w = new ArrayList<>();
        ServerConfig c = ServerConfig.defaults(false).with(ServerConfig.ALLOW_ALL, true);
        TargetRules<String> r = rules(c, big, w);
        int size = r.configPacket(c).toBytes().length;
        assertTrue(size <= TargetRules.MAX_CONFIG_PAYLOAD_BYTES, "size " + size);
        assertTrue(r.catalog().size() < 20_000);
        assertTrue(r.catalog().get(0).spec().startsWith("@"), "groups are kept first");
        assertEquals(1, w.size());
        assertTrue(w.get(0).startsWith("the catalog has "), w.toString());
    }

    @Test
    void categoriesWithRenamesAreCarriedToThePacket() {
        ServerConfig c = ServerConfig.defaults(false).withCategories(List.of(
                new CategoryDef("gems", "Gems", "minecraft:diamond_ore", List.of(Designation.tag("minecraft:diamond_ores"))),
                new CategoryDef("ores", "Ores", "minecraft:diamond_ore", CategoryDef.ORE_DESIGNATIONS)));
        TargetRules<String> r = rules(c, world(), new ArrayList<>());
        assertEquals(List.of(new ConfigPacket.Category("gems", "Gems", false, "minecraft:diamond_ore"),
                new ConfigPacket.Category("ores", "oreradar.category.ores", true, "minecraft:diamond_ore")), r.categories());
        ConfigPacket.CatalogEntry diamond = r.catalog().get(0);
        assertEquals("@ores/diamond", diamond.spec());
        assertEquals(0b11L, diamond.categoryMask());
        assertTrue(diamond.inCategory(0) && diamond.inCategory(1) && !diamond.inCategory(2));
    }
}
