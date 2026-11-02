package net.loserver.oreradar.station;

import java.util.List;

import net.loserver.oreradar.item.RadarItem;
import net.minecraft.ChatFormatting;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import org.jetbrains.annotations.Nullable;

/** The station as an item: a broken station keeps its buffer (BlockEntityTag), and the tooltip shows it. */
public class ChargingStationItem extends BlockItem {
    public ChargingStationItem(Block block, Properties properties) {
        super(block, properties);
    }

    @Override
    public void appendHoverText(ItemStack stack, @Nullable Level level, List<Component> lines, TooltipFlag flag) {
        CompoundTag blockEntity = getBlockEntityData(stack);
        int buffer = blockEntity == null ? 0 : blockEntity.getInt(ChargingStationBlockEntity.TAG_BUFFER);
        if (buffer > 0) {
            lines.add(Component.translatable("oreradar.tooltip.station_buffer", RadarItem.formatNumber(buffer))
                    .withStyle(ChatFormatting.YELLOW));
        }
    }
}
