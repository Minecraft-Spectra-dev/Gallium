package cn.spectra.gallium.glowoutline.capture;

import java.util.Objects;
import java.util.function.BiFunction;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import static cn.spectra.gallium.glowoutline.sr.streaming.SrStreamingCoordinator.*;

/**
 * Unique owner of one reusable native mask. T is the backend target; states never own it.
 * Allocation/disposal are injected so the same live ownership protocol can be tested without GL.
 */
public final class MaskRenderContext<T> implements AutoCloseable {
    private enum FrameUse { STREAMING, ORDINARY }

    private final BiFunction<Integer, Integer, T> allocate;
    private final Consumer<T> dispose;
    private T target;
    private int width, height;
    private long epoch = -1L, generation, frameToken;
    private Object backendKey;
    private BooleanSupplier backendCurrent = () -> false;
    private GlowCaptureState occupant;
    private boolean framePrepared;

    public MaskRenderContext(BiFunction<Integer, Integer, T> allocate, Consumer<T> dispose) {
        this.allocate = Objects.requireNonNull(allocate);
        this.dispose = Objects.requireNonNull(dispose);
    }

    /** Called at frame HEAD, including empty frames, so stale-size and fallback resources retire. */
    public void beginFrame(long epoch, int width, int height, Object backendKey,
                           BooleanSupplier backendCurrent) {
        Objects.requireNonNull(backendKey);
        Objects.requireNonNull(backendCurrent);
        if (epoch < 0 || width <= 0 || height <= 0 || !backendCurrent.getAsBoolean()) {
            close();
            return;
        }
        if (occupant != null || this.width != width || this.height != height
                || !Objects.equals(this.backendKey, backendKey)) close();
        this.epoch = epoch;
        this.width = width;
        this.height = height;
        this.backendKey = Objects.requireNonNull(backendKey);
        this.backendCurrent = Objects.requireNonNull(backendCurrent);
        frameToken++;
        framePrepared = false;
    }

    /** Lazy final-hook resource preparation: at most one target, regardless of capture count. */
    public Frame prepareFrame() {
        return prepareFrame(FrameUse.STREAMING);
    }

    /** Ordinary frames borrow the same owner without inventing an SR plan or eligibility. */
    public Frame prepareOrdinaryFrame() {
        return prepareFrame(FrameUse.ORDINARY);
    }

    private Frame prepareFrame(FrameUse use) {
        if (framePrepared || epoch < 0 || width <= 0 || height <= 0 || !backendCurrent.getAsBoolean()) return null;
        framePrepared = true;
        if (target == null) {
            target = allocate.apply(width, height);
            if (target == null) return null;
            generation++;
        }
        return new Frame(use);
    }

    /** A borrowed frame reference. Closing it never transfers ownership to any capture state. */
    public final class Frame implements AutoCloseable {
        private final T mask = target;
        private final long expectedEpoch = epoch, expectedGeneration = generation, token = frameToken;
        private final FrameUse use;
        private boolean closed;

        private Frame(FrameUse use) {
            this.use = use;
        }

        public boolean valid() {
            return !closed && mask != null && mask == target && token == frameToken
                    && expectedEpoch == epoch && expectedGeneration == generation
                    && backendCurrent.getAsBoolean();
        }

        /** Borrowing for final resource validation does not authorize writes. */
        public T target() {
            return valid() ? mask : null;
        }

        public boolean beginState(GlowCaptureState state) {
            var plan = state.streamingReplayPlan();
            if (use != FrameUse.STREAMING || !valid() || occupant != null || plan == null
                    || state.hasPayloadReplayAttempted()
                    || state.captureStage() != CaptureStage.SCHEDULED
                    || plan.epoch() != expectedEpoch || state.captureEpoch != expectedEpoch
                    || plan.targetMode() != ReplayTargetMode.SHARED_MASK
                    || plan.outputWidth() != width || plan.outputHeight() != height) return false;
            occupant = state;
            return true;
        }

        public boolean beginOrdinaryState(GlowCaptureState state) {
            if (use != FrameUse.ORDINARY || !valid() || occupant != null || state.maskTarget != null
                    || !state.canBeginOrdinaryReplay(expectedEpoch)) return false;
            occupant = state;
            return true;
        }

        public T targetFor(GlowCaptureState state) {
            return valid() && occupant == state ? mask : null;
        }

        /** The next clear is forbidden until this state's actual composite has completed. */
        public boolean finishState(GlowCaptureState state) {
            if (use != FrameUse.STREAMING || !valid() || occupant != state || !state.compositedThisFrame
                    || state.captureStage() != CaptureStage.COMPOSITED) return false;
            occupant = null;
            return true;
        }

        /** A completed ordinary composite, not replay alone, permits the next state's clear. */
        public boolean finishOrdinaryState(GlowCaptureState state) {
            if (use != FrameUse.ORDINARY || !valid() || occupant != state
                    || state.captureEpoch != expectedEpoch || state.hasOpenCaptureScope()
                    || state.streamingReplayPlan() != null || !state.hasPayloadReplayAttempted()
                    || !state.capturedThisFrame || !state.maskPreparedThisFrame || !state.compositedThisFrame
                    || (state.captureStage() != CaptureStage.CAPTURED
                    && state.captureStage() != CaptureStage.ELIGIBLE)) return false;
            occupant = null;
            return true;
        }

        @Override
        public void close() {
            if (!closed && token == frameToken && occupant != null) MaskRenderContext.this.close();
            closed = true;
        }
    }

    @Override
    public void close() {
        T retired = target;
        target = null;
        occupant = null;
        epoch = -1L;
        backendKey = null;
        backendCurrent = () -> false;
        generation++;
        frameToken++;
        if (retired != null) dispose.accept(retired);
    }
}
