package net.loserver.oreradar.neoforge.client;

import com.mojang.blaze3d.pipeline.RenderTarget;
import net.loserver.oreradar.OreRadar;
import net.loserver.oreradar.client.FrameCapture;
import net.loserver.oreradar.client.OutlineRenderer;
import net.loserver.oreradar.client.RadarClient;
import net.loserver.oreradar.client.RadarHud;
import net.loserver.oreradar.client.RadarKeys;
import net.loserver.oreradar.client.screen.ChargingStationScreen;
import net.loserver.oreradar.client.screen.RadarSettingsScreen;
import net.loserver.oreradar.content.RadarContent;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.item.ItemProperties;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.ModList;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.event.RegisterMenuScreensEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;
import net.neoforged.neoforge.client.gui.VanillaGuiLayers;
import net.neoforged.neoforge.common.NeoForge;

/**
 * NeoForge client glue (DESIGN §6): creates the {@link RadarClient} and hooks it to NeoForge's
 * client events: hello on login, reset on logout, the client tick, the key mapping, the HUD layers,
 * the frame capture for the outlines, the station screen, the radar's model predicate, and the mod
 * list's Config button. Loaded only on the physical client.
 */
public final class OreRadarNeoForgeClient {
    private OreRadarNeoForgeClient() {}

    /** Called from the mod constructor, behind a {@code Dist.CLIENT} check. */
    public static void init(IEventBus modBus, ModContainer container) {
        RadarClient client = RadarClient.create(FMLPaths.CONFIGDIR.get(), OreRadarNeoForgeClient::modName);

        container.registerExtensionPoint(IConfigScreenFactory.class, (mod, parent) -> new RadarSettingsScreen(parent));

        modBus.addListener((FMLClientSetupEvent event) -> event.enqueueWork(
                () -> ItemProperties.register(RadarContent.RADAR.get(), OreRadar.id("state"), client.itemState)));
        modBus.addListener((RegisterMenuScreensEvent event) -> event.register(RadarContent.STATION_MENU.get(), ChargingStationScreen::new));
        modBus.addListener((RegisterKeyMappingsEvent event) -> event.register(RadarKeys.OPEN));
        modBus.addListener((RegisterGuiLayersEvent event) -> {
            // Below everything else on the HUD, like the world they belong to.
            event.registerBelowAll(OreRadar.id("outlines"), (graphics, delta) -> OutlineRenderer.renderHud(graphics));
            event.registerAbove(VanillaGuiLayers.CROSSHAIR, OreRadar.id("hud"), RadarHud::render);
        });

        IEventBus gameBus = NeoForge.EVENT_BUS;
        gameBus.addListener((ClientPlayerNetworkEvent.LoggingIn event) -> client.onJoin());
        gameBus.addListener((ClientPlayerNetworkEvent.LoggingOut event) -> client.onDisconnect());
        gameBus.addListener((ClientTickEvent.Post event) -> client.tick());
        gameBus.addListener(OreRadarNeoForgeClient::onRenderStage);
    }

    /**
     * Captures the frame's camera for the outlines: the event's model-view matrix is the camera
     * rotation and its projection the world's, at every stage (its pose stack holds no camera on
     * 1.21.1). {@code AFTER_WEATHER} also draws the depth-tested outlines, the last stage with the
     * world's depth still bound.
     */
    private static void onRenderStage(RenderLevelStageEvent event) {
        RenderLevelStageEvent.Stage stage = event.getStage();
        if (stage != RenderLevelStageEvent.Stage.AFTER_PARTICLES && stage != RenderLevelStageEvent.Stage.AFTER_WEATHER) return;
        FrameCapture.capture(event.getModelViewMatrix(), event.getProjectionMatrix(), event.getCamera().getPosition());
        if (stage != RenderLevelStageEvent.Stage.AFTER_WEATHER) return;
        // Fabulous graphics draw weather into a target of its own, which has no depth: draw into the
        // main target, which has, then hand the weather target back for the world border.
        RenderTarget weather = event.getLevelRenderer().getWeatherTarget();
        if (weather != null) Minecraft.getInstance().getMainRenderTarget().bindWrite(false);
        OutlineRenderer.renderWorld();
        if (weather != null) weather.bindWrite(false);
    }

    private static String modName(String modId) {
        return ModList.get().getModContainerById(modId).map(container -> container.getModInfo().getDisplayName()).orElse(modId);
    }
}
