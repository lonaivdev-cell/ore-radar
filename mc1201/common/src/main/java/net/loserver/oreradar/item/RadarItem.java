package net.loserver.oreradar.item;

import java.util.List;
import java.util.Locale;

import net.loserver.oreradar.EnergySettings;
import net.loserver.oreradar.client.ClientBridge;
import net.loserver.oreradar.core.energy.ChargeMath;
import net.loserver.oreradar.core.net.RadarProtocol;
import net.loserver.oreradar.server.RadarServer;
import net.minecraft.ChatFormatting;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;
/**
 * The radar (DESIGN §3). Use opens the radar screen; sneak + use on a block tracks that block (its
 * group if it has one); sneak + use in the air switches it off or on. Its state lives in NBT under
 * 1.0's keys, so 1.0 radars load unchanged; only the server writes it. Each loader subclasses it
 * for the re-equip animation and the energy item capability.
 */
public class RadarItem extends Item {
    public static final String TAG_TARGET = "RadarTarget";
    public static final String TAG_OFF = "RadarOff";
    public static final String TAG_CHARGE = "RadarCharge";
    /** Holding the use key repeats the use every 4 ticks; this keeps a long press from toggling twice (1.0). */
    public static final int USE_COOLDOWN_TICKS = 10;

    public RadarItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (player.isSecondaryUseActive()) {
            if (!level.isClientSide) {
                boolean off = !isOff(stack);
                setOff(stack, off);
                player.displayClientMessage(Component.translatable(off ? "oreradar.msg.off" : "oreradar.msg.on"), true);
                player.getCooldowns().addCooldown(this, USE_COOLDOWN_TICKS);
            }
        } else if (level.isClientSide) {
            ClientBridge.get().openRadarScreen(slotOf(player, hand));
        }
        return InteractionResultHolder.sidedSuccess(stack, level.isClientSide);
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        Player player = context.getPlayer();
        if (player == null || !player.isSecondaryUseActive()) {
            return InteractionResult.PASS;
        }
        Level level = context.getLevel();
        if (player instanceof ServerPlayer serverPlayer) {
            RadarServer server = RadarServer.get();
            if (server != null) {
                server.trackBlock(serverPlayer, context.getItemInHand(), level.getBlockState(context.getClickedPos()).getBlock());
            }
            player.getCooldowns().addCooldown(this, USE_COOLDOWN_TICKS);
        }
        return InteractionResult.sidedSuccess(level.isClientSide);
    }

    @Override
    public void appendHoverText(ItemStack stack, @Nullable Level level, List<Component> lines, TooltipFlag flag) {
        String spec = target(stack);
        if (spec == null) {
            lines.add(Component.translatable("oreradar.tooltip.no_target").withStyle(ChatFormatting.GRAY));
        } else {
            lines.add(Component.translatable("oreradar.tooltip.target", TargetNames.of(spec)).withStyle(ChatFormatting.AQUA));
            if (flag.isAdvanced()) {
                lines.add(Component.literal(spec).withStyle(ChatFormatting.DARK_GRAY));
            }
        }
        if (isOff(stack)) {
            lines.add(Component.translatable("oreradar.tooltip.off").withStyle(ChatFormatting.RED));
        }
        EnergySettings energy = EnergySettings.current();
        if (energy.enabled()) {
            int charge = charge(stack);
            long minutes = ChargeMath.minutesLeft(charge, energy.drainPerSecond());
            Component amount = Component.translatable("oreradar.tooltip.charge", formatNumber(charge), formatNumber(energy.capacity()));
            if (minutes >= 0) {
                amount = Component.translatable("oreradar.tooltip.charge_time", amount, minutes);
            }
            lines.add(amount.copy().withStyle(charge > 0 ? ChatFormatting.YELLOW : ChatFormatting.RED));
            // Running low: say where charge comes from, which nothing else in the game tells a player.
            if (charge * 10L < energy.capacity()) {
                lines.add(Component.translatable(energy.externalChargers() ? "oreradar.tooltip.recharge_external" : "oreradar.tooltip.recharge")
                        .withStyle(ChatFormatting.GOLD));
            }
        }
        lines.add(Component.translatable("oreradar.tooltip.hint_use").withStyle(ChatFormatting.DARK_GRAY));
        lines.add(Component.translatable("oreradar.tooltip.hint_pick").withStyle(ChatFormatting.DARK_GRAY));
        lines.add(Component.translatable("oreradar.tooltip.hint_toggle").withStyle(ChatFormatting.DARK_GRAY));
    }

    /** The charge bar replaces the durability bar, and shows whenever radars use charge. */
    @Override
    public boolean isBarVisible(ItemStack stack) {
        return EnergySettings.current().enabled();
    }

    @Override
    public int getBarWidth(ItemStack stack) {
        return ChargeMath.barWidth(charge(stack), EnergySettings.current().capacity());
    }

    @Override
    public int getBarColor(ItemStack stack) {
        return ChargeMath.barColor(charge(stack), EnergySettings.current().capacity());
    }

    /**
     * Whether swapping {@code oldStack} for {@code newStack} in hand is a different item rather
     * than this one's data changing (target, power, charge), which must not replay the re-equip
     * animation (DESIGN §3).
     */
    public static boolean isDifferentItem(ItemStack oldStack, ItemStack newStack) {
        return oldStack.getItem() != newStack.getItem();
    }

    /** The protocol slot of the stack in that hand: the selected hotbar slot, or 40 for the off hand. */
    public static int slotOf(Player player, InteractionHand hand) {
        return hand == InteractionHand.MAIN_HAND ? player.getInventory().selected : RadarProtocol.OFFHAND_SLOT;
    }

    /** The stored target spec, or null for none. Not validated: only the server's rules decide what it finds. */
    @Nullable
    public static String target(ItemStack stack) {
        CompoundTag tag = stack.getTag();
        if (tag == null || !tag.contains(TAG_TARGET, Tag.TAG_STRING)) return null;
        String spec = tag.getString(TAG_TARGET);
        return spec.isEmpty() ? null : spec;
    }

    /** Sets the target, or clears it with null. Choosing a target also switches the radar on (1.0). */
    public static void setTarget(ItemStack stack, @Nullable String spec) {
        if (spec == null) {
            CompoundTag tag = stack.getTag();
            if (tag != null) tag.remove(TAG_TARGET);
        } else {
            stack.getOrCreateTag().putString(TAG_TARGET, spec);
            setOff(stack, false);
        }
    }

    public static boolean isOff(ItemStack stack) {
        CompoundTag tag = stack.getTag();
        return tag != null && tag.getBoolean(TAG_OFF);
    }

    public static void setOff(ItemStack stack, boolean off) {
        if (off) {
            stack.getOrCreateTag().putBoolean(TAG_OFF, true);
        } else if (stack.getTag() != null) {
            stack.getTag().remove(TAG_OFF);
        }
    }

    /** The stored charge, or null when the key is missing, which means full (1.0 and freshly crafted radars). */
    @Nullable
    public static Integer storedCharge(ItemStack stack) {
        CompoundTag tag = stack.getTag();
        return tag != null && tag.contains(TAG_CHARGE, Tag.TAG_ANY_NUMERIC) ? tag.getInt(TAG_CHARGE) : null;
    }

    /** The radar's charge against the current capacity; a missing value reads as full. */
    public static int charge(ItemStack stack) {
        return ChargeMath.read(storedCharge(stack), EnergySettings.current().capacity());
    }

    public static void setCharge(ItemStack stack, int charge) {
        stack.getOrCreateTag().putInt(TAG_CHARGE, Math.max(0, charge));
    }

    /** "31,200": charge is shown with digit grouping in every language, as the screen shows it. */
    public static String formatNumber(long value) {
        return String.format(Locale.ROOT, "%,d", value);
    }
}
