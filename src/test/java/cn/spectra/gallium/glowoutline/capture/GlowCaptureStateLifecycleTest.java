package cn.spectra.gallium.glowoutline.capture;

import cn.spectra.gallium.glowoutline.sr.streaming.SrStreamingCoordinator.CaptureDomain;
import cn.spectra.gallium.glowoutline.sr.streaming.SrStreamingCoordinator.CaptureStage;
import cn.spectra.gallium.glowoutline.sr.streaming.SrStreamingCoordinator.Eligibility;
import cn.spectra.gallium.glowoutline.sr.streaming.SrStreamingCoordinator.GenerationChange;
import cn.spectra.gallium.glowoutline.sr.streaming.SrStreamingCoordinator.SceneDepthCapturePolicy;
import cn.spectra.gallium.glowoutline.sr.streaming.SrStreamingCoordinator.SnapshotAuthority;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GlowCaptureStateLifecycleTest {

    @Test
    void phaseZeroShadowLifecycleTracksCaptureEligibilityAndDiscard() {
        GlowCaptureState state = new GlowCaptureState();
        state.beginCaptureLifecycle(17L, false);
        assertEquals(CaptureStage.CAPTURING, state.captureStage());

        assertTrue(state.markPayloadCaptured());
        assertTrue(state.markPayloadCaptured(), "additional submits remain legal in one scope");
        assertEquals(CaptureStage.CAPTURED, state.captureStage());

        assertTrue(state.markStreamingEligible(new Eligibility(
                17L, CaptureDomain.WORLD, 3L, SnapshotAuthority.AUTHORITATIVE)));
        assertEquals(CaptureStage.ELIGIBLE, state.captureStage());

        state.discardPayload();
        state.discardPayload();
        assertEquals(CaptureStage.INVALID, state.captureStage());

        state.resetFrame();
        assertEquals(CaptureStage.IDLE, state.captureStage());
    }

    @Test
    void authoritativeSceneGenerationInvalidatesPreparedDepthAndAbortsLegacyReplay() {
        GlowCaptureState pending = new GlowCaptureState();
        pending.beginCaptureLifecycle(3L, false);
        pending.markPayloadCaptured();
        pending.maskDepthPrepared = true;
        pending.maskDepthSnapshotGeneration = 4L;

        assertEquals(GenerationChange.INVALIDATE_PREPARED_DEPTH,
                GlowCaptureManager.applySceneDepthGeneration(pending, 5L));
        assertFalse(pending.maskDepthPrepared);
        assertEquals(-1L, pending.maskDepthSnapshotGeneration);

        GlowCaptureState alreadyReplayed = new GlowCaptureState();
        alreadyReplayed.beginCaptureLifecycle(3L, false);
        alreadyReplayed.markPayloadCaptured();
        alreadyReplayed.capturedThisFrame = true;
        alreadyReplayed.maskPreparedThisFrame = true;
        alreadyReplayed.superResolutionPrepared = true;
        alreadyReplayed.maskDepthPrepared = true;
        alreadyReplayed.maskDepthSnapshotGeneration = 4L;

        assertEquals(GenerationChange.SYSTEMIC_ABORT,
                GlowCaptureManager.applySceneDepthGeneration(alreadyReplayed, 5L));
        assertEquals(CaptureStage.INVALID, alreadyReplayed.captureStage());
        assertFalse(alreadyReplayed.capturedThisFrame);
        assertFalse(alreadyReplayed.maskPreparedThisFrame);
        assertFalse(alreadyReplayed.maskDepthPrepared);
    }

    @Test
    void ordinaryAttemptAbortsOnGenerationReplacementBeforePreparedFlagIsSet() {
        GlowCaptureState state = new GlowCaptureState();
        state.beginCaptureLifecycle(8L, false);
        state.markPayloadCaptured();
        state.finishCaptureScope();
        state.capturedThisFrame = true;
        state.config = new cn.spectra.gallium.glowoutline.ItemEffectConfig("test", java.util.List.of());
        state.markStreamingEligible(new Eligibility(8L, CaptureDomain.WORLD, 4L, SnapshotAuthority.AUTHORITATIVE));
        state.maskDepthSnapshotGeneration = 4L;
        assertTrue(state.beginOrdinaryReplayAttempt(8L));
        assertEquals(CaptureStage.ELIGIBLE, state.captureStage(), "ordinary shadow stage is not an SR schedule");
        assertFalse(state.maskPreparedThisFrame);
        assertEquals(GenerationChange.SYSTEMIC_ABORT, GlowCaptureManager.applySceneDepthGeneration(state, 5L));
        assertEquals(CaptureStage.INVALID, state.captureStage());
        assertTrue(state.hasPayloadReplayAttempted());
        assertFalse(state.beginOrdinaryReplayAttempt(8L));
    }

    @Test
    void sceneDepthDemandStartsOnlyAfterActualWorldSubmit() {
        GlowCaptureManager.beginFrame();
        assertFalse(GlowCaptureManager.needsSceneDepthCapture());

        GlowCaptureState firstPerson = new GlowCaptureState();
        firstPerson.beginCaptureLifecycle(0L, true);
        firstPerson.firstPerson = true;
        GlowCaptureManager.markCaptured(firstPerson);
        assertTrue(firstPerson.capturedThisFrame);
        assertFalse(GlowCaptureManager.needsSceneDepthCapture());

        GlowCaptureState world = new GlowCaptureState();
        world.beginCaptureLifecycle(0L, false);
        GlowCaptureManager.markCaptured(world);
        assertTrue(world.capturedThisFrame);
        assertTrue(GlowCaptureManager.needsSceneDepthCapture());

        GlowCaptureManager.beginFrame();
        assertFalse(GlowCaptureManager.needsSceneDepthCapture());
    }

    @Test
    void discardedPayloadCannotBeRevivedByALateSubmit() {
        GlowCaptureState state = new GlowCaptureState();
        state.beginCaptureLifecycle(0L, false);
        state.invalidateCapture();

        GlowCaptureManager.markCaptured(state);

        assertEquals(CaptureStage.INVALID, state.captureStage());
        assertFalse(state.capturedThisFrame);
    }

    @Test
    void firstPersonOnlyFrameDoesNotRequireOrAttemptAWorldSnapshot() {
        GlowCaptureManager.beginFrame();
        GlowCaptureState firstPerson = new GlowCaptureState();
        try {
            firstPerson.beginCaptureLifecycle(0L, true);
            firstPerson.firstPerson = true;
            GlowCaptureManager.markCaptured(firstPerson);
            GlowCaptureManager.getActiveStates().add(firstPerson);

            var result = GlowCaptureManager.captureSceneDepth(
                    null, SceneDepthCapturePolicy.SNAPSHOT_ONLY, false);

            assertEquals(GlowCaptureManager.SceneDepthSnapshotStatus.NOT_REQUIRED,
                    result.status());
            assertEquals(CaptureStage.CAPTURED, firstPerson.captureStage());
            assertTrue(firstPerson.capturedThisFrame);
        } finally {
            GlowCaptureManager.beginFrame();
        }
    }

    @Test
    void worldSnapshotFailureAbortsWorldWithoutTouchingFirstPersonPayload() {
        GlowCaptureManager.beginFrame();
        GlowCaptureState world = new GlowCaptureState();
        GlowCaptureState firstPerson = new GlowCaptureState();
        try {
            world.beginCaptureLifecycle(0L, false);
            firstPerson.beginCaptureLifecycle(0L, true);
            firstPerson.firstPerson = true;
            GlowCaptureManager.markCaptured(world);
            GlowCaptureManager.markCaptured(firstPerson);
            GlowCaptureManager.getActiveStates().add(world);
            GlowCaptureManager.getActiveStates().add(firstPerson);

            assertEquals(1, GlowCaptureManager.abortPayloadsInDomain(CaptureDomain.WORLD));
            assertEquals(0, GlowCaptureManager.abortPayloadsInDomain(CaptureDomain.WORLD));
            assertEquals(CaptureStage.INVALID, world.captureStage());
            assertFalse(world.capturedThisFrame);
            assertEquals(CaptureStage.CAPTURED, firstPerson.captureStage());
            assertTrue(firstPerson.capturedThisFrame);
        } finally {
            GlowCaptureManager.beginFrame();
        }
    }

    @Test
    void nestedCaptureScopesRestoreTheOuterCapture() throws Exception {
        GlowCaptureManager.beginFrame();
        Field current = GlowCaptureManager.class.getDeclaredField("currentCapture");
        current.setAccessible(true);
        GlowCaptureState outer = new GlowCaptureState();
        GlowCaptureState inner = new GlowCaptureState();
        try {
            current.set(null, outer);

            GlowCaptureManager.beginItemCaptureScope();
            GlowCaptureManager.endItemCapture();
            assertSame(outer, GlowCaptureManager.currentCapture(),
                    "a nested fast-return must not end the outer capture");

            GlowCaptureManager.beginItemCaptureScope();
            current.set(null, inner);
            GlowCaptureManager.markItemCaptureScopeStarted();
            GlowCaptureManager.endItemCapture();
            assertSame(outer, GlowCaptureManager.currentCapture(),
                    "a nested successful capture must restore its parent");
        } finally {
            current.set(null, null);
            GlowCaptureManager.beginFrame();
        }
    }

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
    void twentySixTwoRetainsOnlyNormallyDrainedSubmitStorage() {
        //#if MC>=1_26_02
        //$$ GlowCaptureState clean = new GlowCaptureState();
        //$$ clean.captureStorage = new net.minecraft.client.renderer.SubmitNodeStorage();
        //$$ clean.captureStorageClean = true;
        //$$ var retained = clean.captureStorage;
        //$$ clean.resetFrame();
        //$$ assertSame(retained, clean.captureStorage);
        //$$
        //$$ GlowCaptureState dirty = new GlowCaptureState();
        //$$ dirty.captureStorage = new net.minecraft.client.renderer.SubmitNodeStorage();
        //$$ dirty.captureStorageClean = false;
        //$$ dirty.resetFrame();
        //$$ assertNull(dirty.captureStorage);
        //#endif
    }

    @Test
    void captureBudgetBoundsWorstCaseFourKStates() {
        long fourKState = GlowCaptureManager.estimatedCaptureTargetBytes(
                3840, 2160, GlowCaptureManager.CAPTURE_TARGET_BYTES_PER_PIXEL);
        assertEquals(66_355_200L, fourKState);
        assertTrue(GlowCaptureManager.captureReservationFits(
                fourKState * 7L, 0L, fourKState,
                GlowCaptureManager.CAPTURE_TARGET_BUDGET_BYTES));
        assertFalse(GlowCaptureManager.captureReservationFits(
                fourKState * 8L, 0L, fourKState,
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
    void submitMirrorIsResettableAndHasNoCaptureConsumerLambdas() throws IOException {
        String source = Files.readString(findCanonicalSource(
                "src/main/java/cn/spectra/gallium/glowoutline/capture/DuplicatingSubmitNodeStorage.java"));

        assertTrue(source.contains("public void reset(SubmitNodeStorage delegate, GlowCaptureState state)"));
        assertTrue(source.contains("public void disableCapture()"));
        assertTrue(source.contains("public void detach()"));
        assertFalse(source.contains("java.util.function.Consumer"));
        assertFalse(source.contains("duplicate(0, c ->"));
        assertFalse(source.contains("dup(c ->"));
        assertTrue(source.indexOf("markCaptureStorageDirty(current)")
                        < source.indexOf("return capture.order(order)"),
                "storage must become dirty before a submit/order call can partially mutate it");
    }

    @Test
    void legacyWorldTeeMarksOnlyAfterARealVertexWrite() throws IOException {
        String source = Files.readString(findCanonicalSource(
                "src/main/java/cn/spectra/gallium/glowoutline/capture/CaptureSites.java"));

        assertTrue(source.contains(
                "capture.addVertex(x, y, z); mark();"));
        assertTrue(source.contains("public void disableCapture()"));
        assertTrue(source.contains(
                "if (mirrorCurrentVertex) capture.setColor(r, g, b, a);"));
        assertTrue(source.contains(
                "if (mirrorCurrentVertex) capture.setUv(u, v);"));
        assertFalse(source.contains(
                "GlowCaptureManager.markCaptured(state);\n//$$             return VertexMultiConsumer.create"));
    }

    @Test
    void worldStateAllocatorUsesTheCommittedActivePrefix() throws IOException {
        String source = Files.readString(findCanonicalSource(
                "src/main/java/cn/spectra/gallium/glowoutline/capture/GlowCaptureManager.java"));
        String allocator = between(source,
                "private static GlowCaptureState allocateState()",
                "private static void releaseState");

        assertTrue(allocator.contains("int index = activeStates.size();"));
        assertTrue(allocator.contains("pool.get(index)"));
        assertFalse(allocator.contains("for (GlowCaptureState state : pool)"));
    }

    @Test
    void reverseZMaskMirrorIsCompositeOwnedRatherThanPerState() throws IOException {
        String stateSource = Files.readString(findCanonicalSource(
                "src/main/java/cn/spectra/gallium/glowoutline/capture/GlowCaptureState.java"));
        String compositeSource = Files.readString(findCanonicalSource(
                "src/main/java/cn/spectra/gallium/glowoutline/shader/GlowComposite.java"));

        assertFalse(stateSource.contains("maskDepthForwardZTarget"));
        assertTrue(compositeSource.contains("maskDepthForwardZScratch"));
        assertTrue(compositeSource.contains(
                "encoder, mask.getDepthTextureView(), maskDepthForward"));
        assertEquals(8, GlowCaptureManager.CAPTURE_TARGET_BYTES_PER_PIXEL);
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

    @Test
    void vanillaWorldHookNotifiesCoordinatorBeforeEmptyFrameReturn() throws IOException {
        String source = Files.readString(findCanonicalSource(
                "src/main/java/cn/spectra/gallium/glowoutline/mixin/GameRendererMixin.java"));
        assertFalse(source.contains(
                "if (!GlowCaptureManager.needsSceneDepthCapture()) return;"));
        assertEquals(2, countOccurrences(
                source, "SuperResolutionCompat.captureVanillaSceneDepth(null);"),
                "modern and legacy branches must both observe an empty authoritative hook");
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

    private static int countOccurrences(String source, String needle) {
        int count = 0;
        int offset = 0;
        while ((offset = source.indexOf(needle, offset)) >= 0) {
            count++;
            offset += needle.length();
        }
        return count;
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
