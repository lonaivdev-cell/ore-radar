package net.loserver.oreradar.paper;

import java.util.Locale;

import org.bukkit.Material;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.loserver.oreradar.core.config.CategoryDef;
import net.loserver.oreradar.core.designation.TargetRules;
import net.loserver.oreradar.core.designation.TargetSpec;
import net.loserver.oreradar.core.net.ConfigPacket;

/**
 * Text shown to vanilla clients. Block names are translation keys every client knows; everything
 * else is English, since a vanilla client has no Ore Radar language file.
 */
final class Texts {
    static final TextColor ACCENT = NamedTextColor.AQUA;
    static final TextColor MUTED = NamedTextColor.GRAY;
    static final TextColor HINT = NamedTextColor.DARK_GRAY;
    static final TextColor WARN = NamedTextColor.RED;
    static final TextColor ENERGY = NamedTextColor.YELLOW;

    private Texts() {}

    /** Text for item names and lore, which Minecraft italicises unless told not to. */
    static Component item(String text, TextColor color) {
        return Component.text(text, color).decoration(TextDecoration.ITALIC, false);
    }

    static Component item(Component text, TextColor color) {
        return text.color(color).decoration(TextDecoration.ITALIC, false);
    }

    /** 12345 → "12,345", the way DESIGN writes charge. */
    static String number(long n) {
        return String.format(Locale.ROOT, "%,d", n);
    }

    static Component blockName(Material block) {
        return Component.translatable(PaperBlockLookup.nameKeyOf(block));
    }

    /**
     * What a target is called: the block (a group's representative) for blocks and groups, the tag
     * id for tags, or the raw spec when this server cannot resolve it.
     */
    static Component targetName(TargetRules<Material> rules, String spec) {
        TargetRules.Resolution<Material> r = rules.resolve(spec);
        if (r.representative() == null) return Component.text(spec);
        if (r.spec() != null && r.spec().kind() == TargetSpec.Kind.TAG) return Component.text(r.spec().toString());
        return blockName(r.representative());
    }

    /**
     * A category tab's name. Built-in tabs have translation keys, which only the mod's language
     * files know, so a vanilla client shows the English fallback.
     */
    static Component categoryName(ConfigPacket.Category c) {
        if (!c.nameIsTranslationKey()) return Component.text(c.name());
        CategoryDef builtIn = CategoryDef.builtIn(c.id());
        return builtIn == null ? Component.text(c.id()) : Component.translatable(c.name(), builtIn.name());
    }

    /** A catalog entry's name, as the config packet carries it. */
    static Component entryName(ConfigPacket.CatalogEntry e) {
        return e.nameIsTranslationKey() ? Component.translatable(e.name()) : Component.text(e.name());
    }

    /** "⚡ 31,200 / 36,000" */
    static String charge(int charge, int capacity) {
        return "⚡ " + number(charge) + " / " + number(capacity);
    }

    /** A text bar of {@code width} cells, filled to {@code fraction}. */
    static String bar(double fraction, int width) {
        int filled = (int) Math.round(Math.max(0, Math.min(1, fraction)) * width);
        return "▮".repeat(filled) + "▯".repeat(width - filled);
    }

    static int percent(long value, long max) {
        return max <= 0 ? 0 : (int) Math.round(100.0 * Math.max(0, Math.min(max, value)) / max);
    }
}
