package cn.spectra.gallium.glowoutline.shader;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GlowCompositeDepthTest {

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
