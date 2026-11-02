package net.loserver.oreradar.paper;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Tag;

import net.loserver.oreradar.core.designation.BlockLookup;

/**
 * The server's blocks as core's rules see them: Bukkit {@link Material}s and the block tags of the
 * loaded datapacks ({@link Bukkit#getTag}). Built once per rules build, on the server thread.
 */
final class PaperBlockLookup implements BlockLookup<Material> {
    private final List<Material> blocks;
    private final Map<String, Material> byId;
    private final Map<Material, String> ids;

    PaperBlockLookup() {
        List<Material> list = new ArrayList<>();
        Map<String, Material> map = new HashMap<>();
        Map<Material, String> idMap = new EnumMap<>(Material.class);
        for (Material m : Material.values()) {
            // Checked by name first: asking a legacy constant anything else makes the server load
            // its legacy material tables and log a warning about it.
            if (m.name().startsWith("LEGACY_") || !m.isBlock()) continue;
            String id = m.getKey().toString();
            list.add(m);
            map.put(id, m);
            idMap.put(m, id);
        }
        this.blocks = Collections.unmodifiableList(list);
        this.byId = map;
        this.ids = idMap;
    }

    @Override
    public Iterable<Material> blocks() {
        return blocks;
    }

    @Override
    public Material block(String id) {
        return byId.get(id);
    }

    @Override
    public String id(Material block) {
        return ids.get(block);
    }

    @Override
    public Iterable<Material> tag(String tagId) {
        NamespacedKey key = NamespacedKey.fromString(tagId);
        Tag<Material> tag = key == null ? null : Bukkit.getTag(Tag.REGISTRY_BLOCKS, key, Material.class);
        return tag == null ? List.of() : tag.getValues();
    }

    @Override
    public Iterable<String> tagIds() {
        List<String> out = new ArrayList<>();
        for (Tag<Material> tag : Bukkit.getTags(Tag.REGISTRY_BLOCKS, Material.class)) out.add(tag.getKey().toString());
        return out;
    }

    @Override
    public boolean isAir(Material block) {
        return block.isAir();
    }

    @Override
    public boolean hasItem(Material block) {
        return block.isItem();
    }

    /** Vanilla's description id for a block, {@code block.<namespace>.<path>}, which every client can translate. */
    @Override
    public String nameKey(Material block) {
        return nameKeyOf(block);
    }

    static String nameKeyOf(Material block) {
        NamespacedKey key = block.getKey();
        return "block." + key.getNamespace() + "." + key.getKey().replace('/', '.');
    }
}
