package net.loserver.oreradar.item;

import java.util.List;
import java.util.Locale;

import net.loserver.oreradar.EnergySettings;
import net.loserver.oreradar.client.ClientBridge;
import net.loserver.oreradar.content.RadarContent;
import net.loserver.oreradar.core.energy.ChargeMath;
import net.loserver.oreradar.core.net.RadarProtocol;
import net.loserver.oreradar.server.RadarServer;
import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
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
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

/**
 * The radar (DESIGN §3). Use opens the radar screen; sneak + use on a block tracks that block (its
 * group if it has one); sneak + use in the air switches it off or on. Its state lives in the data
 * components {@code oreradar:target}, {@code oreradar:off} and {@code oreradar:charge}; only the
 * server writes them. Each loader subclasses it for the re-equip animation.
 *
 * <p><b>Radars from 1.20.1.</b> The world upgrader keeps item NBT it does not know in
 * {@code minecraft:custom_data}, so a 1.20.1 radar (1.0's or 2.0's) arrives with
 * {@code RadarTarget}, {@code RadarOff} and {@code RadarCharge} there. Each is read where its
 * component is missing, and all three move to the components the next time the server writes the
 * radar, so the radar reads the same before and after.
 */
public class RadarItem extends Item {
    /** 1.20.1's NBT keys, which a world upgraded to 1.21.1 holds in {@code minecraft:custom_data}. */
    public static final String LEGACY_TARGET = "RadarTarget";
    public static final String LEGACY_OFF = "RadarOff";
    public static final String LEGACY_CHARGE = "RadarCharge";
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
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> lines, TooltipFlag flag) {
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
        String spec = stack.get(RadarContent.TARGET.get());
        if (spec == null) {
            CompoundTag legacy = legacyData(stack);
            if (legacy != null && legacy.contains(LEGACY_TARGET, Tag.TAG_STRING)) spec = legacy.getString(LEGACY_TARGET);
        }
        return spec == null || spec.isEmpty() ? null : spec;
    }

    /** Sets the target, or clears it with null. Choosing a target also switches the radar on (1.0). */
    public static void setTarget(ItemStack stack, @Nullable String spec) {
        migrateLegacyData(stack);
        if (spec == null) {
            stack.remove(RadarContent.TARGET.get());
        } else {
            stack.set(RadarContent.TARGET.get(), spec);
            setOff(stack, false);
        }
    }

    public static boolean isOff(ItemStack stack) {
        Boolean off = stack.get(RadarContent.OFF.get());
        if (off != null) return off;
        CompoundTag legacy = legacyData(stack);
        return legacy != null && legacy.getBoolean(LEGACY_OFF);
    }

    public static void setOff(ItemStack stack, boolean off) {
        migrateLegacyData(stack);
        if (off) {
            stack.set(RadarContent.OFF.get(), true);
        } else {
            stack.remove(RadarContent.OFF.get());
        }
    }

    /** The stored charge, or null when there is none, which means full (1.0 and freshly crafted radars). */
    @Nullable
    public static Integer storedCharge(ItemStack stack) {
        Integer charge = stack.get(RadarContent.CHARGE.get());
        if (charge != null) return charge;
        CompoundTag legacy = legacyData(stack);
        return legacy != null && legacy.contains(LEGACY_CHARGE, Tag.TAG_ANY_NUMERIC) ? legacy.getInt(LEGACY_CHARGE) : null;
    }

    /** The radar's charge against the current capacity; a missing value reads as full. */
    public static int charge(ItemStack stack) {
        return ChargeMath.read(storedCharge(stack), EnergySettings.current().capacity());
    }

    public static void setCharge(ItemStack stack, int charge) {
        migrateLegacyData(stack);
        stack.set(RadarContent.CHARGE.get(), Math.max(0, charge));
    }

    /** "31,200": charge is shown with digit grouping in every language, as the screen shows it. */
    public static String formatNumber(long value) {
        return String.format(Locale.ROOT, "%,d", value);
    }

    /** The custom data of a radar from 1.20.1 that still holds a 1.20.1 key, read only; null for any other stack. */
    @Nullable
    private static CompoundTag legacyData(ItemStack stack) {
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        if (data == null || !(data.contains(LEGACY_TARGET) || data.contains(LEGACY_OFF) || data.contains(LEGACY_CHARGE))) return null;
        // Shared with the component, so only ever read; the writes below replace the whole component.
        return data.getUnsafe();
    }

    /**
     * Moves a 1.20.1 radar's keys from custom data into the components that are still missing, and
     * drops them from the custom data (any other custom data stays). Every write calls it first, so
     * a cleared component never falls back to an old key.
     */
    private static void migrateLegacyData(ItemStack stack) {
        CompoundTag legacy = legacyData(stack);
        if (legacy == null) return;
        if (!stack.has(RadarContent.TARGET.get()) && legacy.contains(LEGACY_TARGET, Tag.TAG_STRING)) {
            String spec = legacy.getString(LEGACY_TARGET);
            // The component holds at most a spec's length; a longer string never was a target, and
            // the radar already treated it as none.
            if (!spec.isEmpty() && spec.length() <= RadarProtocol.MAX_SPEC_LENGTH) stack.set(RadarContent.TARGET.get(), spec);
        }
        if (!stack.has(RadarContent.OFF.get()) && legacy.getBoolean(LEGACY_OFF)) {
            stack.set(RadarContent.OFF.get(), true);
        }
        if (!stack.has(RadarContent.CHARGE.get()) && legacy.contains(LEGACY_CHARGE, Tag.TAG_ANY_NUMERIC)) {
            stack.set(RadarContent.CHARGE.get(), Math.max(0, legacy.getInt(LEGACY_CHARGE)));
        }
        CustomData.update(DataComponents.CUSTOM_DATA, stack, tag -> {
            tag.remove(LEGACY_TARGET);
            tag.remove(LEGACY_OFF);
            tag.remove(LEGACY_CHARGE);
        });
    }
}
