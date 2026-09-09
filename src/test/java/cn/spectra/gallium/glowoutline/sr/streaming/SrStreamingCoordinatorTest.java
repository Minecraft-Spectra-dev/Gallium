package cn.spectra.gallium.glowoutline.sr.streaming;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static cn.spectra.gallium.glowoutline.sr.streaming.SrStreamingCoordinator.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SrStreamingCoordinatorTest {

    @Test
    void modernFinalHookGateExcludesEveryUnmigratedVersion() {
        assertFalse(supportsModernFinalHookBuild(1_21_01));
        assertFalse(supportsModernFinalHookBuild(1_21_03));
        assertFalse(supportsModernFinalHookBuild(1_21_04));
        assertFalse(supportsModernFinalHookBuild(1_21_05));
        assertFalse(supportsModernFinalHookBuild(1_21_08));
        assertFalse(supportsModernFinalHookBuild(1_21_10));
        assertTrue(supportsModernFinalHookBuild(1_21_11));
        assertTrue(supportsModernFinalHookBuild(1_26_01));
        assertTrue(supportsModernFinalHookBuild(1_26_02));
    }

    @Test
    void disabledCapabilityAlwaysForcesLegacyPerStatePrefill() {
        ModeCapability disabled = enabledCapability().withHook(
                HookKind.WORLD, HookCapabilityState.DISABLED);
        FramePolicy policy = derivePhaseOnePolicy(
                true, true, CaptureMode.A, false, disabled);

        assertEquals(FrameIntent.LEGACY, policy.intent());
        assertEquals(StreamingCapability.DISABLED, policy.capability());
        assertEquals(MaskOwnershipMode.PER_STATE, policy.ownershipMode());
        assertEquals(SceneDepthCapturePolicy.SNAPSHOT_AND_PREFILL,
                policy.sceneDepthCapturePolicy());
        assertThrows(IllegalArgumentException.class, () -> new FramePolicy(
                FrameIntent.HACK_STREAMING_CANDIDATE,
                StreamingCapability.DISABLED,
                MaskOwnershipMode.PER_STATE,
                SceneDepthCapturePolicy.SNAPSHOT_ONLY));
    }

    @Test
    void candidateProbingUsesSnapshotOnlyButRetainsPerStateMasks() {
        FramePolicy policy = derivePhaseOnePolicy(
                true, true, CaptureMode.B, false, ModeCapability.unknown());

        assertEquals(FrameIntent.HACK_STREAMING_CANDIDATE, policy.intent());
        assertEquals(StreamingCapability.PROBING, policy.capability());
        assertEquals(MaskOwnershipMode.PER_STATE, policy.ownershipMode());
        assertEquals(SceneDepthCapturePolicy.SNAPSHOT_ONLY,
                policy.sceneDepthCapturePolicy());
    }

    @Test
    void oldBuildAndUnknownModeCanNeverBecomeCandidates() {
        FramePolicy oldBuild = derivePhaseOnePolicy(
                false, true, CaptureMode.A, false, enabledCapability());
        FramePolicy unknownMode = derivePhaseOnePolicy(
                true, true, CaptureMode.UNKNOWN, false, enabledCapability());

        assertEquals(FrameIntent.LEGACY, oldBuild.intent());
        assertEquals(FrameIntent.LEGACY, unknownMode.intent());
        assertEquals(SceneDepthCapturePolicy.SNAPSHOT_AND_PREFILL,
                oldBuild.sceneDepthCapturePolicy());
        assertEquals(SceneDepthCapturePolicy.SNAPSHOT_AND_PREFILL,
                unknownMode.sceneDepthCapturePolicy());
    }

    @Test
    void firstPersonCameraKeepsModeProbingUntilHandHookIsEnabled() {
        ModeCapability noHand = enabledCapability().withHook(
                HookKind.FIRST_PERSON, HookCapabilityState.UNKNOWN);

        assertEquals(StreamingCapability.ENABLED,
                deriveStreamingCapability(noHand, false));
        assertEquals(StreamingCapability.PROBING,
                deriveStreamingCapability(noHand, true));
        FramePolicy firstPerson = derivePhaseOnePolicy(
                true, true, CaptureMode.C, true, noHand);
        assertEquals(MaskOwnershipMode.PER_STATE, firstPerson.ownershipMode());
        assertEquals(StreamingCapability.PROBING, firstPerson.capability());
    }

    @Test
    void backendOrderingOnlyGatesSharedReuseNotPerStateLateReplay() {
        for (HookCapabilityState backend : HookCapabilityState.values()) {
            ModeCapability capability = enabledCapability().withHook(HookKind.BACKEND_ORDERING, backend);
            assertEquals(StreamingCapability.ENABLED, lateReplayReadiness(capability, true));
            StreamingCapability shared = switch (backend) {
                case ENABLED -> StreamingCapability.ENABLED;
                case DISABLED -> StreamingCapability.DISABLED;
                case UNKNOWN, PROBING -> StreamingCapability.PROBING;
            };
            assertEquals(shared, sharedReuseReadiness(capability, true));
            assertEquals(shared, deriveStreamingCapability(capability, true));

            PreparedFramePlanFactory perState = new PreparedFramePlanFactory(30L, 30L);
            assertTrue(perState.eligibilityPreflight(List.of(eligibility(30L)), true));
            assertTrue(perState.selectHackExecutionMode(
                    FrameExecutionMode.HACK_PER_STATE_OUTPUT, capability, true));
            assertTrue(perState.prepareFrameResources(frameResources(), true));
            assertTrue(perState.validateFinalResources(true));
            PreparedFramePlan plan = perState.createFramePlan().orElseThrow();
            assertEquals(FrameExecutionMode.HACK_PER_STATE_OUTPUT, plan.frameMode());
            assertEquals(ReplayTargetMode.STATE_MASK, plan.resources().targetMode());
            assertFalse(plan.outputDepthAlignmentExact());

            PreparedFramePlanFactory sharedFactory = new PreparedFramePlanFactory(30L, 30L);
            sharedFactory.eligibilityPreflight(List.of(eligibility(30L)), true);
            assertEquals(backend == HookCapabilityState.ENABLED,
                    sharedFactory.selectHackExecutionMode(
                            FrameExecutionMode.HACK_SHARED_OUTPUT, capability, true));
            if (backend != HookCapabilityState.ENABLED) {
                assertEquals(PreparedFramePlanStage.DROPPED, sharedFactory.stage());
                assertTrue(sharedFactory.createFramePlan().isEmpty());
            }

            // The live Phase 0/1 policy has not acquired shared ownership or a new intent.
            FramePolicy livePolicy = derivePhaseOnePolicy(true, true, CaptureMode.A, true, capability);
            assertEquals(MaskOwnershipMode.PER_STATE, livePolicy.ownershipMode());
            assertEquals(backend == HookCapabilityState.DISABLED
                    ? FrameIntent.LEGACY : FrameIntent.HACK_STREAMING_CANDIDATE, livePolicy.intent());
        }
    }

    @Test
    void lateReplayStillRequiresEveryApplicableDomainAndFinalHook() {
        for (HookKind hook : List.of(HookKind.WORLD, HookKind.FIRST_PERSON,
                HookKind.UPSCALE_FINISH, HookKind.FINAL)) {
            for (HookCapabilityState missing : List.of(HookCapabilityState.UNKNOWN,
                    HookCapabilityState.PROBING, HookCapabilityState.DISABLED)) {
                ModeCapability capability = enabledCapability().withHook(hook, missing);
                StreamingCapability expected = missing == HookCapabilityState.DISABLED
                        ? StreamingCapability.DISABLED : StreamingCapability.PROBING;
                assertEquals(expected, lateReplayReadiness(capability, true));
                assertEquals(expected, sharedReuseReadiness(capability, true));
                assertEquals(hook == HookKind.FIRST_PERSON ? StreamingCapability.ENABLED : expected,
                        lateReplayReadiness(capability, false));

                PreparedFramePlanFactory factory = new PreparedFramePlanFactory(31L, 31L);
                factory.eligibilityPreflight(List.of(eligibility(31L)), true);
                assertFalse(factory.selectHackExecutionMode(
                        FrameExecutionMode.HACK_PER_STATE_OUTPUT, capability, true));
                assertTrue(factory.createFramePlan().isEmpty());
            }
        }
    }

    @Test
    void readyLateReplayStillEnforcesUpscaleEpochAndOneFrameSelection() {
        PreparedFramePlanFactory stale = new PreparedFramePlanFactory(32L, 31L);
        stale.eligibilityPreflight(List.of(eligibility(32L)), true);
        assertTrue(stale.selectHackExecutionMode(
                FrameExecutionMode.HACK_PER_STATE_OUTPUT, enabledCapability(), true));
        assertThrows(IllegalStateException.class, () -> stale.selectHackExecutionMode(
                FrameExecutionMode.HACK_SHARED_OUTPUT, enabledCapability(), true));
        stale.prepareFrameResources(frameResources(), true);
        stale.validateFinalResources(true);
        assertTrue(stale.createFramePlan().isEmpty());
    }

    @Test
    void currentHackSpecKeepsWorldRawAndHandClearInOnePreparedFrame() {
        Eligibility world = eligibility(33L);
        Eligibility hand = new Eligibility(33L, CaptureDomain.FIRST_PERSON,
                -1L, SnapshotAuthority.AUTHORITATIVE);
        PreparedFramePlan frame = preparedFramePlan(33L, List.of(world, hand), frameResources());
        ReplayPlanFactory factory = frame.replayPlanFactory();
        ReplayPlan worldPlan = factory.createPlan(world,
                currentHackOutputStateSpec(CaptureDomain.WORLD, true, true, false, true)).orElseThrow();
        ReplayPlan handPlan = factory.createPlan(hand,
                currentHackOutputStateSpec(CaptureDomain.FIRST_PERSON, true, true, false, true)).orElseThrow();
        assertSame(worldPlan.preparedFramePlan(), handPlan.preparedFramePlan());
        assertEquals(MaskDepthStrategy.RAW_DISPLAY_COPY, worldPlan.maskDepthStrategy());
        assertEquals(SceneDepthRoute.DISPLAY_SCENE, worldPlan.sceneDepthRoute());
        assertEquals(MaskDepthStrategy.CLEAR_FAR, handPlan.maskDepthStrategy());
        assertEquals(SceneDepthRoute.MASK, handPlan.sceneDepthRoute());
        assertFalse(worldPlan.outputDepthAlignmentExact());
        assertFalse(handPlan.outputDepthAlignmentExact());

        // Hand-only frames do not gain a dependency on world or foreground depth.
        PreparedFrameResources handResources = new PreparedFrameResources(
                ReplayTargetMode.STATE_MASK, -1L, -1L, -1L, 1920, 1080);
        PreparedFramePlan handOnly = preparedFramePlan(33L, List.of(hand), handResources);
        assertTrue(handOnly.replayPlanFactory().createPlan(hand,
                currentHackOutputStateSpec(CaptureDomain.FIRST_PERSON, false, false, false, true)).isPresent());
    }

    @Test
    void capabilityEvidenceIsIsolatedByCaptureModeAndHasFiveFacts() {
        CapabilityTracker tracker = new CapabilityTracker();
        tracker.update(CaptureMode.A, HookKind.WORLD, HookCapabilityState.ENABLED);
        tracker.update(CaptureMode.A, HookKind.UPSCALE_FINISH, HookCapabilityState.PROBING);
        tracker.update(CaptureMode.A, HookKind.BACKEND_ORDERING,
                HookCapabilityState.DISABLED);

        assertEquals(HookCapabilityState.ENABLED,
                tracker.capability(CaptureMode.A).worldHook());
        assertEquals(HookCapabilityState.PROBING,
                tracker.capability(CaptureMode.A).upscaleFinishHook());
        assertEquals(HookCapabilityState.DISABLED,
                tracker.capability(CaptureMode.A).backendOrdering());
        assertEquals(ModeCapability.unknown(), tracker.capability(CaptureMode.C));
        assertThrows(UnsupportedOperationException.class,
                () -> tracker.snapshot().put(CaptureMode.B, enabledCapability()));
    }

    @Test
    void disabledCapabilityIsTerminalUntilAbiReset() {
        CapabilityTracker tracker = new CapabilityTracker();
        tracker.update(CaptureMode.A, HookKind.WORLD, HookCapabilityState.DISABLED);
        tracker.update(CaptureMode.A, HookKind.WORLD, HookCapabilityState.ENABLED);
        assertEquals(HookCapabilityState.DISABLED,
                tracker.capability(CaptureMode.A).worldHook());

        tracker.resetMode(CaptureMode.A);
        tracker.update(CaptureMode.A, HookKind.WORLD, HookCapabilityState.PROBING);
        assertEquals(HookCapabilityState.PROBING,
                tracker.capability(CaptureMode.A).worldHook());
    }

    @Test
    void handlerAbiFingerprintIsRememberedPerCaptureMode() {
        CapabilityTracker tracker = new CapabilityTracker();
        assertFalse(tracker.observeHandlerAbiFingerprint(CaptureMode.A, 10));
        tracker.update(CaptureMode.A, HookKind.WORLD, HookCapabilityState.ENABLED);
        assertFalse(tracker.observeHandlerAbiFingerprint(CaptureMode.B, 20));
        tracker.update(CaptureMode.B, HookKind.WORLD, HookCapabilityState.ENABLED);
        assertEquals(HookCapabilityState.ENABLED,
                tracker.capability(CaptureMode.A).worldHook());

        assertTrue(tracker.observeHandlerAbiFingerprint(CaptureMode.A, 11));
        assertEquals(ModeCapability.unknown(), tracker.capability(CaptureMode.A));
        assertEquals(HookCapabilityState.ENABLED,
                tracker.capability(CaptureMode.B).worldHook());
    }

    @Test
    void domainClosureOnlyMakesAlreadyCapturedMatchingStatesEligible() {
        DomainLifecycle domains = new DomainLifecycle(40L);
        assertTrue(domains.canBeginCapture(40L, CaptureDomain.WORLD));
        assertTrue(domains.close(40L, CaptureDomain.WORLD));
        assertFalse(domains.close(40L, CaptureDomain.WORLD));
        assertFalse(domains.canBeginCapture(40L, CaptureDomain.WORLD));
        assertTrue(domains.canBeginCapture(40L, CaptureDomain.FIRST_PERSON));

        Optional<Eligibility> eligible = domains.eligibilityFor(
                40L, CaptureDomain.WORLD, CaptureStage.CAPTURED, 7L,
                SnapshotAuthority.AUTHORITATIVE);
        assertTrue(eligible.isPresent());
        assertEquals(7L, eligible.orElseThrow().snapshotGeneration());
        assertTrue(domains.eligibilityFor(
                40L, CaptureDomain.WORLD, CaptureStage.CAPTURING, 7L,
                SnapshotAuthority.AUTHORITATIVE).isEmpty());
        assertTrue(domains.eligibilityFor(
                39L, CaptureDomain.WORLD, CaptureStage.CAPTURED, 7L,
                SnapshotAuthority.AUTHORITATIVE).isEmpty());
    }

    @Test
    void snapshotAuthorityMatchesCaptureModesAndOnlyBReplacesVanilla() {
        assertEquals(SnapshotAuthority.AUTHORITATIVE,
                worldSnapshotAuthority(CaptureMode.A, SnapshotSite.VANILLA_PRE_HAND));
        assertEquals(SnapshotAuthority.PROVISIONAL,
                worldSnapshotAuthority(CaptureMode.A, SnapshotSite.SR_PRE_UPSCALE));
        assertEquals(SnapshotAuthority.PROVISIONAL,
                worldSnapshotAuthority(CaptureMode.B, SnapshotSite.VANILLA_PRE_HAND));
        assertEquals(SnapshotAuthority.AUTHORITATIVE,
                worldSnapshotAuthority(CaptureMode.B, SnapshotSite.SR_PRE_UPSCALE));
        assertEquals(SnapshotAuthority.AUTHORITATIVE,
                worldSnapshotAuthority(CaptureMode.C, SnapshotSite.VANILLA_PRE_HAND));

        assertEquals(SnapshotUpdateDecision.VALIDATE_ONLY, decideSnapshotUpdate(
                Optional.of(SnapshotAuthority.AUTHORITATIVE),
                SnapshotAuthority.PROVISIONAL));
        assertEquals(SnapshotUpdateDecision.REPLACE, decideSnapshotUpdate(
                Optional.of(SnapshotAuthority.PROVISIONAL),
                SnapshotAuthority.AUTHORITATIVE));
        assertEquals(9L, nextSnapshotGeneration(
                9L, SnapshotUpdateDecision.VALIDATE_ONLY, true));
        assertEquals(10L, nextSnapshotGeneration(
                9L, SnapshotUpdateDecision.REPLACE, true));
    }

    @Test
    void worldDomainClosesAtAuthoritativeOrFinalFallbackBoundary() {
        assertTrue(shouldCloseWorldDomain(
                SnapshotSite.VANILLA_PRE_HAND,
                SnapshotAuthority.AUTHORITATIVE, true));
        assertFalse(shouldCloseWorldDomain(
                SnapshotSite.VANILLA_PRE_HAND,
                SnapshotAuthority.PROVISIONAL, true));
        assertTrue(shouldCloseWorldDomain(
                SnapshotSite.VANILLA_PRE_HAND,
                SnapshotAuthority.AUTHORITATIVE, false));
        assertTrue(shouldCloseWorldDomain(
                SnapshotSite.SR_PRE_UPSCALE,
                SnapshotAuthority.PROVISIONAL, false));
    }

    @Test
    void failedAuthoritativeReplacementRetainsFrozenProvisionalSnapshot() {
        WorldDomainSnapshot provisional = new WorldDomainSnapshot(
                9L, true, SnapshotSite.VANILLA_PRE_HAND,
                SnapshotAuthority.PROVISIONAL, true,
                1280, 720, 3L, 10, 20);
        WorldDomainSnapshot failedAuthoritative = new WorldDomainSnapshot(
                9L, true, SnapshotSite.SR_PRE_UPSCALE,
                SnapshotAuthority.AUTHORITATIVE, false,
                1280, 720, 3L, 11, 21);
        assertSame(provisional,
                selectFrozenWorldSnapshot(provisional, failedAuthoritative));

        WorldDomainSnapshot authoritative = new WorldDomainSnapshot(
                9L, true, SnapshotSite.SR_PRE_UPSCALE,
                SnapshotAuthority.AUTHORITATIVE, true,
                1280, 720, 4L, 11, 21);
        assertSame(authoritative,
                selectFrozenWorldSnapshot(provisional, authoritative));
    }

    @Test
    void changedGenerationInvalidatesPreparedDepthBeforeReplayAndAbortsAfter() {
        assertEquals(GenerationChange.UNCHANGED,
                generationChange(CaptureStage.ELIGIBLE, 3L, 3L));
        assertEquals(GenerationChange.INVALIDATE_PREPARED_DEPTH,
                generationChange(CaptureStage.ELIGIBLE, 2L, 3L));
        assertEquals(GenerationChange.SYSTEMIC_ABORT,
                generationChange(CaptureStage.REPLAY_ATTEMPTED, 2L, 3L));
        assertEquals(GenerationChange.SYSTEMIC_ABORT,
                generationChange(CaptureStage.COMPOSITED, 2L, 3L));
    }

    @Test
    void handSchedulingDoesNotDependOnAnUnneededForegroundCopy() {
        HandDomainSnapshot irisHand = new HandDomainSnapshot(
                1L, true, SnapshotSite.INLINE_HAND_TAIL,
                true, 2, false, false, -1L, 1920, 1080);
        HandDomainSnapshot vanillaFailure = new HandDomainSnapshot(
                1L, true, SnapshotSite.SR_SEPARATED_HAND_PRE_RESTORE,
                true, 2, true, false, -1L, 1920, 1080);

        assertTrue(irisHand.ready());
        assertFalse(vanillaFailure.ready());
    }

    @Test
    void preparedFrameFactoryEnforcesPreflightPrepareValidatePlanOrder() {
        PreparedFramePlanFactory outOfOrder = new PreparedFramePlanFactory(5L, 5L);
        assertThrows(IllegalStateException.class,
                () -> outOfOrder.selectExecutionMode(
                        FrameExecutionMode.HACK_PER_STATE_OUTPUT));

        Eligibility eligibility = eligibility(5L);
        PreparedFrameResources resources = frameResources();
        PreparedFramePlanFactory factory = new PreparedFramePlanFactory(5L, 5L);
        assertTrue(factory.eligibilityPreflight(List.of(eligibility), true));
        assertTrue(factory.selectExecutionMode(FrameExecutionMode.HACK_PER_STATE_OUTPUT));
        assertTrue(factory.prepareFrameResources(resources, true));
        assertTrue(factory.validateFinalResources(true));
        PreparedFramePlan framePlan = factory.createFramePlan().orElseThrow();
        ReplayPlan plan = framePlan.replayPlanFactory()
                .createPlan(eligibility, stateSpec()).orElseThrow();

        assertEquals(PreparedFramePlanStage.FRAME_PLAN_CREATED, factory.stage());
        assertSame(framePlan, factory.framePlan().orElseThrow());
        assertSame(framePlan, plan.preparedFramePlan());
        assertSame(resources, framePlan.resources());
        assertFalse(plan.outputDepthAlignmentExact());
        assertThrows(IllegalStateException.class, factory::createFramePlan);
    }

    @Test
    void dropFrameNeverCreatesAFrameOrReplayPlan() {
        PreparedFramePlanFactory factory = new PreparedFramePlanFactory(6L, 6L);
        assertTrue(factory.eligibilityPreflight(List.of(eligibility(6L)), true));
        assertFalse(factory.selectExecutionMode(FrameExecutionMode.DROP_FRAME));
        assertEquals(PreparedFramePlanStage.DROPPED, factory.stage());
        assertTrue(factory.createFramePlan().isEmpty());
        assertTrue(factory.framePlan().isEmpty());
        assertThrows(IllegalArgumentException.class, () -> new PreparedFramePlan(
                6L, FrameExecutionMode.DROP_FRAME, frameResources(),
                Map.of(CaptureDomain.WORLD, new DomainEligibilityRequirement(
                        4L, SnapshotAuthority.AUTHORITATIVE)), false));
    }

    @Test
    void hackOutputRejectsAStaleUpscaleCompletionEpoch() {
        PreparedFramePlanFactory stale = preparedHackFactory(12L, 11L);
        assertTrue(stale.createFramePlan().isEmpty());
        assertEquals(PreparedFramePlanStage.DROPPED, stale.stage());

        PreparedFramePlanFactory current = preparedHackFactory(12L, 12L);
        assertTrue(current.createFramePlan().isPresent());
    }

    @Test
    void everyStatePlanSharesTheFramesSingleModeAndResourceSet() {
        Eligibility world = eligibility(13L);
        Eligibility hand = new Eligibility(
                13L, CaptureDomain.FIRST_PERSON, 7L, SnapshotAuthority.AUTHORITATIVE);
        PreparedFrameResources resources = frameResources();
        PreparedFramePlanFactory preparer = new PreparedFramePlanFactory(13L, 13L);
        assertTrue(preparer.eligibilityPreflight(List.of(world, world, hand), true));
        assertTrue(preparer.selectExecutionMode(FrameExecutionMode.HACK_PER_STATE_OUTPUT));
        assertTrue(preparer.prepareFrameResources(resources, true));
        assertTrue(preparer.validateFinalResources(true));
        PreparedFramePlan framePlan = preparer.createFramePlan().orElseThrow();

        ReplayPlanFactory statePlans = framePlan.replayPlanFactory();
        StateReplayPlanSpec worldSpec = new StateReplayPlanSpec(
                PackTransformPolicy.OUTPUT_FULL_EXTENT,
                MaskDepthStrategy.RAW_DISPLAY_COPY,
                SceneDepthRoute.DISPLAY_SCENE);
        StateReplayPlanSpec handSpec = new StateReplayPlanSpec(
                PackTransformPolicy.NATIVE_INTERNAL,
                MaskDepthStrategy.CLEAR_FAR,
                SceneDepthRoute.MASK);
        ReplayPlan worldPlan = statePlans.createPlan(world, worldSpec).orElseThrow();
        ReplayPlan handPlan = statePlans.createPlan(hand, handSpec).orElseThrow();

        assertSame(framePlan, statePlans.framePlan());
        assertSame(framePlan, worldPlan.preparedFramePlan());
        assertSame(framePlan, handPlan.preparedFramePlan());
        assertEquals(2, framePlan.eligibleDomainRequirements().size());
        assertSame(resources, worldPlan.preparedFramePlan().resources());
        assertSame(resources, handPlan.preparedFramePlan().resources());
        assertEquals(FrameExecutionMode.HACK_PER_STATE_OUTPUT, worldPlan.frameMode());
        assertEquals(worldPlan.frameMode(), handPlan.frameMode());
        assertEquals(CaptureDomain.WORLD, worldPlan.domain());
        assertEquals(CaptureDomain.FIRST_PERSON, handPlan.domain());
        assertEquals(4L, worldPlan.captureSnapshotGeneration());
        assertEquals(7L, handPlan.captureSnapshotGeneration());
        assertSame(worldSpec, worldPlan.stateSpec());
        assertSame(handSpec, handPlan.stateSpec());
        assertEquals(SceneDepthRoute.DISPLAY_SCENE, worldPlan.sceneDepthRoute());
        assertEquals(SceneDepthRoute.MASK, handPlan.sceneDepthRoute());
        assertEquals(MaskDepthStrategy.RAW_DISPLAY_COPY, worldPlan.maskDepthStrategy());
        assertEquals(MaskDepthStrategy.CLEAR_FAR, handPlan.maskDepthStrategy());
        assertTrue(statePlans.createPlan(eligibility(14L), stateSpec()).isEmpty());
    }

    @Test
    void preflightNormalizesMatchingDomainsAndRejectsConflictingRequirements() {
        Eligibility world = eligibility(16L);
        PreparedFramePlanFactory generationConflict =
                new PreparedFramePlanFactory(16L, 16L);
        assertFalse(generationConflict.eligibilityPreflight(List.of(
                world,
                new Eligibility(16L, CaptureDomain.WORLD, 5L,
                        SnapshotAuthority.AUTHORITATIVE)), true));
        assertEquals(PreparedFramePlanStage.DROPPED, generationConflict.stage());

        PreparedFramePlanFactory authorityConflict =
                new PreparedFramePlanFactory(16L, 16L);
        assertFalse(authorityConflict.eligibilityPreflight(List.of(
                world,
                new Eligibility(16L, CaptureDomain.WORLD, 4L,
                        SnapshotAuthority.PROVISIONAL)), true));
        assertEquals(PreparedFramePlanStage.DROPPED, authorityConflict.stage());
    }

    @Test
    void replayFactoryRejectsUnfrozenMismatchedAndMissingStateResources() {
        Eligibility world = eligibility(17L);
        Eligibility hand = new Eligibility(
                17L, CaptureDomain.FIRST_PERSON, 7L, SnapshotAuthority.AUTHORITATIVE);
        ReplayPlanFactory worldOnly = preparedFramePlan(
                17L, List.of(world), frameResources()).replayPlanFactory();

        assertTrue(worldOnly.createPlan(hand, stateSpec()).isEmpty());
        assertTrue(worldOnly.createPlan(new Eligibility(
                17L, CaptureDomain.WORLD, 5L,
                SnapshotAuthority.AUTHORITATIVE), stateSpec()).isEmpty());
        assertTrue(worldOnly.createPlan(new Eligibility(
                17L, CaptureDomain.WORLD, 4L,
                SnapshotAuthority.PROVISIONAL), stateSpec()).isEmpty());

        Eligibility mismatchedWorld = new Eligibility(
                18L, CaptureDomain.WORLD, 5L, SnapshotAuthority.AUTHORITATIVE);
        PreparedFrameResources wrongWorldGeneration = new PreparedFrameResources(
                ReplayTargetMode.STATE_MASK, 4L, 6L, -1L, 1920, 1080);
        PreparedFramePlanFactory mismatchedWorldFactory =
                new PreparedFramePlanFactory(18L, 18L);
        assertTrue(mismatchedWorldFactory.eligibilityPreflight(
                List.of(mismatchedWorld), true));
        assertTrue(mismatchedWorldFactory.selectExecutionMode(
                FrameExecutionMode.HACK_PER_STATE_OUTPUT));
        assertTrue(mismatchedWorldFactory.prepareFrameResources(
                wrongWorldGeneration, true));
        assertTrue(mismatchedWorldFactory.validateFinalResources(true));
        assertTrue(mismatchedWorldFactory.createFramePlan().isEmpty());
        assertEquals(PreparedFramePlanStage.DROPPED, mismatchedWorldFactory.stage());
        assertThrows(IllegalArgumentException.class, () -> new PreparedFramePlan(
                18L, FrameExecutionMode.HACK_PER_STATE_OUTPUT,
                wrongWorldGeneration,
                Map.of(CaptureDomain.WORLD,
                        new DomainEligibilityRequirement(
                                5L, SnapshotAuthority.AUTHORITATIVE)),
                false));

        Eligibility displayHand = new Eligibility(
                19L, CaptureDomain.FIRST_PERSON, 7L, SnapshotAuthority.AUTHORITATIVE);
        PreparedFrameResources noWorldDepth = new PreparedFrameResources(
                ReplayTargetMode.STATE_MASK, -1L, 6L, -1L, 1920, 1080);
        ReplayPlanFactory noWorldDepthFactory = preparedFramePlan(
                19L, List.of(displayHand), noWorldDepth).replayPlanFactory();
        assertTrue(noWorldDepthFactory.createPlan(displayHand, new StateReplayPlanSpec(
                PackTransformPolicy.NATIVE_INTERNAL,
                MaskDepthStrategy.RAW_DISPLAY_COPY,
                SceneDepthRoute.MASK)).isEmpty());
        assertTrue(noWorldDepthFactory.createPlan(displayHand, new StateReplayPlanSpec(
                PackTransformPolicy.NATIVE_INTERNAL,
                MaskDepthStrategy.CURRENT_NATIVE,
                SceneDepthRoute.DISPLAY_SCENE)).isEmpty());

        Eligibility foregroundHand = new Eligibility(
                20L, CaptureDomain.FIRST_PERSON, 7L, SnapshotAuthority.AUTHORITATIVE);
        PreparedFrameResources noForegroundDepth = new PreparedFrameResources(
                ReplayTargetMode.STATE_MASK, 4L, -1L, -1L, 1920, 1080);
        ReplayPlanFactory noForegroundFactory = preparedFramePlan(
                20L, List.of(foregroundHand), noForegroundDepth).replayPlanFactory();
        assertTrue(noForegroundFactory.createPlan(foregroundHand,
                new StateReplayPlanSpec(
                        PackTransformPolicy.NATIVE_INTERNAL,
                        MaskDepthStrategy.CURRENT_NATIVE,
                        SceneDepthRoute.FOREGROUND)).isEmpty());
    }

    @Test
    void preparedModeAndResourcesCannotBeRepeatedOrChanged() {
        PreparedFrameResources resources = frameResources();
        PreparedFrameResources replacement = new PreparedFrameResources(
                ReplayTargetMode.STATE_MASK,
                8L, -1L, -1L, 1280, 720);
        PreparedFramePlanFactory factory = new PreparedFramePlanFactory(15L, 15L);
        factory.eligibilityPreflight(List.of(eligibility(15L)), true);
        factory.selectExecutionMode(FrameExecutionMode.HACK_PER_STATE_OUTPUT);
        factory.prepareFrameResources(resources, true);

        assertThrows(IllegalStateException.class,
                () -> factory.selectExecutionMode(FrameExecutionMode.ORDINARY_NATIVE_FINAL));
        assertThrows(IllegalStateException.class,
                () -> factory.prepareFrameResources(replacement, true));

        factory.validateFinalResources(true);
        PreparedFramePlan framePlan = factory.createFramePlan().orElseThrow();
        assertSame(resources, framePlan.resources());
        assertThrows(IllegalStateException.class, factory::createFramePlan);
        assertThrows(IllegalStateException.class,
                () -> factory.prepareFrameResources(replacement, true));
    }

    @Test
    void replayPlanIsImmutableAndPayloadCanOnlyBeAttemptedOnce() {
        CaptureLifecycle lifecycle = new CaptureLifecycle();
        lifecycle.beginCapture();
        lifecycle.markCaptured();
        Eligibility eligibility = eligibility(9L);
        lifecycle.markEligible(eligibility);

        PreparedFramePlanFactory factory = new PreparedFramePlanFactory(9L, 9L);
        factory.eligibilityPreflight(List.of(eligibility), true);
        factory.selectExecutionMode(FrameExecutionMode.HACK_PER_STATE_OUTPUT);
        factory.prepareFrameResources(frameResources(), true);
        factory.validateFinalResources(true);
        PreparedFramePlan framePlan = factory.createFramePlan().orElseThrow();
        ReplayPlan plan = framePlan.replayPlanFactory()
                .createPlan(eligibility, stateSpec()).orElseThrow();
        lifecycle.schedule(plan);

        assertTrue(lifecycle.beginReplay());
        assertFalse(lifecycle.beginReplay());
        assertThrows(IllegalStateException.class, () -> lifecycle.schedule(plan));
        lifecycle.markComposited();
        assertEquals(CaptureStage.COMPOSITED, lifecycle.stage());
        assertFalse(lifecycle.beginReplay());
    }

    @Test
    void illegalCaptureTransitionsAreRejectedAndInvalidationIsUniversal() {
        assertThrows(IllegalStateException.class,
                () -> transition(CaptureStage.IDLE, CaptureStage.CAPTURED));
        assertThrows(IllegalStateException.class,
                () -> transition(CaptureStage.ELIGIBLE, CaptureStage.REPLAY_ATTEMPTED));
        for (CaptureStage stage : CaptureStage.values()) {
            assertTrue(canTransition(stage, CaptureStage.INVALID));
        }
    }

    private static ModeCapability enabledCapability() {
        return new ModeCapability(
                HookCapabilityState.ENABLED,
                HookCapabilityState.ENABLED,
                HookCapabilityState.ENABLED,
                HookCapabilityState.ENABLED,
                HookCapabilityState.ENABLED);
    }

    private static Eligibility eligibility(long epoch) {
        return new Eligibility(
                epoch, CaptureDomain.WORLD, 4L, SnapshotAuthority.AUTHORITATIVE);
    }

    private static PreparedFrameResources frameResources() {
        return new PreparedFrameResources(
                ReplayTargetMode.STATE_MASK,
                4L,
                6L,
                -1L,
                1920,
                1080);
    }

    private static StateReplayPlanSpec stateSpec() {
        return new StateReplayPlanSpec(
                PackTransformPolicy.OUTPUT_FULL_EXTENT,
                MaskDepthStrategy.RAW_DISPLAY_COPY,
                SceneDepthRoute.MASK);
    }

    private static PreparedFramePlanFactory preparedHackFactory(long epoch, long upscaleEpoch) {
        PreparedFramePlanFactory factory = new PreparedFramePlanFactory(epoch, upscaleEpoch);
        factory.eligibilityPreflight(List.of(eligibility(epoch)), true);
        factory.selectExecutionMode(FrameExecutionMode.HACK_PER_STATE_OUTPUT);
        factory.prepareFrameResources(frameResources(), true);
        factory.validateFinalResources(true);
        return factory;
    }

    private static PreparedFramePlan preparedFramePlan(
            long epoch,
            List<Eligibility> eligibility,
            PreparedFrameResources resources) {
        PreparedFramePlanFactory factory = new PreparedFramePlanFactory(epoch, epoch);
        assertTrue(factory.eligibilityPreflight(eligibility, true));
        assertTrue(factory.selectExecutionMode(FrameExecutionMode.HACK_PER_STATE_OUTPUT));
        assertTrue(factory.prepareFrameResources(resources, true));
        assertTrue(factory.validateFinalResources(true));
        return factory.createFramePlan().orElseThrow();
    }
}
