package net.loserver.oreradar.paper;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;

import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.world.ChunkUnloadEvent;
import org.bukkit.event.world.WorldSaveEvent;
import org.bukkit.event.world.WorldUnloadEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;

import net.loserver.oreradar.core.energy.ChargingStation;

/**
 * Charging stations (DESIGN §8): where they are, what they hold, and their ticking.
 *
 * <ul>
 *   <li><b>Registry.</b> Each station is an entry {@code oreradar:station/<x>/<y>/<z>} in its
 *       chunk's persistent data (buffer and both slots), loaded with the chunk and written back on
 *       changes, while it works, on unload, on world save and at disable.</li>
 *   <li><b>Ticking.</b> One scheduler task, running only while some loaded station has work
 *       (fuel that fits, or a radar to fill).</li>
 *   <li><b>Block life.</b> Breaking drops the contents and a station item that keeps the buffer;
 *       explosions do the same; pistons and mobs cannot move or take a station.</li>
 * </ul>
 */
final class StationManager implements Listener {
    /** While working, a station is written to its chunk this often, so an autosave catches it. */
    private static final int SAVE_TICKS = 20;
    private static final int RENDER_TICKS = 5;

    private final OreRadarPlugin plugin;
    private final Map<Station.Pos, Station> stations = new HashMap<>();
    private final Set<Station> working = new LinkedHashSet<>();
    private BukkitTask ticker;
    private long ticks;

    StationManager(OreRadarPlugin plugin) {
        this.plugin = plugin;
    }

    ServerRules rules() {
        return plugin.rules();
    }

    /** The station at this block, or null. */
    Station at(Block b) {
        return b.getType() == Material.LODESTONE ? stations.get(Station.Pos.of(b)) : null;
    }

    int count() {
        return stations.size();
    }

    int workingCount() {
        return working.size();
    }

    void open(Player p, Station st) {
        st.renderPanes(rules());
        p.openInventory(st.getInventory());
    }

    /** At enable: the chunks that loaded before the plugin did. */
    void loadLoadedChunks() {
        for (World w : Bukkit.getWorlds()) {
            for (Chunk c : w.getLoadedChunks()) load(c);
        }
    }

    /** At disable: write everything back and close the GUIs. */
    void shutdown() {
        if (ticker != null) ticker.cancel();
        ticker = null;
        for (Station st : new ArrayList<>(stations.values())) {
            closeViewers(st);
            save(st);
            st.gone = true;
        }
        stations.clear();
        working.clear();
    }

    void rulesReloaded() {
        ServerRules r = rules();
        for (Station st : stations.values()) {
            st.logic.setBuffer(st.logic.buffer(), r.station());
            if (!st.getInventory().getViewers().isEmpty()) st.renderPanes(r);
            wake(st);
        }
    }

    /** A GUI click changed a slot; the inventory holds the new contents on the next tick. */
    void changedSoon(Station st) {
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (st.gone) return;
            st.renderPanes(rules());
            save(st);
            wake(st);
        });
    }

    private void wake(Station st) {
        if (st.gone || !st.hasWork(rules())) return;
        working.add(st);
        if (ticker == null) ticker = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 1, 1);
    }

    private void tick() {
        ServerRules r = rules();
        ticks++;
        for (Iterator<Station> it = working.iterator(); it.hasNext(); ) {
            Station st = it.next();
            if (st.gone) {
                it.remove();
                continue;
            }
            tick(st, r);
            boolean more = st.hasWork(r);
            if (!more) {
                it.remove();
                st.charging = false;
            }
            if (!more || ticks % SAVE_TICKS == 0) save(st);
            if ((!more || ticks % RENDER_TICKS == 0) && !st.getInventory().getViewers().isEmpty()) st.renderPanes(r);
        }
        if (working.isEmpty() && ticker != null) {
            ticker.cancel();
            ticker = null;
        }
    }

    private static void tick(Station st, ServerRules r) {
        ItemStack fuel = st.fuel();
        ItemStack radarItem = st.radar();
        RadarItems.RadarData radar = RadarItems.read(radarItem);
        int charge = radar == null ? -1 : radar.charge(r.config().capacity());
        ChargingStation.TickResult t = st.logic.tick(r.station(), r.fuelValue(fuel), charge);
        if (t.consumeFuel()) {
            fuel.setAmount(fuel.getAmount() - 1);
            st.setFuel(fuel.getAmount() > 0 ? fuel : null);
        }
        if (radar != null && t.radarCharge() != charge) {
            RadarItems.setCharge(radarItem, t.radarCharge(), r);
            st.setRadar(radarItem);
        }
        st.charging = t.charging();
    }

    // ---- persistence ----

    private void load(Chunk chunk) {
        PersistentDataContainer pdc = chunk.getPersistentDataContainer();
        for (NamespacedKey key : pdc.getKeys()) {
            int[] p = Keys.parseStationEntry(key);
            if (p == null) continue;
            Block b = chunk.getWorld().getBlockAt(p[0], p[1], p[2]);
            Station st = new Station(this, Station.Pos.of(b));
            try {
                PersistentDataContainer entry = pdc.get(key, PersistentDataType.TAG_CONTAINER);
                if (entry == null) continue;
                Integer buffer = entry.get(Keys.BUFFER, PersistentDataType.INTEGER);
                st.logic.setBuffer(buffer == null ? 0 : buffer, rules().station());
                st.setRadar(item(entry, Keys.RADAR_SLOT));
                st.setFuel(item(entry, Keys.FUEL_SLOT));
            } catch (RuntimeException e) {
                // Leave the entry as it is, so nothing is lost; the block works as a plain lodestone meanwhile.
                plugin.getLogger().log(Level.WARNING, "Cannot read the charging station at " + where(st.pos) + "; leaving its data untouched", e);
                continue;
            }
            if (b.getType() != Material.LODESTONE) {
                // Removed behind our back (another plugin, a world edit): hand back what it held.
                plugin.getLogger().warning("The charging station at " + where(st.pos) + " is gone (now " + b.getType()
                        + "); dropping its contents and " + st.logic.buffer() + " buffered charge as a station item");
                pdc.remove(key);
                List<ItemStack> drops = contents(st, true);
                Location at = b.getLocation().add(0.5, 0.5, 0.5);
                // Not while the chunk is still loading.
                Bukkit.getScheduler().runTask(plugin, () -> drops.forEach(i -> at.getWorld().dropItemNaturally(at, i)));
                continue;
            }
            stations.put(st.pos, st);
            wake(st);
        }
    }

    private static ItemStack item(PersistentDataContainer entry, NamespacedKey key) {
        byte[] bytes = entry.get(key, PersistentDataType.BYTE_ARRAY);
        return bytes == null ? null : ItemStack.deserializeBytes(bytes);
    }

    /** Writes the station into its chunk's persistent data. */
    void save(Station st) {
        if (st.gone) return;
        World w = Bukkit.getWorld(st.pos.world());
        if (w == null || !w.isChunkLoaded(st.pos.x() >> 4, st.pos.z() >> 4)) return;
        PersistentDataContainer pdc = w.getChunkAt(st.pos.x() >> 4, st.pos.z() >> 4).getPersistentDataContainer();
        PersistentDataContainer entry = pdc.getAdapterContext().newPersistentDataContainer();
        entry.set(Keys.BUFFER, PersistentDataType.INTEGER, st.logic.buffer());
        put(entry, Keys.RADAR_SLOT, st.radar());
        put(entry, Keys.FUEL_SLOT, st.fuel());
        pdc.set(Keys.stationEntry(st.pos.x(), st.pos.y(), st.pos.z()), PersistentDataType.TAG_CONTAINER, entry);
    }

    private static void put(PersistentDataContainer entry, NamespacedKey key, ItemStack item) {
        if (item != null && !item.getType().isAir()) entry.set(key, PersistentDataType.BYTE_ARRAY, item.serializeAsBytes());
    }

    private void unload(Station st) {
        closeViewers(st);
        save(st);
        st.gone = true;
        stations.remove(st.pos);
        working.remove(st);
    }

    /** Removes a broken station and returns what it drops. */
    private List<ItemStack> destroy(Station st, Block b, boolean dropStation) {
        closeViewers(st); // their cursor items go back to them; nothing more can enter the slots
        List<ItemStack> drops = contents(st, dropStation);
        st.gone = true;
        stations.remove(st.pos);
        working.remove(st);
        b.getChunk().getPersistentDataContainer().remove(Keys.stationEntry(st.pos.x(), st.pos.y(), st.pos.z()));
        return drops;
    }

    private List<ItemStack> contents(Station st, boolean withStation) {
        List<ItemStack> out = new ArrayList<>();
        for (ItemStack i : new ItemStack[] {st.radar(), st.fuel()}) {
            if (i != null && !i.getType().isAir()) out.add(i.clone());
        }
        st.setRadar(null);
        st.setFuel(null);
        if (withStation) out.add(RadarItems.newStation(st.logic.buffer(), rules()));
        return out;
    }

    private static void closeViewers(Station st) {
        for (HumanEntity viewer : new ArrayList<>(st.getInventory().getViewers())) viewer.closeInventory();
    }

    private static String where(Station.Pos p) {
        World w = Bukkit.getWorld(p.world());
        return (w == null ? p.world().toString() : w.getName()) + " " + p.x() + " " + p.y() + " " + p.z();
    }

    private static void drop(Block b, List<ItemStack> items) {
        Location at = b.getLocation().add(0.5, 0.5, 0.5);
        for (ItemStack i : items) b.getWorld().dropItemNaturally(at, i);
    }

    // ---- events ----

    @EventHandler(priority = EventPriority.MONITOR)
    public void onChunkLoad(ChunkLoadEvent e) {
        load(e.getChunk());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onChunkUnload(ChunkUnloadEvent e) {
        Chunk c = e.getChunk();
        UUID world = c.getWorld().getUID();
        for (Station st : new ArrayList<>(stations.values())) {
            if (st.pos.world().equals(world) && st.pos.x() >> 4 == c.getX() && st.pos.z() >> 4 == c.getZ()) unload(st);
        }
    }

    @EventHandler
    public void onWorldSave(WorldSaveEvent e) {
        UUID world = e.getWorld().getUID();
        for (Station st : stations.values()) {
            if (st.pos.world().equals(world)) save(st);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorldUnload(WorldUnloadEvent e) {
        if (e.isCancelled()) return;
        UUID world = e.getWorld().getUID();
        for (Station st : new ArrayList<>(stations.values())) {
            if (st.pos.world().equals(world)) unload(st);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) {
        if (!RadarItems.isStation(e.getItemInHand())) return;
        Block b = e.getBlockPlaced();
        Station st = new Station(this, Station.Pos.of(b));
        st.logic.setBuffer(RadarItems.stationBuffer(e.getItemInHand()), rules().station());
        stations.put(st.pos, st);
        save(st);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        Station st = at(e.getBlock());
        if (st == null) return;
        Player p = e.getPlayer();
        // As for any block: nothing in creative, nothing without a tool that harvests it; the contents always.
        boolean dropStation = p.getGameMode() != GameMode.CREATIVE
                && !e.getBlock().getDrops(p.getInventory().getItemInMainHand(), p).isEmpty();
        e.setDropItems(false);
        drop(e.getBlock(), destroy(st, e.getBlock(), dropStation));
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent e) {
        explode(e.blockList());
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent e) {
        explode(e.blockList());
    }

    /**
     * Takes stations out of the explosion and breaks them ourselves, so they drop as stations. The
     * drops appear on the next tick, when the blast is over: where the event fires relative to the
     * blast hurting entities is an implementation detail, and items spawned before would burn.
     */
    private void explode(List<Block> blocks) {
        for (Iterator<Block> it = blocks.iterator(); it.hasNext(); ) {
            Block b = it.next();
            Station st = at(b);
            if (st == null) continue;
            it.remove();
            List<ItemStack> drops = destroy(st, b, true);
            b.setType(Material.AIR);
            Bukkit.getScheduler().runTask(plugin, () -> drop(b, drops));
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPistonExtend(BlockPistonExtendEvent e) {
        for (Block b : e.getBlocks()) {
            if (at(b) != null) {
                e.setCancelled(true);
                return;
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPistonRetract(BlockPistonRetractEvent e) {
        for (Block b : e.getBlocks()) {
            if (at(b) != null) {
                e.setCancelled(true);
                return;
            }
        }
    }

    /** Withers and the like would take the block without its data. */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEntityChangeBlock(EntityChangeBlockEvent e) {
        if (at(e.getBlock()) != null) e.setCancelled(true);
    }
}
