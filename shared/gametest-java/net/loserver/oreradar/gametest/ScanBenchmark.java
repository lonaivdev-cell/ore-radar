package net.loserver.oreradar.gametest;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

import net.loserver.oreradar.OreRadar;
import net.loserver.oreradar.core.config.ServerConfig;
import net.loserver.oreradar.core.designation.TargetRules;
import net.loserver.oreradar.core.scan.RadarScan;
import net.loserver.oreradar.core.scan.ScanBudget;
import net.loserver.oreradar.core.scan.ScanResult;
import net.loserver.oreradar.core.util.Deadline;
import net.loserver.oreradar.server.LevelScanWorld;
import net.loserver.oreradar.server.PackGroups;
import net.loserver.oreradar.server.RadarRules;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * Times real scans on a generated world (the dedicated dev server's, around spawn, whose chunks
 * stay loaded), for DESIGN §5's budget: each target is scanned whole, then in budgeted steps as the
 * server runs it, and the numbers are logged. Runs once at server start when the JVM has
 * {@code -Doreradar.bench=true} (the {@code benchServer} run), then stops the server.
 */
public final class ScanBenchmark {
    public static final String PROPERTY = "oreradar.bench";
    private static final int WARMUP = 3;
    private static final int RUNS = 7;

    private ScanBenchmark() {}

    public static boolean enabled() {
        return Boolean.getBoolean(PROPERTY);
    }

    public static void run(MinecraftServer server) {
        ServerLevel level = server.overworld();
        ServerConfig defaults = ServerConfig.defaults(false);
        TargetRules<Block> ores = RadarRules.build(defaults, PackGroups.latest(), new ArrayList<>()).targets();
        TargetRules<Block> all = RadarRules.build(defaults.with(ServerConfig.ALLOW_ALL, true), PackGroups.latest(),
                new ArrayList<>()).targets();
        BlockPos spawn = level.getSharedSpawnPos();
        int surface = level.getHeight(Heightmap.Types.MOTION_BLOCKING, spawn.getX(), spawn.getZ());
        List<BlockPos> origins = List.of(new BlockPos(spawn.getX(), surface + 1, spawn.getZ()), new BlockPos(spawn.getX(), 16, spawn.getZ()),
                new BlockPos(spawn.getX(), -40, spawn.getZ()));
        String anyOre = ores.resolve("#forge:ores").ok() ? "#forge:ores" : "#c:ores";
        // One budget for the whole run, as the server keeps one: it learns how long a section takes.
        ScanBudget budget = ScanBudget.perTick();

        OreRadar.LOGGER.info("Ore Radar bench: radius {}, probe {} chunks, {} markers, budget {} µs per tick",
                defaults.maxRadius(), defaults.probeChunks(), defaults.maxMarkers(), defaults.scanBudgetMicros());
        for (BlockPos origin : origins) {
            for (String spec : List.of(anyOre, "@ores/iron", "@ores/diamond")) {
                time(level, origin, "ores", spec, ores.resolve(spec), defaults, budget);
            }
            time(level, origin, "allowAll", "minecraft:stone", all.resolve("minecraft:stone"), defaults, budget);
        }
        OreRadar.LOGGER.info("Ore Radar bench: done; stopping the server");
        server.halt(false);
    }

    private static void time(ServerLevel level, BlockPos origin, String rules, String spec, TargetRules.Resolution<Block> target,
                             ServerConfig config, ScanBudget budget) {
        if (!target.ok()) {
            OreRadar.LOGGER.info("Ore Radar bench: {} {}: does not resolve ({})", rules, spec, target.status());
            return;
        }
        Set<Block> blocks = target.blocks();
        long[] micros = new long[RUNS];
        ScanResult result = null;
        for (int i = 0; i < WARMUP + RUNS; i++) {
            RadarScan scan = newScan(level, origin, config);
            long start = System.nanoTime();
            scan.step(new LevelScanWorld(level, blocks, ScanBudget.unlimited()), Deadline.NONE);
            if (i >= WARMUP) micros[i - WARMUP] = (System.nanoTime() - start) / 1000;
            result = scan.result();
        }
        Arrays.sort(micros);

        // As the server runs it: one budget per tick, the scan continued on the next.
        RadarScan scan = newScan(level, origin, config);
        LevelScanWorld world = new LevelScanWorld(level, blocks, budget);
        int ticks = 0;
        long longestStep = 0;
        boolean done = false;
        while (!done) {
            long start = System.nanoTime();
            budget.startTick(config.scanBudgetMicros(), 1);
            done = scan.step(world, budget.deadline());
            longestStep = Math.max(longestStep, (System.nanoTime() - start) / 1000);
            ticks++;
        }
        boolean same = scan.result().totalMatches() == result.totalMatches() && scan.result().nearest() == result.nearest();
        OreRadar.LOGGER.info("Ore Radar bench: {} {} from {}: {} (nearest {} m, {} matches within {} m, {} markers); whole scan "
                        + "median {} µs (min {}, max {}); budgeted: {} ticks, longest tick {} µs{}",
                rules, spec, origin.toShortString(), result.found() ? "found" : "none found",
                result.found() ? String.format("%.1f", result.nearestDistance()) : "-", result.totalMatches(),
                result.outlineRadius(), result.markerCount(), micros[RUNS / 2], micros[0], micros[RUNS - 1], ticks, longestStep,
                same ? "" : " (DIFFERENT RESULT)");
    }

    private static RadarScan newScan(ServerLevel level, BlockPos origin, ServerConfig config) {
        return new RadarScan(origin.getX() + 0.5, origin.getY() + 1.62, origin.getZ() + 0.5, config.maxRadius(), config.probeChunks(),
                config.maxMarkers(), level.getMinBuildHeight(), level.getMaxBuildHeight());
    }
}
