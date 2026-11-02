package net.loserver.oreradar.server;

import java.util.List;

import net.loserver.oreradar.core.designation.BlockLookup;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import org.jetbrains.annotations.Nullable;

/**
 * Core's view of the block registry and its tags. Tags are the ones currently bound to the
 * registry: the server's after a datapack load, which is why the rules are rebuilt after each one.
 */
public final class RegistryBlockLookup implements BlockLookup<Block> {
    public static final RegistryBlockLookup INSTANCE = new RegistryBlockLookup();

    private RegistryBlockLookup() {}

    @Override
    public Iterable<Block> blocks() {
        return BuiltInRegistries.BLOCK;
    }

    @Override
    @Nullable
    public Block block(String id) {
        ResourceLocation location = ResourceLocation.tryParse(id);
        return location == null ? null : BuiltInRegistries.BLOCK.getOptional(location).orElse(null);
    }

    @Override
    public String id(Block block) {
        return BuiltInRegistries.BLOCK.getKey(block).toString();
    }

    @Override
    public Iterable<Block> tag(String tagId) {
        ResourceLocation location = ResourceLocation.tryParse(tagId);
        if (location == null) return List.of();
        return BuiltInRegistries.BLOCK.getTag(TagKey.create(Registries.BLOCK, location))
                .<List<Block>>map(set -> set.stream().map(Holder::value).toList())
                .orElse(List.of());
    }

    @Override
    public Iterable<String> tagIds() {
        return BuiltInRegistries.BLOCK.getTagNames().map(key -> key.location().toString()).toList();
    }

    @Override
    public boolean isAir(Block block) {
        return block.defaultBlockState().isAir();
    }

    @Override
    public boolean hasItem(Block block) {
        return block.asItem() != Items.AIR;
    }

    @Override
    public String nameKey(Block block) {
        return block.getDescriptionId();
    }
}
