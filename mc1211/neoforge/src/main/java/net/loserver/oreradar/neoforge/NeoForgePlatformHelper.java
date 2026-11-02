package net.loserver.oreradar.neoforge;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;

import net.loserver.oreradar.OreRadar;
import net.loserver.oreradar.core.net.RadarProtocol;
import net.loserver.oreradar.item.RadarItem;
import net.loserver.oreradar.platform.IPlatformHelper;
import net.loserver.oreradar.station.ChargingStationBlockEntity;
import net.loserver.oreradar.station.ChargingStationMenu;
import net.minecraft.core.Registry;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.flag.FeatureFlags;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.neoforged.fml.ModLoadingContext;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class NeoForgePlatformHelper implements IPlatformHelper {
    /** One deferred register per registry, each hooked to the mod bus when first used. */
    private final Map<ResourceKey<?>, DeferredRegister<?>> registers = new HashMap<>();

    @Override
    public String getPlatformName() {
        return "NeoForge";
    }

    @Override
    public Path getConfigDir() {
        return FMLPaths.CONFIGDIR.get();
    }

    @Override
    public <R, T extends R> Supplier<T> register(ResourceKey<Registry<R>> registry, String name, Supplier<T> factory) {
        @SuppressWarnings("unchecked") // the map is keyed by the registry, so the value's type matches
        DeferredRegister<R> register = (DeferredRegister<R>) registers.computeIfAbsent(registry, key -> {
            DeferredRegister<R> created = DeferredRegister.create(registry, OreRadar.MOD_ID);
            // Registration runs while the mod is constructed, when our container is the active one.
            created.register(ModLoadingContext.get().getActiveContainer().getEventBus());
            return created;
        });
        return register.register(name, factory);
    }

    @Override
    public RadarItem createRadarItem(Item.Properties properties) {
        return new NeoForgeRadarItem(properties);
    }

    /** NeoForge attaches capabilities from outside (NeoForgeCapabilities), so the common block entity serves as is. */
    @Override
    public BlockEntityType<ChargingStationBlockEntity> createStationBlockEntityType(Block station) {
        return BlockEntityType.Builder.of(ChargingStationBlockEntity::new, station).build(null);
    }

    @Override
    public MenuType<ChargingStationMenu> createStationMenuType() {
        return new MenuType<>(ChargingStationMenu::new, FeatureFlags.VANILLA_SET);
    }

    @Override
    public void sendToPlayer(ServerPlayer player, RadarProtocol.Channel channel, byte[] payload) {
        NeoForgeNetworking.sendToPlayer(player, channel, payload);
    }

    @Override
    public void sendToServer(RadarProtocol.Channel channel, byte[] payload) {
        NeoForgeClientNetworking.sendToServer(channel, payload);
    }
}
