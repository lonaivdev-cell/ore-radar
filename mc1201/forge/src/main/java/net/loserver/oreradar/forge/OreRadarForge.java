package net.loserver.oreradar.forge;

import net.loserver.oreradar.OreRadar;
import net.loserver.oreradar.content.RadarContent;
import net.loserver.oreradar.forge.client.OreRadarForgeClient;
import net.loserver.oreradar.server.PackGroups;
import net.loserver.oreradar.server.RadarCommands;
import net.loserver.oreradar.server.RadarServer;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.AddReloadListenerEvent;
import net.minecraftforge.event.BuildCreativeModeTabContentsEvent;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.TagsUpdatedEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.server.ServerAboutToStartEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.fml.loading.FMLEnvironment;

/**
 * Forge entrypoint: registers the content and the network channels, and forwards the server's
 * lifecycle, ticks, players, commands and datapack reloads to common code. On the physical client
 * it also starts {@link OreRadarForgeClient} (screens, HUD, outlines, the client bridge).
 */
@Mod(OreRadar.MOD_ID)
public final class OreRadarForge {
    public OreRadarForge() {
        OreRadar.init();
        ForgeNetworking.register();

        // FMLJavaModLoadingContext.get() rather than a constructor parameter: the jar must also load
        // on NeoForge 47.1, which predates that injection.
        IEventBus modBus = FMLJavaModLoadingContext.get().getModEventBus();
        modBus.addListener(OreRadarForge::addToCreativeTab);
        // Client classes are loaded only here, so a dedicated server never sees them.
        if (FMLEnvironment.dist == Dist.CLIENT) OreRadarForgeClient.init(modBus);

        IEventBus forgeBus = MinecraftForge.EVENT_BUS;
        // About to start, not starting: Forge fires ServerStartingEvent after the spawn and forced
        // chunks have loaded, and a station loaded before the radar's config would clamp its buffer
        // to the default capacity. The datapack and its tags are bound by then, as Fabric's
        // SERVER_STARTING finds them.
        forgeBus.addListener((ServerAboutToStartEvent event) -> RadarServer.start(event.getServer()));
        forgeBus.addListener((ServerStoppedEvent event) -> RadarServer.stop());
        forgeBus.addListener(OreRadarForge::onServerTick);
        forgeBus.addListener((PlayerEvent.PlayerLoggedOutEvent event) -> {
            RadarServer server = RadarServer.get();
            if (server != null) server.onPlayerLeft(event.getEntity().getUUID());
        });
        forgeBus.addListener((RegisterCommandsEvent event) -> RadarCommands.register(event.getDispatcher()));
        forgeBus.addListener((AddReloadListenerEvent event) -> event.addListener(new PackGroups()));
        forgeBus.addListener((TagsUpdatedEvent event) -> {
            if (event.getUpdateCause() == TagsUpdatedEvent.UpdateCause.SERVER_DATA_LOAD) RadarServer.onTagsLoaded();
        });
    }

    private static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        RadarServer server = RadarServer.get();
        if (server != null) server.tick();
    }

    private static void addToCreativeTab(BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey() == CreativeModeTabs.TOOLS_AND_UTILITIES) {
            event.accept(RadarContent.RADAR.get());
            event.accept(RadarContent.STATION_ITEM.get());
        }
    }
}
