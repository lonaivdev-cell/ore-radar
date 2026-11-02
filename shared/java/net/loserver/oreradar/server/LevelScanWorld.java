package net.loserver.oreradar.server;

import java.util.Set;
import java.util.function.Predicate;

import net.loserver.oreradar.core.scan.RadarScan;
import net.loserver.oreradar.core.scan.ScanBudget;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;
import org.jetbrains.annotations.Nullable;

/**
 * A {@link RadarScan} over a server level (DESIGN §5): full chunks that are already loaded, never
 * loading or generating one, and in each the sections whose palette may hold a target block.
 * Holds a chunk only between {@code selectChunk} and the end of a step; the scan selects it again
 * after an interruption, so an unloaded chunk is skipped rather than kept alive.
 */
public final class LevelScanWorld implements RadarScan.World {
    private final ServerLevel level;
    private final Set<Block> targets;
    private final Predicate<BlockState> matches;
    private final ScanBudget budget;
    @Nullable
    private LevelChunk chunk;

    /**
     * @param targets the blocks to find; read only, so the rules' resolved set can be passed as is
     * @param budget  learns how long a section takes, to keep its ticks within the budget
     */
    public LevelScanWorld(ServerLevel level, Set<Block> targets, ScanBudget budget) {
        this.level = level;
        this.targets = targets;
        this.matches = state -> targets.contains(state.getBlock());
        this.budget = budget;
    }

    @Override
    public boolean selectChunk(int chunkX, int chunkZ) {
        chunk = level.getChunkSource().getChunkNow(chunkX, chunkZ);
        return chunk != null;
    }

    @Override
    public boolean mayContain(int sectionY) {
        LevelChunkSection section = section(sectionY);
        return section != null && !section.hasOnlyAir() && section.getStates().maybeHas(matches);
    }

    @Override
    public void scanSection(int sectionY, RadarScan.MatchSink sink) {
        LevelChunkSection section = section(sectionY);
        if (section == null) return;
        long start = System.nanoTime();
        PalettedContainer<BlockState> states = section.getStates();
        int baseX = chunk.getPos().getMinBlockX();
        int baseY = SectionPos.sectionToBlockCoord(sectionY);
        int baseZ = chunk.getPos().getMinBlockZ();
        // Neighbouring blocks are mostly the same state, so remember the last answer rather than
        // looking every block up in the target set.
        BlockState last = null;
        boolean lastMatches = false;
        for (int y = 0; y < SectionPos.SECTION_SIZE; y++) {
            for (int z = 0; z < SectionPos.SECTION_SIZE; z++) {
                for (int x = 0; x < SectionPos.SECTION_SIZE; x++) {
                    BlockState state = states.get(x, y, z);
                    if (state != last) {
                        last = state;
                        lastMatches = targets.contains(state.getBlock());
                    }
                    if (lastMatches) sink.match(baseX + x, baseY + y, baseZ + z);
                }
            }
        }
        budget.sectionScanned(System.nanoTime() - start);
    }

    @Nullable
    private LevelChunkSection section(int sectionY) {
        if (chunk == null) return null;
        int index = level.getSectionIndexFromSectionY(sectionY);
        return index >= 0 && index < level.getSectionsCount() ? chunk.getSection(index) : null;
    }
}
