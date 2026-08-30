package cn.spectra.gallium.glowoutline.capture;

//#if MC>=1_21_05
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Regression coverage for HD-screenshot render-target transitions. */
class TextureCopyBoundsTest {

    @Test
    void rejectsReportedHighResolutionCopyIntoWindowSizedMask() {
        assertFalse(GlowCaptureManager.copyDimensionsMatch(
                7680, 4053, 2560, 1351, 7680, 4053));
    }

    @Test
    void rejectsReverseTransitionInsteadOfCopyingOnlyASubRectangle() {
        assertFalse(GlowCaptureManager.copyDimensionsMatch(
                2560, 1351, 7680, 4053, 2560, 1351));
    }

    @Test
    void rejectsSourceWhoseActualExtentDisagreesWithLogicalFrame() {
        assertFalse(GlowCaptureManager.copyDimensionsMatch(
                2560, 1351, 7680, 4053, 7680, 4053));
        assertFalse(GlowCaptureManager.copyDimensionsMatch(
                7680, 4053, 2560, 1351, 2560, 1351));
    }

    @Test
    void acceptsOnlyAnExactFullFrameCopy() {
        assertTrue(GlowCaptureManager.copyDimensionsMatch(
                7680, 4053, 7680, 4053, 7680, 4053));
    }

    @Test
    void rejectsNonPositiveFrameExtents() {
        assertFalse(GlowCaptureManager.copyDimensionsMatch(0, 4053, 0, 4053, 0, 4053));
        assertFalse(GlowCaptureManager.copyDimensionsMatch(2560, 0, 2560, 0, 2560, 0));
        assertFalse(GlowCaptureManager.copyDimensionsMatch(-1, 4053, -1, 4053, -1, 4053));
        assertFalse(GlowCaptureManager.copyDimensionsMatch(2560, -1, 2560, -1, 2560, -1));
    }
}
//#endif
