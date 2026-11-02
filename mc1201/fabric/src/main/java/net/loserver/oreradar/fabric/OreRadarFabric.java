package net.loserver.oreradar.fabric;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.CommonLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.itemgroup.v1.ItemGroupEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.resource.IdentifiableResourceReloadListener;
import net.fabricmc.fabric.api.resource.ResourceManagerHelper;
import net.loserver.oreradar.OreRadar;
import net.loserver.oreradar.content.RadarContent;
import net.loserver.oreradar.server.PackGroups;
import net.loserver.oreradar.server.RadarCommands;
import net.loserver.oreradar.server.RadarServer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.PackType;
import net.minecraft.world.item.CreativeModeTabs;

/**
 * Fabric entrypoint: registers the content, the energy lookups and the server's network receivers,
 * and forwards the server's lifecycle, ticks, players, commands and datapack reloads to common code.
 */
public final class OreRadarFabric implements ModInitializer {
    @Override
    public void onInitialize() {
        OreRadar.init();
        FabricEnergy.register();
        FabricNetworking.registerServerReceivers();

        ItemGroupEvents.modifyEntriesEvent(CreativeModeTabs.TOOLS_AND_UTILITIES).register(entries -> {
            entries.accept(RadarContent.RADAR.get());
            entries.accept(RadarContent.STATION_ITEM.get());
        });

        ServerLifecycleEvents.SERVER_STARTING.register(RadarServer::start);
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> RadarServer.stop());
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            RadarServer radar = RadarServer.get();
            if (radar != null) radar.tick();
        });
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            RadarServer radar = RadarServer.get();
            if (radar != null) radar.onPlayerLeft(handler.getPlayer().getUUID());
        });
        CommandRegistrationCallback.EVENT.register((dispatcher, registries, environment) -> RadarCommands.register(dispatcher));
        ResourceManagerHelper.get(PackType.SERVER_DATA).registerReloadListener(new FabricPackGroups());
        CommonLifecycleEvents.TAGS_LOADED.register((registries, client) -> {
            if (!client) RadarServer.onTagsLoaded();
        });
    }

    /** Fabric identifies its reload listeners by id. */
    private static final class FabricPackGroups extends PackGroups implements IdentifiableResourceReloadListener {
        @Override
        public ResourceLocation getFabricId() {
            return ID;
        }
    }
}
