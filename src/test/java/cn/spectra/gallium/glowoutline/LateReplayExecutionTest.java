package cn.spectra.gallium.glowoutline;

import cn.spectra.gallium.glowoutline.capture.GlowCaptureState;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static cn.spectra.gallium.glowoutline.sr.streaming.SrStreamingCoordinator.*;
import static org.junit.jupiter.api.Assertions.*;

class LateReplayExecutionTest {
    @Test
    void headGateMigratesOnly12111And261ACAndIgnoresBackendOrdering() {
        for (HookCapabilityState backend : HookCapabilityState.values()) {
            ModeCapability ready = new ModeCapability(HookCapabilityState.ENABLED,
                    HookCapabilityState.ENABLED, HookCapabilityState.ENABLED,
                    HookCapabilityState.ENABLED, backend);
            assertFalse(SuperResolutionCompat.selectsLateReplayAtHead(true, CaptureMode.B, true, ready));
            assertFalse(SuperResolutionCompat.selectsLateReplayAtHead(false, CaptureMode.A, true, ready));
            assertFalse(SuperResolutionCompat.selectsLateReplayAtHead(true, CaptureMode.UNKNOWN, true, ready));
            for (CaptureMode mode : List.of(CaptureMode.A, CaptureMode.C)) {
                //#if MC==1_21_11 || MC==1_26_01
                assertTrue(SuperResolutionCompat.selectsLateReplayAtHead(true, mode, true, ready));
                //#else
                //$$ assertFalse(SuperResolutionCompat.selectsLateReplayAtHead(true, mode, true, ready));
                //#endif
                assertFalse(SuperResolutionCompat.selectsLateReplayAtHead(true, mode, true,
                        ready.withHook(HookKind.FIRST_PERSON, HookCapabilityState.UNKNOWN)));
                assertFalse(SuperResolutionCompat.selectsLateReplayAtHead(true, mode, true,
                        ready.withHook(HookKind.FINAL, HookCapabilityState.DISABLED)));
            }
        }
    }

    @Test
    void actualSequenceIsReplayThenCompositeForEachIndependentlyOwnedState() {
        List<GlowCaptureState> states = scheduledPair();
        List<String> events = new ArrayList<>();
        assertSame(states.get(0).streamingReplayPlan().preparedFramePlan(),
                states.get(1).streamingReplayPlan().preparedFramePlan());
        assertTrue(SuperResolutionCompat.replayPreparedStates(states, state -> {
            assertTrue(state.beginStreamingReplayAttempt());
            events.add("replay " + state.captureDomain);
            return true;
        }, state -> {
            assertEquals(CaptureStage.REPLAY_ATTEMPTED, state.captureStage());
            events.add("composite " + state.captureDomain);
            return state.markStreamingComposited();
        }, () -> fail("Successful sequence must not abort")));
        assertEquals(List.of("replay WORLD", "composite WORLD", "replay FIRST_PERSON",
                "composite FIRST_PERSON"), events);
        assertFalse(states.get(0).beginStreamingReplayAttempt());
        assertFalse(states.get(1).beginStreamingReplayAttempt());
    }

    @Test
    void throwingDispatcherStopsRemainingPayloadsAndCannotBeRetried() {
        List<GlowCaptureState> states = scheduledPair();
        List<GlowCaptureState> dispatches = new ArrayList<>();
        RuntimeException failure = new RuntimeException("dispatcher failed");
        RuntimeException actual = assertThrows(RuntimeException.class, () ->
                SuperResolutionCompat.replayPreparedStates(states, state -> {
                    assertTrue(state.beginStreamingReplayAttempt());
                    dispatches.add(state);
                    throw failure;
                }, state -> { fail("No composite after failure"); return false; },
                        () -> states.forEach(GlowCaptureState::invalidateCapture)));
        assertSame(failure, actual);
        assertEquals(List.of(states.get(0)), dispatches);
        assertTrue(states.stream().allMatch(state -> state.captureStage() == CaptureStage.INVALID));
        assertFalse(SuperResolutionCompat.replayPreparedStates(states,
                state -> { fail("Consumed payload cannot re-enter dispatcher"); return true; },
                state -> true, () -> {}));
    }

    @Test
    void failedCompositeStopsTheNextReplayAndPreservesOneAttempt() {
        List<GlowCaptureState> states = scheduledPair();
        List<GlowCaptureState> attempted = new ArrayList<>();
        assertFalse(SuperResolutionCompat.replayPreparedStates(states, state -> {
            attempted.add(state);
            return state.beginStreamingReplayAttempt();
        }, state -> false, () -> states.forEach(GlowCaptureState::invalidateCapture)));
        assertEquals(List.of(states.get(0)), attempted);
        assertFalse(states.get(0).beginStreamingReplayAttempt());
        assertFalse(states.get(1).beginStreamingReplayAttempt());
    }

    @Test
    void openCaptureScopeCannotBeScheduledEvenAfterDomainClosure() {
        GlowCaptureState state = capturedState(false);
        PreparedFramePlan frame = frameFor(List.of(state));
        var replay = frame.replayPlanFactory().createPlan(state.streamingEligibility(),
                currentHackOutputStateSpec(CaptureDomain.WORLD, true, true, false, true)).orElseThrow();
        assertFalse(state.scheduleStreamingReplay(replay));
        assertFalse(state.markPayloadCaptured(), "a late submit cannot extend the eligible payload");
        state.finishCaptureScope();
        assertTrue(state.scheduleStreamingReplay(replay));
    }

    private static GlowCaptureState capturedState(boolean hand) {
        GlowCaptureState state = new GlowCaptureState();
        state.firstPerson = hand;
        state.beginCaptureLifecycle(7L, hand);
        state.markPayloadCaptured();
        state.capturedThisFrame = true;
        assertTrue(state.markStreamingEligible(new Eligibility(7L,
                hand ? CaptureDomain.FIRST_PERSON : CaptureDomain.WORLD,
                hand ? -1L : 3L, SnapshotAuthority.AUTHORITATIVE)));
        return state;
    }

    private static PreparedFramePlan frameFor(List<GlowCaptureState> states) {
        PreparedFramePlanFactory factory = new PreparedFramePlanFactory(7L, 7L);
        assertTrue(factory.eligibilityPreflight(states.stream().map(GlowCaptureState::streamingEligibility).toList(), true));
        factory.selectExecutionMode(FrameExecutionMode.HACK_PER_STATE_OUTPUT);
        factory.prepareFrameResources(new PreparedFrameResources(ReplayTargetMode.STATE_MASK,
                3L, -1L, -1L, 854, 480), true);
        factory.validateFinalResources(true);
        return factory.createFramePlan().orElseThrow();
    }

    private static List<GlowCaptureState> scheduledPair() {
        List<GlowCaptureState> states = List.of(capturedState(false), capturedState(true));
        PreparedFramePlan frame = frameFor(states);
        for (var state : states) {
            state.finishCaptureScope();
            assertTrue(state.scheduleStreamingReplay(frame.replayPlanFactory().createPlan(state.streamingEligibility(),
                    currentHackOutputStateSpec(state.captureDomain, true, true, false, true)).orElseThrow()));
        }
        return states;
    }
}
