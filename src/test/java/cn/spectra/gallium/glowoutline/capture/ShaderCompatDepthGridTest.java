package cn.spectra.gallium.glowoutline.capture;

import cn.spectra.gallium.glowoutline.ShaderPackHint.ProjectionTransform;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ShaderCompatDepthGridTest {
    @Test
    void knownProjectionIsInsufficientForPostUpscaleSingleSampleDepth() {
        assertTrue(GlowCaptureManager.usesExactShaderCompatDepth(
                new ProjectionTransform(1, 1, 0, 0, true)));
        assertFalse(GlowCaptureManager.usesExactShaderCompatDepth(null));
        assertFalse(GlowCaptureManager.usesExactShaderCompatDepth(ProjectionTransform.IDENTITY));
        // Even a zero-jitter frame still compares different raster positions when upscaling.
        assertFalse(GlowCaptureManager.usesExactShaderCompatDepth(
                new ProjectionTransform(284 / 854.0f, 160 / 480.0f, 0, 0, true)));
        assertFalse(GlowCaptureManager.usesExactShaderCompatDepth(
                new ProjectionTransform(1, 0.75f, 0, 0, true)));
        // DLAA also separates scene and display pixel centres on jittered frames.
        assertFalse(GlowCaptureManager.usesExactShaderCompatDepth(
                new ProjectionTransform(1, 1, 0.5f / 854, 0, true)));
        assertFalse(GlowCaptureManager.usesExactShaderCompatDepth(
                new ProjectionTransform(1, 1, 0, -0.5f / 480, true)));
        assertFalse(GlowCaptureManager.usesExactShaderCompatDepth(
                new ProjectionTransform(1, 1, 0, 0, 0.125f, 0, true)));
    }

    @Test
    void slopingItemFaceDoesNotOccludeItselfAsTheSourceGridJitters() {
        // Analytic depth of one planar item-frame face, sampled by two independent grids.
        // This recreates the failed comparison without relying on a GPU or a shader pack ZIP.
        int screenWidth = 854, screenHeight = 480, renderWidth = 284, renderHeight = 160;
        double u = 405.5 / screenWidth, v = 233.5 / screenHeight;
        double maskDepth = faceDepth(u, v);
        boolean singleSampleVisible = false, singleSampleOccluded = false;
        for (int frame = 1; frame <= 32; frame++) {
            double jitterX = halton(frame, 2) - 0.5, jitterY = halton(frame, 3) - 0.5;
            var transform = new ProjectionTransform(
                    renderWidth / (float) screenWidth, renderHeight / (float) screenHeight,
                    (float) (2 * jitterX / screenWidth), (float) (2 * jitterY / screenHeight), true);
            int x = (int) Math.floor(u * renderWidth + jitterX);
            int y = (int) Math.floor(v * renderHeight + jitterY);
            double nearest = sampledDepth(x, y, jitterX, jitterY, renderWidth, renderHeight);
            singleSampleVisible |= maskDepth <= nearest;
            singleSampleOccluded |= maskDepth > nearest;

            int radius = GlowCaptureManager.usesExactShaderCompatDepth(transform) ? 0 : 1;
            double sceneDepth = nearest;
            for (int dy = -radius; dy <= radius; dy++) {
                for (int dx = -radius; dx <= radius; dx++) {
                    sceneDepth = Math.max(sceneDepth, sampledDepth(
                            x + dx, y + dy, jitterX, jitterY, renderWidth, renderHeight));
                }
            }
            assertTrue(maskDepth <= sceneDepth, "The same face must remain visible at frame " + frame);
            // A genuinely nearer face covering the footprint still occludes the item.
            assertTrue(maskDepth > sceneDepth - 0.1, "Foreground must retain occlusion");
        }
        assertTrue(singleSampleVisible && singleSampleOccluded,
                "The original single-sample comparison must reproduce temporal self-occlusion");
    }

    private static double sampledDepth(int x, int y, double jitterX, double jitterY, int w, int h) {
        return faceDepth((x + 0.5 - jitterX) / w, (y + 0.5 - jitterY) / h);
    }

    private static double faceDepth(double u, double v) {
        return 0.5 + 0.2 * u - 0.15 * v;
    }

    private static double halton(int index, int base) {
        double result = 0, weight = 1;
        while (index > 0) {
            weight /= base;
            result += weight * (index % base);
            index /= base;
        }
        return result;
    }
}
