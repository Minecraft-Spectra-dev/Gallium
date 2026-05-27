package cn.spectra.gallium.glowoutline.capture;

import org.joml.Matrix4f;
import org.joml.Vector4f;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Locks down the matrix used to align the mask with shader-pack {@code VertexDownscaling}.
 * The scale factor maps post-divide NDC corners to a {@code [-1, -1+2*scale]²} subrect — the
 * same subrect Iris's {@code gl_Position.xy = gl_Position.xy * scale - (1-scale) * gl_Position.w}
 * produces — and the rest of the projection's geometry must pass through unchanged.
 */
class ScaledProjectionTest {

    private static Vector4f project(Matrix4f m, float x, float y, float z) {
        Vector4f v = new Vector4f(x, y, z, 1.0f);
        return m.transform(v);
    }

    private static float ndcX(Vector4f clip) {
        return clip.x / clip.w;
    }

    private static float ndcY(Vector4f clip) {
        return clip.y / clip.w;
    }

    @Test
    void scaleOneIsIdentityOverProjection() {
        Matrix4f base = new Matrix4f().perspective((float) Math.toRadians(70), 16f / 9f, 0.05f, 1000f);
        Matrix4f scaled = GlowCaptureManager.computeScaledProjection(base, 1.0f, new Matrix4f());
        for (int i = 0; i < 16; i++) {
            assertEquals(base.get(i % 4, i / 4), scaled.get(i % 4, i / 4), 1e-6f);
        }
    }

    @Test
    void halfScaleMapsCornersIntoNegativeOneToZeroSubrect() {
        // A simple symmetric projection: the right-edge eye-space ray (x = z*tan(fov/2)) lands
        // at NDC x = +1 under base, and at NDC x = +1 * 0.5 - 0.5 = 0 under scale = 0.5.
        Matrix4f base = new Matrix4f().perspective((float) Math.toRadians(90), 1f, 0.1f, 100f);
        Matrix4f scaled = GlowCaptureManager.computeScaledProjection(base, 0.5f, new Matrix4f());

        // Eye-space point on the right-clip plane at z = -1 (forward in OpenGL eye space).
        // tan(45°) = 1 → x = 1.
        Vector4f baseClip = project(base, 1f, 0f, -1f);
        Vector4f scaledClip = project(scaled, 1f, 0f, -1f);
        assertEquals(1.0f, ndcX(baseClip), 1e-5f);
        assertEquals(0.0f, ndcX(scaledClip), 1e-5f);

        // Symmetric on the left.
        Vector4f leftBase = project(base, -1f, 0f, -1f);
        Vector4f leftScaled = project(scaled, -1f, 0f, -1f);
        assertEquals(-1.0f, ndcX(leftBase), 1e-5f);
        assertEquals(-1.0f, ndcX(leftScaled), 1e-5f);

        // Top edge at y = 1.
        Vector4f topBase = project(base, 0f, 1f, -1f);
        Vector4f topScaled = project(scaled, 0f, 1f, -1f);
        assertEquals(1.0f, ndcY(topBase), 1e-5f);
        assertEquals(0.0f, ndcY(topScaled), 1e-5f);
    }

    @Test
    void zAndWPassThroughUnchanged() {
        Matrix4f base = new Matrix4f().perspective((float) Math.toRadians(70), 16f / 9f, 0.05f, 1000f);
        Matrix4f scaled = GlowCaptureManager.computeScaledProjection(base, 0.6667f, new Matrix4f());

        Vector4f baseClip = project(base, 0.3f, -0.5f, -10f);
        Vector4f scaledClip = project(scaled, 0.3f, -0.5f, -10f);
        // Depth and w must match — only xy gets scaled. Otherwise depth-test against sceneDepth
        // would shift and outline occlusion would fail.
        assertEquals(baseClip.z, scaledClip.z, 1e-5f);
        assertEquals(baseClip.w, scaledClip.w, 1e-5f);
    }

    @Test
    void destOutputArgumentIsReused() {
        Matrix4f base = new Matrix4f().perspective((float) Math.toRadians(70), 16f / 9f, 0.05f, 1000f);
        Matrix4f dest = new Matrix4f();
        Matrix4f result = GlowCaptureManager.computeScaledProjection(base, 0.75f, dest);
        // computeScaledProjection writes into dest and returns the same instance — render-thread
        // reuse depends on this contract.
        assertEquals(System.identityHashCode(dest), System.identityHashCode(result));
    }
}
