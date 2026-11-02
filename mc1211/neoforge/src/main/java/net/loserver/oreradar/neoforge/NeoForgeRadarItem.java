package net.loserver.oreradar.neoforge;

import net.loserver.oreradar.item.RadarItem;
import net.minecraft.world.item.ItemStack;

/**
 * The radar on NeoForge: no re-equip bob for its own data changes (DESIGN §3). Its FE item
 * capability is registered in {@link NeoForgeCapabilities}.
 */
final class NeoForgeRadarItem extends RadarItem {
    NeoForgeRadarItem(Properties properties) {
        super(properties);
    }

    @Override
    public boolean shouldCauseReequipAnimation(ItemStack oldStack, ItemStack newStack, boolean slotChanged) {
        return slotChanged || isDifferentItem(oldStack, newStack);
    }
}
