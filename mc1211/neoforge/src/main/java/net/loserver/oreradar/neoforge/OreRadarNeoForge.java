package net.loserver.oreradar.neoforge;

import net.loserver.oreradar.OreRadar;
import net.loserver.oreradar.content.RadarContent;
import net.loserver.oreradar.neoforge.client.OreRadarNeoForgeClient;
import net.loserver.oreradar.server.PackGroups;
import net.loserver.oreradar.server.RadarCommands;
import net.loserver.oreradar.server.RadarServer;
import net.minecraft.world.item.CreativeModeTabs;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.AddReloadListenerEvent;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.TagsUpdatedEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerAboutToStartEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * NeoForge entrypoint: registers the content, the payloads and the capabilities, and forwards the
 * server's lifecycle, ticks, players, commands and datapack reloads to common code. On the physical
 * client it also starts {@link OreRadarNeoForgeClient} (screens, HUD, outlines, the client bridge).
 */
@Mod(OreRadar.MOD_ID)
public final class OreRadarNeoForge {
    public OreRadarNeoForge(IEventBus modBus, ModContainer container) {
        OreRadar.init();
        modBus.addListener(NeoForgeNetworking::register);
        modBus.addListener(NeoForgeCapabilities::register);
        modBus.addListener(OreRadarNeoForge::addToCreativeTab);
        // Client classes are loaded only here, so a dedicated server never sees them.
        if (FMLEnvironment.dist == Dist.CLIENT) OreRadarNeoForgeClient.init(modBus, container);

        IEventBus gameBus = NeoForge.EVENT_BUS;
        // About to start, not starting: NeoForge fires ServerStartingEvent after the spawn and forced
        // chunks have loaded, and a station loaded before the radar's config would clamp its buffer
        // to the default capacity. The datapack and its tags are bound by then, as Fabric's
        // SERVER_STARTING finds them.
        gameBus.addListener((ServerAboutToStartEvent event) -> RadarServer.start(event.getServer()));
        gameBus.addListener((ServerStoppedEvent event) -> RadarServer.stop());
        gameBus.addListener((ServerTickEvent.Post event) -> {
            RadarServer server = RadarServer.get();
            if (server != null) server.tick();
        });
        gameBus.addListener((PlayerEvent.PlayerLoggedOutEvent event) -> {
            RadarServer server = RadarServer.get();
            if (server != null) server.onPlayerLeft(event.getEntity().getUUID());
        });
        gameBus.addListener((RegisterCommandsEvent event) -> RadarCommands.register(event.getDispatcher()));
        gameBus.addListener((AddReloadListenerEvent event) -> event.addListener(new PackGroups()));
        gameBus.addListener((TagsUpdatedEvent event) -> {
            if (event.getUpdateCause() == TagsUpdatedEvent.UpdateCause.SERVER_DATA_LOAD) RadarServer.onTagsLoaded();
        });
    }

    private static void addToCreativeTab(BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey() == CreativeModeTabs.TOOLS_AND_UTILITIES) {
            event.accept(RadarContent.RADAR.get());
            event.accept(RadarContent.STATION_ITEM.get());
        }
    }
}
