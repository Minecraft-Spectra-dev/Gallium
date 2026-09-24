package cn.spectra.gallium.glowoutline.capture;

/** Keeps the recent working set without retaining a brief capture peak indefinitely. */
final class CapturePoolRetention {
    private final int minimum;
    private final int maximum;
    private final int shrinkDelay;
    private int retained;
    private int underusedFrames;

    CapturePoolRetention(int minimum, int maximum, int shrinkDelay) {
        if (minimum < 0 || maximum < minimum || shrinkDelay < 1) {
            throw new IllegalArgumentException("Invalid capture retention limits");
        }
        this.minimum = minimum;
        this.maximum = maximum;
        this.shrinkDelay = shrinkDelay;
        reset();
    }

    int nextFrame(int previousFrameUsed) {
        if (previousFrameUsed < 0) throw new IllegalArgumentException("Negative capture count");
        int required = Math.max(minimum, Math.min(maximum, previousFrameUsed));
        if (required >= retained) {
            retained = required;
            underusedFrames = 0;
        } else if (previousFrameUsed > retained / 2) {
            // Small visibility changes should not repeatedly destroy/recreate native builders.
            underusedFrames = 0;
        } else if (++underusedFrames >= shrinkDelay) {
            retained = required;
            underusedFrames = 0;
        }
        return retained;
    }

    void reset() {
        retained = minimum;
        underusedFrames = 0;
    }
}
