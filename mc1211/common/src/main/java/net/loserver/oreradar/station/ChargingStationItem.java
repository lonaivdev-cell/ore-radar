package net.loserver.oreradar.station;

import java.util.List;

import net.loserver.oreradar.item.RadarItem;
import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.block.Block;

/**
 * The station as an item: a broken station keeps its buffer in {@code block_entity_data}, and the
 * tooltip shows it. A station item from 1.20.1 had it in {@code BlockEntityTag}, which the world
 * upgrader moves to that same component; vanilla loads the item only if that tag names the block
 * entity ({@code id}).
 */
public class ChargingStationItem extends BlockItem {
    public ChargingStationItem(Block block, Properties properties) {
        super(block, properties);
    }

    /** The charge buffer the item will give the station it places; 0 for a new station. */
    public static int buffer(ItemStack stack) {
        CustomData blockEntity = stack.get(DataComponents.BLOCK_ENTITY_DATA);
        // Read only; CustomData shares its tag.
        return blockEntity == null ? 0 : blockEntity.getUnsafe().getInt(ChargingStationBlockEntity.TAG_BUFFER);
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> lines, TooltipFlag flag) {
        int buffer = buffer(stack);
        if (buffer > 0) {
            lines.add(Component.translatable("oreradar.tooltip.station_buffer", RadarItem.formatNumber(buffer))
                    .withStyle(ChatFormatting.YELLOW));
        }
    }
}
