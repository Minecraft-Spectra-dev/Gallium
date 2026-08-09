package cn.spectra.gallium.glowoutline.shader;

import org.junit.jupiter.api.Test;

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
}
