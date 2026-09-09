package cn.spectra.gallium.glowoutline.capture;

import cn.spectra.gallium.glowoutline.ShaderPackHint;
import org.joml.Matrix4f;
import org.joml.Vector4f;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
        assertSame(dest, result);
    }

    @Test
    void anisotropicScaleAndTemporalOffsetApplyAfterProjection() {
        Matrix4f base = new Matrix4f().perspective(
                (float) Math.toRadians(80), 16f / 9f, 0.05f, 500f);
        float scaleX = 0.6666f;
        float scaleY = 0.6662f;
        float jitterX = 0.00031f;
        float jitterY = -0.00047f;
        Matrix4f transformed = GlowCaptureManager.computeScaledProjection(
                base, scaleX, scaleY, jitterX, jitterY, 0.0f, new Matrix4f());

        Vector4f baseClip = project(base, 0.7f, -0.3f, -3.0f);
        Vector4f transformedClip = project(transformed, 0.7f, -0.3f, -3.0f);
        assertEquals(scaleX * ndcX(baseClip) + scaleX - 1.0f + jitterX,
                ndcX(transformedClip), 1e-6f);
        assertEquals(scaleY * ndcY(baseClip) + scaleY - 1.0f + jitterY,
                ndcY(transformedClip), 1e-6f);
        assertEquals(baseClip.z, transformedClip.z, 1e-5f);
        assertEquals(baseClip.w, transformedClip.w, 1e-5f);
    }

    @Test
    void exactTemporalReplayRequiresWorldProjectionAndDeclaredJitter() {
        ShaderPackHint.ProjectionTransform exact = new ShaderPackHint.ProjectionTransform(
                0.5f, 0.5f, 0.001f, -0.001f, true);
        assertTrue(GlowCaptureManager.usesExactTemporalReplay(false, true, exact));
        assertFalse(GlowCaptureManager.usesExactTemporalReplay(true, true, exact));
        assertFalse(GlowCaptureManager.usesExactTemporalReplay(false, false, exact));
        assertFalse(GlowCaptureManager.usesExactTemporalReplay(false, true,
                exact.withoutTemporalJitter()));
    }

    @Test
    void outputSpaceReplayDropsInputScaleOriginAndJitter() {
        ShaderPackHint.ProjectionTransform input = new ShaderPackHint.ProjectionTransform(
                0.5f, 0.4995f, 0.002f, -0.003f,
                0.125f, 0.25f, true);

        ShaderPackHint.ProjectionTransform output =
                GlowCaptureManager.projectionForReplay(input, true);

        assertEquals(1.0f, output.scaleX(), 0.0f);
        assertEquals(1.0f, output.scaleY(), 0.0f);
        assertEquals(0.0f, output.jitterX(), 0.0f);
        assertEquals(0.0f, output.jitterY(), 0.0f);
        assertEquals(0.0f, output.viewportOriginX(), 0.0f);
        assertEquals(0.0f, output.viewportOriginY(), 0.0f);
        assertFalse(output.exactTemporalJitter());
    }

    @Test
    void ordinaryShaderPackReplayKeepsItsDeclaredTransform() {
        ShaderPackHint.ProjectionTransform input = new ShaderPackHint.ProjectionTransform(
                0.5f, 0.5f, 0.002f, -0.003f, true);

        assertSame(input, GlowCaptureManager.projectionForReplay(input, false));
    }

    @Test
    void irisReplayFailsClosedWhenShaderBypassCouldNotBeEnabled() {
        assertFalse(GlowCaptureManager.canReplayWithShaderBypass(true, false));
        assertTrue(GlowCaptureManager.canReplayWithShaderBypass(true, true));
        assertTrue(GlowCaptureManager.canReplayWithShaderBypass(false, false));
    }

    @Test
    void exactIrisReplayDefersWorldOcclusionToComposite() {
        assertTrue(GlowCaptureManager.clearsMaskDepthForReplay(false, true, true));
        assertFalse(GlowCaptureManager.clearsMaskDepthForReplay(false, true, false));
        assertFalse(GlowCaptureManager.clearsMaskDepthForReplay(false, false, true));
        // First-person masks are intentionally independent of world depth, with or without Iris.
        assertTrue(GlowCaptureManager.clearsMaskDepthForReplay(true, false, false));
    }

    @Test
    void zBiasShiftsNearNdcZButNotFar() {
        // The z-bias is depth-adaptive: it pulls geometry toward the camera by ~zBias in the near
        // field (where sub-pixel TAA jitter maps to a large NDC-depth error) and tapers to zero at
        // the far plane (where the same jitter maps to a negligible error). A constant bias would
        // otherwise over-pull far geometry and let items just behind a wall pass the replay's
        // LEQUAL -> x-ray outlines through the wall at distance.
        Matrix4f base = new Matrix4f().perspective((float) Math.toRadians(70), 16f / 9f, 0.05f, 1000f);
        float zBias = 0.001f;
        Matrix4f biased = GlowCaptureManager.computeScaledProjection(base, 1.0f, zBias, new Matrix4f());

        // scale = 1 -> t = 0, so xy and w are untouched regardless of depth.
        for (float eyeZ : new float[]{-0.1f, -10f, -500f}) {
            Vector4f baseClip = project(base, 0.3f, -0.5f, eyeZ);
            Vector4f biasedClip = project(biased, 0.3f, -0.5f, eyeZ);
            assertEquals(ndcX(baseClip), ndcX(biasedClip), 1e-5f);
            assertEquals(ndcY(baseClip), ndcY(biasedClip), 1e-5f);
            assertEquals(baseClip.w, biasedClip.w, 1e-5f);
        }

        // Near field: NDC.z pulled toward the camera by ~zBias.
        Vector4f nearBase = project(base, 0.05f, -0.05f, -0.1f);
        Vector4f nearBiased = project(biased, 0.05f, -0.05f, -0.1f);
        assertEquals(nearBase.z / nearBase.w - zBias, nearBiased.z / nearBiased.w, 1e-4f);

        // Far field: essentially no shift (fixes far-distance x-ray through occluders).
        Vector4f farBase = project(base, 0.05f, -0.05f, -500f);
        Vector4f farBiased = project(biased, 0.05f, -0.05f, -500f);
        assertEquals(farBase.z / farBase.w, farBiased.z / farBiased.w, 1e-5f);
    }
}
