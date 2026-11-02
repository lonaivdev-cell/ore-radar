package net.loserver.oreradar.station;

import com.mojang.serialization.Dynamic;
import net.loserver.oreradar.content.RadarContent;
import net.loserver.oreradar.core.config.ServerConfig;
import net.loserver.oreradar.core.energy.ChargingStation;
import net.loserver.oreradar.item.RadarItem;
import net.loserver.oreradar.server.RadarRules;
import net.loserver.oreradar.server.RadarServer;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.NonNullList;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.util.datafix.DataFixers;
import net.minecraft.util.datafix.fixes.References;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.WorldlyContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
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
 * loader's capability or API lookup.
 */
public class ChargingStationBlockEntity extends BaseContainerBlockEntity implements WorldlyContainer {
    public static final int SLOT_RADAR = 0;
    public static final int SLOT_FUEL = 1;
    public static final int SLOT_COUNT = 2;
    /** The buffer in NBT, and in the dropped item's {@code block_entity_data} (see the loot table). */
    public static final String TAG_BUFFER = "Buffer";
    /** Minecraft 1.20.1's data version: what a station's items are in when they still have the old form. */
    private static final int LEGACY_DATA_VERSION = 3465;

    private static final int[] SLOTS_TOP = {SLOT_RADAR, SLOT_FUEL};
    private static final int[] SLOTS_SIDE = {SLOT_FUEL};
    private static final int[] SLOTS_BOTTOM = {SLOT_RADAR};

    private NonNullList<ItemStack> items = NonNullList.withSize(SLOT_COUNT, ItemStack.EMPTY);
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
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        items = NonNullList.withSize(SLOT_COUNT, ItemStack.EMPTY);
        ContainerHelper.loadAllItems(withCurrentItems(tag), items, registries);
        station.setBuffer(tag.getInt(TAG_BUFFER), settings());
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        ContainerHelper.saveAllItems(tag, items, registries);
        // An empty buffer is left out, so a broken empty station stacks with new ones.
        if (station.buffer() > 0) tag.putInt(TAG_BUFFER, station.buffer());
    }

    /**
     * What a station dropped as an item carries (the loot table copies the custom name and this):
     * the buffer as {@code block_entity_data}, which vanilla loads into the block entity when the
     * item is placed. An empty buffer is left out, so the item stacks with new stations.
     */
    @Override
    protected void collectImplicitComponents(DataComponentMap.Builder components) {
        super.collectImplicitComponents(components);
        if (station.buffer() > 0) {
            CompoundTag blockEntityData = new CompoundTag();
            blockEntityData.putInt(TAG_BUFFER, station.buffer());
            // The component's codec refuses data without the block entity's id.
            addEntityType(blockEntityData, getType());
            components.set(DataComponents.BLOCK_ENTITY_DATA, CustomData.of(blockEntityData));
        }
    }

    /**
     * The saved items, brought up to date if Minecraft 1.20.1 saved them ({@code Count} and
     * {@code tag}); the tag itself stays as it is. The world upgrader rewrites the items of the
     * block entities it knows, not of a mod's, so a station from a 1.20.1 world still holds them in
     * the old form, which 1.21.1 would read as one item without its data. Vanilla's own item fix
     * turns 16 coal into 16 coal and a radar's NBT into its custom data, as it does for the radars
     * in players' inventories and in chests.
     */
    private static CompoundTag withCurrentItems(CompoundTag tag) {
        ListTag saved = tag.getList(ContainerHelper.TAG_ITEMS, Tag.TAG_COMPOUND);
        ListTag upgraded = null;
        for (int i = 0; i < saved.size(); i++) {
            CompoundTag item = saved.getCompound(i);
            if (!item.contains("Count", Tag.TAG_ANY_NUMERIC) || item.contains("count")) continue;
            if (upgraded == null) upgraded = saved.copy();
            upgraded.set(i, DataFixers.getDataFixer()
                    .update(References.ITEM_STACK, new Dynamic<>(NbtOps.INSTANCE, item), LEGACY_DATA_VERSION,
                            SharedConstants.getCurrentVersion().getDataVersion().getVersion())
                    .getValue());
        }
        if (upgraded == null) return tag;
        CompoundTag current = new CompoundTag();
        current.put(ContainerHelper.TAG_ITEMS, upgraded);
        return current;
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
    protected NonNullList<ItemStack> getItems() {
        return items;
    }

    @Override
    protected void setItems(NonNullList<ItemStack> items) {
        this.items = items;
    }

    @Override
    public int getContainerSize() {
        return SLOT_COUNT;
    }

    /** A radar in the radar slot, a fuel (by the server's fuel list) in the fuel slot. */
    @Override
    public boolean canPlaceItem(int slot, ItemStack stack) {
        if (slot == SLOT_RADAR) return stack.getItem() instanceof RadarItem;
        RadarServer server = RadarServer.get();
        return server != null && server.rules().fuels().isFuel(stack);
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
