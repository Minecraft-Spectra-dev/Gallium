package cn.spectra.gallium.glowoutline.capture;

import cn.spectra.gallium.glowoutline.ItemEffectConfig;
import cn.spectra.gallium.glowoutline.sr.streaming.SrStreamingCoordinator.CaptureDomain;
import cn.spectra.gallium.glowoutline.sr.streaming.SrStreamingCoordinator.CaptureLifecycle;
import cn.spectra.gallium.glowoutline.sr.streaming.SrStreamingCoordinator.CaptureStage;
import cn.spectra.gallium.glowoutline.sr.streaming.SrStreamingCoordinator.Eligibility;
import cn.spectra.gallium.glowoutline.sr.streaming.SrStreamingCoordinator.ReplayPlan;
//#if MC>=1_21_02
import com.mojang.blaze3d.ProjectionType;
//#else
//$$ import com.mojang.blaze3d.vertex.VertexSorting;
//#endif
//#if MC>=1_21_06
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
//#endif
import com.mojang.blaze3d.pipeline.TextureTarget;
//#if MC>=1_21_09
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher;
//#endif
//#if MC>=1_26_02
//$$ import net.minecraft.client.renderer.SubmitNodeStorage;
//#endif
import org.joml.Matrix4f;
import org.jspecify.annotations.Nullable;

public final class GlowCaptureState {

    /** Authoritative for selected late frames; shadow lifecycle on the retained legacy paths. */
    private final CaptureLifecycle streamingLifecycle = new CaptureLifecycle();
    public long captureEpoch = -1L;
    public CaptureDomain captureDomain = CaptureDomain.WORLD;
    public long maskDepthSnapshotGeneration = -1L;
    private boolean payloadDiscarded;
    /** Actual dispatcher consumption, including ordinary paths with a shadow SR lifecycle. */
    private boolean payloadReplayAttempted;
    private boolean captureScopeActive;
    private boolean deferredDiscardUntilScopeEnd;
    //#if MC==1_21_11 || MC==1_26_01
    /** Immutable pack transform frozen at the domain's old output-space prepare site. */
    public cn.spectra.gallium.glowoutline.ShaderPackHint.@Nullable ProjectionTransform lateReplayProjection;
    //#endif

    public @Nullable TextureTarget maskTarget;
    //#if MC>=1_21_09
    public @Nullable FeatureRenderDispatcher captureDispatcher;
    /** Reused submit mirror; reset to the current vanilla storage for each capture. */
    public @Nullable DuplicatingSubmitNodeStorage duplicatingStorage;
    //#endif
    //#if MC>=1_26_02
    //$$ // 26.2: FeatureRenderDispatcher no longer owns a SubmitNodeStorage — renderAllFeatures
    //$$ // takes it per call. Each capture state holds its own storage that the duplicating
    //$$ // wrapper mirrors into and that renderAllFeatures drains via drainPhases.
    //$$ public @Nullable SubmitNodeStorage captureStorage;
    //$$ /** True only when renderAllFeatures drained the storage, or it never received a node. */
    //$$ public boolean captureStorageClean;
    //#endif
    // Pre-1.21.9 immediate-mode capture buffer. Retained across frames (its native buffers are
    // pooled by DelayingMultiBufferSource) and freed on release; see GlowCaptureManager.releaseState.
    //#if MC<1_21_09
    //$$ public CaptureSites.@Nullable DelayingMultiBufferSource customBufferSource;
    //$$ public CaptureSites.@Nullable ReusableTeeMultiBufferSource reusableTee;
    //#endif
    public boolean capturedThisFrame;
    /** The captured mesh has been replayed into its owned or borrowed mask for this frame.
     * Legacy SR prepares before upscale; selected shared/late frames replay at final. */
    public boolean maskPreparedThisFrame;
    /** The prepared mask has already contributed to the display color.  World glow uses
     *  additive blending, so this flag is the guard against a second TAIL/compat callback
     *  doubling the effect. */
    public boolean compositedThisFrame;
    /** True when the prepared mask belongs to the post-upscale output-space path. */
    public boolean superResolutionPrepared;
    public boolean active;
    public boolean firstPerson;
    /** Whether captureSceneDepth populated mask depth for this state in the current frame. Exact
     *  Iris replay intentionally leaves this false; if enabling Iris bypass fails later, replay
     *  restores the scene snapshot before drawing instead of treating an empty depth target as
     *  trustworthy. */
    public boolean maskDepthPrepared;
    /** Whether mask replay and scene depth share the exact same per-frame projection. False means
     *  the fragment shader must retain its bounded temporal-mismatch fallback. */
    public boolean exactDepthAlignment = true;
    public @Nullable ItemEffectConfig config;

    /**
     * Conservative byte reservation for this state's pooled mask attachments. The reservation
     * follows the GPU target across frames and is managed by {@link GlowCaptureManager}; frame
     * reset deliberately does not clear it because the pooled textures remain allocated.
     */
    public long captureTargetBytesReserved;
    public int captureTargetReservedWidth;
    public int captureTargetReservedHeight;

    public @Nullable Matrix4f capturedModelViewMatrix;
    public boolean capturedModelViewMatrixValid;
    //#if MC>=1_21_06
    public @Nullable GpuBufferSlice capturedProjectionMatrix;
    /**
     * Projection UBO used only by this capture state when Iris downscaling/jitter must be
     * replayed. A shared buffer is unsafe on deferred backends: a later item's upload can replace
     * the matrix before an earlier item's render pass consumes it.
     */
    public @Nullable GpuBuffer scaledProjectionBuffer;
    public @Nullable GpuBufferSlice scaledProjectionSlice;
    //#endif
    //#if MC>=1_21_02
    public @Nullable ProjectionType capturedProjectionType;
    //#else
    //$$ // 1.21.1 has no ProjectionType; the (Matrix4f, VertexSorting) projection overload
    //$$ // carries the equivalent. backup/restoreProjectionMatrix preserves vertexSorting too.
    //$$ public @Nullable VertexSorting capturedProjectionType;
    //#endif
    /** Plain Matrix4f form of {@link #capturedProjectionMatrix} when available. We need it
     *  to apply VertexDownscaling for shader-pack scale alignment on the mask render; when
     *  {@link #capturedProjectionMatrix4fValid} is {@code false} the mask render falls back
     *  to the unmodified slice (no downscale). Final & owned per-state to avoid per-frame
     *  Matrix4f allocations: the pool keeps one of these alive per slot, refilled in place
     *  via {@link ProjectionMatrixTracker#lookupInto}. */
    public final Matrix4f capturedProjectionMatrix4f = new Matrix4f();
    public boolean capturedProjectionMatrix4fValid;
    /** Per-axis scale actually applied during the most recent mask replay. Shader packs that
     *  floor their internal pixel dimensions can produce slightly different X/Y ratios. */
    public float lastMaskScaleX = 1.0f;
    public float lastMaskScaleY = 1.0f;
    /** Per-axis scale of the selected scene-depth source. This can differ from the mask scale
     *  when projection capture fails and the replay must fall back to its unmodified matrix. */
    public float lastSceneScaleX = 1.0f;
    public float lastSceneScaleY = 1.0f;
    /**
     * Full-texture UV offsets used when mapping the final output pixel back to an internal
     * Iris texture. They are half the declared NDC jitter because NDC spans two UV units.
     * Mask and scene offsets are kept separate: a failed mask projection can still use the
     * exact offset of Iris's scene-depth snapshot while retaining the temporal fallback.
     */
    public float lastMaskOffsetX;
    public float lastMaskOffsetY;
    public float lastSceneOffsetX;
    public float lastSceneOffsetY;

    public CaptureStage captureStage() {
        return streamingLifecycle.stage();
    }

    public @Nullable Eligibility streamingEligibility() {
        return streamingLifecycle.eligibility().orElse(null);
    }

    public @Nullable ReplayPlan streamingReplayPlan() {
        return streamingLifecycle.replayPlan().orElse(null);
    }

    public void beginCaptureLifecycle(long epoch, boolean firstPersonCapture) {
        streamingLifecycle.reset();
        streamingLifecycle.beginCapture();
        captureEpoch = epoch;
        captureDomain = firstPersonCapture ? CaptureDomain.FIRST_PERSON : CaptureDomain.WORLD;
        maskDepthSnapshotGeneration = -1L;
        payloadDiscarded = false;
        payloadReplayAttempted = false;
        captureScopeActive = true;
        deferredDiscardUntilScopeEnd = false;
    }

    /** Idempotent for the many submit calls emitted by one capture scope. */
    public boolean markPayloadCaptured() {
        if (payloadDiscarded || payloadReplayAttempted) return false;
        CaptureStage stage = streamingLifecycle.stage();
        if (stage == CaptureStage.CAPTURING) {
            streamingLifecycle.markCaptured();
            return true;
        }
        if (stage == CaptureStage.CAPTURED) {
            return true;
        }
        return false;
    }

    public boolean markStreamingEligible(Eligibility eligibility) {
        if (payloadReplayAttempted || eligibility == null || streamingLifecycle.stage() != CaptureStage.CAPTURED
                || eligibility.epoch() != captureEpoch
                || eligibility.domain() != captureDomain) return false;
        streamingLifecycle.markEligible(eligibility);
        return true;
    }

    public boolean hasOpenCaptureScope() {
        return captureScopeActive;
    }

    /** The final hook schedules only closed, eligible payloads. */
    public boolean scheduleStreamingReplay(ReplayPlan plan) {
        if (payloadReplayAttempted || plan == null || captureScopeActive
                || streamingLifecycle.stage() != CaptureStage.ELIGIBLE) return false;
        streamingLifecycle.schedule(plan);
        return true;
    }

    /** Must be called immediately before dispatcher/flush on the selected late path. */
    public boolean beginStreamingReplayAttempt() {
        if (payloadReplayAttempted || !streamingLifecycle.beginReplay()) return false;
        payloadReplayAttempted = true;
        return true;
    }

    public boolean hasPayloadReplayAttempted() {
        return payloadReplayAttempted;
    }

    /** Ordinary replay has no SR plan; its shadow lifecycle retains CAPTURED/ELIGIBLE metadata. */
    boolean canBeginOrdinaryReplay(long epoch) {
        CaptureStage stage = streamingLifecycle.stage();
        return epoch >= 0 && captureEpoch == epoch && !captureScopeActive && !payloadDiscarded && !payloadReplayAttempted
                && capturedThisFrame && config != null && !maskPreparedThisFrame && !compositedThisFrame
                && !superResolutionPrepared && streamingReplayPlan() == null
                && (stage == CaptureStage.CAPTURED || stage == CaptureStage.ELIGIBLE);
    }

    /** Called immediately before ordinary dispatcher entry; failures retain the attempt guard. */
    public boolean beginOrdinaryReplayAttempt(long epoch) {
        if (!canBeginOrdinaryReplay(epoch)) return false;
        payloadReplayAttempted = true;
        return true;
    }

    public boolean markStreamingComposited() {
        if (!payloadReplayAttempted || streamingLifecycle.stage() != CaptureStage.REPLAY_ATTEMPTED) return false;
        streamingLifecycle.markComposited();
        return true;
    }

    /** Version-specific, idempotent release of captured CPU payload references. */
    public void discardPayload() {
        if (!payloadDiscarded) {
            //#if MC>=1_21_09
            if (duplicatingStorage != null) duplicatingStorage.disableCapture();
            //#else
            //$$ if (reusableTee != null) reusableTee.disableCapture();
            //#endif
            if (captureScopeActive) deferredDiscardUntilScopeEnd = true;
            else discardPayloadStorageNow();
            payloadDiscarded = true;
        }
        streamingLifecycle.invalidate();
    }

    private void discardPayloadStorageNow() {
        //#if MC>=1_21_09
        //#if MC>=1_26_02
        //$$ if (!captureStorageClean) {
        //$$     captureStorage = null;
        //$$     captureStorageClean = false;
        //$$ }
        //#else
        if (captureDispatcher != null) captureDispatcher.getSubmitNodeStorage().clear();
        //#endif
        //#else
        //$$ if (customBufferSource != null) customBufferSource.endFrame();
        //#endif
    }

    /** Physical wrapper detach is legal only after the wrapped vanilla renderer returned. */
    public void finishCaptureScope() {
        captureScopeActive = false;
        if (deferredDiscardUntilScopeEnd) discardPayloadStorageNow();
        //#if MC>=1_21_09
        if (duplicatingStorage != null) duplicatingStorage.detach();
        //#else
        //$$ if (reusableTee != null) reusableTee.detach();
        //#endif
        deferredDiscardUntilScopeEnd = false;
    }

    /** Marks this capture unusable and drops any 26.2 submit nodes that can no longer be replayed. */
    public void invalidateCapture() {
        discardPayload();
        capturedThisFrame = false;
        maskPreparedThisFrame = false;
        superResolutionPrepared = false;
        maskDepthPrepared = false;
        //#if MC>=1_26_02
        //$$ // A failed replay cannot prove that drainPhases emptied the storage. Drop dirty
        //$$ // nodes immediately so models and closures do not remain retained in the pool.
        //$$ if (!captureScopeActive && !captureStorageClean) captureStorage = null;
        //#endif
    }

    public void resetFrame() {
        // Release any payload not consumed by the current legacy path before dropping metadata.
        discardPayload();
        finishCaptureScope();
        streamingLifecycle.reset();
        captureEpoch = -1L;
        captureDomain = CaptureDomain.WORLD;
        maskDepthSnapshotGeneration = -1L;
        payloadDiscarded = false;
        payloadReplayAttempted = false;
        capturedThisFrame = false;
        maskPreparedThisFrame = false;
        compositedThisFrame = false;
        superResolutionPrepared = false;
        active = false;
        firstPerson = false;
        maskDepthPrepared = false;
        exactDepthAlignment = true;
        config = null;
        capturedModelViewMatrixValid = false;
        //#if MC==1_21_11 || MC==1_26_01
        lateReplayProjection = null;
        //#endif
        //#if MC>=1_21_06
        capturedProjectionMatrix = null;
        // Keep the state-owned slice alive across frame resets. The backing UBO is retained
        // until releaseState(), and uploadScaledProjection() only creates the slice when the
        // buffer is first allocated. Clearing the slice here would leave a live buffer with no
        // usable slice on the second frame.
        //#endif
        capturedProjectionType = null;
        capturedProjectionMatrix4fValid = false;
        lastMaskScaleX = 1.0f;
        lastMaskScaleY = 1.0f;
        lastSceneScaleX = 1.0f;
        lastSceneScaleY = 1.0f;
        lastMaskOffsetX = 0.0f;
        lastMaskOffsetY = 0.0f;
        lastSceneOffsetX = 0.0f;
        lastSceneOffsetY = 0.0f;
        //#if MC>=1_26_02
        //$$ // Preserve normally-drained storage for the next capture. A failed/aborted replay
        //$$ // may retain SubmitNodes, so release only the dirty instance.
        //$$ if (!captureStorageClean) captureStorage = null;
        //#endif
        // Pre-1.21.9: rewind any builder left open by an early-returned renderCapturedNodes
        // (see DelayingMultiBufferSource.endFrame). Native buffers are pooled, only released in releaseState.
        //#if MC<1_21_09
        //$$ if (customBufferSource != null) {
        //$$     customBufferSource.endFrame();
        //$$ }
        //#endif
    }
}
