package net.loserver.oreradar.paper;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.InventoryHolder;

/**
 * A chest GUI of this plugin, recognised by its inventory holder. The events give the top
 * inventory directly ({@code event.getInventory()}); this plugin never touches
 * {@code InventoryView}, which turned from a class into an interface in 1.21 and so breaks code
 * compiled against 1.20.1. The holder is asked for without a block snapshot
 * ({@code getHolder(false)}): these events fire for every chest, furnace and hopper on the server,
 * and our menus are plugin objects, never blocks.
 */
interface Menu extends InventoryHolder {
    void click(InventoryClickEvent e);

    void drag(InventoryDragEvent e);

    void closed(Player p);

    /** Routes inventory events to the menu they belong to. */
    final class Events implements Listener {
        @EventHandler(priority = EventPriority.LOW)
        public void onClick(InventoryClickEvent e) {
            if (e.getInventory().getHolder(false) instanceof Menu m) m.click(e);
        }

        @EventHandler(priority = EventPriority.LOW)
        public void onDrag(InventoryDragEvent e) {
            if (e.getInventory().getHolder(false) instanceof Menu m) m.drag(e);
        }

        @EventHandler
        public void onClose(InventoryCloseEvent e) {
            if (e.getInventory().getHolder(false) instanceof Menu m && e.getPlayer() instanceof Player p) m.closed(p);
        }
    }
}
