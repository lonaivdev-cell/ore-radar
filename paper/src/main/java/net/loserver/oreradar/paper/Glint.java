package net.loserver.oreradar.paper;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

import org.bukkit.NamespacedKey;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.meta.ItemMeta;

/**
 * The enchantment shimmer that marks the selected tab and the tracked entry in the chest GUI, and
 * that a radar must not have. Paper 1.20.5 and later set it directly
 * ({@code ItemMeta#setEnchantmentGlintOverride}, reached by reflection since the 1.20.1 API lacks
 * it); 1.20.1 gets a hidden enchantment instead, by key, because the enchantment constants were
 * renamed in 1.20.5.
 */
final class Glint {
    private static final Method OVERRIDE = find();

    private Glint() {}

    static void apply(ItemMeta meta) {
        if (OVERRIDE != null) {
            override(meta, true);
            return;
        }
        Enchantment unbreaking = Enchantment.getByKey(NamespacedKey.minecraft("unbreaking"));
        if (unbreaking == null) return;
        meta.addEnchant(unbreaking, 1, true);
        meta.addItemFlags(ItemFlag.HIDE_ENCHANTS);
    }

    /**
     * No shimmer, even where the item would have one: a compass pointing at a lodestone, which a
     * radar's needle is (DESIGN §8 "Arrow"). 1.20.1 has no way to turn it off.
     */
    static void remove(ItemMeta meta) {
        if (OVERRIDE != null) override(meta, false);
    }

    private static void override(ItemMeta meta, boolean glint) {
        try {
            OVERRIDE.invoke(meta, glint);
        } catch (IllegalAccessException | InvocationTargetException e) {
            throw new IllegalStateException("ItemMeta#setEnchantmentGlintOverride failed", e);
        }
    }

    private static Method find() {
        try {
            return ItemMeta.class.getMethod("setEnchantmentGlintOverride", Boolean.class);
        } catch (NoSuchMethodException e) {
            return null;
        }
    }
}
