package cn.spectra.gallium.glowoutline.capture;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GlowCaptureStateLifecycleTest {

    @Test
    void frameResetClearsPrepareAndCompositeConsumption() {
        GlowCaptureState state = new GlowCaptureState();
        state.capturedThisFrame = true;
        state.maskPreparedThisFrame = true;
        state.compositedThisFrame = true;
        state.superResolutionPrepared = true;
        state.maskDepthPrepared = true;
        state.captureTargetBytesReserved = 123L;
        //#if MC>=1_26_02
        //$$ state.captureStorage = new net.minecraft.client.renderer.SubmitNodeStorage();
        //#endif

        state.resetFrame();

        assertFalse(state.capturedThisFrame);
        assertFalse(state.maskPreparedThisFrame);
        assertFalse(state.compositedThisFrame);
        assertFalse(state.superResolutionPrepared);
        assertFalse(state.maskDepthPrepared);
        // GPU attachment reservations outlive frame flags and remain globally accounted.
        assertEquals(123L, state.captureTargetBytesReserved);
        //#if MC>=1_26_02
        //$$ assertNull(state.captureStorage);
        //#endif
    }

    @Test
    void invalidationDropsPreparedStateAndUnreplayableStorage() {
        GlowCaptureState state = new GlowCaptureState();
        state.capturedThisFrame = true;
        state.maskPreparedThisFrame = true;
        state.superResolutionPrepared = true;
        state.maskDepthPrepared = true;
        //#if MC>=1_26_02
        //$$ state.captureStorage = new net.minecraft.client.renderer.SubmitNodeStorage();
        //#endif

        state.invalidateCapture();

        assertFalse(state.capturedThisFrame);
        assertFalse(state.maskPreparedThisFrame);
        assertFalse(state.superResolutionPrepared);
        assertFalse(state.maskDepthPrepared);
        //#if MC>=1_26_02
        //$$ assertNull(state.captureStorage);
        //#endif
    }

    @Test
    void captureBudgetBoundsWorstCaseFourKStates() {
        long fourKState = GlowCaptureManager.estimatedCaptureTargetBytes(3840, 2160, 12);
        assertEquals(99_532_800L, fourKState);
        assertTrue(GlowCaptureManager.captureReservationFits(
                fourKState * 4L, 0L, fourKState,
                GlowCaptureManager.CAPTURE_TARGET_BUDGET_BYTES));
        assertFalse(GlowCaptureManager.captureReservationFits(
                fourKState * 5L, 0L, fourKState,
                GlowCaptureManager.CAPTURE_TARGET_BUDGET_BYTES));
    }

    @Test
    void reservationReplacementIsOverflowSafe() {
        assertTrue(GlowCaptureManager.captureReservationFits(400L, 100L, 200L, 512L));
        assertFalse(GlowCaptureManager.captureReservationFits(400L, 100L, 213L, 512L));
        assertFalse(GlowCaptureManager.captureReservationFits(10L, 11L, 1L, 512L));
        assertEquals(Long.MAX_VALUE,
                GlowCaptureManager.estimatedCaptureTargetBytes(Integer.MAX_VALUE,
                        Integer.MAX_VALUE, Integer.MAX_VALUE));
    }

    @Test
    void ownedResourcesAreClosedExactlyOnceAndFailuresStayContained() {
        AtomicBoolean closed = new AtomicBoolean();
        assertTrue(GlowCaptureManager.closeOwnedResource(() -> closed.set(true)));
        assertTrue(closed.get());
        assertFalse(GlowCaptureManager.closeOwnedResource(
                () -> { throw new Exception("expected"); }));
        assertTrue(GlowCaptureManager.closeOwnedResource(null));
    }

    @Test
    void reverseZUnknownJitterDefersOnlyIrisVulkanWorldOcclusion() {
        assertTrue(GlowCaptureManager.shouldDeferReverseZWorldOcclusion(
                false, true, false, false));

        assertFalse(GlowCaptureManager.shouldDeferReverseZWorldOcclusion(
                true, true, false, false));
        assertFalse(GlowCaptureManager.shouldDeferReverseZWorldOcclusion(
                false, false, false, false));
        assertFalse(GlowCaptureManager.shouldDeferReverseZWorldOcclusion(
                false, true, true, false));
        assertFalse(GlowCaptureManager.shouldDeferReverseZWorldOcclusion(
                false, true, false, true));
    }

    @Test
    void immediateModeReplayUsesOnlyTheSourceThatActuallyCapturedVertices() throws IOException {
        String source = Files.readString(findCanonicalSource(
                "src/main/java/cn/spectra/gallium/glowoutline/capture/GlowCaptureManager.java"));

        String branch12106 = between(source,
                "//#elseif MC>=1_21_06", "//#elseif MC>=1_21_05");
        String branch12105 = between(source,
                "//#elseif MC>=1_21_05", "//#else");

        assertImmediateModeSourceContract(branch12106, "flush()");
        assertImmediateModeSourceContract(branch12105, "flushToTarget(state.maskTarget)");
    }

    @Test
    void foregroundReplacementValidatesNewSourceBeforeInvalidatingOldSnapshot() throws IOException {
        String source = Files.readString(findCanonicalSource(
                "src/main/java/cn/spectra/gallium/glowoutline/capture/GlowCaptureManager.java"));
        String method = between(source,
                "public static void captureForegroundDepth",
                "public static @Nullable TextureTarget getForegroundDepthTarget");
        int sourceValidation = method.indexOf(
                "if (srcDepth == null || !renderTargetSizeMatches(mainTarget, w, h)) return;");
        int snapshotInvalidation = method.indexOf("foregroundDepthCaptured = false;");

        assertTrue(sourceValidation >= 0 && snapshotInvalidation > sourceValidation,
                "a failed display replacement must retain the valid earlier hand-depth snapshot");
    }

    private static void assertImmediateModeSourceContract(String branch, String flushCall) {
        assertTrue(branch.contains(
                "state.customBufferSource == null || state.maskTarget == null"));
        assertTrue(branch.contains("state.customBufferSource." + flushCall));
        assertFalse(branch.contains("state.captureBuffers"),
                "1.21.5-1.21.8 replay must not touch the unallocated captureBuffers field");
    }

    private static String between(String source, String startMarker, String endMarker) {
        int start = source.indexOf(startMarker);
        int end = source.indexOf(endMarker, start + startMarker.length());
        assertTrue(start >= 0 && end > start,
                () -> "Missing preprocessor branch " + startMarker + " .. " + endMarker);
        return source.substring(start, end);
    }

    private static Path findCanonicalSource(String relativePath) {
        Path directory = Path.of("").toAbsolutePath();
        while (directory != null) {
            Path candidate = directory.resolve(relativePath);
            // A version subproject may have a generated, already-preprocessed src tree by the
            // time tests run. Only the repository root owns settings.gradle and the canonical
            // source whose mutually-exclusive branches this contract is intended to inspect.
            if (Files.isRegularFile(directory.resolve("settings.gradle"))
                    && Files.isRegularFile(candidate)) return candidate;
            directory = directory.getParent();
        }
        throw new AssertionError("Cannot locate canonical source " + relativePath);
    }
}
