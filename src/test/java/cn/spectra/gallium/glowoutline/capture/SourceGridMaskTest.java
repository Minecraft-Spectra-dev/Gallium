package cn.spectra.gallium.glowoutline.capture;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SourceGridMaskTest {
    @Test
    void sourceReplayUsesActualIndependentExtentsRatherThanAnAssumedThreeTimesScale() {
        assertTrue(GlowCaptureManager.usesSourceGridReplay(true, false, 284, 160, 854, 480));
        assertTrue(GlowCaptureManager.usesSourceGridReplay(true, false, 427, 240, 854, 480));
        assertTrue(GlowCaptureManager.usesSourceGridReplay(true, false, 1280, 720, 3840, 2160));
        assertTrue(GlowCaptureManager.usesSourceGridReplay(true, false, 639, 359, 1919, 1079));
        assertTrue(GlowCaptureManager.usesSourceGridReplay(true, false, 639, 480, 854, 480));
    }

    @Test
    void ordinaryFirstPersonAndAlreadyMatchingGridsStayOnTheirExistingPath() {
        assertFalse(GlowCaptureManager.usesSourceGridReplay(false, false, 284, 160, 854, 480));
        assertFalse(GlowCaptureManager.usesSourceGridReplay(true, true, 284, 160, 854, 480));
        assertFalse(GlowCaptureManager.usesSourceGridReplay(false, true, 284, 160, 854, 480));
        assertFalse(GlowCaptureManager.usesSourceGridReplay(true, false, 854, 480, 854, 480));
    }

    @Test
    void invalidOrDownsampledExtentsCannotEnterTheUpscaledScratchPath() {
        assertFalse(GlowCaptureManager.usesSourceGridReplay(true, false, 0, 160, 854, 480));
        assertFalse(GlowCaptureManager.usesSourceGridReplay(true, false, 284, -1, 854, 480));
        assertFalse(GlowCaptureManager.usesSourceGridReplay(true, false, 284, 160, 0, 480));
        assertFalse(GlowCaptureManager.usesSourceGridReplay(true, false, 855, 160, 854, 480));
        assertFalse(GlowCaptureManager.usesSourceGridReplay(true, false, 284, 481, 854, 480));
    }

    @Test
    void twoFixedScratchMasksFitWithOutputInsideTheUnchangedCap() {
        long cap = GlowCaptureManager.CAPTURE_TARGET_BUDGET_BYTES;
        assertEquals(512L * 1024 * 1024, cap);
        long output = GlowCaptureManager.estimatedCaptureTargetBytes(3840, 2160, 8);
        long source = GlowCaptureManager.estimatedCaptureTargetBytes(1280, 720, 8) + output;
        assertTrue(GlowCaptureManager.captureReservationFits(output, 0, source, cap));
        assertTrue(GlowCaptureManager.captureReservationFits(cap - source, 0, source, cap));
        assertFalse(GlowCaptureManager.captureReservationFits(cap - source + 1, 0, source, cap));
        assertFalse(GlowCaptureManager.captureReservationFits(cap, 0, source, cap));
    }
}
