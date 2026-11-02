package net.loserver.oreradar.fabric;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.loserver.oreradar.OreRadar;
import net.loserver.oreradar.client.FrameCapture;
import net.loserver.oreradar.client.OutlineRenderer;
import net.loserver.oreradar.client.RadarClient;
import net.loserver.oreradar.client.RadarHud;
import net.loserver.oreradar.client.RadarKeys;
import net.loserver.oreradar.client.screen.ChargingStationScreen;
import net.loserver.oreradar.content.RadarContent;
import net.minecraft.client.gui.screens.MenuScreens;
import net.minecraft.client.renderer.item.ItemProperties;

/**
 * Fabric client entrypoint (DESIGN §6): creates the {@link RadarClient}, routes the server-to-client
 * channels to it, and hooks it to Fabric's client events: hello on join, reset on disconnect, the
 * client tick, the key mapping, the HUD, the frame capture for the outlines, the station screen and
 * the radar's model predicate. The through-walls outlines are drawn by {@code GuiMixin}, below the
 * rest of the HUD, which no Fabric API event reaches on 1.20.1.
 */
public final class OreRadarFabricClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        OreRadar.LOGGER.info("{} client initialising", OreRadar.MOD_NAME);
        RadarClient client = RadarClient.create(FabricLoader.getInstance().getConfigDir(), OreRadarFabricClient::modName);
        FabricClientNetworking.registerClientReceivers();

        KeyBindingHelper.registerKeyBinding(RadarKeys.OPEN);
        ItemProperties.register(RadarContent.RADAR.get(), OreRadar.id("state"), client.itemState);
        MenuScreens.register(RadarContent.STATION_MENU.get(), ChargingStationScreen::new);

        ClientPlayConnectionEvents.JOIN.register((handler, sender, minecraft) -> client.onJoin());
        // Fabric may report a disconnect off the client thread.
        ClientPlayConnectionEvents.DISCONNECT.register((handler, minecraft) -> minecraft.execute(client::onDisconnect));
        ClientTickEvents.END_CLIENT_TICK.register(minecraft -> client.tick());

        // LAST runs inside renderLevel with the frame's camera-rotation pose and projection, after
        // the translucent layers, with the main target and its depth bound.
        WorldRenderEvents.LAST.register(context -> {
            FrameCapture.capture(context.matrixStack().last().pose(), context.projectionMatrix(), context.camera().getPosition());
            OutlineRenderer.renderWorld();
        });
        HudRenderCallback.EVENT.register(RadarHud::render);
    }

    private static String modName(String modId) {
        return FabricLoader.getInstance().getModContainer(modId).map(container -> container.getMetadata().getName()).orElse(modId);
    }
}
