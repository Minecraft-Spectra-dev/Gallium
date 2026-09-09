package cn.spectra.gallium.glowoutline.capture;

import cn.spectra.gallium.glowoutline.ItemEffectConfig;
import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import static cn.spectra.gallium.glowoutline.sr.streaming.SrStreamingCoordinator.*;
import static org.junit.jupiter.api.Assertions.*;

class MaskRenderContextTest {
    static class Target { boolean closed; }

    static MaskRenderContext<Target> owner(List<Target> allocations) {
        return new MaskRenderContext<>((w,h) -> {
            assertTrue(allocations.stream().allMatch(t -> t.closed), "never two live native masks");
            Target target = new Target(); allocations.add(target); return target;
        }, target -> { assertFalse(target.closed, "only the owner disposes once"); target.closed = true; });
    }

    static GlowCaptureState scheduled(long epoch, int width, int height) {
        GlowCaptureState state = new GlowCaptureState();
        state.beginCaptureLifecycle(epoch, false);
        state.markPayloadCaptured(); state.finishCaptureScope();
        Eligibility eligibility = new Eligibility(epoch, CaptureDomain.WORLD, 3L, SnapshotAuthority.AUTHORITATIVE);
        state.markStreamingEligible(eligibility);
        PreparedFramePlanFactory factory = new PreparedFramePlanFactory(epoch, epoch);
        factory.eligibilityPreflight(List.of(eligibility), true);
        factory.selectExecutionMode(FrameExecutionMode.HACK_SHARED_OUTPUT);
        factory.prepareFrameResources(new PreparedFrameResources(ReplayTargetMode.SHARED_MASK,
                3L,-1L,-1L,width,height), true);
        factory.validateFinalResources(true);
        var plan = factory.createFramePlan().orElseThrow().replayPlanFactory().createPlan(eligibility,
                currentHackOutputStateSpec(CaptureDomain.WORLD, true, true, false, true)).orElseThrow();
        assertTrue(state.scheduleStreamingReplay(plan));
        return state;
    }

    static void composite(GlowCaptureState state) {
        assertTrue(state.beginStreamingReplayAttempt());
        state.compositedThisFrame = true;
        assertTrue(state.markStreamingComposited());
    }

    static GlowCaptureState ordinary(long epoch, boolean firstPerson, boolean eligible) {
        var state = new GlowCaptureState();
        state.beginCaptureLifecycle(epoch, firstPerson);
        assertTrue(state.markPayloadCaptured());
        state.finishCaptureScope();
        state.capturedThisFrame = true;
        state.config = new ItemEffectConfig("test", List.of());
        if (eligible) assertTrue(state.markStreamingEligible(new Eligibility(
                epoch, state.captureDomain, 3L, SnapshotAuthority.AUTHORITATIVE)));
        return state;
    }

    @Test
    void oneMaskSurvivesManyStatesAndFramesWithoutBeingOwnedByStates() {
        List<Target> allocations = new ArrayList<>();
        try (var owner = owner(allocations)) {
            for (long epoch=1;epoch<=3;epoch++) {
                owner.beginFrame(epoch,854,480,"same-context",()->true);
                try(var frame=owner.prepareFrame()) {
                    assertNotNull(frame);
                    assertNull(owner.prepareFrame(), "one prepared resource lease per frame");
                    for(int i=0;i<100;i++) {
                        GlowCaptureState state=scheduled(epoch,854,480);
                        assertNull(state.maskTarget);
                        assertTrue(frame.beginState(state));
                        assertSame(allocations.get(0),frame.targetFor(state));
                        composite(state);
                        assertTrue(frame.finishState(state));
                        assertNull(frame.targetFor(state));
                    }
                }
            }
            assertEquals(1,allocations.size());
            assertFalse(allocations.get(0).closed);
        }
        assertTrue(allocations.get(0).closed);
    }

    @Test
    void cannotOverwriteBetweenReplayAndCompositeOrAcceptStalePlan() {
        var allocations=new ArrayList<Target>();
        try(var owner=owner(allocations)) {
            owner.beginFrame(8,854,480,"gl",()->true);
            try(var frame=owner.prepareFrame()) {
                var first=scheduled(8,854,480);var second=scheduled(8,854,480);
                assertFalse(frame.beginState(scheduled(7,854,480)));
                assertFalse(frame.beginState(scheduled(8,1280,720)));
                assertTrue(frame.beginState(first));
                assertFalse(frame.beginState(second));
                assertTrue(first.beginStreamingReplayAttempt());
                assertFalse(frame.finishState(first));
                assertFalse(frame.beginState(second));
                first.compositedThisFrame=true;first.markStreamingComposited();
                assertTrue(frame.finishState(first));
                assertTrue(frame.beginState(second));
                composite(second);assertTrue(frame.finishState(second));
            }
        }
    }

    @Test
    void unfinishedReplayRetiresTargetAndInvalidatesOldLease() {
        var allocations=new ArrayList<Target>();
        try(var owner=owner(allocations)) {
            owner.beginFrame(1,854,480,"gl",()->true);
            var frame=owner.prepareFrame();
            var state=scheduled(1,854,480);
            assertTrue(frame.beginState(state));
            state.beginStreamingReplayAttempt();
            frame.close(); // failed composite/exception unwinds the borrowed frame
            assertTrue(allocations.get(0).closed);
            assertFalse(frame.valid());assertNull(frame.targetFor(state));
            owner.beginFrame(2,854,480,"gl",()->true);
            try(var next=owner.prepareFrame()) {
                assertNotSame(allocations.get(0),next.target());
                assertFalse(frame.valid());
                assertFalse(next.beginState(state));
            }
        }
        assertEquals(2,allocations.size());
    }

    @Test
    void emptyResizeBackendChangeAndTeardownReleaseBeforeReplacement() {
        var allocations=new ArrayList<Target>();
        var owner=owner(allocations);
        owner.beginFrame(1,854,480,"gl1",()->true);
        var first=owner.prepareFrame();first.close();
        owner.beginFrame(2,1280,720,"gl1",()->true);
        assertTrue(allocations.get(0).closed, "even an empty resized frame releases the old target");
        assertEquals(1,allocations.size());
        var second=owner.prepareFrame();second.close();
        owner.beginFrame(3,1280,720,"gl2",()->true);
        assertTrue(allocations.get(1).closed);
        var third=owner.prepareFrame();third.close();
        owner.close();owner.close();
        assertTrue(allocations.stream().allMatch(t->t.closed));
        assertNull(owner.prepareFrame());
    }

    @Test
    void lostBackendEvidenceCannotGrantOrReuseMask() {
        var allocations=new ArrayList<Target>();var current=new AtomicBoolean(false);
        try(var owner=owner(allocations)) {
            owner.beginFrame(1,854,480,"gl",current::get);
            assertNull(owner.prepareFrame());assertTrue(allocations.isEmpty());
            current.set(true);
            assertNull(owner.prepareOrdinaryFrame(), "HEAD proof cannot be upgraded within the frame");
            owner.beginFrame(2,854,480,"gl",current::get);
            var frame=owner.prepareFrame();
            current.set(false);
            assertFalse(frame.valid());assertNull(frame.target());
            assertFalse(frame.beginState(scheduled(2,854,480)));
            owner.beginFrame(3,854,480,"gl",current::get);
            assertTrue(allocations.get(0).closed, "lost proof retires even an unoccupied same-size mask");
            current.set(true);
            assertNull(owner.prepareFrame());
            owner.beginFrame(4,854,480,"gl",current::get);
            try (var next = owner.prepareOrdinaryFrame()) {
                assertNotNull(next);
                assertNotSame(allocations.get(0), next.target());
                frame.close();
                assertTrue(next.valid(), "a retired lease cannot close the next frame's target");
            }
        }
    }

    @Test
    void ordinaryWorldAndHandReuseOneMaskWithoutInventingStreamingPlans() {
        var allocations = new ArrayList<Target>();
        try (var owner = owner(allocations)) {
            for (long epoch = 1; epoch <= 3; epoch++) {
                owner.beginFrame(epoch, 3840, 2054, "gl", () -> true);
                try (var frame = owner.prepareOrdinaryFrame()) {
                    assertNull(owner.prepareFrame(), "one resource lease across both execution APIs");
                    for (int i = 0; i < 20; i++) {
                        boolean hand = i % 2 == 1;
                        var state = ordinary(epoch, hand, !hand);
                        var stage = state.captureStage();
                        assertTrue(frame.beginOrdinaryState(state));
                        assertSame(allocations.get(0), frame.targetFor(state));
                        assertFalse(frame.finishOrdinaryState(state));
                        assertTrue(state.beginOrdinaryReplayAttempt(epoch));
                        assertFalse(state.beginOrdinaryReplayAttempt(epoch));
                        state.maskPreparedThisFrame = true;
                        assertFalse(frame.finishOrdinaryState(state), "replay alone cannot release the mask");
                        assertFalse(frame.beginOrdinaryState(ordinary(epoch, false, true)));
                        state.compositedThisFrame = true;
                        assertTrue(frame.finishOrdinaryState(state));
                        assertFalse(frame.beginOrdinaryState(state), "the same payload cannot be retried");
                        assertEquals(stage, state.captureStage());
                        assertNull(state.streamingReplayPlan());
                        assertNull(state.maskTarget);
                    }
                }
            }
            assertEquals(1, allocations.size());
        }
        assertTrue(allocations.get(0).closed);
    }

    @Test
    void ordinaryAndStreamingLeaseEntrypointsCannotBeMixed() {
        var allocations = new ArrayList<Target>();
        try (var owner = owner(allocations)) {
            owner.beginFrame(5, 854, 480, "gl", () -> true);
            try (var frame = owner.prepareOrdinaryFrame()) {
                var ordinary = ordinary(5, false, true);
                var planned = scheduled(5, 854, 480);
                assertFalse(frame.beginState(planned));
                assertFalse(frame.beginOrdinaryState(planned));
                assertFalse(ordinary.beginStreamingReplayAttempt());
                assertTrue(frame.beginOrdinaryState(ordinary));
                assertTrue(ordinary.beginOrdinaryReplayAttempt(5));
                ordinary.maskPreparedThisFrame = ordinary.compositedThisFrame = true;
                assertFalse(frame.finishState(ordinary));
                assertTrue(frame.finishOrdinaryState(ordinary));
            }
            owner.beginFrame(6, 854, 480, "gl", () -> true);
            try (var frame = owner.prepareFrame()) {
                var planned = scheduled(6, 854, 480);
                assertFalse(frame.beginOrdinaryState(ordinary(6, true, false)));
                assertFalse(frame.beginState(ordinary(6, false, true)));
                assertTrue(frame.beginState(planned));
                assertFalse(planned.beginOrdinaryReplayAttempt(6));
                composite(planned);
                assertTrue(planned.hasPayloadReplayAttempted());
                assertFalse(planned.beginStreamingReplayAttempt());
                assertFalse(frame.finishOrdinaryState(planned));
                assertTrue(frame.finishState(planned));
            }
        }
    }

    @Test
    void ordinaryLeaseRejectsStaleOpenInvalidOrPreviouslyPreparedCaptures() {
        var allocations = new ArrayList<Target>();
        try (var owner = owner(allocations)) {
            owner.beginFrame(9, 854, 480, "gl", () -> true);
            try (var frame = owner.prepareOrdinaryFrame()) {
                assertFalse(frame.beginOrdinaryState(ordinary(8, false, true)));
                var open = ordinary(9, true, false);
                open.beginCaptureLifecycle(9, true);
                open.markPayloadCaptured();
                assertFalse(frame.beginOrdinaryState(open));
                assertFalse(open.beginOrdinaryReplayAttempt(9));
                var invalid = ordinary(9, false, true);
                invalid.invalidateCapture();
                assertFalse(frame.beginOrdinaryState(invalid));
                var state = ordinary(9, true, false);
                state.config = null;
                assertFalse(frame.beginOrdinaryState(state));
                state.config = new ItemEffectConfig("test", List.of());
                state.capturedThisFrame = false;
                assertFalse(frame.beginOrdinaryState(state));
                state.capturedThisFrame = true;
                state.maskPreparedThisFrame = true;
                assertFalse(frame.beginOrdinaryState(state));
                state.maskPreparedThisFrame = false;
                state.compositedThisFrame = true;
                assertFalse(frame.beginOrdinaryState(state));
                state.compositedThisFrame = false;
                assertTrue(frame.beginOrdinaryState(state));
                assertFalse(state.beginOrdinaryReplayAttempt(8));
                assertFalse(state.hasPayloadReplayAttempted());
            }
        }
    }

    @Test
    void ordinaryAttemptCannotBecomeStreamingRetryAndSurvivesDiscardUntilReset() {
        var state = ordinary(7, false, true);
        var plan = scheduled(7, 854, 480).streamingReplayPlan();
        assertTrue(state.beginOrdinaryReplayAttempt(7));
        assertFalse(state.markPayloadCaptured());
        assertFalse(state.markStreamingEligible(state.streamingEligibility()));
        assertFalse(state.scheduleStreamingReplay(plan));
        assertFalse(state.beginStreamingReplayAttempt());
        assertFalse(state.markStreamingComposited());
        state.discardPayload();
        assertTrue(state.hasPayloadReplayAttempted());
        assertFalse(state.beginOrdinaryReplayAttempt(7));
        state.resetFrame();
        assertFalse(state.hasPayloadReplayAttempted());
        assertFalse(state.beginOrdinaryReplayAttempt(7));
        state.beginCaptureLifecycle(8, true);
        state.markPayloadCaptured();
        state.finishCaptureScope();
        state.config = new ItemEffectConfig("test", List.of());
        state.capturedThisFrame = true;
        assertTrue(state.beginOrdinaryReplayAttempt(8));
        state.beginCaptureLifecycle(9, true);
        assertFalse(state.hasPayloadReplayAttempted());
    }

    @Test
    void ordinaryIncompleteReplayAndContextChangeInvalidateOldLeases() {
        var allocations = new ArrayList<Target>();
        var proof = new AtomicBoolean(true);
        try (var owner = owner(allocations)) {
            owner.beginFrame(1, 854, 480, "gl1", proof::get);
            var old = owner.prepareOrdinaryFrame();
            var first = ordinary(1, true, false);
            assertTrue(old.beginOrdinaryState(first));
            assertTrue(first.beginOrdinaryReplayAttempt(1));
            first.maskPreparedThisFrame = true;
            proof.set(false);
            assertFalse(old.valid());
            first.compositedThisFrame = true;
            assertFalse(old.finishOrdinaryState(first));
            assertNull(old.targetFor(first));
            owner.beginFrame(2, 854, 480, "gl2", () -> true);
            assertTrue(allocations.get(0).closed);
            try (var next = owner.prepareOrdinaryFrame()) {
                assertNotSame(allocations.get(0), next.target());
                old.close();
                assertTrue(next.valid());
                assertFalse(next.beginOrdinaryState(first));
                var second = ordinary(2, false, true);
                assertTrue(next.beginOrdinaryState(second));
                assertTrue(second.beginOrdinaryReplayAttempt(2));
                second.invalidateCapture();
                second.maskPreparedThisFrame = second.compositedThisFrame = true;
                assertFalse(next.finishOrdinaryState(second), "invalidated payload cannot release a usable mask");
            }
            assertTrue(allocations.get(1).closed);
            assertTrue(first.hasPayloadReplayAttempted());
        }
    }

    //#if MC==1_21_11 || MC==1_26_01
    static class RetiredTarget extends com.mojang.blaze3d.pipeline.TextureTarget {
        int disposals;
        RetiredTarget() { super("unused-test-constructor",1,1,true); }
        @Override public void destroyBuffers() { disposals++; }
    }

    @Test
    @SuppressWarnings("unchecked")
    void sharedEntryPurgesColdAndActiveOldTargetsAndAllReservations() throws Exception {
        var unsafeField=sun.misc.Unsafe.class.getDeclaredField("theUnsafe");unsafeField.setAccessible(true);
        var unsafe=(sun.misc.Unsafe)unsafeField.get(null);
        var poolField=GlowCaptureManager.class.getDeclaredField("pool");poolField.setAccessible(true);
        var budgetField=GlowCaptureManager.class.getDeclaredField("captureTargetBytesReserved");budgetField.setAccessible(true);
        var pool=(List<GlowCaptureState>)poolField.get(null);
        var saved=new ArrayList<>(pool);long savedBudget=budgetField.getLong(null);
        var cold=new GlowCaptureState();var active=new GlowCaptureState();active.active=true;
        var coldTarget=(RetiredTarget)unsafe.allocateInstance(RetiredTarget.class);
        var activeTarget=(RetiredTarget)unsafe.allocateInstance(RetiredTarget.class);
        try {
            pool.clear();pool.add(cold);pool.add(active);
            cold.maskTarget=coldTarget;active.maskTarget=activeTarget;
            cold.captureTargetBytesReserved=100;active.captureTargetBytesReserved=200;
            cold.captureTargetReservedWidth=active.captureTargetReservedWidth=854;
            cold.captureTargetReservedHeight=active.captureTargetReservedHeight=480;
            budgetField.setLong(null,300);
            GlowCaptureManager.releaseAllPerStateTargets();
            GlowCaptureManager.releaseAllPerStateTargets();
            assertNull(cold.maskTarget);assertNull(active.maskTarget);
            assertEquals(1,coldTarget.disposals);assertEquals(1,activeTarget.disposals);
            assertEquals(0,budgetField.getLong(null));
            for(var state:pool) {
                assertEquals(0,state.captureTargetBytesReserved);
                assertEquals(0,state.captureTargetReservedWidth);
                assertEquals(0,state.captureTargetReservedHeight);
            }
        } finally {pool.clear();pool.addAll(saved);budgetField.setLong(null,savedBudget);}
    }
    //#endif
}
