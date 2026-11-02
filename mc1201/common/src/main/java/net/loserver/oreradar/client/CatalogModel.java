package net.loserver.oreradar.client;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;

import net.loserver.oreradar.OreRadar;
import net.loserver.oreradar.core.designation.TargetSpec;
import net.loserver.oreradar.core.net.ConfigPacket;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import org.jetbrains.annotations.Nullable;

/**
 * The server's catalog and category tabs as the radar screen shows them: names as components, block
 * ids as icons, specs indexed for lookup. Built once per config packet; the screen only reads it.
 */
public final class CatalogModel {
    public static final CatalogModel EMPTY = new CatalogModel(List.of(), List.of());

    /** A server category tab; {@code index} is its bit in each entry's category mask. */
    public record Category(int index, String id, Component name, ItemStack icon) {}

    /** One selectable target. */
    public static final class Entry {
        private final String spec;
        private final String canonicalSpec;
        private final Component name;
        private final ItemStack icon;
        @Nullable private final Block block;
        @Nullable private final BlockState previewBlock;
        private final String namespace;
        private final long categoryMask;
        private final int memberCount;
        /** The name in the language it was last searched in; names can be translation keys. */
        private String searchName;
        private String searchLanguage;

        private Entry(ConfigPacket.CatalogEntry source, @Nullable ResourceLocation iconId) {
            this.spec = source.spec();
            TargetSpec parsed = TargetSpec.parse(source.spec());
            this.canonicalSpec = parsed != null ? parsed.toString() : source.spec();
            this.name = displayName(source.name(), source.nameIsTranslationKey());
            this.block = iconId == null ? null : BuiltInRegistries.BLOCK.getOptional(iconId).orElse(null);
            this.icon = iconFor(block, source.iconBlockId());
            this.previewBlock = previewState(block);
            this.namespace = iconId != null ? iconId.getNamespace() : "";
            this.categoryMask = source.categoryMask();
            this.memberCount = source.memberCount();
        }

        /** What a select packet sends: the spec exactly as the server listed it. */
        public String spec() {
            return spec;
        }

        public Component name() {
            return name;
        }

        public ItemStack icon() {
            return icon;
        }

        /** The block to show in 3D in the preview, or null to show the icon flat (fluids, invisible blocks). */
        @Nullable
        public BlockState previewBlock() {
            return previewBlock;
        }

        /** The namespace of the block that stands for this entry: the mod shown in its tooltip. */
        public String namespace() {
            return namespace;
        }

        public int memberCount() {
            return memberCount;
        }

        public boolean inCategory(int index) {
            return index >= 0 && index < 64 && (categoryMask & (1L << index)) != 0;
        }

        private boolean matches(String word, Function<String, String> modNames) {
            if (word.startsWith("@")) {
                String mod = word.substring(1);
                return namespace.startsWith(mod) || modNames.apply(namespace).toLowerCase(Locale.ROOT).contains(mod);
            }
            if (word.startsWith("#")) {
                String tag = word.substring(1);
                if (canonicalSpec.startsWith("#") && canonicalSpec.contains(tag)) return true;
                return block != null && block.defaultBlockState().getTags().anyMatch(t -> t.location().toString().contains(tag));
            }
            return searchName().contains(word) || canonicalSpec.contains(word);
        }

        private String searchName() {
            String language = Minecraft.getInstance().getLanguageManager().getSelected();
            if (!language.equals(searchLanguage)) {
                searchName = name.getString().toLowerCase(Locale.ROOT);
                searchLanguage = language;
            }
            return searchName;
        }
    }

    private final List<Category> categories;
    private final List<Entry> entries;
    private final Map<String, Entry> byCanonicalSpec = new HashMap<>();

    private CatalogModel(List<Category> categories, List<Entry> entries) {
        this.categories = categories;
        this.entries = entries;
        for (Entry e : entries) byCanonicalSpec.putIfAbsent(e.canonicalSpec, e);
    }

    public static CatalogModel of(ConfigPacket packet) {
        List<Category> categories = new ArrayList<>(packet.categories().size());
        for (int i = 0; i < packet.categories().size(); i++) {
            ConfigPacket.Category c = packet.categories().get(i);
            ResourceLocation icon = ResourceLocation.tryParse(c.iconBlockId());
            Block block = icon == null ? null : BuiltInRegistries.BLOCK.getOptional(icon).orElse(null);
            categories.add(new Category(i, c.id(), displayName(c.name(), c.nameIsTranslationKey()), iconFor(block, c.iconBlockId())));
        }
        List<Entry> entries = new ArrayList<>(packet.catalog().size());
        for (ConfigPacket.CatalogEntry e : packet.catalog()) {
            entries.add(new Entry(e, ResourceLocation.tryParse(e.iconBlockId())));
        }
        return new CatalogModel(Collections.unmodifiableList(categories), Collections.unmodifiableList(entries));
    }

    public List<Category> categories() {
        return categories;
    }

    public List<Entry> entries() {
        return entries;
    }

    /** The entry for a spec in any spelling the server accepts, or null. */
    @Nullable
    public Entry find(@Nullable String spec) {
        if (spec == null) return null;
        TargetSpec parsed = TargetSpec.parse(spec);
        return byCanonicalSpec.get(parsed != null ? parsed.toString() : spec);
    }

    /** Entries for these specs, in their order, skipping any the server no longer offers. */
    public List<Entry> findAll(List<String> specs) {
        List<Entry> out = new ArrayList<>(specs.size());
        for (String spec : specs) {
            Entry e = find(spec);
            if (e != null) out.add(e);
        }
        return out;
    }

    /**
     * The entries that match every word of a search (DESIGN §6): a plain word matches the name or
     * the spec, {@code @word} the mod (namespace or display name), {@code #word} a tag.
     *
     * @param modNames a mod's display name by its id
     */
    public static List<Entry> search(List<Entry> base, String query, Function<String, String> modNames) {
        String[] words = query.trim().toLowerCase(Locale.ROOT).split("\\s+");
        if (words.length == 0 || words[0].isEmpty()) return base;
        List<Entry> out = new ArrayList<>();
        for (Entry e : base) {
            boolean all = true;
            for (String w : words) {
                if (!e.matches(w, modNames)) {
                    all = false;
                    break;
                }
            }
            if (all) out.add(e);
        }
        return out;
    }

    private static Component displayName(String name, boolean isTranslationKey) {
        return isTranslationKey ? Component.translatable(name) : Component.literal(name);
    }

    /** The state the preview turns in 3D, or null for blocks without a block model (fluids, invisible blocks). */
    @Nullable
    public static BlockState previewState(@Nullable Block block) {
        if (block == null) return null;
        BlockState state = block.defaultBlockState();
        RenderShape shape = state.getRenderShape();
        return shape == RenderShape.MODEL || shape == RenderShape.ENTITYBLOCK_ANIMATED ? state : null;
    }

    /**
     * The item that shows a block: its own, its fluid's bucket, its pick-block item (a wall torch's
     * torch), else a barrier. Blocks without items (water, lava, fire) can be in a category.
     */
    public static ItemStack iconFor(@Nullable Block block, String id) {
        if (block == null) return new ItemStack(Items.BARRIER);
        Item item = block.asItem();
        if (item != Items.AIR) return new ItemStack(item);
        BlockState state = block.defaultBlockState();
        FluidState fluid = state.getFluidState();
        if (!fluid.isEmpty()) {
            Item bucket = fluid.getType().getBucket();
            return new ItemStack(bucket != Items.AIR ? bucket : Items.BUCKET);
        }
        try {
            ItemStack pick = block.getCloneItemStack(EmptyBlockGetter.INSTANCE, BlockPos.ZERO, state);
            if (!pick.isEmpty()) return pick;
        } catch (RuntimeException e) {
            // Some modded blocks expect a real level here; the barrier stands in for them.
            OreRadar.LOGGER.debug("No pick-block item for {} outside a level: {}", id, e.toString());
        }
        return new ItemStack(Items.BARRIER);
    }
}
