package net.loserver.oreradar.paper;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.loserver.oreradar.core.designation.Designation;
import net.loserver.oreradar.core.designation.TargetRules;
import net.loserver.oreradar.core.net.ConfigPacket;

/**
 * The radar screen for vanilla clients (DESIGN §8 "GUI"): a paged chest. The top row holds "All"
 * and the category tabs, the middle four rows the catalog, the bottom row previous / next page,
 * power, the current target, clear and search. One instance per opening, for the radar in one slot;
 * every click is cancelled and handled on the next tick, where opening and closing inventories
 * is safe.
 */
final class RadarMenu implements Menu {
    private static final int SIZE = 54;
    private static final int TABS = 9;
    private static final int FIRST_ENTRY = 9;
    private static final int PAGE_SIZE = 36;
    private static final int PREV = 45, POWER = 47, CURRENT = 48, CLEAR = 49, SEARCH = 50, NEXT = 53;
    /** Longer searches are not names; also keeps the title short. */
    static final int MAX_QUERY_LENGTH = 48;

    private final OreRadarPlugin plugin;
    private final int radarSlot;
    private final String query;
    private final Inventory inventory;
    /** Index into the rules' categories; -1 for "All". */
    private int category = -1;
    /** First category shown in the tab row when there are more than fit. */
    private int tabOffset;
    private int page;
    private List<ConfigPacket.CatalogEntry> shown = List.of();

    private RadarMenu(OreRadarPlugin plugin, int radarSlot, String query) {
        this.plugin = plugin;
        this.radarSlot = radarSlot;
        this.query = query;
        Component title = Component.text("Ore Radar");
        if (query != null) title = title.append(Component.text(" · \"" + query + "\""));
        this.inventory = Bukkit.createInventory(this, SIZE, title);
    }

    /** Opens the menu for the radar in {@code radarSlot}, filtered by {@code query} when not null. */
    static void open(OreRadarPlugin plugin, Player p, int radarSlot, String query) {
        RadarMenu menu = new RadarMenu(plugin, radarSlot, query);
        menu.render(p);
        p.openInventory(menu.inventory);
        plugin.openMenus().add(menu);
    }

    void closeAll() {
        for (HumanEntity viewer : new ArrayList<>(inventory.getViewers())) viewer.closeInventory();
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }

    @Override
    public void click(InventoryClickEvent e) {
        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player p)) return;
        int raw = e.getRawSlot();
        if (raw < 0 || raw >= SIZE || !acts(e.getClick())) return;
        boolean right = e.getClick().isRightClick();
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (inventory.getViewers().contains(p)) handle(p, raw, right);
        });
    }

    /**
     * Plain and shift clicks act. A double-click arrives as two left clicks and then a
     * DOUBLE_CLICK, which {@code isLeftClick()} also counts: acting on that as well would turn a
     * third page, or toggle the power a third time. CREATIVE is a creative client writing a slot,
     * not a click.
     */
    private static boolean acts(ClickType click) {
        return switch (click) {
            case LEFT, SHIFT_LEFT, RIGHT, SHIFT_RIGHT -> true;
            default -> false;
        };
    }

    @Override
    public void drag(InventoryDragEvent e) {
        e.setCancelled(true);
    }

    /** Nothing to save: the radar item holds all state, and every open builds a new menu. */
    @Override
    public void closed(Player p) {
        plugin.openMenus().remove(this);
    }

    private void handle(Player p, int raw, boolean rightClick) {
        ServerRules r = plugin.rules();
        ItemStack radar = p.getInventory().getItem(radarSlot);
        if (!p.hasPermission(RadarService.USE_PERMISSION)) {
            p.closeInventory();
            p.sendMessage(Component.text("You may not use the Ore Radar here.", Texts.WARN));
            return;
        }
        if (!RadarItems.isRadar(radar)) {
            p.closeInventory();
            p.sendMessage(Component.text("That radar is no longer where it was.", Texts.WARN));
            return;
        }
        List<ConfigPacket.Category> cats = r.rules().categories();
        if (raw < TABS) {
            int first = hasAllTab(r.rules()) ? 1 : 0;
            if (raw < first) {
                category = -1;
            } else if (scrollsTabs(cats) && raw == TABS - 1) {
                tabOffset += TABS - 2;
                if (tabOffset >= cats.size()) tabOffset = 0;
            } else if (tabOffset + raw - first < cats.size()) {
                category = tabOffset + raw - first;
            }
            page = 0;
        } else if (raw < FIRST_ENTRY + PAGE_SIZE) {
            int index = page * PAGE_SIZE + raw - FIRST_ENTRY;
            if (index < shown.size()) {
                track(p, radar, shown.get(index), r);
                return;
            }
        } else if (raw == PREV) {
            page = Math.max(0, page - 1);
        } else if (raw == NEXT) {
            page++;
        } else if (raw == POWER) {
            RadarItems.RadarData d = RadarItems.read(radar);
            RadarItems.setOff(radar, !d.off(), r);
            p.getInventory().setItem(radarSlot, radar);
        } else if (raw == CLEAR) {
            RadarItems.setTarget(radar, null, r);
            p.getInventory().setItem(radarSlot, radar);
        } else if (raw == SEARCH) {
            if (rightClick && query != null) open(plugin, p, radarSlot, null);
            else plugin.search().start(p, radarSlot);
            return;
        }
        render(p);
    }

    private void track(Player p, ItemStack radar, ConfigPacket.CatalogEntry entry, ServerRules r) {
        TargetRules.Resolution<Material> res = r.rules().resolve(entry.spec());
        if (!res.ok()) {
            p.sendMessage(Component.text("That target is no longer allowed here.", Texts.WARN));
            render(p);
            return;
        }
        RadarItems.setTarget(radar, entry.spec(), r);
        p.getInventory().setItem(radarSlot, radar);
        p.closeInventory();
        plugin.radars().message(p, Component.text("Tracking ", Texts.MUTED).append(Texts.entryName(entry).color(Texts.ACCENT)));
    }

    private void render(Player p) {
        ServerRules r = plugin.rules();
        List<ConfigPacket.Category> cats = r.rules().categories();
        boolean all = hasAllTab(r.rules());
        if (!all) category = 0;
        else if (category >= cats.size()) category = -1;
        RadarItems.RadarData radar = RadarItems.read(p.getInventory().getItem(radarSlot));
        String tracked = radar == null ? null : radar.spec();
        shown = filter(r, cats);
        int pages = Math.max(1, (shown.size() + PAGE_SIZE - 1) / PAGE_SIZE);
        page = Math.min(page, pages - 1);
        inventory.clear();

        int first = all ? 1 : 0;
        if (all) inventory.setItem(0, tab(Material.COMPASS, Component.text("All"), category < 0));
        boolean scroll = scrollsTabs(cats);
        int tabSlots = TABS - first - (scroll ? 1 : 0);
        for (int i = 0; i < tabSlots && tabOffset + i < cats.size(); i++) {
            ConfigPacket.Category c = cats.get(tabOffset + i);
            inventory.setItem(first + i, tab(icon(c.iconBlockId()), Texts.categoryName(c), tabOffset + i == category));
        }
        if (scroll) {
            int last = Math.min(cats.size(), tabOffset + tabSlots);
            inventory.setItem(TABS - 1, button(Material.ARROW, "More categories ▶",
                    "Showing " + (tabOffset + 1) + "–" + last + " of " + cats.size()));
        }

        for (int i = 0; i < PAGE_SIZE; i++) {
            int index = page * PAGE_SIZE + i;
            if (index >= shown.size()) break;
            ConfigPacket.CatalogEntry e = shown.get(index);
            inventory.setItem(FIRST_ENTRY + i, entry(e, e.spec().equals(tracked)));
        }
        if (shown.isEmpty()) {
            inventory.setItem(FIRST_ENTRY + 13, button(Material.PAPER, "Nothing to show",
                    query != null ? "Nothing here matches \"" + query + "\"" : "This server allows nothing in this tab"));
        }

        ItemStack filler = filler();
        for (int slot = PREV; slot < SIZE; slot++) inventory.setItem(slot, filler);
        String pageText = "Page " + (page + 1) + " of " + pages;
        if (page > 0) inventory.setItem(PREV, button(Material.ARROW, "◀ Previous page", pageText));
        if (page < pages - 1) inventory.setItem(NEXT, button(Material.ARROW, "Next page ▶", pageText));
        if (radar != null) {
            inventory.setItem(POWER, radar.off()
                    ? button(Material.GRAY_DYE, "Radar is off", "Click to switch it on")
                    : button(Material.LIME_DYE, "Radar is on", "Click to switch it off"));
            inventory.setItem(CURRENT, current(p, radar, r));
            if (tracked != null) inventory.setItem(CLEAR, button(Material.BARRIER, "Clear target", "The radar stops looking"));
        }
        inventory.setItem(SEARCH, query == null
                ? button(Material.OAK_SIGN, "Search", "Click, then type a name in chat", "or @namespace, or #tag")
                : button(Material.OAK_SIGN, "Search: \"" + query + "\"", "Click to search again", "Right-click to show everything"));
    }

    /** "All" would repeat a lone category that lists every entry, as the default (ores only) does; the mods leave it out too. */
    private static boolean hasAllTab(TargetRules<Material> rules) {
        return rules.categories().size() != 1 || !rules.catalog().stream().allMatch(e -> (e.categoryMask() & 1L) != 0);
    }

    private static boolean scrollsTabs(List<ConfigPacket.Category> cats) {
        return cats.size() > TABS - 1;
    }

    /** The catalog in the selected tab that matches the search: a name or id, {@code @namespace} or {@code #tag}. */
    private List<ConfigPacket.CatalogEntry> filter(ServerRules r, List<ConfigPacket.Category> cats) {
        String q = query == null ? null : query.toLowerCase(Locale.ROOT).trim();
        Set<Material> tagMembers = null;
        if (q != null && q.startsWith("#")) {
            TargetRules.Resolution<Material> tag = Designation.parse(q) == null ? null : r.rules().resolve(q);
            tagMembers = tag != null && tag.ok() ? tag.blocks() : Set.of();
        }
        List<ConfigPacket.CatalogEntry> out = new ArrayList<>();
        for (ConfigPacket.CatalogEntry e : r.rules().catalog()) {
            if (category >= 0 && (e.categoryMask() & (1L << category)) == 0) continue;
            if (q != null && !matches(e, q, tagMembers, r)) continue;
            out.add(e);
        }
        return out;
    }

    private static boolean matches(ConfigPacket.CatalogEntry e, String q, Set<Material> tagMembers, ServerRules r) {
        if (tagMembers != null) {
            for (Material m : r.rules().resolve(e.spec()).blocks()) {
                if (tagMembers.contains(m)) return true;
            }
            return false;
        }
        if (q.startsWith("@")) {
            String ns = q.substring(1);
            return e.iconBlockId().startsWith(ns.contains(":") ? ns : ns + ":");
        }
        // Server-side names are translation keys, so match ids: "iron ore" finds minecraft:iron_ore.
        String words = q.replace(' ', '_');
        String haystack = (e.spec() + " " + e.iconBlockId() + " " + e.name()).toLowerCase(Locale.ROOT);
        return haystack.contains(words);
    }

    private ItemStack current(Player p, RadarItems.RadarData radar, ServerRules r) {
        if (radar.spec() == null) return button(Material.COMPASS, "No target", "Pick one above");
        TargetRules.Resolution<Material> res = r.rules().resolve(radar.spec());
        Material icon = res.representative() == null ? Material.COMPASS : icon(res.representative().getKey().toString());
        ItemStack item = new ItemStack(icon);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Texts.item(Component.text("Tracking ").append(Texts.targetName(r.rules(), radar.spec()).color(Texts.ACCENT)),
                NamedTextColor.WHITE));
        List<Component> lore = new ArrayList<>();
        lore.add(Texts.item(radar.spec(), Texts.HINT));
        Component status = plugin.radars().statusFor(p, radarSlot);
        if (status != null) lore.add(status.decoration(TextDecoration.ITALIC, false));
        if (r.config().energyEnabled()) {
            lore.add(Texts.item(Texts.charge(plugin.radars().chargeOf(p, radarSlot), r.config().capacity()), Texts.ENERGY));
        }
        meta.lore(lore);
        meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
        item.setItemMeta(meta);
        return item;
    }

    private static ItemStack entry(ConfigPacket.CatalogEntry e, boolean tracked) {
        ItemStack item = new ItemStack(icon(e.iconBlockId()));
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Texts.item(Texts.entryName(e), NamedTextColor.WHITE));
        List<Component> lore = new ArrayList<>();
        lore.add(Texts.item(e.spec(), Texts.HINT));
        if (e.memberCount() > 1) lore.add(Texts.item("Finds " + e.memberCount() + " kinds of block", Texts.MUTED));
        lore.add(tracked ? Texts.item("✔ Tracking", NamedTextColor.GREEN) : Texts.item("Click to track", Texts.MUTED));
        meta.lore(lore);
        meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
        if (tracked) Glint.apply(meta);
        item.setItemMeta(meta);
        return item;
    }

    private static ItemStack tab(Material icon, Component name, boolean selected) {
        ItemStack item = new ItemStack(icon);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Texts.item(name, selected ? Texts.ACCENT : NamedTextColor.WHITE));
        meta.lore(List.of(Texts.item(selected ? "Shown below" : "Click to show", Texts.MUTED)));
        meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
        if (selected) Glint.apply(meta);
        item.setItemMeta(meta);
        return item;
    }

    private static ItemStack button(Material icon, String name, String... lore) {
        ItemStack item = new ItemStack(icon);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Texts.item(name, NamedTextColor.WHITE));
        List<Component> lines = new ArrayList<>();
        for (String l : lore) lines.add(Texts.item(l, Texts.MUTED));
        meta.lore(lines);
        meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
        item.setItemMeta(meta);
        return item;
    }

    private static ItemStack filler() {
        ItemStack item = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text(" "));
        item.setItemMeta(meta);
        return item;
    }

    /**
     * An item that pictures a block. Blocks without an item form get the item that places them
     * (redstone wire → redstone) or a bucket for the fluids.
     */
    static Material icon(String blockId) {
        Material m = Material.matchMaterial(blockId);
        if (m == null) return Material.STRUCTURE_VOID;
        if (m.isItem() && !m.isAir()) return m;
        if (m == Material.WATER) return Material.WATER_BUCKET;
        if (m == Material.LAVA) return Material.LAVA_BUCKET;
        if (m == Material.POWDER_SNOW) return Material.POWDER_SNOW_BUCKET;
        Material placed = m.isBlock() ? m.createBlockData().getPlacementMaterial() : Material.AIR;
        return placed.isItem() && !placed.isAir() ? placed : Material.STRUCTURE_VOID;
    }
}
