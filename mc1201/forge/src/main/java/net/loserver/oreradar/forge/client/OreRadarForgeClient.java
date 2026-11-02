package net.loserver.oreradar.forge.client;

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
import net.minecraft.client.gui.screens.MenuScreens;
import net.minecraft.client.renderer.item.ItemProperties;
import net.minecraftforge.client.ConfigScreenHandler;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.client.event.RegisterGuiOverlaysEvent;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.client.gui.overlay.VanillaGuiOverlay;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import net.minecraftforge.fml.loading.FMLPaths;

/**
 * Forge client glue (DESIGN §6): creates the {@link RadarClient} and hooks it to Forge's client
 * events: hello on login, reset on logout, the client tick, the key mapping, the HUD overlays, the
 * frame capture for the outlines, the station screen, the radar's model predicate, and the mod
 * list's Config button. Loaded only on the physical client.
 */
public final class OreRadarForgeClient {
    private OreRadarForgeClient() {}

    /** Called from the mod constructor, behind a {@code Dist.CLIENT} check. */
    public static void init(IEventBus modBus) {
        RadarClient client = RadarClient.create(FMLPaths.CONFIGDIR.get(), OreRadarForgeClient::modName);

        // ModLoadingContext.get(), deprecated in 47.4, because the jar must also load on NeoForge 47.1.
        ModLoadingContext.get().registerExtensionPoint(ConfigScreenHandler.ConfigScreenFactory.class,
                () -> new ConfigScreenHandler.ConfigScreenFactory((minecraft, parent) -> new RadarSettingsScreen(parent)));

        modBus.addListener((FMLClientSetupEvent event) -> event.enqueueWork(() -> {
            ItemProperties.register(RadarContent.RADAR.get(), OreRadar.id("state"), client.itemState);
            MenuScreens.register(RadarContent.STATION_MENU.get(), ChargingStationScreen::new);
        }));
        modBus.addListener((RegisterKeyMappingsEvent event) -> event.register(RadarKeys.OPEN));
        modBus.addListener((RegisterGuiOverlaysEvent event) -> {
            // Below everything else on the HUD, like the world they belong to.
            event.registerBelowAll("outlines", (gui, graphics, partialTick, width, height) -> OutlineRenderer.renderHud(graphics));
            event.registerAbove(VanillaGuiOverlay.CROSSHAIR.id(), "hud",
                    (gui, graphics, partialTick, width, height) -> RadarHud.render(graphics, partialTick));
        });

        IEventBus forgeBus = MinecraftForge.EVENT_BUS;
        forgeBus.addListener((ClientPlayerNetworkEvent.LoggingIn event) -> client.onJoin());
        forgeBus.addListener((ClientPlayerNetworkEvent.LoggingOut event) -> client.onDisconnect());
        forgeBus.addListener((TickEvent.ClientTickEvent event) -> {
            if (event.phase == TickEvent.Phase.END) client.tick();
        });
        forgeBus.addListener(OreRadarForgeClient::onRenderStage);
    }

    /**
     * Captures the frame's camera for the outlines. Forge 47 hands {@code AFTER_PARTICLES} and
     * {@code AFTER_WEATHER} the camera-rotation pose and the world's projection; {@code AFTER_LEVEL}
     * gets the projection pose instead, which mirrored and froze 1.0's overlay.
     * {@code AFTER_WEATHER} also draws the depth-tested outlines, the last stage with the world's
     * depth still bound.
     */
    private static void onRenderStage(RenderLevelStageEvent event) {
        RenderLevelStageEvent.Stage stage = event.getStage();
        if (stage != RenderLevelStageEvent.Stage.AFTER_PARTICLES && stage != RenderLevelStageEvent.Stage.AFTER_WEATHER) return;
        FrameCapture.capture(event.getPoseStack().last().pose(), event.getProjectionMatrix(), event.getCamera().getPosition());
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
