package net.loserver.oreradar.paper;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;

import net.kyori.adventure.text.Component;
import net.loserver.oreradar.core.config.ServerConfig;
import net.loserver.oreradar.core.designation.FuelEntry;
import net.loserver.oreradar.core.energy.ChargingStation;

/**
 * One placed charging station (DESIGN §8): a lodestone with a 9-slot GUI holding a radar slot, a
 * fuel slot and filler panes that show the buffer. The inventory is the station's storage; core's
 * {@link ChargingStation} holds the buffer. Only a single radar goes in the radar slot (a stack
 * would share one charge) and only fuels in the fuel slot; the panes can never be taken.
 */
final class Station implements Menu {
    static final int SIZE = 9;
    static final int RADAR_SLOT = 1;
    static final int FUEL_SLOT = 7;
    /** The five panes between the two slots draw the buffer as a bar. */
    private static final int BAR_FIRST = 2, BAR_LAST = 6;

    /** A station's block. */
    record Pos(UUID world, int x, int y, int z) {
        static Pos of(Block b) {
            return new Pos(b.getWorld().getUID(), b.getX(), b.getY(), b.getZ());
        }
    }

    final Pos pos;
    final ChargingStation logic = new ChargingStation();
    private final StationManager manager;
    private final Inventory inventory;
    /** Charge moved into the radar on the last tick. */
    boolean charging;
    /** Broken or unloaded; a pending task must leave it alone. */
    boolean gone;

    Station(StationManager manager, Pos pos) {
        this.manager = manager;
        this.pos = pos;
        this.inventory = Bukkit.createInventory(this, SIZE, Component.text("Charging Station"));
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }

    ItemStack radar() {
        return inventory.getItem(RADAR_SLOT);
    }

    ItemStack fuel() {
        return inventory.getItem(FUEL_SLOT);
    }

    void setRadar(ItemStack item) {
        inventory.setItem(RADAR_SLOT, item);
    }

    void setFuel(ItemStack item) {
        inventory.setItem(FUEL_SLOT, item);
    }

    @Override
    public void click(InventoryClickEvent e) {
        InventoryAction action = e.getAction();
        int raw = e.getRawSlot();
        if (raw < 0) return; // outside the window: dropping what the cursor holds
        // Double-click collecting could pull panes out of the station.
        if (action == InventoryAction.COLLECT_TO_CURSOR || action == InventoryAction.UNKNOWN) {
            e.setCancelled(true);
            return;
        }
        if (raw >= SIZE) {
            if (action == InventoryAction.MOVE_TO_OTHER_INVENTORY && e.getWhoClicked() instanceof Player p) {
                e.setCancelled(true);
                quickMove(p, e.getSlot());
            }
            return;
        }
        if (raw != RADAR_SLOT && raw != FUEL_SLOT) {
            e.setCancelled(true);
            return;
        }
        ItemStack current = e.getCurrentItem();
        int inSlot = current == null || current.getType().isAir() ? 0 : current.getAmount();
        ItemStack incoming;
        int resulting; // how many the slot would then hold
        switch (action) {
            case NOTHING, PICKUP_ALL, PICKUP_SOME, PICKUP_HALF, PICKUP_ONE, DROP_ALL_SLOT, DROP_ONE_SLOT, MOVE_TO_OTHER_INVENTORY,
                    CLONE_STACK -> {
                manager.changedSoon(this); // takes out at most: nothing to check
                return;
            }
            case PLACE_ONE -> {
                incoming = e.getCursor();
                resulting = inSlot + 1;
            }
            case PLACE_ALL, PLACE_SOME -> {
                incoming = e.getCursor();
                resulting = inSlot + (incoming == null ? 0 : incoming.getAmount());
            }
            case SWAP_WITH_CURSOR -> {
                incoming = e.getCursor();
                resulting = incoming == null ? 0 : incoming.getAmount();
            }
            case HOTBAR_SWAP, HOTBAR_MOVE_AND_READD -> {
                incoming = e.getWhoClicked() instanceof Player p ? hotbarItem(p, e.getHotbarButton()) : null;
                resulting = incoming == null ? 0 : incoming.getAmount();
            }
            default -> {
                // Any other action could put an unchecked item in: those newer than the 1.20.1 API
                // this is compiled against, such as 1.21.2's PLACE_FROM_BUNDLE, all land here.
                e.setCancelled(true);
                return;
            }
        }
        if (incoming != null && !incoming.getType().isAir() && !accepts(raw, incoming, resulting)) {
            e.setCancelled(true);
            return;
        }
        manager.changedSoon(this);
    }

    @Override
    public void drag(InventoryDragEvent e) {
        boolean touchesStation = false;
        for (int raw : e.getRawSlots()) {
            if (raw >= SIZE) continue;
            if ((raw != RADAR_SLOT && raw != FUEL_SLOT) || !accepts(raw, e.getOldCursor(), e.getNewItems().get(raw).getAmount())) {
                e.setCancelled(true);
                return;
            }
            touchesStation = true;
        }
        if (touchesStation) manager.changedSoon(this);
    }

    @Override
    public void closed(Player p) {
        manager.save(this);
    }

    private static ItemStack hotbarItem(Player p, int button) {
        PlayerInventory inv = p.getInventory();
        // -1: the swap-with-off-hand key.
        return button >= 0 ? inv.getItem(button) : inv.getItemInOffHand();
    }

    /** @param resulting how many items the slot would hold afterwards */
    private boolean accepts(int slot, ItemStack item, int resulting) {
        return slot == RADAR_SLOT ? RadarItems.isRadar(item) && resulting == 1 : manager.rules().fuelValue(item) > 0;
    }

    /** Shift-click from the player's inventory: one radar to the radar slot, fuel onto the fuel slot. */
    private void quickMove(Player p, int playerSlot) {
        PlayerInventory inv = p.getInventory();
        ItemStack item = inv.getItem(playerSlot);
        if (item == null || item.getType().isAir()) return;
        if (RadarItems.isRadar(item)) {
            if (radar() != null && !radar().getType().isAir()) return;
            ItemStack one = item.clone();
            one.setAmount(1);
            setRadar(one);
            item.setAmount(item.getAmount() - 1);
            inv.setItem(playerSlot, item.getAmount() > 0 ? item : null);
        } else if (manager.rules().fuelValue(item) > 0) {
            ItemStack fuel = fuel();
            if (fuel == null || fuel.getType().isAir()) {
                setFuel(item);
                inv.setItem(playerSlot, null);
            } else if (fuel.isSimilar(item)) {
                int moved = Math.min(item.getAmount(), fuel.getMaxStackSize() - fuel.getAmount());
                if (moved <= 0) return;
                fuel.setAmount(fuel.getAmount() + moved);
                setFuel(fuel);
                item.setAmount(item.getAmount() - moved);
                inv.setItem(playerSlot, item.getAmount() > 0 ? item : null);
            } else {
                return;
            }
        } else {
            return;
        }
        manager.changedSoon(this);
    }

    /** Draws the panes: the buffer in every pane's name, a bar across the middle five. */
    void renderPanes(ServerRules r) {
        ServerConfig c = r.config();
        int capacity = r.station().bufferCapacity();
        int buffer = logic.buffer();
        double fill = buffer / (double) capacity;
        Component name = Texts.item("Buffer: " + Texts.number(buffer) + " / " + Texts.number(capacity) + " ⚡", Texts.ENERGY);
        List<Component> lore = new ArrayList<>();
        lore.add(Texts.item(Texts.bar(fill, 20) + " " + Texts.percent(buffer, capacity) + "%", Texts.ENERGY));
        RadarItems.RadarData radar = RadarItems.read(radar());
        if (radar == null) {
            lore.add(Texts.item("Put a radar in the left slot", Texts.MUTED));
        } else {
            int charge = radar.charge(c.capacity());
            String state = charging ? " · charging" : charge >= c.capacity() ? " · full" : buffer == 0 ? " · needs fuel" : "";
            lore.add(Texts.item("Radar " + Texts.charge(charge, c.capacity()) + state, Texts.ACCENT));
        }
        lore.add(Texts.item("Fuel goes in the right slot (charge per item):", Texts.MUTED));
        for (FuelEntry f : r.fuelEntries()) lore.add(Texts.item("  " + fuelName(f) + "  " + Texts.number(f.value()), Texts.HINT));
        int lit = (int) Math.ceil(fill * (BAR_LAST - BAR_FIRST + 1) - 1e-9);
        for (int slot = 0; slot < SIZE; slot++) {
            if (slot == RADAR_SLOT || slot == FUEL_SLOT) continue;
            Material glass;
            if (slot < BAR_FIRST || slot > BAR_LAST) glass = Material.BLACK_STAINED_GLASS_PANE;
            else if (slot - BAR_FIRST < lit) glass = charging ? Material.YELLOW_STAINED_GLASS_PANE : Material.LIME_STAINED_GLASS_PANE;
            else glass = Material.GRAY_STAINED_GLASS_PANE;
            ItemStack pane = new ItemStack(glass);
            ItemMeta meta = pane.getItemMeta();
            meta.displayName(name);
            meta.lore(lore);
            pane.setItemMeta(meta);
            inventory.setItem(slot, pane);
        }
    }

    /** "minecraft:redstone" → "redstone"; tags and other namespaces stay as written. */
    private static String fuelName(FuelEntry f) {
        String id = f.item().toString();
        return id.startsWith("minecraft:") ? id.substring("minecraft:".length()).replace('_', ' ').toLowerCase(Locale.ROOT) : id;
    }

    /** Whether one more tick would change anything: fuel that fits, or a radar to fill from a non-empty buffer. */
    boolean hasWork(ServerRules r) {
        ChargingStation.Settings s = r.station();
        int fuelValue = r.fuelValue(fuel());
        if (fuelValue > 0 && (long) logic.buffer() + fuelValue <= s.bufferCapacity()) return true;
        RadarItems.RadarData radar = RadarItems.read(radar());
        return radar != null && logic.buffer() > 0 && radar.charge(s.radarCapacity()) < s.radarCapacity();
    }
}
