package net.loserver.oreradar.client;

import java.util.Arrays;

import com.mojang.blaze3d.platform.Window;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexSorting;
import net.loserver.oreradar.core.config.ClientConfig;
import net.loserver.oreradar.core.config.EffectiveClientSettings;
import net.loserver.oreradar.core.geometry.OutlineGeometry;
import net.loserver.oreradar.core.net.BlockPosPacking;
import net.loserver.oreradar.core.net.ScanPacket;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.util.Mth;
import org.joml.Matrix4f;
import org.joml.Vector4f;
import org.lwjgl.opengl.GL11;

/**
 * Draws the merged vein silhouettes of the last scan (DESIGN §6 Outlines), ported from 1.0. Block
 * edges are projected with the camera of the frame ({@link FrameCapture}) and drawn as screen-space
 * quads: thick lines, a soft glow, a faint fill on exposed faces, the nearest vein brighter and
 * pulsing, everything farther a little fainter.
 *
 * <p>Two passes share that code:
 * <ul>
 *   <li><b>Through walls</b> (the server's default): on the HUD layer, below every other HUD element.
 *       The GUI pass comes after the world and any shader pack's composite, so the outlines look the
 *       same with Iris or Oculus as without.</li>
 *   <li><b>Depth-tested</b>, when the server sets {@code throughWalls = false}: during world
 *       rendering, with the world's depth buffer still bound (the GUI pass clears it). The same
 *       screen-space quads carry each point's depth, pulled a hair towards the camera so edges lying
 *       on a visible face do not z-fight with it.</li>
 * </ul>
 * Render thread only; the scratch arrays below are reused every frame.
 */
public final class OutlineRenderer {
    /** 1.0's line opacity and glow strength, which 2.0's config reduces to an on/off glow. */
    private static final float LINE_ALPHA = 0.9F;
    private static final float GLOW_ALPHA = 0.22F;
    /** Depth-tested points move this fraction of their distance towards the camera: 3 mm at 1 m, under a block at 256 m. */
    private static final float DEPTH_PULL = 0.997F;
    /** How much the farthest outline fades compared with the nearest (1.0's distance fade). */
    private static final float FADE = 0.65F;
    private static final long PULSE_MILLIS = 1400;
    /** The four corners of each face of a unit cube, by direction ordinal. */
    private static final int[][] FACE_CORNERS = new int[6][];

    static {
        for (int direction = 0; direction < 6; direction++) FACE_CORNERS[direction] = OutlineGeometry.faceCorners(direction);
    }

    // Scratch state.
    /** PROJECTION * VIEW for this frame. Clipping happens in its clip space, so the view bobbing, hurt
     *  tilt and nausea Minecraft folds into the projection cannot push points behind w = 0. */
    private static final Matrix4f CLIP = new Matrix4f();
    private static final Matrix4f PIXELS_TO_NDC = new Matrix4f();
    private static final Vector4f A = new Vector4f();
    private static final Vector4f B = new Vector4f();
    private static final Vector4f[] POLY_IN = vectors(4);
    private static final Vector4f[] POLY_OUT = vectors(8);
    private static final float[] XYZ = new float[3];
    private static final float[] POLY_XYZ = new float[3 * 8];
    /** Projected segments, 7 floats each: x0, y0, z0, x1, y1, z1, alpha. */
    private static float[] segments = new float[7 * 1024];
    private static int segmentCount;
    private static float framebufferWidth;
    private static float framebufferHeight;
    private static boolean withDepth;

    private OutlineRenderer() {}

    /**
     * The through-walls pass. Forge calls it from a "below all" GUI overlay, Fabric from the head of
     * {@code Gui.render}; it does nothing when the server wants the outlines depth-tested.
     */
    public static void renderHud(GuiGraphics graphics) {
        ScanView view = drawable(true);
        if (view == null) return;
        Window window = Minecraft.getInstance().getWindow();
        graphics.flush();
        float guiScale = (float) window.getGuiScale();
        graphics.pose().pushPose();
        // Framebuffer pixels: lines stay as thin as configured whatever the GUI scale.
        graphics.pose().scale(1F / guiScale, 1F / guiScale, 1F);
        RenderSystem.disableDepthTest();
        draw(view, graphics.pose().last().pose(), false);
        RenderSystem.depthMask(true);
        RenderSystem.enableDepthTest();
        graphics.pose().popPose();
    }

    /**
     * The depth-tested pass, called while the level renders with the main target and its depth
     * bound (Forge {@code AFTER_WEATHER}, Fabric {@code WorldRenderEvents.LAST}). Does nothing when
     * outlines show through walls.
     */
    public static void renderWorld() {
        ScanView view = drawable(false);
        if (view == null) return;
        PIXELS_TO_NDC.translation(-1F, 1F, 0F).scale(2F / framebufferWidth, -2F / framebufferHeight, 1F);
        // The stage decides whether depth writes are on (Forge's weather stage turns them off, Fabric's
        // LAST has them on); hand it back as found.
        boolean depthWrites = GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK);
        RenderSystem.backupProjectionMatrix();
        RenderSystem.setProjectionMatrix(new Matrix4f(), VertexSorting.ORTHOGRAPHIC_Z);
        PoseStack modelView = RenderSystem.getModelViewStack();
        modelView.pushPose();
        modelView.setIdentity();
        RenderSystem.applyModelViewMatrix();
        RenderSystem.enableDepthTest();
        draw(view, PIXELS_TO_NDC, true);
        RenderSystem.depthMask(depthWrites);
        modelView.popPose();
        RenderSystem.applyModelViewMatrix();
        RenderSystem.restoreProjectionMatrix();
    }

    /** The scan to draw in this pass, or null; also records the framebuffer size. */
    private static ScanView drawable(boolean throughWallsPass) {
        Minecraft mc = Minecraft.getInstance();
        RadarClient client = RadarClient.get();
        if (client == null || !client.isReady() || mc.options.hideGui || mc.player == null || !FrameCapture.valid) return null;
        EffectiveClientSettings settings = client.effective();
        if (!settings.outlines() || settings.outlinesThroughWalls() != throughWallsPass) return null;
        ScanView view = client.scan();
        if (view == null || view.status() != ScanPacket.Status.FOUND || !view.isFor(mc.level) || !view.hasOutlines()) return null;
        Window window = mc.getWindow();
        framebufferWidth = window.getWidth();
        framebufferHeight = window.getHeight();
        return framebufferWidth > 0 && framebufferHeight > 0 ? view : null;
    }

    private static void draw(ScanView view, Matrix4f pose, boolean depthTested) {
        ClientConfig config = RadarClient.get().config();
        withDepth = depthTested;
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.depthMask(false);
        RenderSystem.disableCull();
        RenderSystem.setShader(GameRenderer::getPositionColorShader);
        RenderSystem.setShaderColor(1F, 1F, 1F, 1F);

        int color = config.outlinesColor();
        int nearestColor = config.outlinesNearestColor();
        float lineWidth = (float) config.outlinesLineWidth() * Math.max(0.75F, framebufferHeight / 1080F);
        float fillAlpha = (float) config.outlinesFillAlpha();
        boolean glow = config.outlinesGlow();
        CLIP.set(FrameCapture.PROJECTION).mul(FrameCapture.VIEW);

        double cx = FrameCapture.cameraX;
        double cy = FrameCapture.cameraY;
        double cz = FrameCapture.cameraZ;
        long first = view.nearestMarker();
        double nearestDistance = Math.sqrt(distanceSquared(BlockPosPacking.x(first) + 0.5 - cx, BlockPosPacking.y(first) + 0.5 - cy,
                BlockPosPacking.z(first) + 0.5 - cz));
        double fadeSpan = Math.max(view.outlineRadius() - nearestDistance, 8);
        float pulse = 0.5F + 0.5F * Mth.sin((float) (Util.getMillis() % PULSE_MILLIS) / PULSE_MILLIS * Mth.TWO_PI);

        BufferBuilder buffer = Tesselator.getInstance().getBuilder();
        if (fillAlpha > 0.001F) {
            buffer.begin(VertexFormat.Mode.TRIANGLES, DefaultVertexFormat.POSITION_COLOR);
            addFaces(buffer, pose, view.others(), color, fillAlpha, nearestDistance, fadeSpan);
            addFaces(buffer, pose, view.nearestVein(), nearestColor, Math.min(1F, fillAlpha * 1.5F), nearestDistance, fadeSpan);
            BufferUploader.drawWithShader(buffer.end());
        }

        buffer.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
        projectEdges(view.others(), nearestDistance, fadeSpan);
        if (glow) emitSegments(buffer, pose, lineWidth * 3.5F, color, GLOW_ALPHA * LINE_ALPHA * 0.6F);
        emitSegments(buffer, pose, lineWidth, color, LINE_ALPHA);
        projectEdges(view.nearestVein(), nearestDistance, fadeSpan);
        if (glow) emitSegments(buffer, pose, lineWidth * (4F + 2F * pulse), nearestColor, GLOW_ALPHA * (0.5F + 0.4F * pulse));
        emitSegments(buffer, pose, lineWidth * 1.5F, nearestColor, 1F);
        BufferUploader.drawWithShader(buffer.end());

        RenderSystem.enableCull();
        RenderSystem.disableBlend();
    }

    /** The faint fill: each exposed face clipped to the near plane, fanned into triangles. */
    private static void addFaces(BufferBuilder buffer, Matrix4f pose, OutlineGeometry geometry, int rgb, float alpha,
                                 double nearestDistance, double fadeSpan) {
        int[] faces = geometry.faces();
        for (int i = 0; i < faces.length; i += 4) {
            int[] corners = FACE_CORNERS[faces[i + 3]];
            double ox = faces[i] - FrameCapture.cameraX;
            double oy = faces[i + 1] - FrameCapture.cameraY;
            double oz = faces[i + 2] - FrameCapture.cameraZ;
            float a = alpha * fade(ox + 0.5, oy + 0.5, oz + 0.5, nearestDistance, fadeSpan);
            for (int k = 0; k < 4; k++) {
                toClip(POLY_IN[k], ox + corners[3 * k], oy + corners[3 * k + 1], oz + corners[3 * k + 2]);
            }
            int n = clipPolygon();
            if (n < 3) continue;
            boolean projected = true;
            for (int k = 0; k < n && projected; k++) {
                projected = project(POLY_OUT[k], XYZ);
                System.arraycopy(XYZ, 0, POLY_XYZ, 3 * k, 3);
            }
            if (!projected) continue;
            for (int k = 1; k + 1 < n; k++) {
                vertex(buffer, pose, POLY_XYZ[0], POLY_XYZ[1], POLY_XYZ[2], rgb, a);
                vertex(buffer, pose, POLY_XYZ[3 * k], POLY_XYZ[3 * k + 1], POLY_XYZ[3 * k + 2], rgb, a);
                vertex(buffer, pose, POLY_XYZ[3 * k + 3], POLY_XYZ[3 * k + 4], POLY_XYZ[3 * k + 5], rgb, a);
            }
        }
    }

    /** Projects a silhouette's unit edges once into {@link #segments}, for the glow and the crisp pass. */
    private static void projectEdges(OutlineGeometry geometry, double nearestDistance, double fadeSpan) {
        segmentCount = 0;
        int[] edges = geometry.edges();
        for (int i = 0; i < edges.length; i += 4) {
            int axis = edges[i + 3];
            double ox = edges[i] - FrameCapture.cameraX;
            double oy = edges[i + 1] - FrameCapture.cameraY;
            double oz = edges[i + 2] - FrameCapture.cameraZ;
            double ex = axis == OutlineGeometry.AXIS_X ? 1 : 0;
            double ey = axis == OutlineGeometry.AXIS_Y ? 1 : 0;
            double ez = axis == OutlineGeometry.AXIS_Z ? 1 : 0;
            float a = fade(ox + ex / 2, oy + ey / 2, oz + ez / 2, nearestDistance, fadeSpan);
            addEdge(ox, oy, oz, ox + ex, oy + ey, oz + ez, a);
        }
    }

    /** 1 near the nearest match, falling to 1 - FADE at the far end of the outline radius. */
    private static float fade(double x, double y, double z, double nearestDistance, double span) {
        double t = Mth.clamp((Math.sqrt(distanceSquared(x, y, z)) - nearestDistance) / span, 0, 1);
        return (float) (1 - FADE * t);
    }

    private static double distanceSquared(double x, double y, double z) {
        return x * x + y * y + z * z;
    }

    /** Camera-relative world coordinates to clip space, pulled towards the camera when depth-tested. */
    private static void toClip(Vector4f out, double x, double y, double z) {
        float pull = withDepth ? DEPTH_PULL : 1F;
        out.set((float) x * pull, (float) y * pull, (float) z * pull, 1F);
        CLIP.transform(out);
    }

    /** Signed distance to the near plane in clip space (OpenGL: visible when z >= -w). */
    private static float near(Vector4f v) {
        return v.z + v.w;
    }

    /** Clips the edge a-b (camera-relative world coordinates) to the near plane and stores it projected. */
    private static void addEdge(double ax, double ay, double az, double bx, double by, double bz, float alpha) {
        toClip(A, ax, ay, az);
        toClip(B, bx, by, bz);
        float da = near(A);
        float db = near(B);
        if (da < 0 && db < 0) return;
        if (da < 0) lerp(A, B, da / (da - db));
        else if (db < 0) lerp(B, A, db / (db - da));
        if (!project(A, XYZ)) return;
        float x0 = XYZ[0];
        float y0 = XYZ[1];
        float z0 = XYZ[2];
        if (!project(B, XYZ)) return;
        float x1 = XYZ[0];
        float y1 = XYZ[1];
        float pad = 64;
        if ((x0 < -pad && x1 < -pad) || (y0 < -pad && y1 < -pad)
                || (x0 > framebufferWidth + pad && x1 > framebufferWidth + pad)
                || (y0 > framebufferHeight + pad && y1 > framebufferHeight + pad)) {
            return;
        }
        if (segmentCount * 7 + 7 > segments.length) segments = Arrays.copyOf(segments, segments.length * 2);
        int o = segmentCount * 7;
        segments[o] = x0;
        segments[o + 1] = y0;
        segments[o + 2] = z0;
        segments[o + 3] = x1;
        segments[o + 4] = y1;
        segments[o + 5] = XYZ[2];
        segments[o + 6] = alpha;
        segmentCount++;
    }

    /** Moves p a fraction t of the way towards q (all four clip-space components). */
    private static void lerp(Vector4f p, Vector4f q, float t) {
        p.set(p.x + (q.x - p.x) * t, p.y + (q.y - p.y) * t, p.z + (q.z - p.z) * t, p.w + (q.w - p.w) * t);
    }

    /** Sutherland-Hodgman against the near plane: POLY_IN[0..4) to POLY_OUT; returns the new count. */
    private static int clipPolygon() {
        int out = 0;
        for (int i = 0; i < 4; i++) {
            Vector4f cur = POLY_IN[i];
            Vector4f next = POLY_IN[(i + 1) % 4];
            float dc = near(cur);
            float dn = near(next);
            if (dc >= 0) POLY_OUT[out++].set(cur);
            if ((dc >= 0) != (dn >= 0)) {
                float t = dc / (dc - dn);
                POLY_OUT[out++].set(cur.x + (next.x - cur.x) * t, cur.y + (next.y - cur.y) * t,
                        cur.z + (next.z - cur.z) * t, cur.w + (next.w - cur.w) * t);
            }
        }
        return out;
    }

    /** Clip space to framebuffer pixels, plus normalised depth. False if the point cannot be projected. */
    private static boolean project(Vector4f clip, float[] out) {
        if (clip.w <= 1e-6F) return false;
        out[0] = (clip.x / clip.w * 0.5F + 0.5F) * framebufferWidth;
        out[1] = (0.5F - clip.y / clip.w * 0.5F) * framebufferHeight;
        out[2] = clip.z / clip.w;
        return true;
    }

    /** Each projected segment as a quad of the given width, extended by half the width at both ends. */
    private static void emitSegments(BufferBuilder buffer, Matrix4f pose, float width, int rgb, float alpha) {
        float hw = width * 0.5F;
        for (int i = 0; i < segmentCount; i++) {
            int o = i * 7;
            float x0 = segments[o];
            float y0 = segments[o + 1];
            float z0 = segments[o + 2];
            float x1 = segments[o + 3];
            float y1 = segments[o + 4];
            float z1 = segments[o + 5];
            float a = alpha * segments[o + 6];
            float dx = x1 - x0;
            float dy = y1 - y0;
            float length = Mth.sqrt(dx * dx + dy * dy);
            if (length < 1e-3F) {
                dx = 1;
                dy = 0;
                length = 1;
            }
            float ux = dx / length * hw;
            float uy = dy / length * hw;
            float nx = -uy;
            float ny = ux;
            float sx = x0 - ux;
            float sy = y0 - uy;
            float ex = x1 + ux;
            float ey = y1 + uy;
            vertex(buffer, pose, sx + nx, sy + ny, z0, rgb, a);
            vertex(buffer, pose, ex + nx, ey + ny, z1, rgb, a);
            vertex(buffer, pose, ex - nx, ey - ny, z1, rgb, a);
            vertex(buffer, pose, sx - nx, sy - ny, z0, rgb, a);
        }
    }

    /** One vertex; z is the normalised depth, which only the depth-tested pass keeps. */
    private static void vertex(BufferBuilder buffer, Matrix4f pose, float x, float y, float z, int rgb, float alpha) {
        buffer.vertex(pose, x, y, withDepth ? z : 0F)
                .color((rgb >> 16) & 255, (rgb >> 8) & 255, rgb & 255, Mth.clamp((int) (alpha * 255), 0, 255))
                .endVertex();
    }

    private static Vector4f[] vectors(int n) {
        Vector4f[] out = new Vector4f[n];
        for (int i = 0; i < n; i++) out[i] = new Vector4f();
        return out;
    }
}
