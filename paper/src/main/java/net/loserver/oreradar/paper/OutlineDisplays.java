package net.loserver.oreradar.paper;

import java.util.ArrayList;
import java.util.List;

import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.Transformation;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

import net.loserver.oreradar.core.net.BlockPosPacking;

/**
 * One player's outlines for a vanilla client (DESIGN §8 "Outlines"): glowing {@link BlockDisplay}s
 * that copy each matched block at scale 1.01, visible to that player only. The entities are reused
 * from scan to scan (moved and re-skinned), never saved with the world, and removed by
 * {@link #clear()}; any that a crash might leave behind carry {@link Keys#OUTLINE} for the cleanup
 * at start.
 */
final class OutlineDisplays {
    private static final float SCALE = 1.01f;
    private static final Transformation ENLARGED = new Transformation(
            new Vector3f((1 - SCALE) / 2), new AxisAngle4f(), new Vector3f(SCALE), new AxisAngle4f());
    private static final Display.Brightness FULL_BRIGHT = new Display.Brightness(15, 15);
    /** A display's view range is a multiple of 64 blocks. */
    private static final float VIEW_RANGE_UNIT = 64f;

    private final List<BlockDisplay> pool = new ArrayList<>();

    /**
     * Shows the first {@code count} markers (nearest first), reusing this player's displays.
     *
     * @param glow      glow through walls (DESIGN's {@code throughWalls}); without it only the
     *                  slightly enlarged block shows
     * @param maxRadius blocks; the client must not cull a display inside the scan radius
     */
    void show(Plugin plugin, Player owner, long[] markers, int count, int glowColor, boolean glow, int maxRadius) {
        World world = owner.getWorld();
        Color color = Color.fromRGB(glowColor);
        float viewRange = Math.max(1f, (maxRadius + 16) / VIEW_RANGE_UNIT);
        int used = 0;
        for (int i = 0; i < markers.length && used < count; i++) {
            long pos = markers[i];
            int x = BlockPosPacking.x(pos), y = BlockPosPacking.y(pos), z = BlockPosPacking.z(pos);
            // Markers come from loaded chunks, but one may have unloaded since the scan.
            if (!world.isChunkLoaded(x >> 4, z >> 4)) continue;
            BlockData block = world.getBlockAt(x, y, z).getBlockData();
            if (block.getMaterial().isAir()) continue; // mined since the scan
            Location at = new Location(world, x, y, z);
            BlockDisplay display = used < pool.size() ? pool.get(used) : null;
            if (display != null && (!display.isValid() || !display.getWorld().equals(world))) {
                display.remove();
                display = null;
            }
            if (display == null) {
                display = Spawner.spawn(world, at, BlockDisplay.class, d -> {
                    d.setVisibleByDefault(false);
                    d.setPersistent(false);
                    d.getPersistentDataContainer().set(Keys.OUTLINE, PersistentDataType.BYTE, (byte) 1);
                    d.setTransformation(ENLARGED);
                    d.setBrightness(FULL_BRIGHT);
                    style(d, block, color, glow, viewRange);
                });
                owner.showEntity(plugin, display);
                if (used < pool.size()) pool.set(used, display);
                else pool.add(display);
            } else {
                if (!sameBlock(display.getLocation(), at)) display.teleport(at);
                style(display, block, color, glow, viewRange);
            }
            used++;
        }
        trim(used);
    }

    /** Removes every display; call when the radar goes idle, on quit, death, world change and disable. */
    void clear() {
        trim(0);
    }

    int size() {
        return pool.size();
    }

    private void trim(int size) {
        while (pool.size() > size) {
            BlockDisplay d = pool.remove(pool.size() - 1);
            if (d.isValid()) d.remove();
        }
    }

    private static void style(BlockDisplay d, BlockData block, Color color, boolean glow, float viewRange) {
        if (!block.equals(d.getBlock())) d.setBlock(block);
        d.setGlowing(glow);
        d.setGlowColorOverride(color);
        d.setViewRange(viewRange);
    }

    private static boolean sameBlock(Location a, Location b) {
        return a.getBlockX() == b.getBlockX() && a.getBlockY() == b.getBlockY() && a.getBlockZ() == b.getBlockZ();
    }
}
