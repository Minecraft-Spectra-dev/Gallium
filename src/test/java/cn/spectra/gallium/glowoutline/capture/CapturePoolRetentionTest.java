package cn.spectra.gallium.glowoutline.capture;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CapturePoolRetentionTest {
    @Test
    void denseSceneDoesNotRecreateTheStatesBeyondThirtyTwoEveryFrame() {
        var policy = new CapturePoolRetention(32, 256, 120);
        int allocated = 0, pool = 0;
        for (int frame = 0; frame < 600; frame++) {
            pool = Math.min(pool, policy.nextFrame(frame == 0 ? 0 : 60));
            allocated += Math.max(0, 60 - pool);
            pool = Math.max(pool, 60);
        }
        assertEquals(60, allocated, "a stable scene should allocate its working set once");
    }

    @Test
    void smallVisibilityChangesDoNotOscillateTheNativeBufferPool() {
        var policy = new CapturePoolRetention(32, 256, 120);
        assertEquals(64, policy.nextFrame(64));
        for (int i = 0; i < 300; i++) assertEquals(64, policy.nextFrame(i % 2 == 0 ? 58 : 63));
    }

    @Test
    void sustainedUnderuseRetiresThePeakAndNewDemandCancelsTheShrinkCountdown() {
        var policy = new CapturePoolRetention(32, 256, 3);
        assertEquals(128, policy.nextFrame(128));
        assertEquals(128, policy.nextFrame(0));
        assertEquals(128, policy.nextFrame(0));
        assertEquals(128, policy.nextFrame(90));
        assertEquals(128, policy.nextFrame(0));
        assertEquals(128, policy.nextFrame(0));
        assertEquals(32, policy.nextFrame(0));
    }

    @Test
    void retentionBoundDoesNotTurnIntoACaptureLimit() {
        var policy = new CapturePoolRetention(32, 256, 120);
        assertEquals(256, policy.nextFrame(1000));
        // The caller may still grow its active pool past this retention limit during the frame.
        policy.reset();
        assertEquals(32, policy.nextFrame(0));
    }

    @Test
    void minimumRetentionDoesNotPreventAnIdleSixtyItemSceneFromShrinking() {
        var policy = new CapturePoolRetention(32, 256, 3);
        assertEquals(60, policy.nextFrame(60));
        assertEquals(60, policy.nextFrame(0));
        assertEquals(60, policy.nextFrame(0));
        assertEquals(32, policy.nextFrame(0));
    }
}
