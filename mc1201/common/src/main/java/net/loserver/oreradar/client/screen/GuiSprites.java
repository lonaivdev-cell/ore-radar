package net.loserver.oreradar.client.screen;

import net.loserver.oreradar.OreRadar;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;

/**
 * The radar screen's sprite sheet, {@code textures/gui/radar.png} (256 x 256), drawn by
 * {@code tools/gui_textures.py}; the coordinates here and there must agree. Every piece is original
 * art in a vanilla-like grey beveled style.
 */
final class GuiSprites {
    static final ResourceLocation SHEET = OreRadar.id("textures/gui/radar.png");

    /** A small icon on the sheet, 16 x 16 unless noted. */
    record Icon(int u, int v, int width, int height) {
        Icon(int u, int v) {
            this(u, v, 16, 16);
        }

        void draw(GuiGraphics graphics, int x, int y) {
            graphics.blit(SHEET, x, y, u, v, width, height);
        }
    }

    // Nine-slice sources: u, v, size, border.
    private static final int PANEL_U = 0, PANEL_V = 0, PANEL_SIZE = 32, PANEL_BORDER = 4;
    private static final int INSET_U = 32, INSET_V = 0, INSET_SIZE = 16, INSET_BORDER = 1;
    private static final int DISPLAY_U = 48, DISPLAY_V = 0, DISPLAY_SIZE = 16, DISPLAY_BORDER = 2;

    static final int SLOT_SIZE = 18;
    static final Icon SLOT = new Icon(64, 0, SLOT_SIZE, SLOT_SIZE);
    /** The slot of the entry the radar tracks: a cyan inner frame. */
    static final Icon SLOT_TRACKED = new Icon(82, 0, SLOT_SIZE, SLOT_SIZE);
    static final Icon SCROLLER = new Icon(100, 0, 12, 15);
    static final Icon SCROLLER_DISABLED = new Icon(112, 0, 12, 15);
    /** The charge bar's fill, cyan (full) at the top to red (empty) at the bottom; stretched to the bar. */
    private static final int CHARGE_U = 124, CHARGE_V = 0, CHARGE_W = 6, CHARGE_H = 64;

    /** Side tabs: the selected one reaches 3 pixels into the panel's border to join it. */
    static final int TAB_HEIGHT = 21;
    static final Icon TAB_SELECTED = new Icon(0, 32, 31, TAB_HEIGHT);
    static final Icon TAB = new Icon(32, 32, 29, TAB_HEIGHT);

    static final Icon ICON_ALL = new Icon(0, 64);
    static final Icon ICON_FAVOURITES = new Icon(16, 64);
    static final Icon ICON_RECENT = new Icon(32, 64);
    static final Icon ICON_SETTINGS = new Icon(48, 64);
    static final Icon ICON_OUTLINES = new Icon(64, 64);
    static final Icon ICON_ARROW = new Icon(80, 64);
    static final Icon ICON_BEEP = new Icon(96, 64);
    static final Icon ICON_POWER = new Icon(112, 64);
    /** A red slash drawn over a toggle's icon while it is off. */
    static final Icon ICON_OFF = new Icon(128, 64);
    /** The favourite mark in a slot's corner. */
    static final Icon STAR = new Icon(144, 64, 8, 8);

    private GuiSprites() {}

    /** The beveled grey panel. */
    static void panel(GuiGraphics graphics, int x, int y, int width, int height) {
        graphics.blitNineSliced(SHEET, x, y, width, height, PANEL_BORDER, PANEL_SIZE, PANEL_SIZE, PANEL_U, PANEL_V);
    }

    /** A recessed light area, like a slot stretched (the scrollbar's track). */
    static void inset(GuiGraphics graphics, int x, int y, int width, int height) {
        graphics.blitNineSliced(SHEET, x, y, width, height, INSET_BORDER, INSET_SIZE, INSET_SIZE, INSET_U, INSET_V);
    }

    /** A recessed dark display (the preview, the charge bar). */
    static void display(GuiGraphics graphics, int x, int y, int width, int height) {
        graphics.blitNineSliced(SHEET, x, y, width, height, DISPLAY_BORDER, DISPLAY_SIZE, DISPLAY_SIZE, DISPLAY_U, DISPLAY_V);
    }

    /** The bottom {@code fraction} of a charge bar's interior, the gradient scaled to the full interior. */
    static void chargeFill(GuiGraphics graphics, int x, int y, int width, int height, float fraction) {
        int filled = Math.round(height * Math.max(0, Math.min(1, fraction)));
        if (filled <= 0) return;
        float v = CHARGE_V + CHARGE_H * (1 - filled / (float) height);
        int sourceHeight = Math.max(1, Math.round(CHARGE_H * filled / (float) height));
        graphics.blit(SHEET, x, y + height - filled, width, filled, CHARGE_U, v, CHARGE_W, sourceHeight, 256, 256);
    }
}
