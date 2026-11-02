package net.loserver.oreradar.content;

import java.util.function.Supplier;
import java.util.function.UnaryOperator;

import com.mojang.serialization.Codec;
import net.loserver.oreradar.core.net.RadarProtocol;
import net.loserver.oreradar.item.RadarItem;
import net.loserver.oreradar.platform.Services;
import net.loserver.oreradar.station.ChargingStationBlock;
import net.loserver.oreradar.station.ChargingStationBlockEntity;
import net.loserver.oreradar.station.ChargingStationItem;
import net.loserver.oreradar.station.ChargingStationMenu;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.util.ExtraCodecs;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;

/**
 * Ore Radar's registry entries (DESIGN §3). The fields register in this order when the class
 * initialises, which {@link #init()} triggers from the loader's entrypoint; read them through
 * {@code get()}, which on NeoForge works only after the registry events.
 */
public final class RadarContent {
    /**
     * The radar's target spec, absent for none. Capped at a spec's length, so whatever a command or
     * another mod stores also fits the network codec.
     */
    public static final Supplier<DataComponentType<String>> TARGET = component("target", builder -> builder
            .persistent(Codec.string(0, RadarProtocol.MAX_SPEC_LENGTH))
            .networkSynchronized(ByteBufCodecs.stringUtf8(RadarProtocol.MAX_SPEC_LENGTH)));

    /** Present (true) while the radar is switched off. */
    public static final Supplier<DataComponentType<Boolean>> OFF = component("off", builder -> builder
            .persistent(Codec.BOOL)
            .networkSynchronized(ByteBufCodecs.BOOL));

    /** The radar's charge; absent means full (DESIGN §3), as on radars from 1.0 and fresh crafts. */
    public static final Supplier<DataComponentType<Integer>> CHARGE = component("charge", builder -> builder
            .persistent(ExtraCodecs.NON_NEGATIVE_INT)
            .networkSynchronized(ByteBufCodecs.VAR_INT));

    public static final Supplier<RadarItem> RADAR = Services.PLATFORM.register(Registries.ITEM, "ore_radar",
            () -> Services.PLATFORM.createRadarItem(new Item.Properties().stacksTo(1)));

    public static final Supplier<ChargingStationBlock> STATION = Services.PLATFORM.register(Registries.BLOCK, "charging_station",
            () -> new ChargingStationBlock(BlockBehaviour.Properties.of()
                    .mapColor(MapColor.METAL)
                    .requiresCorrectToolForDrops()
                    .strength(3.5F, 6.0F)
                    .sound(SoundType.METAL)
                    .lightLevel(state -> state.getValue(ChargingStationBlock.LIT) ? ChargingStationBlock.LIT_LIGHT : 0)));

    public static final Supplier<ChargingStationItem> STATION_ITEM = Services.PLATFORM.register(Registries.ITEM, "charging_station",
            () -> new ChargingStationItem(STATION.get(), new Item.Properties()));

    public static final Supplier<BlockEntityType<ChargingStationBlockEntity>> STATION_BLOCK_ENTITY = Services.PLATFORM.register(
            Registries.BLOCK_ENTITY_TYPE, "charging_station",
            () -> Services.PLATFORM.createStationBlockEntityType(STATION.get()));

    public static final Supplier<MenuType<ChargingStationMenu>> STATION_MENU = Services.PLATFORM.register(Registries.MENU, "charging_station",
            Services.PLATFORM::createStationMenuType);

    private RadarContent() {}

    /** Registers everything above; called once by {@code OreRadar.init()}. */
    public static void init() {
        // Loading the class runs the registrations in the field initialisers.
    }

    private static <T> Supplier<DataComponentType<T>> component(String name, UnaryOperator<DataComponentType.Builder<T>> settings) {
        return Services.PLATFORM.register(Registries.DATA_COMPONENT_TYPE, name, () -> settings.apply(DataComponentType.builder()).build());
    }
}
