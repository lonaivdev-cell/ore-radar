package net.loserver.oreradar.paper;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemFactory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.CompassMeta;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.loserver.oreradar.core.config.ServerConfig;
import net.loserver.oreradar.core.energy.ChargeMath;

/**
 * The radar and charging-station items (DESIGN §3 "Item data", §8): a {@code minecraft:compass}
 * marked {@code oreradar:radar=1} and a {@code minecraft:lodestone} marked
 * {@code oreradar:station=1}, with their data in the item's persistent data container. Every write
 * re-renders the lore in the same meta update, so the item changes (and syncs) once.
 *
 * <p>A vanilla client replays the hand's equip animation whenever the held item changes, so the
 * radar shows its charge coarsely: the lore in 5% steps ({@link #chargeStep}), which is all the
 * plugin needs to write while the radar is in use (see {@code RadarService}). From 1.20.5 the item
 * also shows a durability-style bar (any item may carry {@code max_damage}); from 1.21.11 the
 * client ignores {@code damage} when deciding whether to replay the animation, so there the bar
 * follows the charge live ({@link #showLiveCharge}).
 */
final class RadarItems {
    /** Lets a server's own resource pack give the radar a model of its own (DESIGN §8). */
    static final int CUSTOM_MODEL_DATA = 7701;
    private static final byte TRUE = 1;
    /** The lore shows the charge in this many steps (5% each). */
    static final int CHARGE_STEPS = 20;
    /** 1.21.11's data version: its client no longer replays the equip animation when only {@code damage} changed. */
    private static final int DATA_VERSION_1_21_11 = 4671;
    /** {@code ItemMeta#setMaxStackSize}, from 1.20.5; null on older servers (the 1.20.1 API lacks it). */
    private static final Method SET_MAX_STACK_SIZE = setter(ItemMeta.class, "setMaxStackSize");
    /** {@code Damageable#setMaxDamage}, from 1.20.5; null before, where a compass cannot show a bar. */
    private static final Method SET_MAX_DAMAGE = setter(Damageable.class, "setMaxDamage");
    /** The radar shows its charge as an item bar (DESIGN §3 "Durability bar"). */
    static final boolean BAR = SET_MAX_STACK_SIZE != null && SET_MAX_DAMAGE != null;
    /** The bar may change while the radar is held without the client replaying the equip animation. */
    static final boolean LIVE_BAR = BAR && dataVersion() >= DATA_VERSION_1_21_11;

    /**
     * A radar's data as stored on the item.
     *
     * @param id           the radar's own id, null on one made from the recipe's template without a craft event (a crafter)
     * @param spec         the target spec, null when none
     * @param storedCharge null when missing, which means full
     */
    record RadarData(Long id, String spec, boolean off, Integer storedCharge) {
        int charge(int capacity) {
            return ChargeMath.read(storedCharge, capacity);
        }
    }

    private RadarItems() {}

    static boolean isRadar(ItemStack stack) {
        return stack != null && stack.getType() == Material.COMPASS && stack.hasItemMeta()
                && marked(stack.getItemMeta().getPersistentDataContainer(), Keys.RADAR);
    }

    static boolean isStation(ItemStack stack) {
        return stack != null && stack.getType() == Material.LODESTONE && stack.hasItemMeta()
                && marked(stack.getItemMeta().getPersistentDataContainer(), Keys.STATION);
    }

    /** The radar data, or null if the stack is not a radar. */
    static RadarData read(ItemStack stack) {
        if (stack == null || stack.getType() != Material.COMPASS || !stack.hasItemMeta()) return null;
        PersistentDataContainer pdc = stack.getItemMeta().getPersistentDataContainer();
        if (!marked(pdc, Keys.RADAR)) return null;
        String spec = pdc.get(Keys.TARGET, PersistentDataType.STRING);
        return new RadarData(pdc.get(Keys.ID, PersistentDataType.LONG), spec == null || spec.isEmpty() ? null : spec,
                marked(pdc, Keys.OFF), pdc.get(Keys.CHARGE, PersistentDataType.INTEGER));
    }

    /**
     * A fresh radar: no target, on, full (no charge key, DESIGN §3), and with an id of its own.
     * Radars must not stack (DESIGN §3: stack size 1): a station would charge a whole stack for one
     * radar's energy, and one drain would pay for all of them. From 1.20.5 the item says so itself
     * ({@code max_stack_size} 1); before, a compass stacks to 64 and only the id keeps two fresh
     * radars apart.
     */
    static ItemStack newRadar(ServerRules rules) {
        return radar(rules, ThreadLocalRandom.current().nextLong());
    }

    /**
     * The radar recipe's result, without an id, since every craft would copy it: crafting replaces
     * it with a {@link #newRadar} (see {@code RadarListener#onPrepareCraft}).
     */
    static ItemStack recipeResult(ServerRules rules) {
        return radar(rules, null);
    }

    private static ItemStack radar(ServerRules rules, Long id) {
        ItemStack stack = new ItemStack(Material.COMPASS);
        ItemMeta meta = stack.getItemMeta();
        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        pdc.set(Keys.RADAR, PersistentDataType.BYTE, TRUE);
        if (id != null) pdc.set(Keys.ID, PersistentDataType.LONG, id);
        meta.displayName(Texts.item("Ore Radar", Texts.ACCENT));
        meta.setCustomModelData(CUSTOM_MODEL_DATA);
        render(meta, new RadarData(id, null, false, null), rules);
        stack.setItemMeta(meta);
        return stack;
    }

    /** Sets or (with null) clears the target. Choosing a target also switches the radar on, as in 1.0. */
    static void setTarget(ItemStack radar, String spec, ServerRules rules) {
        edit(radar, rules, (pdc, d) -> {
            if (spec == null) {
                pdc.remove(Keys.TARGET);
                return new RadarData(d.id(), null, d.off(), d.storedCharge());
            }
            pdc.set(Keys.TARGET, PersistentDataType.STRING, spec);
            pdc.remove(Keys.OFF);
            return new RadarData(d.id(), spec, false, d.storedCharge());
        });
    }

    static void setOff(ItemStack radar, boolean off, ServerRules rules) {
        edit(radar, rules, (pdc, d) -> {
            if (off) pdc.set(Keys.OFF, PersistentDataType.BYTE, TRUE);
            else pdc.remove(Keys.OFF);
            return new RadarData(d.id(), d.spec(), off, d.storedCharge());
        });
    }

    static void setCharge(ItemStack radar, int charge, ServerRules rules) {
        int value = Math.max(0, Math.min(rules.config().capacity(), charge));
        edit(radar, rules, (pdc, d) -> {
            pdc.set(Keys.CHARGE, PersistentDataType.INTEGER, value);
            return new RadarData(d.id(), d.spec(), d.off(), value);
        });
    }

    /** Re-renders the lore, e.g. after a reload renamed or forbade the target. */
    static void refresh(ItemStack radar, ServerRules rules) {
        edit(radar, rules, (pdc, d) -> d);
    }

    /**
     * Gives a radar without an id one (one made from the recipe's template by a crafter), so its
     * unwritten drain can follow it when it moves.
     */
    static long assignId(ItemStack radar, ServerRules rules) {
        long id = ThreadLocalRandom.current().nextLong();
        edit(radar, rules, (pdc, d) -> {
            pdc.set(Keys.ID, PersistentDataType.LONG, id);
            return new RadarData(id, d.spec(), d.off(), d.storedCharge());
        });
        return id;
    }

    /**
     * Which of the {@link #CHARGE_STEPS} steps the lore shows, rounded up: 20 is full down to 95%,
     * 1 is anything left at all, 0 empty. A radar in use is written when this changes.
     */
    static int chargeStep(int charge, int capacity) {
        if (capacity <= 0 || charge <= 0) return 0;
        return (int) ((Math.min(charge, capacity) * (long) CHARGE_STEPS + capacity - 1) / capacity);
    }

    /**
     * Shows {@code charge} on the bar alone, leaving the stored charge and the lore as they are,
     * where that cannot replay the equip animation ({@link #LIVE_BAR}).
     *
     * @return whether the item changed
     */
    static boolean showLiveCharge(ItemStack radar, int charge, ServerRules rules) {
        ServerConfig c = rules.config();
        if (!LIVE_BAR || !c.energyEnabled() || !(radar.getItemMeta() instanceof Damageable meta)) return false;
        int damage = c.capacity() - Math.max(0, Math.min(c.capacity(), charge));
        if (meta.getDamage() == damage) return false;
        meta.setDamage(damage);
        radar.setItemMeta(meta);
        return true;
    }

    /**
     * Points the compass needle at {@code target} (DESIGN §8 "Arrow"), or with null restores a
     * plain compass. Untracked, so the client does not look for a lodestone block there.
     *
     * @return whether the item changed; unchanged positions are not rewritten, since every write
     *         makes a vanilla client replay the equip animation
     */
    static boolean setLodestone(ItemStack radar, Location target) {
        if (!(radar.getItemMeta() instanceof CompassMeta meta)) return false;
        if (target == null) {
            // CompassMeta cannot drop lodestone data once set: from 1.20.5 it keeps an empty
            // lodestone_tracker, which makes the needle spin and the item glint. A round trip
            // through a meta without compass fields keeps everything else and drops that.
            ItemFactory factory = Bukkit.getItemFactory();
            ItemMeta plain = factory.asMetaFor(factory.asMetaFor(meta, Material.STONE), Material.COMPASS);
            if (plain.equals(meta)) return false;
            radar.setItemMeta(plain);
            return true;
        }
        if (meta.hasLodestone() && sameBlock(meta.getLodestone(), target) && !meta.isLodestoneTracked()) return false;
        meta.setLodestone(target);
        meta.setLodestoneTracked(false);
        // A lodestone compass shimmers like an enchanted item; a radar would whenever it points.
        Glint.remove(meta);
        radar.setItemMeta(meta);
        return true;
    }

    /** A station item carrying {@code buffer} charge (DESIGN §8: breaking a station keeps its buffer). */
    static ItemStack newStation(int buffer, ServerRules rules) {
        ItemStack stack = new ItemStack(Material.LODESTONE);
        ItemMeta meta = stack.getItemMeta();
        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        pdc.set(Keys.STATION, PersistentDataType.BYTE, TRUE);
        if (buffer > 0) pdc.set(Keys.BUFFER, PersistentDataType.INTEGER, buffer);
        meta.displayName(Texts.item("Charging Station", NamedTextColor.GOLD));
        int capacity = rules.config().bufferCapacity();
        meta.lore(List.of(
                Texts.item("Buffer: " + Texts.number(Math.min(buffer, capacity)) + " / " + Texts.number(capacity) + " ⚡", Texts.ENERGY),
                Texts.item("Place it, then right-click it to charge a radar", Texts.HINT)));
        stack.setItemMeta(meta);
        return stack;
    }

    static int stationBuffer(ItemStack station) {
        Integer v = station.getItemMeta().getPersistentDataContainer().get(Keys.BUFFER, PersistentDataType.INTEGER);
        return v == null ? 0 : Math.max(0, v);
    }

    @FunctionalInterface
    private interface Change {
        RadarData apply(PersistentDataContainer pdc, RadarData before);
    }

    private static void edit(ItemStack radar, ServerRules rules, Change change) {
        RadarData before = read(radar);
        if (before == null) return;
        ItemMeta meta = radar.getItemMeta();
        RadarData after = change.apply(meta.getPersistentDataContainer(), before);
        render(meta, after, rules);
        radar.setItemMeta(meta);
    }

    private static void render(ItemMeta meta, RadarData d, ServerRules rules) {
        ServerConfig c = rules.config();
        List<Component> lore = new ArrayList<>();
        if (d.spec() == null) {
            lore.add(Texts.item("No target", Texts.MUTED));
        } else {
            boolean allowed = rules.rules().resolve(d.spec()).ok();
            Component name = Texts.targetName(rules.rules(), d.spec());
            lore.add(Texts.item(Component.text("Target: ").append(name.color(allowed ? Texts.ACCENT : Texts.WARN)), Texts.MUTED));
            if (!allowed) lore.add(Texts.item("Not trackable on this server", Texts.WARN));
        }
        int charge = d.charge(c.capacity());
        if (c.energyEnabled()) {
            // Coarse on purpose: a radar in use is only written when this line changes.
            if (charge <= 0) {
                lore.add(Texts.item("⚡ Empty: charge it at a Charging Station", Texts.WARN));
            } else {
                long minutes = ChargeMath.minutesLeft(charge, c.drainPerSecond());
                String left = minutes < 0 ? "" : " · about " + Texts.number(minutes) + " min left";
                lore.add(Texts.item("⚡ " + chargeStep(charge, c.capacity()) * (100 / CHARGE_STEPS) + "%" + left, Texts.ENERGY));
            }
        }
        if (d.off()) lore.add(Texts.item("Off", Texts.WARN));
        lore.add(Texts.item("Right-click: choose a target", Texts.HINT));
        lore.add(Texts.item("Sneak + right-click a block: track it", Texts.HINT));
        lore.add(Texts.item("Sneak + right-click the air: switch on or off", Texts.HINT));
        meta.lore(lore);
        // Each write also makes sure a radar written before 1.20.5 (or before a reload changed the capacity) does not stack and shows the bar.
        if (SET_MAX_STACK_SIZE != null) invoke(SET_MAX_STACK_SIZE, meta, 1);
        if (BAR && meta instanceof Damageable bar) {
            // max_damage needs max_stack_size 1, set just above: a damageable item that stacks is invalid.
            boolean shown = c.energyEnabled() && c.capacity() > 0;
            invoke(SET_MAX_DAMAGE, meta, shown ? c.capacity() : null);
            bar.setDamage(shown ? c.capacity() - Math.min(charge, c.capacity()) : 0);
        }
    }

    /** A setter that takes an Integer, or null if this server's API lacks it. */
    private static Method setter(Class<?> type, String name) {
        try {
            return type.getMethod(name, Integer.class);
        } catch (NoSuchMethodException e) {
            return null;
        }
    }

    private static void invoke(Method setter, ItemMeta meta, Integer value) {
        try {
            setter.invoke(meta, value);
        } catch (IllegalAccessException | InvocationTargetException e) {
            throw new IllegalStateException(setter.getDeclaringClass().getSimpleName() + "#" + setter.getName() + " failed", e);
        }
    }

    @SuppressWarnings("deprecation") // the only way a plugin can tell which client features the server's version has
    private static int dataVersion() {
        return Bukkit.getUnsafe().getDataVersion();
    }

    private static boolean marked(PersistentDataContainer pdc, NamespacedKey key) {
        Byte b = pdc.get(key, PersistentDataType.BYTE);
        return b != null && b == TRUE;
    }

    private static boolean sameBlock(Location a, Location b) {
        return a != null && b != null && a.getWorld() != null && a.getWorld().equals(b.getWorld())
                && a.getBlockX() == b.getBlockX() && a.getBlockY() == b.getBlockY() && a.getBlockZ() == b.getBlockZ();
    }
}
