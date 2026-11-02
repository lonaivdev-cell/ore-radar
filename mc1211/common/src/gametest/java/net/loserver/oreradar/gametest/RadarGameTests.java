package net.loserver.oreradar.gametest;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

import com.mojang.authlib.GameProfile;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.serialization.Dynamic;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import net.loserver.oreradar.EnergySettings;
import net.loserver.oreradar.content.RadarContent;
import net.loserver.oreradar.core.ActiveFrom;
import net.loserver.oreradar.core.config.CategoryDef;
import net.loserver.oreradar.core.config.ServerConfig;
import net.loserver.oreradar.core.designation.Designation;
import net.loserver.oreradar.core.designation.TargetRules;
import net.loserver.oreradar.core.energy.ChargeMath;
import net.loserver.oreradar.core.net.BlockPosPacking;
import net.loserver.oreradar.core.net.ClientPrefs;
import net.loserver.oreradar.core.net.HelloPacket;
import net.loserver.oreradar.core.net.PowerPacket;
import net.loserver.oreradar.core.net.PrefsPacket;
import net.loserver.oreradar.core.net.RadarPacket;
import net.loserver.oreradar.core.net.RadarProtocol;
import net.loserver.oreradar.core.net.ScanPacket;
import net.loserver.oreradar.core.net.SelectPacket;
import net.loserver.oreradar.core.scan.RadarScan;
import net.loserver.oreradar.core.scan.ScanBudget;
import net.loserver.oreradar.core.scan.ScanMath;
import net.loserver.oreradar.core.scan.ScanResult;
import net.loserver.oreradar.core.scan.ScanSettings;
import net.loserver.oreradar.core.util.Deadline;
import net.loserver.oreradar.item.RadarItem;
import net.loserver.oreradar.network.RadarPayload;
import net.loserver.oreradar.server.LevelScanWorld;
import net.loserver.oreradar.server.PackGroups;
import net.loserver.oreradar.server.PlayerRadar;
import net.loserver.oreradar.server.RadarRules;
import net.loserver.oreradar.server.RadarServer;
import net.loserver.oreradar.server.RegistryBlockLookup;
import net.loserver.oreradar.station.ChargingStationBlock;
import net.loserver.oreradar.station.ChargingStationBlockEntity;
import net.loserver.oreradar.station.ChargingStationItem;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.TestFunction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.nbt.TagParser;
import net.minecraft.network.Connection;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.datafix.DataFixers;
import net.minecraft.util.datafix.fixes.References;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Ore Radar's GameTests, shared by NeoForge and Fabric (DESIGN §12). Each loader's test mod returns
 * {@link #all()} from a {@code @GameTestGenerator}, so both run the same tests in the same
 * structures. They are 1.20.1's tests, ported, plus the 1.21.1 specifics: what the world upgrader
 * makes of 1.20.1 radars and stations, and the exact bytes of a payload on the wire.
 *
 * <p>Both loaders run them on vanilla's GameTest server, in its flat world, and all tests of the
 * batch run side by side, so each test that scans looks for its own kind of block, one that never
 * generates in the overworld near the tests. Tests that read the running server's rules (fuels,
 * select, sneak-use) expect the run directory's {@code oreradar-server.toml} to keep the defaults.
 * The GameTest mod's datapack adds {@code minecraft:spawner} to {@code #oreradar:untrackable} and a
 * group in each of {@code oreradar/groups.json} and {@code veinmining/groups.json}.
 */
public final class RadarGameTests {
    /** Empty 8 x 8 x 8 and 8 x 48 x 8 structures, generated by the build (gametest.gradle). */
    public static final String EMPTY = "oreradar:empty";
    public static final String TALL = "oreradar:tall";
    private static final String BATCH = "oreradar";
    private static final int TIMEOUT_TICKS = 200;
    /** Minecraft 1.20.1's data version, which the world upgrader starts from for the items below. */
    private static final int DATA_VERSION_1_20_1 = 3465;

    private RadarGameTests() {}

    public static Collection<TestFunction> all() {
        return List.of(
                test("scan_nearest_and_markers", EMPTY, RadarGameTests::nearestAndMarkers),
                test("scan_continues_across_ticks", EMPTY, RadarGameTests::continuesAcrossTicks),
                test("scan_precision_rounding", EMPTY, RadarGameTests::precisionRounding),
                test("scan_max_radius", TALL, RadarGameTests::maxRadius),
                test("rules_deny_list_and_untrackable", EMPTY, RadarGameTests::denyListAndUntrackable),
                test("rules_allow_all_on_stone", EMPTY, RadarGameTests::allowAllOnStone),
                test("rules_datapack_groups", EMPTY, RadarGameTests::datapackGroups),
                test("data_recipes_and_tags", EMPTY, RadarGameTests::recipesAndTags),
                test("item_legacy_radar", EMPTY, RadarGameTests::legacyRadar),
                test("item_world_upgrade_migrates_on_write", EMPTY, RadarGameTests::worldUpgradeMigratesOnWrite),
                test("item_drain", EMPTY, RadarGameTests::drain),
                test("item_unvalidated_target_nbt", EMPTY, RadarGameTests::unvalidatedTargetNbt),
                test("item_energy_rules_belong_to_the_server", EMPTY, RadarGameTests::energyRulesBelongToTheServer),
                test("item_sneak_use_tracks_group", EMPTY, RadarGameTests::sneakUseTracksGroup),
                test("net_select_and_power_validation", EMPTY, RadarGameTests::selectAndPowerValidation),
                test("net_wire_bytes", EMPTY, RadarGameTests::wireBytes),
                test("net_raw_payloads_through_the_loader", EMPTY, RadarGameTests::rawPayloadsThroughTheLoader),
                test("net_rescans_follow_changes_not_floods", EMPTY, RadarGameTests::rescansFollowChangesNotFloods),
                test("net_repeated_hello", EMPTY, RadarGameTests::repeatedHello),
                test("station_burns_fuel", EMPTY, RadarGameTests::stationBurnsFuel),
                test("station_charges_radar", EMPTY, RadarGameTests::stationChargesRadar),
                test("station_energy_input", EMPTY, RadarGameTests::stationEnergyInput),
                test("station_comparator", EMPTY, RadarGameTests::stationComparator),
                test("station_automation_faces", EMPTY, RadarGameTests::stationAutomationFaces),
                test("station_drop_keeps_buffer", EMPTY, RadarGameTests::stationDropKeepsBuffer),
                test("station_world_upgrade", EMPTY, RadarGameTests::stationWorldUpgrade));
    }

    private static TestFunction test(String name, String structure, Consumer<GameTestHelper> body) {
        return new TestFunction(BATCH, "oreradar." + name, structure, TIMEOUT_TICKS, 0L, true, body);
    }

    // ------------------------------------------------------------------ scanning

    /** The nearest of a placed cluster, every match within the outline radius, and the marker cap. */
    private static void nearestAndMarkers(GameTestHelper h) {
        BlockPos origin = new BlockPos(4, 2, 4);
        h.setBlock(new BlockPos(5, 2, 4), Blocks.STONE);                 // nearer, but not a target
        h.setBlock(new BlockPos(6, 2, 4), Blocks.DEEPSLATE_DIAMOND_ORE); // 2 blocks: the nearest
        h.setBlock(new BlockPos(4, 5, 4), Blocks.DIAMOND_ORE);           // 3
        h.setBlock(new BlockPos(1, 2, 4), Blocks.DIAMOND_ORE);           // 3
        h.setBlock(new BlockPos(4, 2, 7), Blocks.DIAMOND_ORE);           // 3
        h.setBlock(new BlockPos(7, 6, 7), Blocks.DIAMOND_ORE);           // about 5.8
        Set<Block> diamonds = defaultRules().targets().resolve("#minecraft:diamond_ores").blocks();

        ScanResult all = scan(h, origin, diamonds, 24, 1, 256);
        check(h, all.found(), "the cluster is found");
        check(h, all.nearest() == packed(h, new BlockPos(6, 2, 4)), "the nearest is the deepslate ore 2 blocks away, got "
                + describe(all.nearest()));
        equal(h, 2.0, all.nearestDistance(), "nearest distance");
        equal(h, 2 + 16, all.outlineRadius(), "outline radius = nearest + 1 chunk");
        equal(h, 5, all.totalMatches(), "matches within the outline radius");
        equal(h, 5, all.markerCount(), "markers");

        ScanResult capped = scan(h, origin, diamonds, 24, 1, 3);
        equal(h, 5, capped.totalMatches(), "matches still counted past the marker cap");
        equal(h, 3, capped.markerCount(), "markers capped");
        check(h, capped.markers()[0] == capped.nearest(), "markers are nearest first");
        ScanSettings outlines = new ScanSettings(ActiveFrom.HANDS, 1, 3, true, true, 1);
        ScanPacket.Found found = capped.toFound(outlines, h.getLevel().getMinBuildHeight(), h.getLevel().getMaxBuildHeight());
        check(h, found.truncated(), "a capped result is marked truncated");

        ScanResult tight = scan(h, origin, diamonds, 24, 0, 256);
        equal(h, 2, tight.outlineRadius(), "outline radius with no probe chunks");
        equal(h, 1, tight.totalMatches(), "only the nearest lies within its own distance");
        h.succeed();
    }

    /** A scan that runs out of budget every section ends with the same result as one unbounded step. */
    private static void continuesAcrossTicks(GameTestHelper h) {
        BlockPos origin = new BlockPos(4, 3, 4);
        for (BlockPos p : List.of(new BlockPos(0, 1, 0), new BlockPos(7, 1, 7), new BlockPos(3, 6, 1), new BlockPos(6, 4, 2))) {
            h.setBlock(p, Blocks.NETHER_QUARTZ_ORE);
        }
        Set<Block> quartz = Set.of(Blocks.NETHER_QUARTZ_ORE);
        ScanResult whole = scan(h, origin, quartz, 64, 2, 256);

        BlockPos abs = h.absolutePos(origin);
        ServerLevel level = h.getLevel();
        RadarScan scan = new RadarScan(abs.getX() + 0.5, abs.getY() + 0.5, abs.getZ() + 0.5, 64, 2, 256,
                level.getMinBuildHeight(), level.getMaxBuildHeight());
        int steps = 0;
        // An already expired deadline: each call scans one section, as an overloaded tick would.
        while (!scan.step(new LevelScanWorld(level, quartz, ScanBudget.unlimited()), Deadline.afterMicros(0))) {
            if (++steps > 100_000) h.fail("the scan never finished");
        }
        ScanResult pieces = scan.result();
        check(h, steps > 1, "the scan was split into " + (steps + 1) + " steps");
        equal(h, whole.nearest(), pieces.nearest(), "nearest after continuing");
        equal(h, whole.totalMatches(), pieces.totalMatches(), "matches after continuing");
        check(h, Arrays.equals(whole.markers(), pieces.markers()), "markers after continuing");
        equal(h, 4, pieces.totalMatches(), "matches");
        h.succeed();
    }

    /** The nearest position is sent rounded to the centre of its precision cell, exact at 1. */
    private static void precisionRounding(GameTestHelper h) {
        BlockPos origin = new BlockPos(1, 1, 1);
        BlockPos ore = new BlockPos(5, 3, 6);
        h.setBlock(ore, Blocks.ANCIENT_DEBRIS);
        ScanResult result = scan(h, origin, Set.of(Blocks.ANCIENT_DEBRIS), 24, 0, 256);
        check(h, result.found(), "the debris is found");
        ServerLevel level = h.getLevel();
        int minY = level.getMinBuildHeight(), maxY = level.getMaxBuildHeight();
        BlockPos abs = h.absolutePos(ore);

        ScanPacket.Found exact = result.toFound(new ScanSettings(ActiveFrom.HANDS, 0, 0, false, true, 1), minY, maxY);
        equal(h, packed(h, ore), exact.nearestPos(), "precision 1 is exact");

        ScanPacket.Found chunk = result.toFound(new ScanSettings(ActiveFrom.HANDS, 0, 0, false, true, 16), minY, maxY);
        long expected = BlockPosPacking.asLong(Math.floorDiv(abs.getX(), 16) * 16 + 8, Math.floorDiv(abs.getY(), 16) * 16 + 8,
                Math.floorDiv(abs.getZ(), 16) * 16 + 8);
        check(h, chunk.nearestPos() == expected, "precision 16 gives the chunk-section centre " + describe(expected) + ", got "
                + describe(chunk.nearestPos()));
        check(h, chunk.markers().length == 0, "no markers when outlines are off");

        ScanPacket.Found none = result.toFound(new ScanSettings(ActiveFrom.HANDS, 0, 0, false, false, 16), minY, maxY);
        check(h, !none.hasNearest(), "no nearest position when arrow, distance and beep are off");
        h.succeed();
    }

    /** Nothing beyond maxRadius is found, even straight up in a loaded chunk. */
    private static void maxRadius(GameTestHelper h) {
        BlockPos origin = new BlockPos(4, 2, 4);
        h.setBlock(new BlockPos(4, 44, 4), Blocks.NETHER_GOLD_ORE); // 42 blocks up
        Set<Block> gold = Set.of(Blocks.NETHER_GOLD_ORE);
        ScanResult near = scan(h, origin, gold, 32, 2, 256);
        check(h, !near.found(), "nothing within 32 blocks");
        equal(h, 32, near.outlineRadius(), "the radius of an empty scan is maxRadius");
        ScanResult far = scan(h, origin, gold, 48, 2, 256);
        check(h, far.found(), "found within 48 blocks");
        equal(h, 42.0, far.nearestDistance(), "distance");
        h.succeed();
    }

    // ------------------------------------------------------------------ rules

    private static void denyListAndUntrackable(GameTestHelper h) {
        ServerConfig denying = ServerConfig.defaults(false)
                .with(ServerConfig.DENY_LIST, List.of(Designation.block("minecraft:deepslate_diamond_ore")));
        TargetRules<Block> ores = rules(denying).targets();
        equal(h, TargetRules.Status.NOT_ALLOWED, ores.resolve("minecraft:deepslate_diamond_ore").status(), "a denied block");
        Set<Block> diamonds = ores.resolve("#minecraft:diamond_ores").blocks();
        check(h, diamonds.contains(Blocks.DIAMOND_ORE) && !diamonds.contains(Blocks.DEEPSLATE_DIAMOND_ORE),
                "a tag loses its denied member, got " + diamonds);
        check(h, ores.specFor(Blocks.DEEPSLATE_DIAMOND_ORE) == null, "sneak-use on a denied block is refused");

        boolean tagged = false;
        for (Block b : RegistryBlockLookup.INSTANCE.tag(TargetRules.UNTRACKABLE_TAG)) tagged |= b == Blocks.SPAWNER;
        check(h, tagged, "the test datapack puts minecraft:spawner in #oreradar:untrackable");
        TargetRules<Block> all = rules(ServerConfig.defaults(false).with(ServerConfig.ALLOW_ALL, true)).targets();
        equal(h, TargetRules.Status.NOT_ALLOWED, all.resolve("minecraft:spawner").status(), "an untrackable block under allowAll");
        check(h, all.specFor(Blocks.SPAWNER) == null, "sneak-use on an untrackable block is refused");
        check(h, all.resolve("minecraft:chest").ok(), "allowAll allows other blocks");
        h.succeed();
    }

    private static void allowAllOnStone(GameTestHelper h) {
        TargetRules<Block> ores = rules(ServerConfig.defaults(false)).targets();
        equal(h, TargetRules.Status.NOT_ALLOWED, ores.resolve("minecraft:stone").status(), "the default (ores only) refuses stone");
        check(h, ores.specFor(Blocks.STONE) == null, "the default: sneak-use on stone is refused");
        check(h, ores.resolve("@ores/iron").ok(), "the default allows the iron group");
        TargetRules<Block> all = rules(ServerConfig.defaults(false).with(ServerConfig.ALLOW_ALL, true)).targets();
        TargetRules.Resolution<Block> stone = all.resolve("minecraft:stone");
        check(h, stone.ok() && stone.blocks().equals(Set.of(Blocks.STONE)), "allowAll accepts stone, got " + stone.status());
        check(h, !all.resolve("minecraft:air").ok(), "not even allowAll tracks air");
        h.succeed();
    }

    /** Groups from datapacks, Vein Mining's file included, keyed by their first entry (DESIGN §4). */
    private static void datapackGroups(GameTestHelper h) {
        TargetRules<Block> all = rules(ServerConfig.defaults(false).with(ServerConfig.ALLOW_ALL, true)).targets();
        TargetRules.Resolution<Block> veinMining = all.resolve("@pack/minecraft.clay");
        check(h, veinMining.ok() && veinMining.blocks().equals(Set.of(Blocks.CLAY, Blocks.GRAVEL)),
                "veinmining/groups.json gives @pack/minecraft.clay, got " + veinMining.status() + " " + veinMining.blocks());
        equal(h, "@pack/minecraft.clay", String.valueOf(all.specFor(Blocks.GRAVEL)), "sneak-use on gravel tracks its group");
        TargetRules.Resolution<Block> oreRadar = all.resolve("@pack/minecraft.logs");
        check(h, oreRadar.ok() && oreRadar.blocks().contains(Blocks.OAK_LOG) && oreRadar.blocks().contains(Blocks.MUSHROOM_STEM),
                "oreradar/groups.json gives @pack/minecraft.logs, got " + oreRadar.status());
        TargetRules<Block> withoutVeinMining = rules(ServerConfig.defaults(false).with(ServerConfig.ALLOW_ALL, true)
                .with(ServerConfig.READ_VEIN_MINING_GROUPS, false)).targets();
        equal(h, TargetRules.Status.UNKNOWN, withoutVeinMining.resolve("@pack/minecraft.clay").status(),
                "readVeinMiningGroups = false ignores Vein Mining's files");
        h.succeed();
    }

    /**
     * The mod's own data loads from 1.21's folder names (recipe/, tags/block/, loot_table/) and in
     * 1.21's recipe format (a result's "id"); the loot table is covered by the station tests.
     */
    private static void recipesAndTags(GameTestHelper h) {
        HolderLookup.Provider registries = h.getLevel().registryAccess();
        RecipeManager recipes = h.getLevel().getRecipeManager();
        for (ItemStack result : List.of(new ItemStack(RadarContent.RADAR.get()), new ItemStack(RadarContent.STATION_ITEM.get()))) {
            ResourceLocation id = BuiltInRegistries.ITEM.getKey(result.getItem());
            Optional<RecipeHolder<?>> recipe = recipes.byKey(id);
            check(h, recipe.isPresent(), "recipe " + id + " loaded");
            check(h, ItemStack.isSameItem(result, recipe.get().value().getResultItem(registries)), "recipe " + id + " makes its item");
        }
        check(h, RadarContent.STATION.get().defaultBlockState().is(BlockTags.MINEABLE_WITH_PICKAXE),
                "the station is in #minecraft:mineable/pickaxe");
        h.succeed();
    }

    // ------------------------------------------------------------------ the item

    /**
     * A radar written by 1.0, brought into 1.21.1 by the world upgrader: a #forge:ores/iron target
     * and no charge, kept as custom data. It reads as full and scans; neither 1.21.1 loader has
     * forge: tags, so the spec finds the auto group ores/iron.
     */
    private static void legacyRadar(GameTestHelper h) {
        ItemStack radar = upgradeFrom1201(h, "{id: \"oreradar:ore_radar\", Count: 1b, tag: {RadarTarget: \"#forge:ores/iron\"}}");
        check(h, radar.is(RadarContent.RADAR.get()), "the upgraded item is a radar, got " + radar);
        check(h, !radar.has(RadarContent.TARGET.get()), "the upgrader keeps the 1.0 data as custom data, not as our component");
        int capacity = EnergySettings.current().capacity();
        check(h, RadarItem.storedCharge(radar) == null, "no charge key");
        equal(h, capacity, RadarItem.charge(radar), "a missing charge reads as full");
        equal(h, ChargeMath.BAR_WIDTH, radar.getBarWidth(), "the charge bar is full");
        equal(h, "#forge:ores/iron", RadarItem.target(radar), "the 1.0 spec");

        RadarRules rules = serverRules(h);
        check(h, rules.targets().resolve("#forge:ores/iron").ok(), "the 1.0 spec resolves");
        h.setBlock(new BlockPos(4, 3, 7), Blocks.IRON_ORE);
        ServerPlayer player = standIn(h, new BlockPos(4, 2, 4), GameType.SURVIVAL);
        player.getInventory().setItem(0, radar);
        PlayerRadar state = new PlayerRadar();
        state.onHello(ClientPrefs.DEFAULT);
        List<RadarPacket> sent = new ArrayList<>();
        ScanBudget budget = ScanBudget.unlimited();
        state.tick(player, rules, 0, budget, sent::add);
        check(h, sent.size() == 1 && sent.get(0) instanceof ScanPacket, "one scan packet, got " + sent);
        ScanPacket packet = (ScanPacket) sent.get(0);
        equal(h, ScanPacket.Status.FOUND, packet.status(), "status");
        equal(h, "#forge:ores/iron", packet.spec(), "spec in the packet");
        equal(h, capacity, packet.charge(), "charge in the packet");
        Vec3 eye = player.getEyePosition();
        double distance = ScanMath.distance(packet.found().nearestPos(), eye.x, eye.y, eye.z);
        check(h, distance <= 3.1, "the placed iron ore, about 3 blocks away, or a nearer one is the nearest, got " + distance);
        h.succeed();
    }

    /**
     * A 1.20.1 radar after the world upgrader: its NBT is custom data. It reads the same, and the
     * first write by the server moves it to the components, keeping any other custom data. Clearing
     * a target that was still in the custom data does not bring it back.
     */
    private static void worldUpgradeMigratesOnWrite(GameTestHelper h) {
        requireDefaultServerRules(h);
        RadarServer server = RadarServer.get();
        ItemStack radar = upgradeFrom1201(h, "{id: \"oreradar:ore_radar\", Count: 1b, tag: {RadarTarget: \"#minecraft:diamond_ores\","
                + " RadarOff: 1b, RadarCharge: 5000, Note: \"kept\"}}");
        CompoundTag custom = customData(radar);
        check(h, custom.contains(RadarItem.LEGACY_TARGET) && custom.contains(RadarItem.LEGACY_OFF)
                && custom.contains(RadarItem.LEGACY_CHARGE), "the upgrader put the 1.20.1 keys in custom data, got " + custom);
        equal(h, "#minecraft:diamond_ores", RadarItem.target(radar), "target read from custom data");
        check(h, RadarItem.isOff(radar), "off read from custom data");
        equal(h, 5000, RadarItem.storedCharge(radar), "charge read from custom data");

        ServerPlayer player = standIn(h, new BlockPos(4, 2, 4), GameType.SURVIVAL);
        player.getInventory().setItem(0, radar);
        server.receive(player, RadarProtocol.Channel.POWER, new PowerPacket(0, true).toBytes());
        equal(h, "#minecraft:diamond_ores", radar.get(RadarContent.TARGET.get()), "the target moved to its component");
        equal(h, 5000, radar.get(RadarContent.CHARGE.get()), "the charge moved to its component");
        check(h, !radar.has(RadarContent.OFF.get()) && !RadarItem.isOff(radar), "switched on, as the packet asked");
        CompoundTag left = customData(radar);
        check(h, !left.contains(RadarItem.LEGACY_TARGET) && !left.contains(RadarItem.LEGACY_OFF)
                && !left.contains(RadarItem.LEGACY_CHARGE), "the 1.20.1 keys left the custom data, got " + left);
        equal(h, "kept", left.getString("Note"), "other custom data stays");

        HolderLookup.Provider registries = h.getLevel().registryAccess();
        ItemStack reloaded = ItemStack.parse(registries, radar.save(registries)).orElse(ItemStack.EMPTY);
        equal(h, "#minecraft:diamond_ores", RadarItem.target(reloaded), "the components survive saving");
        equal(h, 5000, RadarItem.storedCharge(reloaded), "and so does the charge");

        ItemStack cleared = upgradeFrom1201(h, "{id: \"oreradar:ore_radar\", Count: 1b, tag: {RadarTarget: \"@ores/iron\"}}");
        player.getInventory().setItem(1, cleared);
        select(server, player, 1, "");
        check(h, RadarItem.target(cleared) == null, "a cleared target does not fall back to the old key");
        check(h, !cleared.has(DataComponents.CUSTOM_DATA), "custom data holding only 1.20.1 keys is removed, got "
                + cleared.get(DataComponents.CUSTOM_DATA));
        h.succeed();
    }

    /** drainPerSecond accrues every active tick and is written every 100 ticks; creative never drains. */
    private static void drain(GameTestHelper h) {
        RadarRules rules = rules(ServerConfig.defaults(false));
        ItemStack radar = radarWithTarget("#minecraft:diamond_ores");
        RadarItem.setCharge(radar, 1000);
        ServerPlayer player = standIn(h, new BlockPos(4, 2, 4), GameType.SURVIVAL);
        player.getInventory().setItem(0, radar);
        PlayerRadar state = new PlayerRadar();
        state.onHello(ClientPrefs.DEFAULT);
        List<RadarPacket> sent = new ArrayList<>();
        ScanBudget budget = ScanBudget.unlimited();
        for (int tick = 0; tick < 99; tick++) state.tick(player, rules, tick, budget, sent::add);
        equal(h, 1000, RadarItem.storedCharge(radar), "nothing written before 100 ticks");
        state.tick(player, rules, 99, budget, sent::add);
        equal(h, 950, RadarItem.storedCharge(radar), "10 per second, written after 100 ticks");
        for (int tick = 100; tick < 200; tick++) state.tick(player, rules, tick, budget, sent::add);
        equal(h, 900, RadarItem.storedCharge(radar), "after 200 ticks");

        RadarItem.setCharge(radar, 5);
        for (int tick = 200; tick < 300; tick++) state.tick(player, rules, tick, budget, sent::add);
        equal(h, 0, RadarItem.storedCharge(radar), "drained to zero");
        state.tick(player, rules, 300, budget, sent::add);
        ScanPacket last = (ScanPacket) sent.get(sent.size() - 1);
        equal(h, ScanPacket.Status.NO_CHARGE, last.status(), "an empty radar reports NO_CHARGE");

        ServerPlayer creative = standIn(h, new BlockPos(4, 2, 4), GameType.CREATIVE);
        ItemStack free = radarWithTarget("#minecraft:diamond_ores");
        RadarItem.setCharge(free, 1000);
        creative.getInventory().setItem(creative.getInventory().selected, free);
        PlayerRadar creativeState = new PlayerRadar();
        creativeState.onHello(ClientPrefs.DEFAULT);
        for (int tick = 0; tick < 200; tick++) creativeState.tick(creative, rules, tick, budget, packet -> {});
        equal(h, 1000, RadarItem.storedCharge(free), "creative players never drain");
        h.succeed();
    }

    /**
     * Radar data is not validated where it comes from (custom data written by /give, a datapack or
     * the world upgrader; a component set by another mod's code): a target too long to be a spec is
     * no target, so the radar is never reported, even switched off or empty. Such a spec in a scan
     * packet would fail the packet's own checks and stop the server tick.
     */
    private static void unvalidatedTargetNbt(GameTestHelper h) {
        String tooLong = "minecraft:" + "a".repeat(300);
        ItemStack off = new ItemStack(RadarContent.RADAR.get());
        CompoundTag legacy = new CompoundTag();
        legacy.putString(RadarItem.LEGACY_TARGET, tooLong);
        legacy.putBoolean(RadarItem.LEGACY_OFF, true);
        CustomData.set(DataComponents.CUSTOM_DATA, off, legacy);
        ItemStack empty = new ItemStack(RadarContent.RADAR.get());
        empty.set(RadarContent.TARGET.get(), tooLong);
        empty.set(RadarContent.CHARGE.get(), 0);
        ServerPlayer player = standIn(h, new BlockPos(4, 2, 4), GameType.SURVIVAL);
        player.getInventory().setItem(0, off);
        player.getInventory().setItem(RadarProtocol.OFFHAND_SLOT, empty);
        PlayerRadar state = new PlayerRadar();
        state.onHello(ClientPrefs.DEFAULT);
        List<RadarPacket> sent = new ArrayList<>();
        RadarRules rules = defaultRules();
        for (int tick = 0; tick < 3; tick++) state.tick(player, rules, tick, ScanBudget.unlimited(), sent::add);
        check(h, sent.isEmpty(), "nothing reported for those radars, got " + sent);
        h.succeed();
    }

    /**
     * While a server runs in this JVM its energy rules hold. In singleplayer the client leaves the
     * world (and would reset them) before the integrated server writes the radars' last drain.
     */
    private static void energyRulesBelongToTheServer(GameTestHelper h) {
        EnergySettings server = EnergySettings.current();
        equal(h, RadarServer.get().config().capacity(), server.capacity(), "the server's capacity");
        EnergySettings.reset();
        equal(h, server, EnergySettings.current(), "a client leaving the world changes nothing");
        EnergySettings.applyLimits(ServerConfig.defaults(false).with(ServerConfig.CAPACITY, 1).limits());
        equal(h, server, EnergySettings.current(), "nor does a config packet");
        h.succeed();
    }

    /** Sneak + use on deepslate iron ore tracks the iron group; on stone (not in the ores category) it is refused. */
    private static void sneakUseTracksGroup(GameTestHelper h) {
        requireDefaultServerRules(h);
        BlockPos ore = new BlockPos(2, 1, 2);
        BlockPos stone = new BlockPos(5, 1, 2);
        h.setBlock(ore, Blocks.DEEPSLATE_IRON_ORE);
        h.setBlock(stone, Blocks.STONE);
        ServerPlayer player = standIn(h, new BlockPos(2, 2, 4), GameType.SURVIVAL);
        ItemStack radar = new ItemStack(RadarContent.RADAR.get());
        player.setItemInHand(InteractionHand.MAIN_HAND, radar);
        player.setShiftKeyDown(true);

        useOn(h, player, stone);
        check(h, RadarItem.target(radar) == null, "stone is not trackable by default");
        useOn(h, player, ore);
        equal(h, "@ores/iron", RadarItem.target(radar), "deepslate iron ore tracks the iron group");
        Set<Block> members = RadarServer.get().rules().targets().resolve("@ores/iron").blocks();
        check(h, members.contains(Blocks.IRON_ORE) && members.contains(Blocks.DEEPSLATE_IRON_ORE), "the group holds every iron ore");
        check(h, player.getCooldowns().isOnCooldown(radar.getItem()), "the 10-tick cooldown is applied");

        radar.use(h.getLevel(), player, InteractionHand.MAIN_HAND);
        check(h, RadarItem.isOff(radar), "sneak + use in the air switches the radar off");
        radar.use(h.getLevel(), player, InteractionHand.MAIN_HAND);
        check(h, !RadarItem.isOff(radar), "and on again");
        equal(h, "@ores/iron", RadarItem.target(radar), "keeping its target");
        h.succeed();
    }

    // ------------------------------------------------------------------ packets

    /** Select and power act only on a radar in the named slot, and only for what the rules allow; garbage is ignored. */
    private static void selectAndPowerValidation(GameTestHelper h) {
        requireDefaultServerRules(h);
        RadarServer server = RadarServer.get();
        ServerPlayer player = standIn(h, new BlockPos(4, 2, 4), GameType.SURVIVAL);
        ItemStack main = new ItemStack(RadarContent.RADAR.get());
        ItemStack off = new ItemStack(RadarContent.RADAR.get());
        player.getInventory().setItem(0, main);
        player.getInventory().setItem(RadarProtocol.OFFHAND_SLOT, off);
        player.getInventory().setItem(5, new ItemStack(Items.COMPASS));

        select(server, player, 0, "minecraft:stone");
        check(h, RadarItem.target(main) == null, "stone refused by default");
        select(server, player, 0, "minecraft:no_such_block");
        check(h, RadarItem.target(main) == null, "an unknown block refused");
        select(server, player, 0, "@ores/iron");
        equal(h, "@ores/iron", RadarItem.target(main), "an allowed group");
        select(server, player, RadarProtocol.OFFHAND_SLOT, "#minecraft:diamond_ores");
        equal(h, "#minecraft:diamond_ores", RadarItem.target(off), "the off hand");
        select(server, player, 5, "@ores/iron");
        check(h, player.getInventory().getItem(5).getComponentsPatch().isEmpty(), "a slot without a radar is left alone");
        select(server, player, 7, "@ores/iron");

        server.receive(player, RadarProtocol.Channel.SELECT, new byte[] {0x7f, 0x05, 'x'});
        server.receive(player, RadarProtocol.Channel.SELECT, new byte[] {(byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff, 0x0f});
        server.receive(player, RadarProtocol.Channel.POWER, new byte[0]);
        equal(h, "@ores/iron", RadarItem.target(main), "malformed packets change nothing");

        server.receive(player, RadarProtocol.Channel.POWER, new PowerPacket(0, false).toBytes());
        check(h, RadarItem.isOff(main), "power off");
        server.receive(player, RadarProtocol.Channel.POWER, new PowerPacket(0, true).toBytes());
        check(h, !RadarItem.isOff(main), "power on");
        select(server, player, 0, "");
        check(h, RadarItem.target(main) == null, "an empty spec clears the target");
        h.succeed();
    }

    /**
     * DESIGN §7 on 1.21.1: a payload's body on the wire is exactly core's bytes, as the 1.20.1 mods
     * send them. Checked through the loader's own custom payload codecs (the ones its
     * connections use), both ways, for every channel: after the channel id comes core's encoding
     * and nothing else (no length prefix, no discriminator), and those bytes decode to our payload.
     */
    private static void wireBytes(GameTestHelper h) {
        requireDefaultServerRules(h);
        long[] markers = {BlockPos.asLong(1, -60, 2), BlockPos.asLong(-30_000_000 + 1, 300, 29_999_999)};
        List<RadarPacket> samples = List.of(
                new HelloPacket(ClientPrefs.DEFAULT),
                new PrefsPacket(new ClientPrefs(ActiveFrom.INVENTORY, -1, true, false, true)),
                new SelectPacket(RadarProtocol.OFFHAND_SLOT, "@ores/iron"),
                new PowerPacket(35, false),
                new ScanPacket(ScanPacket.Status.FOUND, "#minecraft:diamond_ores", 3, 31_200,
                        new ScanPacket.Found(true, markers[0], 18, 5, true, markers)));
        RadarServer server = RadarServer.get();
        for (RadarPacket packet : samples) {
            wireCheck(h, packet.channel(), packet.toBytes());
        }
        // The config packet is the server's own, catalog and all: the largest payload there is.
        wireCheck(h, RadarProtocol.Channel.CONFIG, server.rules().configPacket());
        h.succeed();
    }

    private static void wireCheck(GameTestHelper h, RadarProtocol.Channel channel, byte[] core) {
        String what = channel.id();
        RegistryFriendlyByteBuf body = buffer(h);
        RadarPayload.codec(channel).encode(body, new RadarPayload(channel, core));
        check(h, Arrays.equals(core, readAll(body)), what + ": the payload body is core's bytes");

        RadarPayload payload = new RadarPayload(channel, core);
        RegistryFriendlyByteBuf wire = buffer(h);
        if (channel.direction() == RadarProtocol.Direction.SERVER_TO_CLIENT) {
            ClientboundCustomPayloadPacket.GAMEPLAY_STREAM_CODEC.encode(wire, new ClientboundCustomPayloadPacket(payload));
        } else {
            ServerboundCustomPayloadPacket.STREAM_CODEC.encode(wire, new ServerboundCustomPayloadPacket(payload));
        }
        RegistryFriendlyByteBuf expected = buffer(h);
        expected.writeResourceLocation(RadarPayload.type(channel).id());
        expected.writeBytes(core);
        byte[] sent = readAll(wire);
        check(h, Arrays.equals(readAll(expected), sent), what + ": the packet is the channel id, then core's bytes and nothing more ("
                + sent.length + " bytes for " + core.length + ")");

        CustomPacketPayload decoded = channel.direction() == RadarProtocol.Direction.SERVER_TO_CLIENT
                ? ClientboundCustomPayloadPacket.GAMEPLAY_STREAM_CODEC.decode(wrap(h, sent)).payload()
                : ServerboundCustomPayloadPacket.STREAM_CODEC.decode(wrap(h, sent)).payload();
        check(h, decoded instanceof RadarPayload p && p.channel() == channel && Arrays.equals(core, p.bytes()),
                what + ": those bytes decode to the same payload, got " + decoded);
    }

    /**
     * Payloads arrive as a client sends them: core's bytes as the whole custom payload on the
     * channel id, with no discriminator byte (DESIGN §7), decoded and dispatched by the loader's
     * own network code.
     */
    private static void rawPayloadsThroughTheLoader(GameTestHelper h) {
        requireDefaultServerRules(h);
        ServerPlayer player = standIn(h, new BlockPos(4, 2, 4), GameType.SURVIVAL);
        ItemStack radar = new ItemStack(RadarContent.RADAR.get());
        player.getInventory().setItem(0, radar);
        customPayload(h, player, RadarProtocol.Channel.HELLO, new HelloPacket(ClientPrefs.DEFAULT).toBytes());
        customPayload(h, player, RadarProtocol.Channel.SELECT, new SelectPacket(0, "@ores/iron").toBytes());
        // Fabric hands the payload to the server thread for the next task run, NeoForge at once.
        h.succeedWhen(() -> {
            PlayerRadar state = RadarServer.get().player(player.getUUID());
            check(h, state != null && state.saidHello(), "hello reached the server");
            equal(h, "@ores/iron", RadarItem.target(radar), "select reached the server");
        });
    }

    /**
     * A radar switched on or recharged is scanned for at once, not at its next slot in the cadence;
     * so is new prefs. But only a few such early scans run back to back: a client repeating its
     * prefs, or changing them every tick, does not make the server scan for it every tick.
     */
    private static void rescansFollowChangesNotFloods(GameTestHelper h) {
        int interval = 200;
        RadarRules rules = rules(ServerConfig.defaults(false).with(ServerConfig.SCAN_INTERVAL_TICKS, interval));
        ItemStack radar = radarWithTarget("#minecraft:diamond_ores");
        RadarItem.setCharge(radar, 10_000);
        ServerPlayer player = standIn(h, new BlockPos(4, 2, 4), GameType.SURVIVAL);
        player.getInventory().setItem(0, radar);
        PlayerRadar state = new PlayerRadar();
        state.onHello(ClientPrefs.DEFAULT);
        List<ScanPacket> sent = new ArrayList<>();
        Consumer<RadarPacket> out = packet -> sent.add((ScanPacket) packet);
        ScanBudget budget = ScanBudget.unlimited();
        // Just past this player's slot in the cadence, so no scan below comes from the cadence.
        int tick = Math.floorMod(1 - player.getId(), interval);

        state.tick(player, rules, tick++, budget, out);
        equal(h, 1, scans(sent), "the first tick scans");
        RadarItem.setOff(radar, true);
        state.tick(player, rules, tick++, budget, out);
        equal(h, ScanPacket.Status.OFF, sent.get(sent.size() - 1).status(), "switched off");
        RadarItem.setOff(radar, false);
        state.tick(player, rules, tick++, budget, out);
        equal(h, 2, scans(sent), "switched on: scanned at once, not shown as off until the cadence");
        RadarItem.setCharge(radar, 0);
        state.tick(player, rules, tick++, budget, out);
        equal(h, ScanPacket.Status.NO_CHARGE, sent.get(sent.size() - 1).status(), "out of charge");
        RadarItem.setCharge(radar, 10_000);
        state.tick(player, rules, tick++, budget, out);
        equal(h, 3, scans(sent), "recharged: scanned at once");

        for (int i = 0; i < 40; i++) {
            state.onPrefs(ClientPrefs.DEFAULT);
            state.tick(player, rules, tick++, budget, out);
        }
        equal(h, 3, scans(sent), "the same prefs again start no scan");
        ClientPrefs other = new ClientPrefs(ActiveFrom.HANDS, 0, true, true, true);
        for (int i = 0; i < 40; i++) {
            state.onPrefs(i % 2 == 0 ? other : ClientPrefs.DEFAULT);
            state.tick(player, rules, tick++, budget, out);
        }
        equal(h, 4, scans(sent), "prefs changing every tick: the one early scan left, then none until the next interval");
        h.succeed();
    }

    /** Scans that report a result (FOUND or NONE_FOUND), as opposed to status changes. */
    private static int scans(List<ScanPacket> sent) {
        return (int) sent.stream().filter(p -> p.status() == ScanPacket.Status.FOUND || p.status() == ScanPacket.Status.NONE_FOUND).count();
    }

    /**
     * Hello is answered with the config once per session (it can be large); a hello repeated only
     * carries prefs. A client in another protocol is noted once, whichever versions it claims.
     */
    private static void repeatedHello(GameTestHelper h) {
        requireDefaultServerRules(h);
        RadarServer server = RadarServer.get();
        ServerPlayer player = standIn(h, new BlockPos(4, 2, 4), GameType.SURVIVAL);
        byte[] hello = new HelloPacket(ClientPrefs.DEFAULT).toBytes();
        server.receive(player, RadarProtocol.Channel.HELLO, hello);
        PlayerRadar state = server.player(player.getUUID());
        check(h, state != null && state.saidHello() && state.hasConfig(), "greeted, and sent the config");
        server.receive(player, RadarProtocol.Channel.HELLO, hello);
        check(h, state.saidHello() && state.hasConfig(), "still greeted");

        PlayerRadar other = new PlayerRadar();
        check(h, other.onIncompatible(2), "the first mismatch is reported");
        check(h, !other.onIncompatible(3), "later ones are not");
        equal(h, 3, other.incompatibleProtocol(), "the version it claims now");
        server.onPlayerLeft(player.getUUID());
        h.succeed();
    }

    // ------------------------------------------------------------------ the charging station

    private static final BlockPos STATION = new BlockPos(3, 1, 3);

    private static void stationBurnsFuel(GameTestHelper h) {
        requireDefaultServerRules(h);
        ChargingStationBlockEntity station = placeStation(h);
        station.setItem(ChargingStationBlockEntity.SLOT_FUEL, new ItemStack(Items.REDSTONE, 2));
        h.succeedWhen(() -> {
            equal(h, 8000, station.buffer(), "two redstone burn into 2 x 4000");
            check(h, station.getItem(ChargingStationBlockEntity.SLOT_FUEL).isEmpty(), "the fuel is used up");
        });
    }

    private static void stationChargesRadar(GameTestHelper h) {
        requireDefaultServerRules(h);
        ChargingStationBlockEntity station = placeStation(h);
        station.setBuffer(10_000);
        ItemStack radar = new ItemStack(RadarContent.RADAR.get());
        RadarItem.setCharge(radar, 0);
        station.setItem(ChargingStationBlockEntity.SLOT_RADAR, radar);
        h.startSequence()
                .thenExecuteAfter(3, () -> {
                    check(h, h.getBlockState(STATION).getValue(ChargingStationBlock.LIT), "lit while charging");
                    int charge = RadarItem.charge(station.getItem(ChargingStationBlockEntity.SLOT_RADAR));
                    check(h, charge > 0 && charge < 10_000, "charging at chargeRatePerTick, got " + charge);
                })
                .thenWaitUntil(() -> {
                    equal(h, 10_000, RadarItem.charge(station.getItem(ChargingStationBlockEntity.SLOT_RADAR)), "the buffer moved into the radar");
                    equal(h, 0, station.buffer(), "buffer");
                    check(h, !h.getBlockState(STATION).getValue(ChargingStationBlock.LIT), "dark once the buffer is empty");
                })
                .thenSucceed();
    }

    private static void stationEnergyInput(GameTestHelper h) {
        requireDefaultServerRules(h);
        GameTestPlatform platform = GameTestPlatform.INSTANCE;
        int ratio = platform.energyPerCharge();
        ChargingStationBlockEntity station = placeStation(h);
        ServerLevel level = h.getLevel();
        BlockPos abs = h.absolutePos(STATION);
        long taken = platform.offerBlockEnergy(level, abs, Direction.NORTH, 1000L * ratio + ratio / 2);
        equal(h, 1000L * ratio + ratio / 2, taken, platform.energyName() + " accepted");
        equal(h, 1000, station.buffer(), "converted at " + ratio + " " + platform.energyName() + " per charge");
        long more = platform.offerBlockEnergy(level, abs, Direction.UP, 5000L * ratio);
        check(h, more <= 1000L * ratio, "capped at maxInputPerTick (2000 charge per tick), took " + more);
        check(h, station.buffer() <= 2000, "buffer within maxInputPerTick, got " + station.buffer());

        ItemStack radar = new ItemStack(RadarContent.RADAR.get());
        RadarItem.setCharge(radar, 0);
        ItemStack charged = platform.offerItemEnergy(radar, 100L * ratio);
        equal(h, 100, RadarItem.charge(charged), "a charger fills the radar item at the same ratio");
        h.succeed();
    }

    private static void stationComparator(GameTestHelper h) {
        ChargingStationBlockEntity station = placeStation(h);
        int capacity = RadarServer.get().rules().station().bufferCapacity();
        station.setBuffer(0);
        equal(h, 0, signal(h), "empty");
        station.setBuffer(capacity / 2);
        equal(h, 1 + 14 / 2, signal(h), "half full");
        station.setBuffer(capacity);
        equal(h, 15, signal(h), "full");
        h.succeed();
    }

    /** Hoppers: fuel from the top and sides, radars from the top, only a full radar out of the bottom. */
    private static void stationAutomationFaces(GameTestHelper h) {
        requireDefaultServerRules(h);
        ChargingStationBlockEntity station = placeStation(h);
        int capacity = RadarServer.get().rules().station().radarCapacity();
        ItemStack partial = new ItemStack(RadarContent.RADAR.get());
        RadarItem.setCharge(partial, capacity - 1);
        ItemStack full = new ItemStack(RadarContent.RADAR.get());
        ItemStack redstone = new ItemStack(Items.REDSTONE);
        int radar = ChargingStationBlockEntity.SLOT_RADAR, fuel = ChargingStationBlockEntity.SLOT_FUEL;
        check(h, Arrays.equals(station.getSlotsForFace(Direction.DOWN), new int[] {radar}), "bottom: the radar slot");
        check(h, Arrays.equals(station.getSlotsForFace(Direction.EAST), new int[] {fuel}), "sides: the fuel slot");
        check(h, station.canPlaceItemThroughFace(fuel, redstone, Direction.NORTH), "fuel from a side");
        check(h, station.canPlaceItemThroughFace(fuel, redstone, Direction.UP), "fuel from the top");
        check(h, !station.canPlaceItemThroughFace(fuel, new ItemStack(Items.DIRT), Direction.UP), "not a fuel");
        check(h, station.canPlaceItemThroughFace(radar, partial, Direction.UP), "a radar from the top");
        check(h, !station.canPlaceItemThroughFace(radar, partial, Direction.NORTH), "not a radar from a side");
        check(h, !station.canTakeItemThroughFace(radar, partial, Direction.DOWN), "a radar stays until full");
        check(h, station.canTakeItemThroughFace(radar, full, Direction.DOWN), "a full radar comes out of the bottom");
        check(h, !station.canTakeItemThroughFace(fuel, redstone, Direction.DOWN), "fuel never comes out");
        h.succeed();
    }

    /**
     * Breaking the station drops its contents and a station item that keeps the buffer, in a
     * {@code block_entity_data} that names the block entity (its codec refuses one that doesn't),
     * so the item also survives being saved. An empty station drops a plain item, which stacks with
     * a crafted one.
     */
    private static void stationDropKeepsBuffer(GameTestHelper h) {
        ChargingStationBlockEntity station = placeStation(h);
        station.setBuffer(12_345);
        station.setItem(ChargingStationBlockEntity.SLOT_FUEL, new ItemStack(Items.COAL, 1));
        // GameTestHelper.destroyBlock drops nothing; break it the way a player's tool does.
        h.getLevel().destroyBlock(h.absolutePos(STATION), true);
        List<ItemEntity> drops = h.getEntities(EntityType.ITEM, STATION, 2.0);
        ItemStack dropped = drops.stream().map(ItemEntity::getItem)
                .filter(s -> s.is(RadarContent.STATION_ITEM.get())).findFirst().orElse(ItemStack.EMPTY);
        check(h, !dropped.isEmpty(), "the station drops itself, got " + drops.stream().map(ItemEntity::getItem).toList());
        equal(h, 12_345, ChargingStationItem.buffer(dropped), "the dropped station keeps its buffer, in " + dropped.getComponentsPatch());
        HolderLookup.Provider registries = h.getLevel().registryAccess();
        ItemStack reloaded = ItemStack.parse(registries, dropped.save(registries)).orElse(ItemStack.EMPTY);
        equal(h, 12_345, ChargingStationItem.buffer(reloaded), "and keeps it when saved");
        check(h, drops.stream().anyMatch(e -> e.getItem().is(Items.COAL)), "and drops its fuel");

        BlockPos emptyStation = new BlockPos(6, 1, 6);
        h.setBlock(emptyStation, RadarContent.STATION.get());
        h.getLevel().destroyBlock(h.absolutePos(emptyStation), true);
        ItemStack plain = h.getEntities(EntityType.ITEM, emptyStation, 1.5).stream().map(ItemEntity::getItem)
                .filter(s -> s.is(RadarContent.STATION_ITEM.get())).findFirst().orElse(ItemStack.EMPTY);
        check(h, !plain.isEmpty() && plain.getComponentsPatch().isEmpty(),
                "an empty station drops an item without data, got " + plain.getComponentsPatch());
        check(h, ItemStack.isSameItemSameComponents(plain, new ItemStack(RadarContent.STATION_ITEM.get())), "which stacks with a crafted one");
        h.succeed();
    }

    /**
     * Stations from a 1.20.1 world, in the two shapes the 1.20.1 mod drops them: with a buffer, a
     * BlockEntityTag holding it and the block entity's {@code id}, which becomes
     * {@code block_entity_data} and places a station with that buffer; empty, no tag at all, which
     * becomes a plain station that stacks with crafted ones. A placed station's items, which the
     * upgrader does not know to fix in a mod's block entity, are brought up to date when it loads.
     *
     * <p>The {@code id} is why the 1.20.1 mod writes one (its {@code ChargingStationBlock.getDrops}):
     * the codec of {@code block_entity_data} refuses data without it, and vanilla would load the
     * item without its buffer and log "Tried to load invalid item".
     */
    private static void stationWorldUpgrade(GameTestHelper h) {
        requireDefaultServerRules(h);
        ItemStack item = upgradeFrom1201(h, "{id: \"oreradar:charging_station\", Count: 1b,"
                + " tag: {BlockEntityTag: {id: \"oreradar:charging_station\", Buffer: 12345}}}");
        check(h, item.is(RadarContent.STATION_ITEM.get()), "the upgraded item is a station, got " + item);
        equal(h, 12_345, ChargingStationItem.buffer(item), "its buffer, now block_entity_data");
        ServerPlayer player = standIn(h, new BlockPos(5, 2, 5), GameType.SURVIVAL);
        player.setItemInHand(InteractionHand.MAIN_HAND, item);
        BlockPos below = new BlockPos(STATION.getX(), STATION.getY() - 1, STATION.getZ());
        h.setBlock(below, Blocks.STONE);
        useOn(h, player, below);
        check(h, h.getBlockEntity(STATION) instanceof ChargingStationBlockEntity, "the item placed a station");
        equal(h, 12_345, ((ChargingStationBlockEntity) h.getBlockEntity(STATION)).buffer(), "the placed station has the buffer");

        ItemStack empty = upgradeFrom1201(h, "{id: \"oreradar:charging_station\", Count: 1b}");
        check(h, ItemStack.isSameItemSameComponents(empty, new ItemStack(RadarContent.STATION_ITEM.get())),
                "an empty 1.20.1 station stacks with a crafted one, got " + empty.getComponentsPatch());

        CompoundTag saved = snbt(h, "{id: \"oreradar:charging_station\", Buffer: 777, Items: ["
                + "{Slot: 0b, id: \"oreradar:ore_radar\", Count: 1b, tag: {RadarTarget: \"@ores/iron\", RadarCharge: 100}},"
                + "{Slot: 1b, id: \"minecraft:redstone\", Count: 16b}]}");
        BlockEntity loaded = BlockEntity.loadStatic(h.absolutePos(new BlockPos(6, 1, 6)),
                RadarContent.STATION.get().defaultBlockState(), saved, h.getLevel().registryAccess());
        check(h, loaded instanceof ChargingStationBlockEntity, "the saved station loads, got " + loaded);
        ChargingStationBlockEntity old = (ChargingStationBlockEntity) loaded;
        equal(h, 777, old.buffer(), "its buffer");
        ItemStack fuel = old.getItem(ChargingStationBlockEntity.SLOT_FUEL);
        check(h, fuel.is(Items.REDSTONE) && fuel.getCount() == 16, "16 redstone stay 16, got " + fuel);
        ItemStack radar = old.getItem(ChargingStationBlockEntity.SLOT_RADAR);
        equal(h, "@ores/iron", RadarItem.target(radar), "the radar in it keeps its target");
        equal(h, 100, RadarItem.storedCharge(radar), "and its charge");
        h.succeed();
    }

    // ------------------------------------------------------------------ helpers

    private static ScanResult scan(GameTestHelper h, BlockPos origin, Set<Block> targets, int maxRadius, int probeChunks, int maxMarkers) {
        BlockPos abs = h.absolutePos(origin);
        ServerLevel level = h.getLevel();
        RadarScan scan = new RadarScan(abs.getX() + 0.5, abs.getY() + 0.5, abs.getZ() + 0.5, maxRadius, probeChunks, maxMarkers,
                level.getMinBuildHeight(), level.getMaxBuildHeight());
        check(h, scan.step(new LevelScanWorld(level, targets, ScanBudget.unlimited()), Deadline.NONE), "an unbounded scan finishes in one step");
        return scan.result();
    }

    /** Rules built from a config, with the datapack groups and tags of the running server. */
    private static RadarRules rules(ServerConfig config) {
        return RadarRules.build(config, PackGroups.latest(), new ArrayList<>());
    }

    private static RadarRules defaultRules() {
        return rules(ServerConfig.defaults(false));
    }

    private static RadarRules serverRules(GameTestHelper h) {
        requireDefaultServerRules(h);
        return RadarServer.get().rules();
    }

    /** These tests read the running server's rules, which must be the defaults (only the ores category, the default fuels). */
    private static void requireDefaultServerRules(GameTestHelper h) {
        RadarServer server = RadarServer.get();
        check(h, server != null, "Ore Radar is running");
        check(h, !server.config().allowAll() && server.config().categories().equals(CategoryDef.DEFAULTS)
                && server.config().fuels().equals(ServerConfig.FUELS.defaultValue()),
                "the GameTest run directory's oreradar-server.toml must keep the default categories, allowAll and fuels");
    }

    /**
     * A player that never logged in, standing at pos in the test level. Its connection runs into an
     * embedded channel, so packets sent to it go nowhere; the loader sets it up as a modded client's.
     */
    private static ServerPlayer standIn(GameTestHelper h, BlockPos pos, GameType mode) {
        ServerLevel level = h.getLevel();
        CommonListenerCookie cookie = CommonListenerCookie.createInitial(new GameProfile(UUID.randomUUID(), "oreradar-test"), false);
        ServerPlayer player = new ServerPlayer(level.getServer(), level, cookie.gameProfile(), cookie.clientInformation());
        Connection connection = new Connection(PacketFlow.SERVERBOUND);
        new EmbeddedChannel(connection);
        GameTestPlatform.INSTANCE.prepareConnection(connection);
        player.connection = new ServerGamePacketListenerImpl(level.getServer(), connection, player, cookie);
        BlockPos abs = h.absolutePos(pos);
        player.setPos(abs.getX() + 0.5, abs.getY(), abs.getZ() + 0.5);
        player.setGameMode(mode);
        return player;
    }

    /**
     * An item as Minecraft 1.20.1 saved it, in SNBT, brought to this version the way the world
     * upgrader brings a player's inventory or a chest: vanilla's own data fixer, then the codec.
     */
    private static ItemStack upgradeFrom1201(GameTestHelper h, String itemSnbt) {
        int current = SharedConstants.getCurrentVersion().getDataVersion().getVersion();
        Tag upgraded = DataFixers.getDataFixer()
                .update(References.ITEM_STACK, new Dynamic<>(NbtOps.INSTANCE, snbt(h, itemSnbt)), DATA_VERSION_1_20_1, current)
                .getValue();
        ItemStack stack = ItemStack.parse(h.getLevel().registryAccess(), upgraded).orElse(ItemStack.EMPTY);
        check(h, !stack.isEmpty(), "the upgraded item loads: " + upgraded);
        return stack;
    }

    private static CompoundTag snbt(GameTestHelper h, String snbt) {
        try {
            return TagParser.parseTag(snbt);
        } catch (CommandSyntaxException e) {
            h.fail("bad test SNBT " + snbt + ": " + e.getMessage());
            throw new IllegalStateException(e);
        }
    }

    private static CompoundTag customData(ItemStack stack) {
        return stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag();
    }

    private static ItemStack radarWithTarget(String spec) {
        ItemStack radar = new ItemStack(RadarContent.RADAR.get());
        RadarItem.setTarget(radar, spec);
        return radar;
    }

    private static ChargingStationBlockEntity placeStation(GameTestHelper h) {
        h.setBlock(STATION, RadarContent.STATION.get());
        if (!(h.getBlockEntity(STATION) instanceof ChargingStationBlockEntity station)) {
            throw new IllegalStateException("no station block entity");
        }
        return station;
    }

    private static int signal(GameTestHelper h) {
        BlockState state = h.getBlockState(STATION);
        return state.getAnalogOutputSignal(h.getLevel(), h.absolutePos(STATION));
    }

    private static void select(RadarServer server, ServerPlayer player, int slot, String spec) {
        server.receive(player, RadarProtocol.Channel.SELECT, new SelectPacket(slot, spec).toBytes());
    }

    /** The custom payload packet a client sends, decoded from its bytes by the loader's codec and handed to the connection. */
    private static void customPayload(GameTestHelper h, ServerPlayer player, RadarProtocol.Channel channel, byte[] payload) {
        RegistryFriendlyByteBuf wire = buffer(h);
        wire.writeResourceLocation(RadarPayload.type(channel).id());
        wire.writeBytes(payload);
        player.connection.handleCustomPayload(ServerboundCustomPayloadPacket.STREAM_CODEC.decode(wire));
    }

    private static RegistryFriendlyByteBuf buffer(GameTestHelper h) {
        return new RegistryFriendlyByteBuf(Unpooled.buffer(), h.getLevel().registryAccess());
    }

    private static RegistryFriendlyByteBuf wrap(GameTestHelper h, byte[] bytes) {
        return new RegistryFriendlyByteBuf(Unpooled.wrappedBuffer(bytes), h.getLevel().registryAccess());
    }

    private static byte[] readAll(RegistryFriendlyByteBuf buffer) {
        byte[] bytes = new byte[buffer.readableBytes()];
        buffer.readBytes(bytes);
        return bytes;
    }

    private static void useOn(GameTestHelper h, ServerPlayer player, BlockPos pos) {
        BlockPos abs = h.absolutePos(pos);
        player.getItemInHand(InteractionHand.MAIN_HAND).useOn(new UseOnContext(player, InteractionHand.MAIN_HAND,
                new BlockHitResult(Vec3.atCenterOf(abs), Direction.UP, abs, false)));
    }

    private static long packed(GameTestHelper h, BlockPos relative) {
        return h.absolutePos(relative).asLong();
    }

    private static String describe(long pos) {
        return BlockPos.of(pos).toShortString();
    }

    private static void check(GameTestHelper h, boolean condition, String what) {
        h.assertTrue(condition, what);
    }

    private static void equal(GameTestHelper h, Object expected, Object actual, String what) {
        h.assertTrue(Objects.equals(expected, actual), what + ": expected " + expected + ", got " + actual);
    }
}
