package net.loserver.oreradar.fabric;

import net.fabricmc.fabric.api.item.v1.FabricItem;
import net.loserver.oreradar.item.RadarItem;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

/** The radar on Fabric: its own data changes (target, power, charge) never replay the re-equip animation. */
final class FabricRadarItem extends RadarItem implements FabricItem {
    FabricRadarItem(Properties properties) {
        super(properties);
    }

    /** Fabric asks only when the item stayed the same and its components changed: never bob for that. */
    @Override
    public boolean allowComponentsUpdateAnimation(Player player, InteractionHand hand, ItemStack oldStack, ItemStack newStack) {
        return false;
    }
}
