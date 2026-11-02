package net.loserver.oreradar.paper;

import java.util.function.Predicate;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

import net.loserver.oreradar.core.net.RadarProtocol;

/**
 * One player's radar needle (DESIGN §8 "Arrow"): the radar's own lodestone, untracked, set to the
 * (rounded) nearest match, so the compass in hand points there in every world. The player's
 * compass target is never touched, so plain compasses and other plugins' targets stay as they
 * are. Each move rewrites the item, which a vanilla client shows by replaying the hand's equip
 * animation, so the needle keeps its target while that block still matches and the new nearest
 * lies within one needle frame of it (the compass texture has 32 frames, 11.25° apart): moving
 * then would change nothing the player can see. Server thread only.
 */
final class CompassNeedle {
    private static final double FRAME_RADIANS = 2 * Math.PI / 32;

    /** The slot whose radar carries our lodestone, and where it points; -1 and null for none. */
    private int lodestoneSlot = -1;
    private Location lodestone;

    /**
     * Points the radar in {@code slot} at {@code target}, or with null stops pointing.
     *
     * @param stillMatches whether a block still holds a match; null when positions are rounded
     *                     ({@code precisionBlocks} > 1), so a block cannot be checked
     */
    void point(Player p, int slot, Location target, Predicate<Location> stillMatches) {
        if (target == null) {
            clear(p);
            return;
        }
        if (slot != lodestoneSlot) clear(p);
        PlayerInventory inv = p.getInventory();
        ItemStack item = inv.getItem(slot);
        if (!RadarItems.isRadar(item)) return;
        boolean keep = lodestone != null && lodestone.getWorld() == target.getWorld()
                && (stillMatches == null || stillMatches.test(lodestone)) && angle(p, lodestone, target) < FRAME_RADIANS;
        Location aim = keep ? lodestone : target;
        if (RadarItems.setLodestone(item, aim)) inv.setItem(slot, item);
        lodestoneSlot = slot;
        lodestone = aim;
    }

    /** Stops pointing: our lodestone off the radar, a plain compass again. */
    void clear(Player p) {
        if (lodestoneSlot < 0) return;
        PlayerInventory inv = p.getInventory();
        ItemStack item = inv.getItem(lodestoneSlot);
        if (RadarItems.isRadar(item) && RadarItems.setLodestone(item, null)) inv.setItem(lodestoneSlot, item);
        lodestoneSlot = -1;
        lodestone = null;
    }

    /** The radar carrying the lodestone moved to another slot (a hand swap); its item data went with it. */
    void moved(int slot) {
        if (lodestoneSlot >= 0) lodestoneSlot = slot;
    }

    /**
     * Drops what this needle remembers without touching the inventory: the player changed world or
     * died, and dropped radars were made plain by {@link RadarListener}.
     */
    void forget() {
        lodestoneSlot = -1;
        lodestone = null;
    }

    /** A radar that moved out of the slot we pointed keeps its lodestone; make every radar but that one plain again. */
    void clearStale(Player p) {
        PlayerInventory inv = p.getInventory();
        for (int slot = 0; slot <= RadarProtocol.OFFHAND_SLOT; slot++) {
            if (!RadarProtocol.isValidSlot(slot) || slot == lodestoneSlot) continue;
            ItemStack item = inv.getItem(slot);
            if (item == null || item.getType() != Material.COMPASS || !RadarItems.isRadar(item)) continue;
            if (RadarItems.setLodestone(item, null)) inv.setItem(slot, item);
        }
    }

    /** The horizontal angle between two blocks as seen from the player, which is all a compass shows. */
    private static double angle(Player p, Location a, Location b) {
        Location at = p.getLocation();
        double ya = Math.atan2(a.getBlockZ() + 0.5 - at.getZ(), a.getBlockX() + 0.5 - at.getX());
        double yb = Math.atan2(b.getBlockZ() + 0.5 - at.getZ(), b.getBlockX() + 0.5 - at.getX());
        double d = Math.abs(ya - yb) % (2 * Math.PI);
        return d > Math.PI ? 2 * Math.PI - d : d;
    }
}
