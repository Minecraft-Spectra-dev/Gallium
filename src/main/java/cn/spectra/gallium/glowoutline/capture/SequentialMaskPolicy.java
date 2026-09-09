package cn.spectra.gallium.glowoutline.capture;

/** HEAD-frozen ownership and final-consumption guard for an ordinary sequential mask frame. */
public final class SequentialMaskPolicy {
    private long epoch = -1L;
    private boolean selected;
    private boolean consumed;
    private boolean forceIndependentNextFrame;

    /** Eligibility is supplied by the caller's current backend and render-path checks. */
    public boolean beginFrame(long epoch, boolean eligible) {
        if (epoch < 0L || epoch < this.epoch) return false;
        if (epoch == this.epoch) return selected;
        this.epoch = epoch;
        selected = eligible && !forceIndependentNextFrame;
        consumed = false;
        forceIndependentNextFrame = false;
        return selected;
    }

    /** Remains selected after failure so no legacy path can take over the same payload. */
    public boolean selected() {
        return selected;
    }

    /** Claims the sole final sequence before it prepares resources or consumes geometry. */
    public boolean consume(long epoch) {
        if (epoch != this.epoch || !selected || consumed) return false;
        consumed = true;
        return true;
    }

    /** A failed selected frame forces the following frame to retain independent masks. */
    public void fail(long epoch) {
        if (epoch != this.epoch || !selected) return;
        consumed = true;
        forceIndependentNextFrame = true;
    }

    public void reset() {
        epoch = -1L;
        selected = false;
        consumed = false;
        forceIndependentNextFrame = false;
    }
}
