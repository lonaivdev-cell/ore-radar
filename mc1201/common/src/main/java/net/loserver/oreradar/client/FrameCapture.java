package net.loserver.oreradar.client;

import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;

/**
 * The camera transform the world was drawn with this frame, so the outline pass can project block
 * edges onto the screen exactly where the world put them (1.0's approach).
 *
 * <p>Each loader captures it from inside {@code LevelRenderer#renderLevel}, where it is handed the
 * camera-rotation pose and the projection (view bobbing, hurt tilt and nausea included):
 * <ul>
 *   <li>Forge 47: {@code AFTER_PARTICLES} / {@code AFTER_WEATHER}. Not {@code AFTER_LEVEL}: Forge 47
 *       fires it from {@code GameRenderer} with the projection pose stack, which mirrors and freezes
 *       the overlay (1.0 learned this the hard way).</li>
 *   <li>Fabric: {@code WorldRenderEvents.LAST}, with the context's matrices.</li>
 * </ul>
 * Shader packs' shadow passes do not go through {@code renderLevel}, so they never overwrite this
 * with the sun's camera. Render thread only.
 */
public final class FrameCapture {
    static final Matrix4f VIEW = new Matrix4f();
    static final Matrix4f PROJECTION = new Matrix4f();
    static double cameraX;
    static double cameraY;
    static double cameraZ;
    /** Set once a world frame has been captured since the last world was joined. */
    static boolean valid;

    private FrameCapture() {}

    public static void capture(Matrix4f view, Matrix4f projection, Vec3 camera) {
        VIEW.set(view);
        PROJECTION.set(projection);
        cameraX = camera.x;
        cameraY = camera.y;
        cameraZ = camera.z;
        valid = true;
    }

    /** Forgets the last frame, so a new world never draws with the previous one's camera. */
    static void invalidate() {
        valid = false;
    }
}
