package net.loserver.oreradar.paper;

import java.util.List;

import org.bukkit.Keyed;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.PrepareAnvilEvent;
import org.bukkit.event.inventory.PrepareGrindstoneEvent;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

import com.destroystokyo.paper.event.inventory.PrepareResultEvent;
import io.papermc.paper.event.server.ServerResourcesReloadedEvent;
import net.kyori.adventure.text.Component;
import net.loserver.oreradar.core.designation.TargetSpec;
import net.loserver.oreradar.core.net.RadarProtocol;

/**
 * Player-facing events: using a radar (DESIGN §3 "Item use"), opening a station, keeping radars
 * out of vanilla crafting, and the cleanup that keeps outlines and compass needles from outliving
 * their radar.
 */
final class RadarListener implements Listener {
    private final OreRadarPlugin plugin;
    private final RadarService radars;
    private final StationManager stations;

    RadarListener(OreRadarPlugin plugin, RadarService radars, StationManager stations) {
        this.plugin = plugin;
        this.radars = radars;
        this.stations = stations;
    }

    /**
     * Right-click with a radar: open its screen; sneaking, track the block clicked or switch the
     * radar on/off in the air. Right-click on a station: open the station. Neither ever links a
     * compass to a lodestone. The client follows an unconsumed click on a block with a click in
     * the air, so the use cooldown also keeps one click from acting twice.
     */
    @EventHandler(priority = EventPriority.HIGH)
    public void onInteract(PlayerInteractEvent e) {
        Action action = e.getAction();
        if (action != Action.RIGHT_CLICK_AIR && action != Action.RIGHT_CLICK_BLOCK) return;
        Player p = e.getPlayer();
        ItemStack item = e.getItem();
        boolean radar = RadarItems.isRadar(item);
        Block block = e.getClickedBlock();

        if (action == Action.RIGHT_CLICK_BLOCK && block != null) {
            Station st = stations.at(block);
            if (st != null) {
                if (item != null && item.getType() == Material.COMPASS) e.setUseItemInHand(Event.Result.DENY);
                boolean placing = p.isSneaking() && item != null && !item.getType().isAir();
                if (!placing) {
                    // Main hand only: the off-hand event of the same click would open it twice.
                    if (e.getHand() == EquipmentSlot.HAND && e.useInteractedBlock() != Event.Result.DENY) stations.open(p, st);
                    e.setUseInteractedBlock(Event.Result.DENY);
                    e.setUseItemInHand(Event.Result.DENY);
                    return;
                }
                if (!radar) return; // sneaking with a block: place it against the station as usual
            }
        }
        if (!radar || e.useItemInHand() == Event.Result.DENY) return;
        // The radar is never a vanilla compass: no lodestone linking.
        e.setUseItemInHand(Event.Result.DENY);
        if (action == Action.RIGHT_CLICK_BLOCK && !p.isSneaking() && block != null && block.getType().isInteractable()
                && block.getType() != Material.LODESTONE) {
            return; // doors, chests and levers work as with any item in hand
        }
        if (block != null) e.setUseInteractedBlock(Event.Result.DENY);
        if (!radars.tryUse(p)) return;
        if (!p.hasPermission(RadarService.USE_PERMISSION)) {
            radars.message(p, Component.text("You may not use the Ore Radar here.", Texts.WARN));
            return;
        }
        int slot = e.getHand() == EquipmentSlot.OFF_HAND ? RadarProtocol.OFFHAND_SLOT : p.getInventory().getHeldItemSlot();
        if (!p.isSneaking()) {
            radars.openRadar(p, slot);
        } else if (action == Action.RIGHT_CLICK_BLOCK && block != null) {
            track(p, slot, block);
        } else {
            toggle(p, slot);
        }
    }

    private void track(Player p, int slot, Block block) {
        ServerRules r = plugin.rules();
        TargetSpec spec = r.rules().specFor(block.getType());
        if (spec == null) {
            radars.message(p, Texts.blockName(block.getType()).color(Texts.WARN)
                    .append(Component.text(" cannot be tracked on this server", Texts.WARN)));
            return;
        }
        ItemStack item = p.getInventory().getItem(slot);
        RadarItems.setTarget(item, spec.toString(), r);
        p.getInventory().setItem(slot, item);
        radars.message(p, Component.text("Tracking ", Texts.MUTED).append(Texts.targetName(r.rules(), spec.toString()).color(Texts.ACCENT)));
    }

    private void toggle(Player p, int slot) {
        ItemStack item = p.getInventory().getItem(slot);
        RadarItems.RadarData d = RadarItems.read(item);
        boolean off = !d.off();
        RadarItems.setOff(item, off, plugin.rules());
        p.getInventory().setItem(slot, item);
        radars.message(p, Component.text(off ? "Ore Radar off" : "Ore Radar on", off ? Texts.MUTED : Texts.ACCENT));
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        radars.join(p);
        p.discoverRecipes(List.of(Keys.RECIPE_RADAR, Keys.RECIPE_STATION));
    }

    /**
     * A radar is not a compass ingredient: on Paper it is a compass item, which recipes match by
     * type alone, so without this it would go into the radar recipe (an empty radar crafting a
     * full one), maps and recovery compasses, and vanilla's repair of two damaged items. Our
     * recipe's result is a template without an id; each craft gets a radar of its own, so two
     * crafted radars never stack. The event fires again for every craft of a shift-click.
     */
    @EventHandler
    public void onPrepareCraft(PrepareItemCraftEvent e) {
        for (ItemStack i : e.getInventory().getMatrix()) {
            if (RadarItems.isRadar(i)) {
                e.getInventory().setResult(null);
                return;
            }
        }
        if (e.getRecipe() instanceof Keyed k && k.getKey().equals(Keys.RECIPE_RADAR)) {
            e.getInventory().setResult(RadarItems.newRadar(plugin.rules()));
        }
    }

    /** An anvil would add another compass's "durability" to a radar's bar (and eat a radar given as the second). */
    @EventHandler
    public void onPrepareAnvil(PrepareAnvilEvent e) {
        refuseCompassMerge(e);
    }

    /** A grindstone would merge two radars' bars into one radar. */
    @EventHandler
    public void onPrepareGrindstone(PrepareGrindstoneEvent e) {
        refuseCompassMerge(e);
    }

    /** Both inputs compasses and one a radar: the radar's charge lives in its data, not in a bar to be merged. */
    private static void refuseCompassMerge(PrepareResultEvent e) {
        Inventory in = e.getInventory();
        ItemStack a = in.getItem(0), b = in.getItem(1);
        boolean compasses = a != null && b != null && a.getType() == Material.COMPASS && b.getType() == Material.COMPASS;
        if (compasses && (RadarItems.isRadar(a) || RadarItems.isRadar(b))) e.setResult(null);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent e) {
        radars.quit(e.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(PlayerDeathEvent e) {
        radars.died(e.getEntity());
        for (ItemStack drop : e.getDrops()) {
            if (RadarItems.isRadar(drop)) RadarItems.setLodestone(drop, null);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorldChange(PlayerChangedWorldEvent e) {
        radars.changedWorld(e.getPlayer());
    }

    /** A radar on the ground is idle: a plain compass again. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent e) {
        Item dropped = e.getItemDrop();
        ItemStack stack = dropped.getItemStack();
        if (RadarItems.isRadar(stack) && RadarItems.setLodestone(stack, null)) dropped.setItemStack(stack);
    }

    /** Outlines are never saved; this removes any that a crash or another plugin kept in a chunk. */
    @EventHandler
    public void onEntitiesLoad(EntitiesLoadEvent e) {
        for (Entity entity : e.getEntities()) {
            if (isOutline(entity)) entity.remove();
        }
    }

    /** {@code /minecraft:reload} reloads datapacks, and with them tags: rebuild the rules. */
    @EventHandler
    public void onResourcesReloaded(ServerResourcesReloadedEvent e) {
        ServerRules.Loaded loaded = plugin.reload();
        plugin.getLogger().info("Datapacks reloaded: rebuilt the rules (" + loaded.rules().rules().catalog().size() + " catalog entries)");
    }

    static boolean isOutline(Entity entity) {
        return entity instanceof BlockDisplay && entity.getPersistentDataContainer().has(Keys.OUTLINE, PersistentDataType.BYTE);
    }
}
