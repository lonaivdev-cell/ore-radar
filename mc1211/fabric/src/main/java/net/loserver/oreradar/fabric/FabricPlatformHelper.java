package net.loserver.oreradar.fabric;

import java.nio.file.Path;
import java.util.function.Supplier;

import net.fabricmc.loader.api.FabricLoader;
import net.loserver.oreradar.OreRadar;
import net.loserver.oreradar.core.net.RadarProtocol;
import net.loserver.oreradar.item.RadarItem;
import net.loserver.oreradar.platform.IPlatformHelper;
import net.loserver.oreradar.station.ChargingStationBlockEntity;
import net.loserver.oreradar.station.ChargingStationMenu;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.flag.FeatureFlags;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntityType;

public final class FabricPlatformHelper implements IPlatformHelper {
    @Override
    public String getPlatformName() {
        return "Fabric";
    }

    @Override
    public Path getConfigDir() {
        return FabricLoader.getInstance().getConfigDir();
    }

    /** Fabric registers at once, while the mod initialises. */
    @Override
    public <R, T extends R> Supplier<T> register(ResourceKey<Registry<R>> registry, String name, Supplier<T> factory) {
        @SuppressWarnings("unchecked") // the root registry maps each key to the registry it names
        Registry<R> target = (Registry<R>) BuiltInRegistries.REGISTRY.get(registry.location());
        if (target == null) throw new IllegalArgumentException("No registry " + registry.location());
        T value = Registry.register(target, OreRadar.id(name), factory.get());
        return () -> value;
    }

    @Override
    public RadarItem createRadarItem(Item.Properties properties) {
        return new FabricRadarItem(properties);
    }

    @Override
    public BlockEntityType<ChargingStationBlockEntity> createStationBlockEntityType(Block station) {
        return BlockEntityType.Builder.<ChargingStationBlockEntity>of(FabricChargingStationBlockEntity::new, station).build(null);
    }

    @Override
    public MenuType<ChargingStationMenu> createStationMenuType() {
        return new MenuType<>(ChargingStationMenu::new, FeatureFlags.VANILLA_SET);
    }

    @Override
    public void sendToPlayer(ServerPlayer player, RadarProtocol.Channel channel, byte[] payload) {
        FabricNetworking.sendToPlayer(player, channel, payload);
    }

    @Override
    public void sendToServer(RadarProtocol.Channel channel, byte[] payload) {
        FabricClientNetworking.sendToServer(channel, payload);
    }
}
