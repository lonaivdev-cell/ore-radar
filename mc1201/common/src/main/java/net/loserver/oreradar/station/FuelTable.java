package net.loserver.oreradar.station;

import java.util.ArrayList;
import java.util.List;

import net.loserver.oreradar.core.designation.FuelEntry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.jetbrains.annotations.Nullable;

/**
 * The station's fuels from {@code [station] fuels}: item ids and item tags with the charge one item
 * adds. The first entry that matches an item wins, in config order. Tags are checked when an item
 * is looked up, so a datapack reload that changes a tag applies at once.
 */
public final class FuelTable {
    private record Entry(@Nullable Item item, @Nullable TagKey<Item> tag, int value) {
        boolean matches(ItemStack stack) {
            return item != null ? stack.is(item) : stack.is(tag);
        }
    }

    private final List<Entry> entries;

    private FuelTable(List<Entry> entries) {
        this.entries = List.copyOf(entries);
    }

    /** @param warnings receives entries naming an item that does not exist */
    public static FuelTable of(List<FuelEntry> fuels, List<String> warnings) {
        List<Entry> entries = new ArrayList<>(fuels.size());
        for (FuelEntry fuel : fuels) {
            ResourceLocation id = new ResourceLocation(fuel.item().namespace(), fuel.item().path());
            if (fuel.item().tag()) {
                entries.add(new Entry(null, TagKey.create(Registries.ITEM, id), fuel.value()));
                continue;
            }
            Item item = BuiltInRegistries.ITEM.getOptional(id).orElse(Items.AIR);
            if (item == Items.AIR) {
                warnings.add("[station] fuels: no item " + id + "; ignoring " + fuel);
            } else {
                entries.add(new Entry(item, null, fuel.value()));
            }
        }
        return new FuelTable(entries);
    }

    /** The charge one of these items burns into, 0 if it is not a fuel. */
    public int valueOf(ItemStack stack) {
        if (stack.isEmpty()) return 0;
        for (Entry entry : entries) {
            if (entry.matches(stack)) return entry.value();
        }
        return 0;
    }

    public boolean isFuel(ItemStack stack) {
        return valueOf(stack) > 0;
    }
}
