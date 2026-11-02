package net.loserver.oreradar.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.math.Axis;
import net.loserver.oreradar.core.config.ClientConfig;
import net.loserver.oreradar.core.config.EffectiveClientSettings;
import net.loserver.oreradar.core.config.HudAnchor;
import net.loserver.oreradar.core.energy.ChargeMath;
import net.loserver.oreradar.core.net.ConfigPacket;
import net.loserver.oreradar.core.net.ScanPacket;
import net.loserver.oreradar.item.TargetNames;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import org.joml.Matrix4f;

/**
 * The HUD (DESIGN §6): an arrow to the nearest match with its distance and height, at the crosshair
 * or in a corner ({@code hud.anchor}), the status when nothing is found, and a small charge bar.
 * Corner anchors also name the target with its icon, as 1.0 did. Drawn above the crosshair layer.
 */
public final class RadarHud {
    private static final int MARGIN = 4;
    /** Gap between the crosshair and the readout below it. */
    private static final int BELOW_CROSSHAIR = 10;
    private static final int ARROW_WIDTH = 12;
    private static final int ICON_WIDTH = 18;
    private static final int CHARGE_BAR_HEIGHT = 2;
    private static final int VERTICAL_ARROW_COLOR = 0xFF7FE0FF;
    private static final int ARROW_RIM = 0x101418;

    private RadarHud() {}

    public static void render(GuiGraphics graphics, DeltaTracker delta) {
        Minecraft mc = Minecraft.getInstance();
        RadarClient client = RadarClient.get();
        LocalPlayer player = mc.player;
        if (client == null || !client.isReady() || player == null || mc.options.hideGui) return;
        ClientConfig config = client.config();
        HudAnchor anchor = config.hudAnchor();
        ScanPacket scan = client.lastScan();
        if (anchor == HudAnchor.HIDDEN || scan == null) return;
        ScanPacket.Status status = scan.status();
        if (status == ScanPacket.Status.IDLE || status == ScanPacket.Status.OFF) return;

        EffectiveClientSettings settings = client.effective();
        ConfigPacket.Limits limits = client.limits();
        RadarClient.Nearest nearest = client.nearest(player);
        Font font = mc.font;

        // What to show: the arrow, a line of text, or both.
        float arrowDegrees = Float.NaN;
        boolean vertical = false;
        Component line = null;
        int lineColor = StatusText.color(status);
        if (status == ScanPacket.Status.FOUND) {
            if (nearest == null) return;
            if (settings.arrow()) {
                double horizontal = Math.sqrt(nearest.dx() * nearest.dx() + nearest.dz() * nearest.dz());
                if (horizontal >= 1.0) {
                    float targetYaw = (float) (Mth.atan2(-nearest.dx(), nearest.dz()) * Mth.RAD_TO_DEG);
                    // The partial tick the world was drawn with, so the arrow turns with the view.
                    arrowDegrees = Mth.wrapDegrees(targetYaw - player.getViewYRot(delta.getGameTimeDeltaPartialTick(true)));
                } else {
                    vertical = true;
                    arrowDegrees = nearest.height() >= 0 ? 0F : 180F;
                }
            }
            if (settings.distance()) {
                line = StatusText.distance(nearest, settings.height());
                lineColor = 0xFF000000 | config.outlinesColor();
            }
        } else {
            line = StatusText.lines(scan, limits, null, false, text -> true).get(0);
        }
        boolean hasArrow = !Float.isNaN(arrowDegrees);
        if (!hasArrow && line == null) return;

        boolean corner = anchor != HudAnchor.CROSSHAIR;
        boolean charge = config.hudShowCharge() && settings.energy();
        Component name = corner ? TargetNames.of(scan.spec()) : null;
        int textWidth = Math.max(line != null ? font.width(line) : 0, name != null ? font.width(name) : 0);
        int iconWidth = corner ? ICON_WIDTH : 0;
        int arrowWidth = hasArrow ? ARROW_WIDTH : 0;
        int boxWidth = iconWidth + arrowWidth + textWidth;
        int textHeight = corner ? 18 : 9;
        int boxHeight = textHeight + (charge ? CHARGE_BAR_HEIGHT + 2 : 0);

        int width = graphics.guiWidth();
        int height = graphics.guiHeight();
        float ax;
        float ay;
        float alignX;
        float alignY;
        switch (anchor) {
            case TOP_LEFT -> { ax = MARGIN; ay = MARGIN; alignX = 0; alignY = 0; }
            case TOP_CENTER -> { ax = width / 2F; ay = MARGIN; alignX = 0.5F; alignY = 0; }
            case TOP_RIGHT -> { ax = width - MARGIN; ay = MARGIN; alignX = 1; alignY = 0; }
            case BOTTOM_LEFT -> { ax = MARGIN; ay = height - MARGIN; alignX = 0; alignY = 1; }
            case BOTTOM_RIGHT -> { ax = width - MARGIN; ay = height - MARGIN; alignX = 1; alignY = 1; }
            default -> { ax = width / 2F; ay = height / 2F + BELOW_CROSSHAIR; alignX = 0.5F; alignY = 0; }
        }

        PoseStack pose = graphics.pose();
        pose.pushPose();
        pose.translate(ax, ay, 0);
        float scale = (float) config.hudScale();
        pose.scale(scale, scale, 1);
        int x = Math.round(-alignX * boxWidth);
        int y = Math.round(-alignY * boxHeight);
        if (corner) {
            graphics.fill(x - 2, y - 2, x + boxWidth + 2, y + boxHeight + 1, 0x80000000);
            CatalogModel.Entry entry = client.catalog().find(scan.spec());
            if (entry != null) graphics.renderFakeItem(entry.icon(), x, y + 1);
            graphics.drawString(font, name, x + iconWidth + arrowWidth, y, 0xFFFFFFFF, true);
            if (line != null) graphics.drawString(font, line, x + iconWidth + arrowWidth, y + 9, lineColor, true);
        } else if (line != null) {
            graphics.drawString(font, line, x + arrowWidth, y + 1, lineColor, true);
        }
        if (hasArrow) {
            int arrowColor = vertical ? VERTICAL_ARROW_COLOR : 0xFF000000 | config.outlinesColor();
            drawArrow(graphics, x + iconWidth + 5, y + textHeight / 2F, arrowDegrees, arrowColor);
        }
        if (charge) {
            int value = client.chargeOf(player, scan.slot());
            int capacity = limits.chargeCapacity();
            int barY = y + textHeight + 1;
            int filled = Math.round(boxWidth * Math.max(0, Math.min(1, value / (float) capacity)));
            graphics.fill(x, barY, x + boxWidth, barY + CHARGE_BAR_HEIGHT, 0x80000000);
            graphics.fill(x, barY, x + filled, barY + CHARGE_BAR_HEIGHT, 0xFF000000 | ChargeMath.barColor(value, capacity));
        }
        pose.popPose();
    }

    /** A small arrow centred on (x, y), pointing up at 0 degrees and turning clockwise (1.0's arrow). */
    private static void drawArrow(GuiGraphics graphics, float x, float y, float degrees, int argb) {
        graphics.flush();
        PoseStack pose = graphics.pose();
        pose.pushPose();
        pose.translate(x, y, 0);
        pose.mulPose(Axis.ZP.rotationDegrees(degrees));
        Matrix4f matrix = pose.last().pose();
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableCull();
        RenderSystem.disableDepthTest();
        RenderSystem.setShader(GameRenderer::getPositionColorShader);
        BufferBuilder buffer = Tesselator.getInstance().begin(VertexFormat.Mode.TRIANGLES, DefaultVertexFormat.POSITION_COLOR);
        float alpha = ((argb >>> 24) & 255) / 255F;
        // A dark rim first, then the arrow, so it reads on bright and dark backgrounds alike.
        arrowTriangles(buffer, matrix, 1.35F, ARROW_RIM, 0.8F * alpha);
        arrowTriangles(buffer, matrix, 1F, argb & 0xFFFFFF, alpha);
        BufferUploader.drawWithShader(buffer.buildOrThrow());
        RenderSystem.enableDepthTest();
        RenderSystem.enableCull();
        RenderSystem.disableBlend();
        pose.popPose();
    }

    private static void arrowTriangles(BufferBuilder buffer, Matrix4f matrix, float s, int rgb, float alpha) {
        float tipY = -5F * s;
        float baseY = 4F * s;
        float notchY = 1.5F * s;
        float half = 4F * s;
        vertex(buffer, matrix, 0, tipY, rgb, alpha);
        vertex(buffer, matrix, -half, baseY, rgb, alpha);
        vertex(buffer, matrix, 0, notchY, rgb, alpha);
        vertex(buffer, matrix, 0, tipY, rgb, alpha);
        vertex(buffer, matrix, 0, notchY, rgb, alpha);
        vertex(buffer, matrix, half, baseY, rgb, alpha);
    }

    private static void vertex(BufferBuilder buffer, Matrix4f matrix, float x, float y, int rgb, float alpha) {
        buffer.addVertex(matrix, x, y, 0F).setColor((rgb >> 16) & 255, (rgb >> 8) & 255, rgb & 255, Mth.clamp((int) (alpha * 255), 0, 255));
    }
}
