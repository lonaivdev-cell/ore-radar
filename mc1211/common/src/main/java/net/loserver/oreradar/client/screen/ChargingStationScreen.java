package net.loserver.oreradar.client.screen;

import net.loserver.oreradar.OreRadar;
import net.loserver.oreradar.item.RadarItem;
import net.loserver.oreradar.station.ChargingStationMenu;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;

/**
 * The charging station's screen (DESIGN §3): vanilla container layout with the fuel and radar slots,
 * a vertical charge bar for the buffer ("Buffer: 12,345 / 200,000 ⚡" on hover), and the arrow to
 * the radar lit while it charges. The texture and its sprite positions come from
 * {@code tools/textures.py}.
 */
public final class ChargingStationScreen extends AbstractContainerScreen<ChargingStationMenu> {
    private static final ResourceLocation TEXTURE = OreRadar.id("textures/gui/container/charging_station.png");
    /** The bar's empty interior on the background, and its fill sprite on the sheet. */
    private static final int BAR_X = 80, BAR_Y = 17, BAR_WIDTH = 16, BAR_HEIGHT = 54;
    private static final int FILL_U = 176, FILL_V = 0;
    /** The bar-to-radar arrow, and its lit sprite. */
    private static final int ARROW_X = 99, ARROW_Y = 39, ARROW_WIDTH = 13, ARROW_HEIGHT = 7;
    private static final int LIT_ARROW_U = 176, LIT_ARROW_V = 54;

    public ChargingStationScreen(ChargingStationMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);
        if (isHovering(BAR_X, BAR_Y, BAR_WIDTH, BAR_HEIGHT, mouseX, mouseY)) {
            graphics.renderTooltip(font, Component.translatable("oreradar.screen.station.buffer",
                    RadarItem.formatNumber(menu.buffer()), RadarItem.formatNumber(menu.bufferCapacity())), mouseX, mouseY);
        } else {
            renderTooltip(graphics, mouseX, mouseY);
        }
    }

    @Override
    protected void renderBg(GuiGraphics graphics, float partialTick, int mouseX, int mouseY) {
        graphics.blit(TEXTURE, leftPos, topPos, 0, 0, imageWidth, imageHeight);
        int capacity = menu.bufferCapacity();
        int filled = capacity > 0 ? (int) Math.round(BAR_HEIGHT * Math.min(1.0, Math.max(0, menu.buffer()) / (double) capacity)) : 0;
        if (filled > 0) {
            graphics.blit(TEXTURE, leftPos + BAR_X, topPos + BAR_Y + BAR_HEIGHT - filled, FILL_U, FILL_V + BAR_HEIGHT - filled, BAR_WIDTH, filled);
        }
        if (menu.isCharging()) {
            graphics.blit(TEXTURE, leftPos + ARROW_X, topPos + ARROW_Y, LIT_ARROW_U, LIT_ARROW_V, ARROW_WIDTH, ARROW_HEIGHT);
        }
    }
}
