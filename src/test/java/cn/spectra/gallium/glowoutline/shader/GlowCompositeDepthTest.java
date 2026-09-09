package cn.spectra.gallium.glowoutline.shader;

import cn.spectra.gallium.glowoutline.sr.streaming.SrStreamingCoordinator;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GlowCompositeDepthTest {

    @Test
    void modeledHackStateSpecPreservesEveryCurrentSceneDepthRoute() {
        // Exhaust all 32 combinations against the unchanged live depth selector.
        for (int flags = 0; flags < 32; flags++) {
            boolean hand = (flags & 1) != 0;
            boolean iris = (flags & 2) != 0;
            boolean display = (flags & 4) != 0;
            boolean foreground = (flags & 8) != 0;
            boolean firstPersonCamera = (flags & 16) != 0;
            var spec = SrStreamingCoordinator.currentHackOutputStateSpec(
                    hand ? SrStreamingCoordinator.CaptureDomain.FIRST_PERSON
                            : SrStreamingCoordinator.CaptureDomain.WORLD,
                    iris, display, foreground, firstPersonCamera);
            assertEquals(GlowComposite.chooseSuperResolutionSceneDepth(
                    hand, iris, display, foreground, firstPersonCamera).name(),
                    spec.sceneDepthRoute().name());
            assertEquals(hand ? SrStreamingCoordinator.MaskDepthStrategy.CLEAR_FAR
                    : SrStreamingCoordinator.MaskDepthStrategy.RAW_DISPLAY_COPY,
                    spec.maskDepthStrategy());
            assertEquals(SrStreamingCoordinator.PackTransformPolicy.OUTPUT_FULL_EXTENT,
                    spec.packTransformPolicy());
        }
    }

    @Test
    void firstPersonWorldMaskUsesLiveDepthWithoutIris() {
        assertTrue(GlowComposite.usesLiveMainDepth(false, true, false));
    }

    @Test
    void firstPersonHandMaskStillSelfCompares() {
        assertFalse(GlowComposite.usesLiveMainDepth(true, true, false));
    }

    @Test
    void thirdPersonWorldMaskUsesEarlyWorldDepth() {
        assertFalse(GlowComposite.usesLiveMainDepth(false, false, false));
    }

    @Test
    void irisWorldMaskUsesSnapshotContainingCustomHand() {
        assertFalse(GlowComposite.usesLiveMainDepth(false, true, true));
    }

    @Test
    void irisPreparedMaskPrefersCombinedDisplaySceneOverStaleForeground() {
        assertEquals(GlowComposite.SuperResolutionSceneDepth.DISPLAY_SCENE,
                GlowComposite.chooseSuperResolutionSceneDepth(
                        false, true, true, true, true));
    }

    @Test
    void irisPreparedMaskFallsBackToItsCopiedCombinedDepth() {
        assertEquals(GlowComposite.SuperResolutionSceneDepth.MASK,
                GlowComposite.chooseSuperResolutionSceneDepth(
                        false, true, false, false, true));
    }

    @Test
    void vanillaPreparedMaskUsesTheSeparatedForeground() {
        assertEquals(GlowComposite.SuperResolutionSceneDepth.FOREGROUND,
                GlowComposite.chooseSuperResolutionSceneDepth(
                        false, false, true, true, true));
    }

    @Test
    void firstPersonPreparedHandAlwaysSelfCompares() {
        assertEquals(GlowComposite.SuperResolutionSceneDepth.MASK,
                GlowComposite.chooseSuperResolutionSceneDepth(
                        true, true, true, true, true));
    }

    @Test
    void reverseZNormalizationRequiresEveryBoundDepthView() {
        assertTrue(GlowComposite.depthNormalizationComplete(true, true, true));
        assertTrue(GlowComposite.depthNormalizationComplete(true, true, false));
        assertFalse(GlowComposite.depthNormalizationComplete(false, true, true));
        assertFalse(GlowComposite.depthNormalizationComplete(true, false, false));
    }
}
