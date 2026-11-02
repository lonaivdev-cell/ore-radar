package net.loserver.oreradar.paper;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.scheduler.BukkitTask;

import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

/**
 * The chest GUI's search (DESIGN §8): the menu closes, the player types the search into chat (or
 * {@code cancel}), and the menu reopens filtered. The typed line is never broadcast. Chat arrives
 * on Paper's async chat thread, so the answer is handed to the server thread; the prompt expires
 * after 30 seconds.
 */
final class SearchPrompt implements Listener {
    private static final long TIMEOUT_TICKS = 30 * 20;

    /** One open prompt; the instance identifies it, so a stale timeout never ends a newer prompt. */
    private static final class Pending {
        final int radarSlot;
        /** Set before the prompt is published to the chat thread. */
        BukkitTask timeout;

        Pending(int radarSlot) {
            this.radarSlot = radarSlot;
        }
    }

    private final OreRadarPlugin plugin;
    /** Read and removed on the chat thread, written on the server thread. */
    private final Map<UUID, Pending> pending = new ConcurrentHashMap<>();

    SearchPrompt(OreRadarPlugin plugin) {
        this.plugin = plugin;
    }

    void start(Player p, int radarSlot) {
        UUID id = p.getUniqueId();
        Pending prompt = new Pending(radarSlot);
        prompt.timeout = Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (pending.remove(id, prompt) && p.isOnline()) p.sendMessage(Component.text("Ore Radar search timed out.", Texts.MUTED));
        }, TIMEOUT_TICKS);
        Pending previous = pending.put(id, prompt);
        if (previous != null) previous.timeout.cancel();
        p.closeInventory();
        p.sendMessage(Component.text("Ore Radar search: type a name, or ", Texts.MUTED)
                .append(Component.text("cancel", Texts.ACCENT))
                .append(Component.text(". @namespace and #tag work too.", Texts.MUTED)));
    }

    /** Before other chat plugins, so the search never reaches them or the other players. */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onChat(AsyncChatEvent e) {
        Player p = e.getPlayer();
        Pending prompt = pending.remove(p.getUniqueId());
        if (prompt == null) return;
        e.setCancelled(true);
        String text = PlainTextComponentSerializer.plainText().serialize(e.message()).trim();
        Bukkit.getScheduler().runTask(plugin, () -> answer(p, prompt, text));
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        Pending prompt = pending.remove(e.getPlayer().getUniqueId());
        if (prompt != null) prompt.timeout.cancel();
    }

    /** Drops every prompt (plugin disable). */
    void clear() {
        pending.values().forEach(p -> p.timeout.cancel());
        pending.clear();
    }

    private void answer(Player p, Pending prompt, String text) {
        prompt.timeout.cancel();
        if (!p.isOnline()) return;
        if (!RadarItems.isRadar(p.getInventory().getItem(prompt.radarSlot))) {
            p.sendMessage(Component.text("That radar is no longer where it was.", Texts.WARN));
            return;
        }
        if (text.isEmpty() || text.equalsIgnoreCase("cancel")) {
            p.sendMessage(Component.text("Search cancelled.", Texts.MUTED));
            RadarMenu.open(plugin, p, prompt.radarSlot, null);
            return;
        }
        String query = text.length() > RadarMenu.MAX_QUERY_LENGTH ? text.substring(0, RadarMenu.MAX_QUERY_LENGTH) : text;
        RadarMenu.open(plugin, p, prompt.radarSlot, query);
    }
}
