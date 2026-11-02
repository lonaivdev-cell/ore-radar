package net.loserver.oreradar.station;

import net.loserver.oreradar.content.RadarContent;
import net.loserver.oreradar.item.RadarItem;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.inventory.SimpleContainerData;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

/**
 * The charging station's menu (DESIGN §3): the radar and fuel slots, the player's inventory, and
 * the buffer for the charge bar, in vanilla's 176 x 166 container layout (the slot positions match
 * {@code textures/gui/container/charging_station.png}).
 *
 * <p><b>Data slots.</b> Minecraft sends each {@link ContainerData} value to the client as a 16-bit
 * short, so 32-bit values travel as two slots. The client reads them with {@link #buffer()},
 * {@link #bufferCapacity()} and {@link #isCharging()}; the raw layout is:
 * <table>
 *   <tr><td>{@value #DATA_BUFFER_LOW}</td><td>buffer, bits 0-15</td></tr>
 *   <tr><td>{@value #DATA_BUFFER_HIGH}</td><td>buffer, bits 16-31</td></tr>
 *   <tr><td>{@value #DATA_CAPACITY_LOW}</td><td>buffer capacity, bits 0-15</td></tr>
 *   <tr><td>{@value #DATA_CAPACITY_HIGH}</td><td>buffer capacity, bits 16-31</td></tr>
 *   <tr><td>{@value #DATA_CHARGING}</td><td>1 while charging a radar (the block's {@code lit}), else 0</td></tr>
 * </table>
 */
public class ChargingStationMenu extends AbstractContainerMenu {
    public static final int DATA_BUFFER_LOW = 0;
    public static final int DATA_BUFFER_HIGH = 1;
    public static final int DATA_CAPACITY_LOW = 2;
    public static final int DATA_CAPACITY_HIGH = 3;
    public static final int DATA_CHARGING = 4;
    public static final int DATA_COUNT = 5;

    /** Top-left corners of the slots' items, in GUI pixels, as vanilla's {@link Slot} takes them. */
    public static final int FUEL_SLOT_X = 44;
    public static final int FUEL_SLOT_Y = 35;
    public static final int RADAR_SLOT_X = 116;
    public static final int RADAR_SLOT_Y = 35;
    public static final int INVENTORY_X = 8;
    public static final int INVENTORY_Y = 84;
    public static final int HOTBAR_Y = 142;

    /** Menu slot indices: the station's two slots, then the player's 27 + 9. */
    private static final int STATION_SLOTS = ChargingStationBlockEntity.SLOT_COUNT;
    private static final int PLAYER_SLOTS_END = STATION_SLOTS + 36;

    private final Container container;
    private final ContainerData data;

    /** The client side: empty slots and data, filled in by the server's sync. */
    public ChargingStationMenu(int containerId, Inventory inventory) {
        this(containerId, inventory, new ClientContainer(), new SimpleContainerData(DATA_COUNT));
    }

    // Vanilla's pattern: a menu adds its slots in its constructor.
    @SuppressWarnings("this-escape")
    public ChargingStationMenu(int containerId, Inventory inventory, Container container, ContainerData data) {
        super(RadarContent.STATION_MENU.get(), containerId);
        checkContainerSize(container, STATION_SLOTS);
        checkContainerDataCount(data, DATA_COUNT);
        this.container = container;
        this.data = data;
        container.startOpen(inventory.player);

        addSlot(new StationSlot(container, ChargingStationBlockEntity.SLOT_RADAR, RADAR_SLOT_X, RADAR_SLOT_Y));
        addSlot(new StationSlot(container, ChargingStationBlockEntity.SLOT_FUEL, FUEL_SLOT_X, FUEL_SLOT_Y));
        for (int row = 0; row < 3; row++) {
            for (int column = 0; column < 9; column++) {
                addSlot(new Slot(inventory, 9 + row * 9 + column, INVENTORY_X + column * 18, INVENTORY_Y + row * 18));
            }
        }
        for (int column = 0; column < 9; column++) {
            addSlot(new Slot(inventory, column, INVENTORY_X + column * 18, HOTBAR_Y));
        }
        addDataSlots(data);
    }

    /** The station's charge buffer. */
    public int buffer() {
        return join(DATA_BUFFER_LOW, DATA_BUFFER_HIGH);
    }

    public int bufferCapacity() {
        return join(DATA_CAPACITY_LOW, DATA_CAPACITY_HIGH);
    }

    /** True while the station is moving charge into a radar. */
    public boolean isCharging() {
        return data.get(DATA_CHARGING) != 0;
    }

    /** The client receives each value sign-extended from 16 bits, so mask before joining. */
    private int join(int low, int high) {
        return (data.get(low) & 0xFFFF) | (data.get(high) & 0xFFFF) << 16;
    }

    @Override
    public boolean stillValid(Player player) {
        return container.stillValid(player);
    }

    @Override
    public void removed(Player player) {
        super.removed(player);
        container.stopOpen(player);
    }

    /** Shift-click: radars and fuel into the station, anything else between inventory and hotbar. */
    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        Slot slot = slots.get(index);
        if (!slot.hasItem()) return ItemStack.EMPTY;
        ItemStack stack = slot.getItem();
        ItemStack original = stack.copy();
        if (index < STATION_SLOTS) {
            if (!moveItemStackTo(stack, STATION_SLOTS, PLAYER_SLOTS_END, true)) return ItemStack.EMPTY;
        } else if (slots.get(ChargingStationBlockEntity.SLOT_RADAR).mayPlace(stack)) {
            if (!moveItemStackTo(stack, ChargingStationBlockEntity.SLOT_RADAR, ChargingStationBlockEntity.SLOT_RADAR + 1, false)) {
                return ItemStack.EMPTY;
            }
        } else if (slots.get(ChargingStationBlockEntity.SLOT_FUEL).mayPlace(stack)) {
            if (!moveItemStackTo(stack, ChargingStationBlockEntity.SLOT_FUEL, ChargingStationBlockEntity.SLOT_FUEL + 1, false)) {
                return ItemStack.EMPTY;
            }
        } else if (index < STATION_SLOTS + 27) {
            if (!moveItemStackTo(stack, STATION_SLOTS + 27, PLAYER_SLOTS_END, false)) return ItemStack.EMPTY;
        } else if (!moveItemStackTo(stack, STATION_SLOTS, STATION_SLOTS + 27, false)) {
            return ItemStack.EMPTY;
        }
        if (stack.isEmpty()) {
            slot.setByPlayer(ItemStack.EMPTY);
        } else {
            slot.setChanged();
        }
        if (stack.getCount() == original.getCount()) return ItemStack.EMPTY;
        slot.onTake(player, stack);
        return original;
    }

    /** A station slot takes what its container accepts, one radar at a time. */
    private static final class StationSlot extends Slot {
        StationSlot(Container container, int index, int x, int y) {
            super(container, index, x, y);
        }

        @Override
        public boolean mayPlace(ItemStack stack) {
            return container.canPlaceItem(getContainerSlot(), stack);
        }

        @Override
        public int getMaxStackSize() {
            return getContainerSlot() == ChargingStationBlockEntity.SLOT_RADAR ? 1 : super.getMaxStackSize();
        }
    }

    /**
     * The client's stand-in for the station. The client does not know the server's fuels, so it
     * predicts only "radars go in the radar slot, anything else in the fuel slot"; the server
     * refuses what does not burn and the slot syncs back.
     */
    private static final class ClientContainer extends SimpleContainer {
        ClientContainer() {
            super(STATION_SLOTS);
        }

        @Override
        public boolean canPlaceItem(int slot, ItemStack stack) {
            return (slot == ChargingStationBlockEntity.SLOT_RADAR) == (stack.getItem() instanceof RadarItem);
        }
    }
}
