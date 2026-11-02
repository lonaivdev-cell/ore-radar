package net.loserver.oreradar.station;

import net.loserver.oreradar.content.RadarContent;
import net.loserver.oreradar.core.config.ServerConfig;
import net.loserver.oreradar.core.energy.ChargingStation;
import net.loserver.oreradar.item.RadarItem;
import net.loserver.oreradar.server.RadarRules;
import net.loserver.oreradar.server.RadarServer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.NonNullList;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.Container;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.WorldlyContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BaseContainerBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

/**
 * The charging station's state (DESIGN §3): a radar slot, a fuel slot and core's
 * {@link ChargingStation} buffer, ticked on the server. As a {@link WorldlyContainer}, hoppers and
 * pipes insert fuel from the top and sides and radars from the top, and pull a radar out of the
 * bottom only once it is full. Energy from other mods reaches {@link #receiveEnergy} through each
 * loader's capability; the loaders subclass this only to expose those capabilities.
 */
public class ChargingStationBlockEntity extends BaseContainerBlockEntity implements WorldlyContainer {
    public static final int SLOT_RADAR = 0;
    public static final int SLOT_FUEL = 1;
    public static final int SLOT_COUNT = 2;
    /** The buffer in NBT, and in the dropped item's BlockEntityTag (see the loot table). */
    public static final String TAG_BUFFER = "Buffer";

    private static final int[] SLOTS_TOP = {SLOT_RADAR, SLOT_FUEL};
    private static final int[] SLOTS_SIDE = {SLOT_FUEL};
    private static final int[] SLOTS_BOTTOM = {SLOT_RADAR};

    private final NonNullList<ItemStack> items = NonNullList.withSize(SLOT_COUNT, ItemStack.EMPTY);
    private final ChargingStation station = new ChargingStation();
    private boolean charging;
    private final ContainerData data = new ContainerData() {
        @Override
        public int get(int index) {
            return switch (index) {
                case ChargingStationMenu.DATA_BUFFER_LOW -> station.buffer() & 0xFFFF;
                case ChargingStationMenu.DATA_BUFFER_HIGH -> station.buffer() >>> 16;
                case ChargingStationMenu.DATA_CAPACITY_LOW -> settings().bufferCapacity() & 0xFFFF;
                case ChargingStationMenu.DATA_CAPACITY_HIGH -> settings().bufferCapacity() >>> 16;
                case ChargingStationMenu.DATA_CHARGING -> charging ? 1 : 0;
                default -> 0;
            };
        }

        /** The menu only reads; the server's values always win. */
        @Override
        public void set(int index, int value) {}

        @Override
        public int getCount() {
            return ChargingStationMenu.DATA_COUNT;
        }
    };

    public ChargingStationBlockEntity(BlockPos pos, BlockState state) {
        super(RadarContent.STATION_BLOCK_ENTITY.get(), pos, state);
    }

    public static void serverTick(Level level, BlockPos pos, BlockState state, ChargingStationBlockEntity station) {
        station.tick(level, pos, state);
    }

    private void tick(Level level, BlockPos pos, BlockState state) {
        RadarServer server = RadarServer.get();
        if (server == null) return;
        RadarRules rules = server.rules();
        ItemStack radar = items.get(SLOT_RADAR);
        ItemStack fuel = items.get(SLOT_FUEL);
        boolean hasRadar = radar.getItem() instanceof RadarItem;
        int radarCharge = hasRadar ? RadarItem.charge(radar) : -1;
        int bufferBefore = station.buffer();

        ChargingStation.TickResult result = station.tick(rules.station(), rules.fuels().valueOf(fuel), radarCharge);
        boolean changed = station.buffer() != bufferBefore;
        if (result.consumeFuel()) {
            fuel.shrink(1);
            changed = true;
        }
        if (hasRadar && result.radarCharge() != radarCharge) {
            RadarItem.setCharge(radar, result.radarCharge());
            changed = true;
        }
        charging = result.charging();
        if (state.getValue(ChargingStationBlock.LIT) != charging) {
            level.setBlock(pos, state.setValue(ChargingStationBlock.LIT, charging), Block.UPDATE_ALL);
        }
        // Saves the chunk and updates comparators, which read the buffer.
        if (changed) setChanged();
    }

    /**
     * Energy offered by another mod (FE, TR Energy), at {@code ratio} energy per unit of charge,
     * within the buffer's room and {@code maxInputPerTick}.
     *
     * @return the energy accepted
     */
    public long receiveEnergy(long offered, int ratio, boolean simulate) {
        long accepted = station.receiveEnergy(settings(), offered, ratio, simulate);
        if (accepted > 0 && !simulate) setChanged();
        return accepted;
    }

    /** The buffer in energy units, for an energy API's "stored" query. */
    public long energyStored(int ratio) {
        return station.energyStored(ratio);
    }

    public long maxEnergyStored(int ratio) {
        return ChargingStation.maxEnergyStored(settings(), ratio);
    }

    public int buffer() {
        return station.buffer();
    }

    /** Sets the buffer, clamped to the current capacity (the GameTests fill stations this way). */
    public void setBuffer(int value) {
        station.setBuffer(value, settings());
        setChanged();
    }

    public int comparatorLevel() {
        return station.comparatorLevel(settings());
    }

    /** The running server's station settings; the defaults before one runs (a block entity loaded on a client). */
    private static ChargingStation.Settings settings() {
        RadarServer server = RadarServer.get();
        return server != null ? server.rules().station() : DefaultSettings.STATION;
    }

    @Override
    public void load(CompoundTag tag) {
        super.load(tag);
        items.clear();
        ContainerHelper.loadAllItems(tag, items);
        station.setBuffer(tag.getInt(TAG_BUFFER), settings());
    }

    @Override
    protected void saveAdditional(CompoundTag tag) {
        super.saveAdditional(tag);
        ContainerHelper.saveAllItems(tag, items);
        // An empty buffer is left out, so a broken empty station stacks with new ones.
        if (station.buffer() > 0) tag.putInt(TAG_BUFFER, station.buffer());
    }

    @Override
    protected Component getDefaultName() {
        return Component.translatable("container.oreradar.charging_station");
    }

    @Override
    protected AbstractContainerMenu createMenu(int containerId, Inventory inventory) {
        return new ChargingStationMenu(containerId, inventory, this, data);
    }

    @Override
    public int getContainerSize() {
        return SLOT_COUNT;
    }

    @Override
    public boolean isEmpty() {
        return items.stream().allMatch(ItemStack::isEmpty);
    }

    @Override
    public ItemStack getItem(int slot) {
        return items.get(slot);
    }

    @Override
    public ItemStack removeItem(int slot, int amount) {
        ItemStack removed = ContainerHelper.removeItem(items, slot, amount);
        if (!removed.isEmpty()) setChanged();
        return removed;
    }

    @Override
    public ItemStack removeItemNoUpdate(int slot) {
        return ContainerHelper.takeItem(items, slot);
    }

    @Override
    public void setItem(int slot, ItemStack stack) {
        items.set(slot, stack);
        if (stack.getCount() > getMaxStackSize()) stack.setCount(getMaxStackSize());
        setChanged();
    }

    /** A radar in the radar slot, a fuel (by the server's fuel list) in the fuel slot. */
    @Override
    public boolean canPlaceItem(int slot, ItemStack stack) {
        if (slot == SLOT_RADAR) return stack.getItem() instanceof RadarItem;
        RadarServer server = RadarServer.get();
        return server != null && server.rules().fuels().isFuel(stack);
    }

    @Override
    public boolean stillValid(Player player) {
        return Container.stillValidBlockEntity(this, player);
    }

    @Override
    public void clearContent() {
        items.clear();
    }

    @Override
    public int[] getSlotsForFace(Direction side) {
        return switch (side) {
            case UP -> SLOTS_TOP;
            case DOWN -> SLOTS_BOTTOM;
            default -> SLOTS_SIDE;
        };
    }

    @Override
    public boolean canPlaceItemThroughFace(int slot, ItemStack stack, @Nullable Direction side) {
        if (side == Direction.DOWN || (slot == SLOT_RADAR && side != Direction.UP)) return false;
        return canPlaceItem(slot, stack);
    }

    @Override
    public boolean canTakeItemThroughFace(int slot, ItemStack stack, Direction side) {
        return side == Direction.DOWN && slot == SLOT_RADAR && stack.getItem() instanceof RadarItem
                && ChargingStation.canExtractRadar(RadarItem.charge(stack), settings().radarCapacity());
    }

    /** Held apart so the defaults are built once, and only if a station exists before a server. */
    private static final class DefaultSettings {
        static final ChargingStation.Settings STATION = ChargingStation.Settings.of(ServerConfig.defaults(false));
    }
}
