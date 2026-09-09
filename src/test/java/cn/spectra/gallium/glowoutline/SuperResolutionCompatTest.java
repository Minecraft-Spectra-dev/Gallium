package cn.spectra.gallium.glowoutline;

import cn.spectra.gallium.glowoutline.capture.GlowCaptureManager.SceneDepthSnapshotStatus;
import cn.spectra.gallium.glowoutline.sr.streaming.SrStreamingCoordinator.CaptureMode;
import cn.spectra.gallium.glowoutline.sr.streaming.SrStreamingCoordinator.SnapshotSite;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SuperResolutionCompatTest {

    @Test
    void cIrisUsesReachableInlineHandBoundaryOnMigratedVersions() {
        assertTrue(SuperResolutionCompat.usesInlineHandCompletion(CaptureMode.A, true));
        assertTrue(SuperResolutionCompat.usesInlineHandCompletion(CaptureMode.B, false));
        assertFalse(SuperResolutionCompat.usesInlineHandCompletion(CaptureMode.C, false));
        assertFalse(SuperResolutionCompat.usesInlineHandCompletion(CaptureMode.UNKNOWN, true));
        //#if MC==1_21_11 || MC==1_26_01
        assertTrue(SuperResolutionCompat.usesInlineHandCompletion(CaptureMode.C, true));
        //#else
        //$$ assertFalse(SuperResolutionCompat.usesInlineHandCompletion(CaptureMode.C, true));
        //#endif
    }

    @Test
    void renderingLayerBuildGateMatchesTheFourSupportedMinecraftLines() {
        //#if MC==1_21_01 || MC==1_21_11 || MC==1_26_01 || MC==1_26_02
        assertTrue(SuperResolutionCompat.hasMainlineRenderingLayer());
        //#else
        //$$ assertFalse(SuperResolutionCompat.hasMainlineRenderingLayer());
        //#endif

        //#if MC==1_21_11 || MC==1_26_01 || MC==1_26_02
        assertTrue(SuperResolutionCompat.usesDeferredFinalHookBuild());
        //#else
        //$$ assertFalse(SuperResolutionCompat.usesDeferredFinalHookBuild());
        //#endif
    }

    @Test
    void onlyMainlineVersionsPassTheRuntimeGate() {
        assertFalse(SuperResolutionCompat.isMainlineVersion("0.8.3-alpha.5"));
        assertFalse(SuperResolutionCompat.isMainlineVersion("0.7.1-alpha.2"));
        assertTrue(SuperResolutionCompat.isMainlineVersion("0.9.0-alpha.1"));
        assertTrue(SuperResolutionCompat.isMainlineVersion("0.9.1-alpha.2+opengl"));
        assertTrue(SuperResolutionCompat.isMainlineVersion(
                "1.21-0.9.1-alpha.1+gl-fabric"));
        assertTrue(SuperResolutionCompat.isMainlineVersion(
                "26.1-0.9.1-alpha.1+gl-fabric"));
        assertFalse(SuperResolutionCompat.isMainlineVersion(
                "1.21.4-0.8.2-alpha.1-fabric"));
        assertTrue(SuperResolutionCompat.isMainlineVersion("1.0.0"));
        assertFalse(SuperResolutionCompat.isMainlineVersion("dev"));
    }

    @Test
    void shaderCompatDisplayReplayRequiresOneCompletedMatchingDispatch() {
        assertTrue(SuperResolutionCompat.completedShaderCompatFrameState(
                true, true, true, true, true, false,
                1280, 720, 3840, 2160, 3840, 2160,
                3840, 2160, "dlss"));
        assertFalse(SuperResolutionCompat.completedShaderCompatFrameState(
                true, false, true, true, true, false,
                1280, 720, 3840, 2160, 3840, 2160,
                3840, 2160, "dlss"));
        assertFalse(SuperResolutionCompat.completedShaderCompatFrameState(
                true, true, true, true, false, true,
                1280, 720, 3840, 2160, 3840, 2160,
                3840, 2160, "dlss"));
        assertFalse(SuperResolutionCompat.completedShaderCompatFrameState(
                true, true, true, true, true, false,
                1280, 720, 3840, 2160, 3840, 2160,
                2560, 1440, "dlss"));
        assertFalse(SuperResolutionCompat.completedShaderCompatFrameState(
                true, true, true, true, true, false,
                3840, 2160, 3840, 2160, 3840, 2160,
                3840, 2160, "none"));
        assertTrue(SuperResolutionCompat.completedShaderCompatFrameState(
                true, true, true, true, true, false,
                3840, 2160, 3840, 2160, 3840, 2160,
                3840, 2160, "dlss"));
        assertFalse(SuperResolutionCompat.completedShaderCompatFrameState(
                true, true, true, true, true, false,
                1280, 720, 3840, 2160, 2560, 1440,
                3840, 2160, "dlss"));
        assertFalse(SuperResolutionCompat.completedShaderCompatFrameState(
                true, true, true, true, true, false,
                4096, 720, 3840, 2160, 3840, 2160,
                3840, 2160, "dlss"));
    }

    @Test
    void vulkanCleanupFallbackRequiresOneActuallySkippedClientRenderFrame() {
        assertTrue(SuperResolutionCompat.shouldRepairVulkanCleanup(
                true, false, true, true));
        assertFalse(SuperResolutionCompat.shouldRepairVulkanCleanup(
                false, false, true, true));
        assertFalse(SuperResolutionCompat.shouldRepairVulkanCleanup(
                true, true, true, true));
        assertFalse(SuperResolutionCompat.shouldRepairVulkanCleanup(
                true, false, false, true));
        assertFalse(SuperResolutionCompat.shouldRepairVulkanCleanup(
                true, false, true, false));
    }

    @Test
    void healthyMainlineFinalHookOwnsTailFromTheFirstFrameEvenWithoutSr() {
        assertFalse(SuperResolutionCompat.shouldDeferLegacyCompositeAtTail(
                false, false));
        assertTrue(SuperResolutionCompat.shouldDeferLegacyCompositeAtTail(
                true, false));
        assertFalse(SuperResolutionCompat.shouldDeferLegacyCompositeAtTail(
                true, true));
    }

    @Test
    void missingFinalHookIsDetectedOnlyAfterARealPriorWorldFrame() {
        assertTrue(SuperResolutionCompat.missedDeferredFinalHook(
                true, true, true, false));
        assertFalse(SuperResolutionCompat.missedDeferredFinalHook(
                false, true, true, false));
        assertFalse(SuperResolutionCompat.missedDeferredFinalHook(
                true, false, true, false));
        assertFalse(SuperResolutionCompat.missedDeferredFinalHook(
                true, true, false, false));
        assertFalse(SuperResolutionCompat.missedDeferredFinalHook(
                true, true, true, true));
    }

    @Test
    void hackCompositeFallsBackForEveryIncompleteHookCombination() {
        assertEquals(SuperResolutionCompat.HackCompositeDecision.NOT_HACK,
                SuperResolutionCompat.decideHackComposite(
                        false, true, true, true, false, false));
        assertEquals(SuperResolutionCompat.HackCompositeDecision.COMPAT,
                SuperResolutionCompat.decideHackComposite(
                        true, true, true, true, false, false));
        // Iris renders both hand phases inside renderLevel, so no separate vanilla hand callback
        // is required for an otherwise complete hack frame.
        assertEquals(SuperResolutionCompat.HackCompositeDecision.COMPAT,
                SuperResolutionCompat.decideHackComposite(
                        true, true, true, false, false, false));
        // Without Iris, a missed A/B/C hand callback must fail closed to the final fallback.
        assertEquals(SuperResolutionCompat.HackCompositeDecision.FALLBACK,
                SuperResolutionCompat.decideHackComposite(
                        true, true, true, false, true, false));
        assertEquals(SuperResolutionCompat.HackCompositeDecision.FALLBACK,
                SuperResolutionCompat.decideHackComposite(
                        true, false, true, true, false, false));
        assertEquals(SuperResolutionCompat.HackCompositeDecision.FALLBACK,
                SuperResolutionCompat.decideHackComposite(
                        true, true, false, true, false, false));
        assertEquals(SuperResolutionCompat.HackCompositeDecision.FALLBACK,
                SuperResolutionCompat.decideHackComposite(
                        true, true, true, true, false, true));
    }

    @Test
    void handHookIsRequiredOnlyForVanillaFirstPersonWorldOcclusion() {
        assertTrue(SuperResolutionCompat.needsHandHook(
                false, true, true));
        assertFalse(SuperResolutionCompat.needsHandHook(
                true, true, true));
        assertFalse(SuperResolutionCompat.needsHandHook(
                false, false, true));
        assertFalse(SuperResolutionCompat.needsHandHook(
                false, true, false));
    }

    @Test
    void handSchedulingIsIndependentFromForegroundDepthCopy() {
        var iris = SuperResolutionCompat.handHookDecision(true, true, true);
        assertTrue(iris.scheduleFirstPerson());
        assertFalse(iris.copyForeground());

        var firstPersonOnly = SuperResolutionCompat.handHookDecision(false, true, false);
        assertTrue(firstPersonOnly.scheduleFirstPerson());
        assertFalse(firstPersonOnly.copyForeground());

        var vanillaOcclusion = SuperResolutionCompat.handHookDecision(false, true, true);
        assertTrue(vanillaOcclusion.scheduleFirstPerson());
        assertTrue(vanillaOcclusion.copyForeground());
    }

    @Test
    void upscaleCompletionBelongsOnlyToItsFrameEpoch() {
        assertTrue(SuperResolutionCompat.completedUpscaleForEpoch(17L, 17L));
        assertFalse(SuperResolutionCompat.completedUpscaleForEpoch(17L, 16L));
        assertFalse(SuperResolutionCompat.completedUpscaleForEpoch(17L, -1L));
    }

    @Test
    void firstPersonDomainCapabilityDoesNotDependOnForegroundCopyDemand() {
        assertTrue(SuperResolutionCompat.missedFirstPersonDomainHook(true, false));
        assertFalse(SuperResolutionCompat.missedFirstPersonDomainHook(false, false));
        assertFalse(SuperResolutionCompat.missedFirstPersonDomainHook(true, true));
    }

    @Test
    void authoritativeWorldCapabilityUsesOnlyTheModesRequiredSite() {
        var emptyA = SuperResolutionCompat.worldHookDecision(
                CaptureMode.A, SnapshotSite.VANILLA_PRE_HAND,
                SceneDepthSnapshotStatus.NOT_REQUIRED);
        var emptyC = SuperResolutionCompat.worldHookDecision(
                CaptureMode.C, SnapshotSite.VANILLA_PRE_HAND,
                SceneDepthSnapshotStatus.NOT_REQUIRED);
        assertTrue(emptyA.authoritativeSite());
        assertTrue(emptyC.authoritativeSite());
        assertTrue(emptyA.closeDomain());
        assertFalse(emptyA.abortWorldDomain());

        var provisionalA = SuperResolutionCompat.worldHookDecision(
                CaptureMode.A, SnapshotSite.SR_PRE_UPSCALE,
                SceneDepthSnapshotStatus.READY);
        var provisionalC = SuperResolutionCompat.worldHookDecision(
                CaptureMode.C, SnapshotSite.SR_PRE_UPSCALE,
                SceneDepthSnapshotStatus.READY);
        assertFalse(provisionalA.authoritativeSite());
        assertFalse(provisionalC.authoritativeSite());

        var vanillaB = SuperResolutionCompat.worldHookDecision(
                CaptureMode.B, SnapshotSite.VANILLA_PRE_HAND,
                SceneDepthSnapshotStatus.READY);
        var emptyAuthoritativeB = SuperResolutionCompat.worldHookDecision(
                CaptureMode.B, SnapshotSite.SR_PRE_UPSCALE,
                SceneDepthSnapshotStatus.NOT_REQUIRED);
        assertFalse(vanillaB.authoritativeSite());
        assertTrue(emptyAuthoritativeB.authoritativeSite());
        assertTrue(emptyAuthoritativeB.closeDomain());
    }

    @Test
    void failedWorldSnapshotAbortsOnlyItsDomainDecision() {
        var failed = SuperResolutionCompat.worldHookDecision(
                CaptureMode.A, SnapshotSite.VANILLA_PRE_HAND,
                SceneDepthSnapshotStatus.FAILED);
        assertTrue(failed.authoritativeSite());
        assertTrue(failed.closeDomain());
        assertTrue(failed.abortWorldDomain());
    }

    @Test
    void fallbackPromotesForEitherMaskOrSceneDepthMismatch() {
        assertFalse(SuperResolutionCompat.needsOutputSpacePromotion(
                false, false, false));
        assertFalse(SuperResolutionCompat.needsOutputSpacePromotion(
                true, true, true));
        assertTrue(SuperResolutionCompat.needsOutputSpacePromotion(
                true, false, true));
        assertTrue(SuperResolutionCompat.needsOutputSpacePromotion(
                true, true, false));
    }
}
