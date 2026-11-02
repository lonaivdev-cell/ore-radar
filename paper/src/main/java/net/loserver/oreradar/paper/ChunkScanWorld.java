package net.loserver.oreradar.paper;

import java.util.List;
import java.util.Set;

import org.bukkit.Chunk;
import org.bukkit.ChunkSnapshot;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;

import net.loserver.oreradar.core.scan.RadarScan;
import net.loserver.oreradar.core.scan.ScanBudget;

/**
 * Core's scan view of one Bukkit world (DESIGN §5, §8), on the server thread only.
 *
 * <ul>
 *   <li>Only chunks the server already has loaded are visited ({@link World#isChunkLoaded}).</li>
 *   <li>A chunk passes the pre-filter when {@link Chunk#contains(BlockData)} (a palette lookup per
 *       section, no block reads) finds one of the target's states; without a full state list
 *       (see {@link TargetStates}) every chunk passes.</li>
 *   <li>A passing chunk is copied once into a {@link ChunkSnapshot}, whose sections are then read
 *       block by block; all-air sections are skipped.</li>
 * </ul>
 * The snapshot is dropped between scan steps, as core allows: an interrupted chunk is selected
 * again and copied afresh. Each section's cost, the snapshot copy included, goes to the
 * {@link ScanBudget}, which keeps room for one more before its deadline.
 */
final class ChunkScanWorld implements RadarScan.World {
    /** Work counters of one scan, for {@code /oreradar info}. */
    static final class Stats {
        int chunks;
        int chunksSkipped;
        int sections;
    }

    private final World world;
    private final Set<Material> targets;
    private final List<BlockData> prefilter;
    private final int minSection;
    private final ScanBudget budget;
    private final Stats stats = new Stats();
    private Chunk chunk;
    private ChunkSnapshot snapshot;
    private boolean chunkMayContain;
    private int chunkX, chunkZ;
    /** When the scan last looked at its deadline: after the previous section, or as this chunk was selected. */
    private long since;

    /**
     * @param targets   the materials to report (an EnumSet, for a cheap membership test per block)
     * @param prefilter every state of those materials, or null to skip the palette check
     * @param budget    learns how long a section takes, to keep its ticks within the budget
     */
    ChunkScanWorld(World world, Set<Material> targets, List<BlockData> prefilter, ScanBudget budget) {
        this.world = world;
        this.targets = targets;
        this.prefilter = prefilter;
        this.budget = budget;
        this.minSection = Math.floorDiv(world.getMinHeight(), 16);
    }

    Stats stats() {
        return stats;
    }

    @Override
    public boolean selectChunk(int x, int z) {
        since = System.nanoTime();
        chunk = null;
        snapshot = null;
        if (!world.isChunkLoaded(x, z)) return false;
        chunk = world.getChunkAt(x, z);
        chunkX = x;
        chunkZ = z;
        stats.chunks++;
        chunkMayContain = prefilter == null || containsAny(chunk);
        if (!chunkMayContain) stats.chunksSkipped++;
        return true;
    }

    private boolean containsAny(Chunk c) {
        for (BlockData state : prefilter) {
            if (c.contains(state)) return true;
        }
        return false;
    }

    @Override
    public boolean mayContain(int sectionY) {
        return chunkMayContain && !snapshot().isSectionEmpty(sectionY - minSection);
    }

    @Override
    public void scanSection(int sectionY, RadarScan.MatchSink sink) {
        ChunkSnapshot s = snapshot();
        stats.sections++;
        int baseX = chunkX << 4, baseY = sectionY << 4, baseZ = chunkZ << 4;
        for (int y = baseY; y < baseY + 16; y++) {
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    if (targets.contains(s.getBlockType(x, y, z))) sink.match(baseX + x, y, baseZ + z);
                }
            }
        }
        long now = System.nanoTime();
        budget.sectionScanned(now - since);
        since = now;
    }

    private ChunkSnapshot snapshot() {
        if (snapshot == null) snapshot = chunk.getChunkSnapshot(false, false, false);
        return snapshot;
    }
}
