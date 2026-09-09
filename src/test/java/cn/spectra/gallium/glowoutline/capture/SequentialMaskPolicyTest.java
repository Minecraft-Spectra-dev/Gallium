package cn.spectra.gallium.glowoutline.capture;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SequentialMaskPolicyTest {
    @Test
    void failureForcesNextFrameIndependentEvenWithFreshProofThenRecovers() {
        var policy = new SequentialMaskPolicy();
        assertTrue(policy.beginFrame(1, true));
        assertTrue(policy.consume(1));
        policy.fail(1);
        assertTrue(policy.selected(), "a failed frame must not fall into legacy replay");
        assertFalse(policy.consume(1));
        assertFalse(policy.beginFrame(2, true));
        assertFalse(policy.consume(2));
        assertTrue(policy.beginFrame(3, true));
        assertTrue(policy.consume(3));
    }

    @Test
    void finalSequenceCannotBeClaimedTwiceOrRevivedByDuplicateHead() {
        var policy = new SequentialMaskPolicy();
        assertTrue(policy.beginFrame(10, true));
        assertTrue(policy.consume(10));
        assertFalse(policy.consume(10));
        assertTrue(policy.beginFrame(10, true));
        assertFalse(policy.consume(10));
    }

    @Test
    void unknownBackendAndSameEpochProofCannotUpgradeOwnership() {
        var policy = new SequentialMaskPolicy();
        assertFalse(policy.beginFrame(1, false));
        assertFalse(policy.beginFrame(1, true));
        assertFalse(policy.selected());
        assertFalse(policy.consume(1));
        assertTrue(policy.beginFrame(2, true));
        assertTrue(policy.beginFrame(2, false), "HEAD choice stays frozen even if final checks fail");
        policy.fail(2);
        assertFalse(policy.consume(2));
        assertFalse(policy.beginFrame(3, true));
    }

    @Test
    void staleEpochCannotConsumeFailOrReplaceCurrentFrame() {
        var policy = new SequentialMaskPolicy();
        assertTrue(policy.beginFrame(8, true));
        assertFalse(policy.consume(7));
        assertFalse(policy.consume(9));
        assertFalse(policy.beginFrame(7, false));
        policy.fail(7);
        policy.fail(9);
        assertTrue(policy.selected());
        assertTrue(policy.consume(8));
        assertTrue(policy.beginFrame(9, true), "stale failures cannot suppress the next frame");
    }

    @Test
    void failureBeforeFinalClaimIsTerminalAndResetDropsOldEpochAndFallback() {
        var policy = new SequentialMaskPolicy();
        assertTrue(policy.beginFrame(4, true));
        policy.fail(4);
        assertFalse(policy.consume(4));
        policy.reset();
        assertFalse(policy.selected());
        assertFalse(policy.consume(4));
        policy.fail(4);
        assertFalse(policy.beginFrame(-1, true));
        assertTrue(policy.beginFrame(5, true));
        assertTrue(policy.consume(5));
    }

    @Test
    void independentFrameFailureDoesNotDisableFutureSharedEligibility() {
        var policy = new SequentialMaskPolicy();
        assertFalse(policy.beginFrame(1, false));
        policy.fail(1);
        assertTrue(policy.beginFrame(2, true));
    }
}
