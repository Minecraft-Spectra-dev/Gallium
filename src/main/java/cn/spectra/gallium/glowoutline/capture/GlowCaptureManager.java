package cn.spectra.gallium.glowoutline.capture;

import cn.spectra.gallium.glowoutline.IrisCompat;
import cn.spectra.gallium.glowoutline.ItemEffectConfig;
import cn.spectra.gallium.glowoutline.ItemEffectsManager;
import cn.spectra.gallium.glowoutline.ShaderPackHint;
import cn.spectra.gallium.glowoutline.SuperResolutionCompat;
import cn.spectra.gallium.glowoutline.sr.streaming.SrStreamingCoordinator.CaptureDomain;
import cn.spectra.gallium.glowoutline.sr.streaming.SrStreamingCoordinator.CaptureStage;
import cn.spectra.gallium.glowoutline.sr.streaming.SrStreamingCoordinator.GenerationChange;
import cn.spectra.gallium.glowoutline.sr.streaming.SrStreamingCoordinator.SceneDepthCapturePolicy;
//#if MC>=1_21_09
import cn.spectra.gallium.glowoutline.mixin.accessor.FeatureRenderDispatcherAccessor;
import cn.spectra.gallium.glowoutline.mixin.accessor.GameRendererAccessor;
//#endif
//#if MC>=1_21_06
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.buffers.Std140Builder;
//#endif
//#if MC>=1_21_05
// CommandEncoder exists from 1.21.5 on (the GpuDevice rewrite); copyDepthBounded types its
// parameter with it on 1.21.5 too, so it gets its own guard rather than riding the 1.21.6 one.
import com.mojang.blaze3d.systems.CommandEncoder;
//#endif
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
//#if MC>=1_26_02
//$$ import com.mojang.blaze3d.GpuFormat;
//#endif
import com.mojang.blaze3d.systems.RenderSystem;
//#if MC>=1_21_05
import com.mojang.blaze3d.textures.GpuTexture;
//#endif
import java.nio.ByteBuffer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderBuffers;
//#if MC>=1_21_09
import net.minecraft.client.renderer.SubmitNodeStorage;
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher;
//#endif
import net.minecraft.world.item.ItemStack;
import org.joml.Matrix4f;
import org.jspecify.annotations.Nullable;
import org.lwjgl.system.MemoryStack;

import java.util.ArrayList;
import java.util.List;

public final class GlowCaptureManager {

    /** Hard cap on world capture states. Each holds a screen-sized TextureTarget (~8MB at 1080p)
     *  plus RenderBuffers; the cap stops a brief peak (many on-screen items + armor) from pinning
     *  VRAM forever. Peaks beyond the cap still render — surplus states are freed at the next
     *  {@link #beginFrame()} (alloc/destroy round-trip, but VRAM stays bounded). */
    private static final int POOL_HIGH_WATER_MARK = 32;

    /**
     * Hard upper bound for pooled per-state mask attachments: RGBA8 mask color plus 32-bit depth
     * (8 B/px). 26.2's R32F forward-Z mask is one composite-owned scratch target and therefore
     * is not multiplied by the state count. A byte budget, rather than a state count, keeps
     * 4K/SR output predictable while preserving roughly 32 simultaneous 1080p captures.
     */
    static final long CAPTURE_TARGET_BUDGET_BYTES = 512L * 1024L * 1024L;
    static final int CAPTURE_TARGET_BYTES_PER_PIXEL = 8;
    private static long captureTargetBytesReserved;
    private static boolean captureBudgetWarningLogged;

    private static final List<GlowCaptureState> pool = new ArrayList<>();
    private static final List<GlowCaptureState> activeStates = new ArrayList<>();
    //#if MC==1_21_11 || MC==1_26_01
    private static final MaskRenderContext<TextureTarget> sharedMask = new MaskRenderContext<>(
            (width, height) -> estimatedCaptureTargetBytes(width, height, CAPTURE_TARGET_BYTES_PER_PIXEL)
                    <= CAPTURE_TARGET_BUDGET_BYTES
                    ? new TextureTarget("GlowSharedNativeMask", width, height, true) : null,
            TextureTarget::destroyBuffers);
    private static boolean sharedOwnershipActive;
    private static final SequentialMaskPolicy sequentialMaskPolicy = new SequentialMaskPolicy();
    private static @Nullable SequentialFrameEvidence sequentialFrame;

    /** Ordinary final replay uses actual native attachments, never SR's event-derived extents. */
    private record SequentialFrameEvidence(long epoch, RenderTarget output,
            GpuTexture color, GpuTexture depth, int width, int height,
            boolean iris, boolean srShaderRuntime, OpenGlMaskOrdering.Stamp backend) {
        boolean current() {
            return epoch == SuperResolutionCompat.currentFrameEpoch() && backend.current()
                    && Minecraft.getInstance().getMainRenderTarget() == output
                    && output.width == width && output.height == height
                    && output.getColorTexture() == color && output.getDepthTexture() == depth
                    && !color.isClosed() && !depth.isClosed()
                    && renderTargetSizeMatches(output, width, height)
                    && IrisCompat.isShaderActive() == iris
                    && IrisCompat.isActiveSrRuntime() == srShaderRuntime
                    && !SuperResolutionCompat.isHackConfigured();
        }
    }
    //#endif

    private static @Nullable GlowCaptureState currentCapture;
    /** Allocation-free nesting stack for unconditional CaptureSites.end() calls. */
    private static GlowCaptureState[] captureScopeParents = new GlowCaptureState[8];
    private static boolean[] captureScopeStarted = new boolean[8];
    private static int captureScopeDepth;
    private static int suppressDepth;
    private static boolean sceneDepthCaptured;
    private static long sceneDepthGeneration;
    /** Set only after a non-first-person capture actually mirrors render data this frame. */
    private static boolean worldCaptureSeenThisFrame;
    /**
     * Snapshot taken immediately before vanilla clears the main depth attachment for its hand
     * stage. Without Iris this contains the completed world and vanilla writes the hand after the
     * clear. With an active Iris pack, Iris renders both hand phases inside LevelRenderer and
     * suppresses vanilla's later hand draw, so the same snapshot already contains world + hand.
     */
    private static @Nullable TextureTarget sceneDepthTarget;
    /** Post-clear first-person hand/arm depth, captured before SR restores or destroys the
     *  matching source target.  World masks keep world depth in MaskDepthSampler and bind this
     *  snapshot as SceneDepthSampler at the final display composite. */
    private static @Nullable TextureTarget foregroundDepthTarget;
    private static boolean foregroundDepthCaptured;
    private static long foregroundDepthGeneration;
    //#if MC>=1_21_06
    /** One display-size resample shared by every world mask in an SR frame. */
    private static @Nullable TextureTarget srDisplaySceneDepthTarget;
    private static boolean srDisplaySceneDepthPrepared;
    private static long srDisplaySceneDepthSourceGeneration = -1L;
    //#endif
    //#if MC>=1_26_02
    //$$ /** Forward-Z R32F mirror of vanilla {@code mainTarget.depth} as it stands AT composite
    //$$  *  time. Used on the no-Iris world path so held-item depth (written by
    //$$  *  {@code renderItemInHand}) is part of the sceneDepth sampled by the glow shader —
    //$$  *  this is what makes the player's hand correctly occlude glowing world items. Refreshed
    //$$  *  via {@link #ensureLiveSceneDepthForwardZTarget} + an explicit flip in
    //$$  *  {@link cn.spectra.gallium.glowoutline.shader.GlowComposite#drawGlow}. */
    //$$ private static @Nullable TextureTarget liveSceneDepthForwardZTarget;
    //#endif
    //#if MC>=1_21_09
    /** Single shared {@link RenderBuffers} used by every capture state's
     *  {@link net.minecraft.client.renderer.feature.FeatureRenderDispatcher}. Per-state
     *  {@code new RenderBuffers(1)} would leak the native {@link java.nio.ByteBuffer}
     *  allocations in its internal {@link com.mojang.blaze3d.vertex.ByteBufferBuilder}s
     *  (RenderBuffers has no {@code close()} API and no Cleaner/finalizer — vanilla
     *  creates one instance for the session and never discards it). Sharing one instance
     *  across captures follows vanilla's single-instance model: {@code endBatch()} /
     *  {@code endOutlineBatch()} rewinds the builders after each state's mask render,
     *  so sequential replay is safe. Disposed on resource reload via reflection in
     *  {@link #releaseSharedBuffers()}. */
    private static @Nullable RenderBuffers sharedCaptureBuffers;
    //#endif
    /** Reused holder for the scaled projection. Render thread is single-threaded, so a static
     *  scratch matrix avoids two {@code Matrix4f} allocations per mask render. */
    private static final Matrix4f SCRATCH_SCALED_PROJECTION = new Matrix4f();

    /** Forward-Z clip-space bias used by shader-pack replay on 1.21.5 and older, where the
     *  farthest-neighbour depth pool cannot use the 1.21.6+ {@code GpuTextureView} API. It absorbs
     *  sub-pixel projection jitter.
     *  Applied depth-adaptively (see {@link #computeScaledProjection}): full pull near the
     *  camera, tapering to (near) zero at the far plane so distance occlusion isn't broken.
     *
     *  <p>The magnitude is tuned to the physical jitter: a sub-pixel projection shift maps to an
     *  NDC-depth error of {@code ~2n·θ/z}, which peaks at {@code ~2θ ≈ 0.003} at the near plane
     *  (for a 1080p window, θ ≈ half-fov pixel ≈ 0.0013). The taper {@code zBias·(1-NDC.z)} is
     *  proportional to {@code 1/z}, so a constant of {@code 0.003} keeps the LEQUAL margin above
     *  the jitter at every distance — while the far-field bias it implies is only {@code ~1e-6}
     *  (thousands of times below the constant {@code 0.001} that caused the x-ray), so distance
     *  occlusion stays intact. The 1.21.6+ paths stay unbiased (they use the depth pool instead). */
    private static final float IRIS_TAA_Z_BIAS = 0.003f;

    private GlowCaptureManager() {}

    public enum SceneDepthSnapshotStatus {
        NOT_REQUIRED,
        READY,
        FAILED
    }

    public record SceneDepthSnapshotResult(
            SceneDepthSnapshotStatus status,
            boolean copied,
            long generation,
            int width,
            int height) {

        public SceneDepthSnapshotResult {
            if (status == null) throw new IllegalArgumentException("status");
        }

        public static SceneDepthSnapshotResult notRequired(long generation) {
            return new SceneDepthSnapshotResult(
                    SceneDepthSnapshotStatus.NOT_REQUIRED, false, generation, 0, 0);
        }

        public static SceneDepthSnapshotResult ready(
                boolean copied, long generation, int width, int height) {
            return new SceneDepthSnapshotResult(
                    SceneDepthSnapshotStatus.READY, copied, generation, width, height);
        }

        public static SceneDepthSnapshotResult failed(
                boolean copied, long generation, int width, int height) {
            return new SceneDepthSnapshotResult(
                    SceneDepthSnapshotStatus.FAILED, copied, generation, width, height);
        }

        public boolean ready() {
            return status == SceneDepthSnapshotStatus.READY;
        }
    }

    public static void beginFrame() {
        for (GlowCaptureState state : activeStates) {
            state.resetFrame();
        }
        activeStates.clear();
        clearCaptureScopes();
        currentCapture = null;
        suppressDepth = 0;
        //#if MC==1_21_11 || MC==1_26_01
        Minecraft minecraft = Minecraft.getInstance();
        RenderTarget nativeOutput = minecraft != null ? minecraft.getMainRenderTarget() : null;
        boolean sequential = SuperResolutionCompat.sequentialFinalHookAvailable()
                && !SuperResolutionCompat.isHackConfigured()
                && nativeOutput != null
                && nativeOutput.getDepthTexture() != null
                && renderTargetSizeMatches(nativeOutput, nativeOutput.width, nativeOutput.height);
        var ordinaryBackend = sequential ? OpenGlMaskOrdering.observe() : null;
        sequentialMaskPolicy.beginFrame(SuperResolutionCompat.currentFrameEpoch(), ordinaryBackend != null);
        sequentialFrame = sequentialMaskPolicy.selected() ? new SequentialFrameEvidence(
                SuperResolutionCompat.currentFrameEpoch(), nativeOutput,
                nativeOutput.getColorTexture(), nativeOutput.getDepthTexture(),
                nativeOutput.width, nativeOutput.height, IrisCompat.isShaderActive(),
                IrisCompat.isActiveSrRuntime(), ordinaryBackend) : null;
        if (ownsAnySharedMaskFrame()) {
            if (!sharedOwnershipActive) releaseAllPerStateTargets();
            sharedOwnershipActive = true;
            if (sequentialFrame != null) {
                sharedMask.beginFrame(sequentialFrame.epoch(), sequentialFrame.width(), sequentialFrame.height(),
                        sequentialFrame.backend(), sequentialFrame.backend()::current);
            } else {
                var frame = SuperResolutionCompat.currentStreamingFramePlan();
                var backend = SuperResolutionCompat.sharedMaskBackend();
                sharedMask.beginFrame(frame.epoch(), frame.expectedDisplayWidth(), frame.expectedDisplayHeight(),
                        backend, backend::current);
            }
        } else if (sharedOwnershipActive) {
            sharedMask.close();
            sharedOwnershipActive = false;
        }
        //#endif
        sceneDepthCaptured = false;
        worldCaptureSeenThisFrame = false;
        foregroundDepthCaptured = false;
        //#if MC>=1_21_06
        srDisplaySceneDepthPrepared = false;
        srDisplaySceneDepthSourceGeneration = -1L;
        //#endif

        if (pool.size() > POOL_HIGH_WATER_MARK) {
            for (int i = pool.size() - 1; i >= POOL_HIGH_WATER_MARK; i--) {
                releaseState(pool.remove(i));
            }
        }
    }

    /** Whether the current frame contains captured world geometry that needs scene occlusion. */
    public static boolean needsSceneDepthCapture() {
        return worldCaptureSeenThisFrame;
    }

    /**
     * Records the first successful mirrored submit for a capture state. Keeping this at the
     * submit/getBuffer sites (rather than beginItemCapture) avoids a full-screen depth copy for
     * empty models and capture attempts that never emit render data.
     */
    static void markCaptured(GlowCaptureState state) {
        if (state == null) return;
        if (!SuperResolutionCompat.canAcceptStreamingCapture(
                state.captureEpoch, state.firstPerson)) {
            invalidateCapture(state);
            return;
        }
        if (!state.markPayloadCaptured()) return;
        if (!state.capturedThisFrame) {
            state.capturedThisFrame = true;
        }
        if (!state.firstPerson) worldCaptureSeenThisFrame = true;
    }

    /** Marks storage dirty before a submit can partially mutate it and throw. */
    static void markCaptureStorageDirty(@Nullable GlowCaptureState state) {
        if (state == null) return;
        //#if MC>=1_26_02
        //$$ state.captureStorageClean = false;
        //#endif
    }

    /**
     * Recycles the GPU buffer pools owned by the capture-only {@link RenderBuffers} (its
     * {@link net.minecraft.client.renderer.StagedVertexBuffer}). Vanilla's
     * {@code GameRenderer.render()} calls {@code renderBuffers.endFrame()} only on ITS OWN
     * RenderBuffers; the shared capture RenderBuffers Gallium allocates would otherwise never
     * recycle the vertex/index/staging buffers that each frame's offscreen entity re-render
     * ({@code FeatureRenderDispatcher.renderAllFeatures -> StagedVertexBuffer.upload ->
     * GpuBufferPool.acquire}) acquires. That is a native-memory leak: on Vulkan it exhausts the
     * GPU (VK_ERROR_OUT_OF_DEVICE_MEMORY), on OpenGL it balloons the process commit charge until
     * the OS kills a JVM malloc — both after just a few minutes of play. Call once per frame from
     * {@code GameRendererMixin.galliumGlowFrameStart} so buffers from the prior frame are recycled
     * before the next frame's capture allocates.
     */
    public static void endFrame() {
        //#if MC>=1_26_02
        //$$ // 26.2's capture dispatcher uploads through the StagedVertexBuffer owned by
        //$$ // sharedCaptureBuffers (a field that only exists on MC>=1_21_09). Its GpuBufferPools
        //$$ // (staging/vertex/index) are only recycled by endFrame(); older versions flush
        //$$ // bufferSource/outlineBufferSource inside renderCapturedNodes and have no endFrame()
        //$$ // here.
        //$$ if (sharedCaptureBuffers == null) return;
        //$$ sharedCaptureBuffers.endFrame();
        //#else
        // no-op: pre-1.21.9 builds have no sharedCaptureBuffers field, and pre-26.2 renderers
        // recycle their own buffer pools inside renderCapturedNodes.
        //#endif
    }

    public static List<GlowCaptureState> getActiveStates() {
        return activeStates;
    }

    /**
     * Replay one capture domain into full-size masks while Super Resolution's matching source
     * target is still alive.  Color composition is intentionally deferred until SR has produced
     * its display frame.
     *
     * <p>World masks receive a nearest-neighbour resample of the retained render-size world depth;
     * first-person masks start at far depth.  The separately captured foreground depth remains a
     * second final-composite sampler, preserving the existing world+hand occlusion contract.</p>
     */
    public static void prepareSuperResolutionMasks(Minecraft minecraft, int displayWidth,
                                                    int displayHeight, boolean firstPerson) {
        if (SuperResolutionCompat.ownsLateReplayFrame() || ownsSequentialSharedMaskFrame()) return;
        if (minecraft == null || displayWidth <= 0 || displayHeight <= 0) return;
        //#if MC>=1_21_06
        if (!firstPerson && hasPendingSuperResolutionCapture(false)
                && !prepareSuperResolutionSceneDepth(displayWidth, displayHeight)) {
            for (GlowCaptureState state : activeStates) {
                if (state.capturedThisFrame && !state.firstPerson
                        && !state.maskPreparedThisFrame) {
                    invalidateCapture(state);
                }
            }
            return;
        }
        //#endif
        for (GlowCaptureState state : activeStates) {
            if (!state.capturedThisFrame || state.config == null || state.firstPerson != firstPerson
                    || state.maskPreparedThisFrame || state.compositedThisFrame
                    || state.maskTarget == null) continue;

            // Reserve the worst-case output-space footprint before resize() can allocate it.
            // Active states are never evicted; captures beyond the byte cap fail independently.
            if (!reserveCaptureTarget(state, displayWidth, displayHeight)) {
                warnCaptureBudget(displayWidth, displayHeight);
                invalidateCapture(state);
                continue;
            }

            //#if MC>=1_21_05
            if (!renderTargetSizeMatches(state.maskTarget, displayWidth, displayHeight)) {
                state.maskTarget.resize(displayWidth, displayHeight);
            }
            if (!renderTargetSizeMatches(state.maskTarget, displayWidth, displayHeight)) {
                invalidateCapture(state);
                continue;
            }
            //#else
            //$$ if (state.maskTarget.width != displayWidth || state.maskTarget.height != displayHeight) {
            //#if MC>=1_21_02
            //$$     state.maskTarget.resize(displayWidth, displayHeight);
            //#else
            //$$     state.maskTarget.resize(displayWidth, displayHeight, net.minecraft.client.Minecraft.ON_OSX);
            //#endif
            //$$ }
            //$$ if (state.maskTarget.width != displayWidth || state.maskTarget.height != displayHeight) {
            //$$     invalidateCapture(state);
            //$$     continue;
            //$$ }
            //#endif

            // resize() replaces/clears the attachment; any pre-fill performed at render size is
            // no longer valid.  Output-space replay deliberately starts from far depth.
            state.maskDepthPrepared = false;
            state.maskDepthSnapshotGeneration = -1L;
            renderCapturedNodes(state, minecraft, true);
            if (state.capturedThisFrame) {
                state.maskPreparedThisFrame = true;
                state.superResolutionPrepared = true;
            }
        }
        //#if MC<1_21_05
        //$$ // Legacy flushToTarget leaves the mask FBO bound.  SR's handler resumes immediately
        //$$ // after this callback and expects its render-size source to remain the write target.
        //$$ RenderTarget sourceTarget = minecraft.getMainRenderTarget();
        //$$ if (sourceTarget != null) sourceTarget.bindWrite(true);
        //#endif
    }

    private static boolean hasPendingSuperResolutionCapture(boolean firstPerson) {
        for (GlowCaptureState state : activeStates) {
            if (state.capturedThisFrame && state.firstPerson == firstPerson
                    && !state.maskPreparedThisFrame && !state.compositedThisFrame) {
                return true;
            }
        }
        return false;
    }

    //#if MC==1_21_11 || MC==1_26_01
    /** Resource preparation only. No dispatcher/geometry consumption occurs before plan creation. */
    public static boolean prepareLateReplayTargets(List<GlowCaptureState> states, int width, int height) {
        if (states.stream().anyMatch(state -> !state.firstPerson)
                && !prepareSuperResolutionSceneDepth(width, height)) return false;
        for (GlowCaptureState state : states) {
            if (!SuperResolutionCompat.ownsSharedMaskFrame()) {
                if (!reserveCaptureTarget(state, width, height)) return false;
                if (!renderTargetSizeMatches(state.maskTarget, width, height)) {
                    state.maskTarget.resize(width, height);
                }
                if (!renderTargetSizeMatches(state.maskTarget, width, height)) return false;
            }
            state.maskDepthPrepared = false;
            state.maskDepthSnapshotGeneration = -1L;
        }
        return true;
    }

    public static boolean lateReplayTargetMatches(TextureTarget mask, int width, int height) {
        return renderTargetSizeMatches(mask, width, height)
                && !mask.getColorTexture().isClosed() && !mask.getDepthTexture().isClosed();
    }

    public static boolean lateReplayPayloadReady(GlowCaptureState state) {
        return state.capturedThisFrame && !state.maskPreparedThisFrame && !state.compositedThisFrame
                && !state.hasOpenCaptureScope() && state.config != null
                && state.captureDispatcher != null && sharedCaptureBuffers != null
                && state.capturedProjectionMatrix4fValid && state.capturedProjectionType != null
                && state.capturedModelViewMatrixValid && state.capturedModelViewMatrix != null
                && state.lateReplayProjection != null;
    }

    /** Sole late-frame dispatcher entry; the ordinary prepare API cannot enter this path. */
    public static boolean replayScheduledMask(GlowCaptureState state, Minecraft minecraft, TextureTarget mask) {
        var plan = state.streamingReplayPlan();
        if (!SuperResolutionCompat.ownsLateReplayFrame() || plan == null
                || plan.epoch() != SuperResolutionCompat.currentFrameEpoch()
                || state.captureStage() != CaptureStage.SCHEDULED
                || !lateReplayPayloadReady(state)
                || !lateReplayTargetMatches(mask, plan.outputWidth(), plan.outputHeight())
                || (SuperResolutionCompat.ownsSharedMaskFrame() ? state.maskTarget != null : mask != state.maskTarget)
                || (!state.firstPerson && (!srDisplaySceneDepthPrepared
                || plan.worldSnapshotGeneration() != sceneDepthGeneration
                || srDisplaySceneDepthSourceGeneration != sceneDepthGeneration))) return false;
        renderCapturedNodes(state, minecraft, true, mask);
        if (!state.capturedThisFrame || state.captureStage() != CaptureStage.REPLAY_ATTEMPTED) return false;
        state.maskPreparedThisFrame = true;
        state.superResolutionPrepared = true;
        return true;
    }

    public static MaskRenderContext<TextureTarget>.Frame prepareSharedMaskFrame() {
        return sharedMask.prepareFrame();
    }

    public static void abortSharedMaskFrame() {
        sharedMask.close();
    }

    public static MaskRenderContext<TextureTarget>.Frame prepareSequentialMaskFrame() {
        return sequentialFrameCurrent() ? sharedMask.prepareOrdinaryFrame() : null;
    }

    public static boolean consumeSequentialFrame() {
        return sequentialMaskPolicy.consume(SuperResolutionCompat.currentFrameEpoch());
    }

    public static boolean sequentialFrameCurrent() {
        return sequentialFrame != null && sequentialMaskPolicy.selected() && sequentialFrame.current()
                && SuperResolutionCompat.sequentialFinalHookCurrent()
                && (!sequentialFrame.srShaderRuntime() || SuperResolutionCompat.hasCompletedShaderCompatDispatch(
                        sequentialFrame.width(), sequentialFrame.height()));
    }

    public static void abortSequentialFrame() {
        sequentialMaskPolicy.fail(SuperResolutionCompat.currentFrameEpoch());
        try {
            abortPendingPayloads();
        } finally {
            sharedMask.close();
        }
    }

    /** Strict payload readiness without requiring an independently owned texture. */
    public static boolean sequentialPayloadReady(GlowCaptureState state) {
        return ownsSequentialSharedMaskFrame() && sequentialFrame != null
                && state.captureEpoch == sequentialFrame.epoch()
                && state.capturedThisFrame && !state.compositedThisFrame && !state.maskPreparedThisFrame
                && !state.hasPayloadReplayAttempted() && !state.hasOpenCaptureScope()
                && state.streamingReplayPlan() == null && state.config != null && state.maskTarget == null
                && (state.captureStage() == CaptureStage.CAPTURED || state.captureStage() == CaptureStage.ELIGIBLE)
                && state.captureDispatcher != null && sharedCaptureBuffers != null
                && state.capturedProjectionMatrix4fValid && state.capturedProjectionType != null
                && state.capturedModelViewMatrixValid && state.capturedModelViewMatrix != null;
    }

    public static boolean sequentialCaptureMatches(GlowCaptureState state, int width, int height) {
        return sequentialPayloadReady(state) && sequentialFrame.width() == width && sequentialFrame.height() == height;
    }

    public static boolean replaySequentialMask(GlowCaptureState state, Minecraft minecraft, TextureTarget mask) {
        if (!sequentialFrameCurrent() || !sequentialPayloadReady(state)
                || !lateReplayTargetMatches(mask, sequentialFrame.width(), sequentialFrame.height())) return false;
        renderCapturedNodes(state, minecraft, false, mask);
        if (!state.capturedThisFrame || !state.hasPayloadReplayAttempted()) return false;
        state.maskPreparedThisFrame = true;
        return true;
    }
    //#endif

    public static boolean ownsSequentialSharedMaskFrame() {
        //#if MC==1_21_11 || MC==1_26_01
        return sequentialMaskPolicy.selected();
        //#else
        //$$ return false;
        //#endif
    }

    public static boolean ownsAnySharedMaskFrame() {
        return ownsSequentialSharedMaskFrame() || SuperResolutionCompat.ownsSharedMaskFrame();
    }

    //#if MC>=1_21_06
    /** Resamples the render-size scene depth once; per-state masks then use exact-size GPU copies. */
    private static boolean prepareSuperResolutionSceneDepth(int width, int height) {
        if (srDisplaySceneDepthPrepared
                && srDisplaySceneDepthSourceGeneration == sceneDepthGeneration
                && renderTargetSizeMatches(srDisplaySceneDepthTarget, width, height)) {
            return true;
        }
        if (!sceneDepthCaptured || sceneDepthTarget == null) return false;
        if (!renderTargetSizeMatches(srDisplaySceneDepthTarget, width, height)) {
            if (srDisplaySceneDepthTarget != null) {
                srDisplaySceneDepthTarget.destroyBuffers();
            }
            srDisplaySceneDepthTarget = new TextureTarget(
                    "GlowSrDisplaySceneDepth", width, height, true
                    //#if MC>=1_26_02
                    //$$ , GpuFormat.RGBA8_UNORM
                    //#endif
            );
            //#if MC<1_21_11
            //$$ srDisplaySceneDepthTarget.getDepthTexture().setUseMipmaps(false);
            //#endif
        }
        if (!renderTargetSizeMatches(srDisplaySceneDepthTarget, width, height)) return false;

        var encoder = RenderSystem.getDevice().createCommandEncoder();
        srDisplaySceneDepthPrepared =
                cn.spectra.gallium.glowoutline.shader.DepthResamplePipeline.resample(
                        encoder,
                        sceneDepthTarget.getDepthTextureView(),
                        srDisplaySceneDepthTarget.getColorTextureView(),
                        srDisplaySceneDepthTarget.getDepthTextureView());
        srDisplaySceneDepthSourceGeneration = srDisplaySceneDepthPrepared
                ? sceneDepthGeneration : -1L;
        return srDisplaySceneDepthPrepared;
    }
    //#endif

    /** Saturating attachment estimate used by the allocator and pure boundary tests. */
    static long estimatedCaptureTargetBytes(int width, int height, int bytesPerPixel) {
        if (width <= 0 || height <= 0 || bytesPerPixel <= 0) return Long.MAX_VALUE;
        long pixels = (long) width * (long) height;
        if (pixels > Long.MAX_VALUE / bytesPerPixel) return Long.MAX_VALUE;
        return pixels * bytesPerPixel;
    }

    /** Whether replacing one retained reservation keeps the aggregate within its hard cap. */
    static boolean captureReservationFits(long totalReserved, long currentReservation,
                                          long requestedReservation, long budget) {
        if (totalReserved < 0L || currentReservation < 0L || requestedReservation < 0L
                || budget < 0L || currentReservation > totalReserved) return false;
        long withoutCurrent = totalReserved - currentReservation;
        return withoutCurrent <= budget && requestedReservation <= budget - withoutCurrent;
    }

    /**
     * Reserve one state's worst-case target footprint. Inactive pooled targets are deterministic
     * eviction candidates; captures already active in this frame are never stolen from. A failed
     * reservation leaves the state and global accounting untouched so the caller can fail closed.
     */
    private static boolean reserveCaptureTarget(GlowCaptureState state, int width, int height) {
        long requested = estimatedCaptureTargetBytes(
                width, height, CAPTURE_TARGET_BYTES_PER_PIXEL);
        long current = state.captureTargetBytesReserved;
        if (requested == Long.MAX_VALUE) return false;
        if (current == requested
                && state.captureTargetReservedWidth == width
                && state.captureTargetReservedHeight == height) return true;

        if (!captureReservationFits(
                captureTargetBytesReserved, current, requested, CAPTURE_TARGET_BUDGET_BYTES)) {
            // Evict cold slots from the end first, matching the pool's high-water shrink order.
            for (int i = pool.size() - 1; i >= 0; i--) {
                GlowCaptureState candidate = pool.get(i);
                if (candidate == state || candidate.active
                        || candidate.captureTargetBytesReserved == 0L) continue;
                releaseCaptureTargets(candidate);
                if (captureReservationFits(captureTargetBytesReserved, current,
                        requested, CAPTURE_TARGET_BUDGET_BYTES)) break;
            }
        }
        if (!captureReservationFits(
                captureTargetBytesReserved, current, requested, CAPTURE_TARGET_BUDGET_BYTES)) {
            return false;
        }

        captureTargetBytesReserved = captureTargetBytesReserved - current + requested;
        state.captureTargetBytesReserved = requested;
        state.captureTargetReservedWidth = width;
        state.captureTargetReservedHeight = height;
        return true;
    }

    /** Releases native mask attachments and their persistent reservation. */
    private static void releaseCaptureTargets(GlowCaptureState state) {
        long reserved = state.captureTargetBytesReserved;
        if (state.maskTarget != null) {
            state.maskTarget.destroyBuffers();
            state.maskTarget = null;
        }
        captureTargetBytesReserved = Math.max(0L, captureTargetBytesReserved - reserved);
        state.captureTargetBytesReserved = 0L;
        state.captureTargetReservedWidth = 0;
        state.captureTargetReservedHeight = 0;
    }

    /** Includes cold pool entries, not just the current active prefix. */
    static void releaseAllPerStateTargets() {
        for (GlowCaptureState state : pool) releaseCaptureTargets(state);
    }

    private static void warnCaptureBudget(int width, int height) {
        if (captureBudgetWarningLogged) return;
        captureBudgetWarningLogged = true;
        cn.spectra.gallium.Gallium.LOGGER.warn(
                "Gallium capture target budget exhausted at {}x{} ({} MiB cap, {} B/px); "
                        + "additional glow captures fail closed",
                width, height, CAPTURE_TARGET_BUDGET_BYTES / (1024L * 1024L),
                CAPTURE_TARGET_BYTES_PER_PIXEL);
    }

    //#if MC>=1_21_05
    /** Returns whether an actual texture extent exactly matches the logical frame extent. */
    static boolean dimensionsMatch(int actualWidth, int actualHeight,
                                   int expectedWidth, int expectedHeight) {
        return expectedWidth > 0 && expectedHeight > 0
                && actualWidth == expectedWidth && actualHeight == expectedHeight;
    }

    /** Pure dimension check used by the GPU copy guard and its regression tests. */
    static boolean copyDimensionsMatch(int sourceWidth, int sourceHeight,
                                       int destinationWidth, int destinationHeight,
                                       int expectedWidth, int expectedHeight) {
        return dimensionsMatch(sourceWidth, sourceHeight, expectedWidth, expectedHeight)
                && dimensionsMatch(destinationWidth, destinationHeight,
                expectedWidth, expectedHeight);
    }

    private static boolean textureSizeMatches(@Nullable GpuTexture texture, int w, int h) {
        return texture != null && dimensionsMatch(texture.getWidth(0), texture.getHeight(0), w, h);
    }

    /** Checks both RenderTarget metadata and its real GPU attachment extents. HD screenshot
     *  mods resize the main target around capture boundaries, so trusting only width/height can
     *  leave Gallium about to use an old pooled attachment with a new frame extent. */
    private static boolean renderTargetSizeMatches(@Nullable RenderTarget target, int w, int h) {
        if (target == null || !dimensionsMatch(target.width, target.height, w, h)
                || !textureSizeMatches(target.getColorTexture(), w, h)) return false;
        return !target.useDepth || textureSizeMatches(target.getDepthTexture(), w, h);
    }

    private static void invalidateAllCaptures() {
        for (GlowCaptureState state : activeStates) invalidateCapture(state);
    }

    /** Marks every stale-size state unusable before either the pool pass or a direct copy can
     *  touch it. This includes first-person and exact-Iris states, which intentionally skip the
     *  depth prefill loop but would otherwise replay a low-resolution mask into a larger frame. */
    private static void invalidateMismatchedCaptures(int w, int h) {
        for (GlowCaptureState state : activeStates) {
            if (!renderTargetSizeMatches(state.maskTarget, w, h)) invalidateCapture(state);
        }
    }

    /** Exact-size depth copy shared by {@link #captureSceneDepth} and replay fallbacks.
     *
     *  <p>Gallium pools scene/mask targets across frames. HD screenshot mods can change the
     *  main target extent at capture boundaries; the old code trusted logical width/height and
     *  could ask Minecraft to write a 7680x4053 rectangle into an actual 2560x1351 texture.
     *  Minecraft 1.21.11 correctly rejects that with {@link IllegalArgumentException}.
     *
     *  <p>Both smaller and larger attachments are rejected. Copying only a sub-rectangle of a
     *  larger stale target is just as incoherent as overflowing a smaller one. On rejection the
     *  destination is cleared to the far plane and the caller skips that capture for this frame.
     *
     *  @return true only when source, destination, and the requested rectangle all match. */
    private static boolean copyDepthBounded(CommandEncoder encoder, GpuTexture src, GpuTexture dst,
                                            int w, int h, double farDepth) {
        if (encoder == null || dst == null) return false;
        if (src == null || !copyDimensionsMatch(
                src.getWidth(0), src.getHeight(0), dst.getWidth(0), dst.getHeight(0), w, h)) {
            encoder.clearDepthTexture(dst, farDepth);
            return false;
        }
        encoder.copyTextureToTexture(src, dst, 0, 0, 0, 0, 0, w, h);
        return true;
    }
    //#endif

    static GenerationChange applySceneDepthGeneration(
            GlowCaptureState state, long newGeneration) {
        GenerationChange change = cn.spectra.gallium.glowoutline.sr.streaming
                .SrStreamingCoordinator.generationChange(
                state.captureStage(), state.maskDepthSnapshotGeneration, newGeneration);
        if (change == GenerationChange.UNCHANGED) return change;
        if (change == GenerationChange.SYSTEMIC_ABORT || state.maskPreparedThisFrame
                || state.hasPayloadReplayAttempted()) {
            invalidateCapture(state);
            return GenerationChange.SYSTEMIC_ABORT;
        }
        state.maskDepthPrepared = false;
        state.maskDepthSnapshotGeneration = -1L;
        return GenerationChange.INVALIDATE_PREPARED_DEPTH;
    }

    private static boolean invalidatePreparedMaskDepthForSceneGeneration(long newGeneration) {
        boolean safe = true;
        for (GlowCaptureState state : activeStates) {
            if (state.firstPerson || !state.capturedThisFrame) continue;
            if (applySceneDepthGeneration(state, newGeneration)
                    == GenerationChange.SYSTEMIC_ABORT) safe = false;
        }
        if (!safe) abortPendingPayloads();
        return safe;
    }

    /** Systemic frame abort: no discarded payload may be revived or retried this frame. */
    public static void abortPendingPayloads() {
        for (GlowCaptureState state : activeStates) {
            if (!state.compositedThisFrame) invalidateCapture(state);
        }
    }

    /** Domain-local failure must not destroy independent first-person/world payloads. */
    public static int abortPayloadsInDomain(CaptureDomain domain) {
        if (domain == null) throw new IllegalArgumentException("domain");
        int aborted = 0;
        for (GlowCaptureState state : activeStates) {
            CaptureStage stage = state.captureStage();
            if (!state.compositedThisFrame && state.captureDomain == domain
                    && stage != CaptureStage.IDLE && stage != CaptureStage.INVALID) {
                invalidateCapture(state);
                aborted++;
            }
        }
        if (domain == CaptureDomain.WORLD) worldCaptureSeenThisFrame = false;
        return aborted;
    }

    private static void recordRenderMaskDepthPrefill(GlowCaptureState state) {
        state.maskDepthPrepared = true;
        state.maskDepthSnapshotGeneration = sceneDepthGeneration;
    }

    /** Pure capture-state invalidation shared by every Minecraft rendering API branch. */
    private static void invalidateCapture(GlowCaptureState state) {
        state.invalidateCapture();
    }

    public static void captureSceneDepth(RenderTarget mainTarget) {
        captureSceneDepth(mainTarget, SceneDepthCapturePolicy.SNAPSHOT_AND_PREFILL, false);
    }

    public static SceneDepthSnapshotResult captureSceneDepth(
            RenderTarget mainTarget,
            SceneDepthCapturePolicy policy,
            boolean replaceExisting) {
        if (policy == null) throw new IllegalArgumentException("policy");
        try {
            return captureSceneDepthInternal(mainTarget, policy, replaceExisting);
        } catch (RuntimeException | Error failure) {
            abortPendingPayloads();
            //#if MC<1_21_05
            //$$ if (mainTarget != null) mainTarget.bindWrite(true);
            //#endif
            throw failure;
        }
    }

    private static SceneDepthSnapshotResult captureSceneDepthInternal(
            RenderTarget mainTarget,
            SceneDepthCapturePolicy policy,
            boolean replaceExisting) {
        // This central guard also covers SuperResolutionCompat's direct capture entry point.
        if (!needsSceneDepthCapture()) {
            return SceneDepthSnapshotResult.notRequired(sceneDepthGeneration);
        }
        if (mainTarget == null) {
            return SceneDepthSnapshotResult.failed(
                    false, sceneDepthGeneration, 0, 0);
        }
        //#if MC>=1_21_05
        if (sceneDepthCaptured && !replaceExisting) {
            return SceneDepthSnapshotResult.ready(
                    false, sceneDepthGeneration,
                    sceneDepthTarget != null ? sceneDepthTarget.width : 0,
                    sceneDepthTarget != null ? sceneDepthTarget.height : 0);
        }
        GpuTexture srcDepth = mainTarget.getDepthTexture();
        if (srcDepth == null) {
            return SceneDepthSnapshotResult.failed(
                    false, sceneDepthGeneration, mainTarget.width, mainTarget.height);
        }

        int w = mainTarget.width, h = mainTarget.height;
        if (!renderTargetSizeMatches(mainTarget, w, h)) {
            return SceneDepthSnapshotResult.failed(
                    false, sceneDepthGeneration, w, h);
        }
        TextureTarget previousSceneDepthTarget = sceneDepthTarget;
        boolean allocateReplacement = replaceExisting && sceneDepthCaptured
                || !renderTargetSizeMatches(sceneDepthTarget, w, h);
        TextureTarget destination = sceneDepthTarget;
        CommandEncoder encoder;
        boolean sceneDepthReady;
        try {
            if (allocateReplacement) {
                destination = new TextureTarget("GlowSceneDepth", w, h, true
                        //#if MC>=1_26_02
                        //$$ , GpuFormat.RGBA8_UNORM
                        //#endif
                );
                //#if MC<1_21_11
                //#if MC>=1_21_06
                //$$ // Same sampler-completeness rationale as the mask depth target in
                //$$ // beginItemCapture: keep useMipmaps=false on the single-mip depth view
                //$$ // so the composite shader's SceneDepthSampler reads real depth values
                //$$ // rather than the (1,1,1,1) returned by an incomplete sampler.
                //$$ destination.getDepthTexture().setUseMipmaps(false);
                //#endif
                //#endif
            }
            encoder = RenderSystem.getDevice().createCommandEncoder();
            //#if MC>=1_26_02
            //$$ sceneDepthReady = copyDepthBounded(
            //$$         encoder, srcDepth, destination.getDepthTexture(), w, h, 0.0);
            //#else
            sceneDepthReady = copyDepthBounded(
                    encoder, srcDepth, destination.getDepthTexture(), w, h, 1.0);
            //#endif
        } catch (RuntimeException | Error failure) {
            if (destination != previousSceneDepthTarget) {
                try {
                    destination.destroyBuffers();
                } catch (Throwable cleanupFailure) {
                    failure.addSuppressed(cleanupFailure);
                }
            }
            abortPendingPayloads();
            throw failure;
        }
        if (!sceneDepthReady) {
            if (destination != previousSceneDepthTarget) destination.destroyBuffers();
            return SceneDepthSnapshotResult.failed(
                    false, sceneDepthGeneration, w, h);
        }
        if (destination != previousSceneDepthTarget) {
            sceneDepthTarget = destination;
            if (previousSceneDepthTarget != null) previousSceneDepthTarget.destroyBuffers();
        }
        sceneDepthCaptured = true;
        sceneDepthGeneration++;
        //#if MC>=1_21_06
        srDisplaySceneDepthPrepared = false;
        srDisplaySceneDepthSourceGeneration = -1L;
        //#endif
        if (!invalidatePreparedMaskDepthForSceneGeneration(sceneDepthGeneration)) {
            return SceneDepthSnapshotResult.failed(
                    true, sceneDepthGeneration, w, h);
        }
        SceneDepthSnapshotResult snapshotResult = SceneDepthSnapshotResult.ready(
                true, sceneDepthGeneration, w, h);
        if (policy == SceneDepthCapturePolicy.SNAPSHOT_ONLY || ownsSequentialSharedMaskFrame()) return snapshotResult;

        // Reject stale pooled targets before the depth-pool path can silently resample into one.
        // Fabrishot always renders a warm-up frame before saving, so dropping an incoherent
        // resize-boundary frame is safer than allocating several gigabytes of replacement masks.
        invalidateMismatchedCaptures(w, h);
        ShaderPackHint.ProjectionTransform packProjection =
                IrisCompat.getShaderProjectionTransform(w, h);

        // Iris's HandRenderer runs inside LevelRenderer before this hook. Consequently srcDepth
        // already contains the Iris hand here; on the vanilla path it still contains only world
        // geometry. GlowComposite selects this snapshot for Iris and combines the vanilla path's
        // post-clear hand depth separately in the resource-pack fragment shader.

        // Pre-fill fallback states' mask depth with world depth. Non-item pixels keep this world
        // depth so the glow shader's mask-vs-scene comparison can reject outlines behind blocks,
        // entities, and other items. Exact Iris states skip this copy and start at far depth in
        // renderCapturedNodes, where the complete mask is captured before composite occlusion.
        //#if MC>=1_26_02
        //$$ // Native 26.2 stores reverse-Z and uses a plain copy for fallback states. Iris 1.11.x
        //$$ // restores forward-Z on OpenGL, but its pack projection may jitter scene depth while
        //$$ // an unknown-pack bypass replay is un-jittered. MAX-pool that forward-Z scene depth
        //$$ // so the replay's LEQUAL remains stable at silhouettes. Vulkan retains native reverse-Z
        //$$ // and therefore skips this MAX pool. Exact declarations skip the pre-fill entirely.
        //$$ boolean useDepthPool = IrisCompat.usesForwardDepthCompatibility()
        //$$         && cn.spectra.gallium.glowoutline.shader.DepthMinPoolPipeline.isReady();
        //$$ GpuTexture pooledDepth = null;
        //$$ for (GlowCaptureState state : activeStates) {
        //$$     if (state.capturedThisFrame && state.maskTarget != null && !state.firstPerson) {
        //$$         boolean exactTemporalReplay = canPrepareExactTemporalReplay(state, packProjection);
        //$$         if (clearsMaskDepthForReplay(state.firstPerson, IrisCompat.isShaderActive(),
        //$$                 exactTemporalReplay)
        //$$                 || shouldDeferReverseZWorldOcclusion(state.firstPerson,
        //$$                 IrisCompat.isShaderActive(), IrisCompat.usesForwardDepthCompatibility(),
        //$$                 exactTemporalReplay)) continue;
        //$$         if (useDepthPool && !exactTemporalReplay) {
        //$$             if (pooledDepth == null) {
        //$$                 if (cn.spectra.gallium.glowoutline.shader.DepthMinPoolPipeline.pool(
        //$$                         encoder,
        //$$                         sceneDepthTarget.getDepthTextureView(),
        //$$                         state.maskTarget.getColorTextureView(),
        //$$                         state.maskTarget.getDepthTextureView())) {
        //$$                     pooledDepth = state.maskTarget.getDepthTexture();
        //$$                     recordRenderMaskDepthPrefill(state);
        //$$                     continue;
        //$$                 }
        //$$                 useDepthPool = false;
        //$$             } else {
        //$$                 // Exact-size copy; any late skew invalidates this state below.
        //$$                 state.maskDepthPrepared = copyDepthBounded(encoder, pooledDepth,
        //$$                         state.maskTarget.getDepthTexture(), w, h, 0.0);
        //$$                 if (state.maskDepthPrepared) recordRenderMaskDepthPrefill(state);
        //$$                 else invalidateCapture(state);
        //$$                 continue;
        //$$             }
        //$$         }
        //$$         state.maskDepthPrepared = copyDepthBounded(encoder, srcDepth,
        //$$                 state.maskTarget.getDepthTexture(), w, h, 0.0);
        //$$         if (state.maskDepthPrepared) recordRenderMaskDepthPrefill(state);
        //$$         else invalidateCapture(state);
        //$$     }
        //$$ }
        //#elseif MC>=1_26_00
        // 26.1: exact pack declarations skip the pre-fill and clear mask depth to far during
        // replay. Unknown packs retain the 3x3 MAX-pool fallback. The pool reads sceneDepthTarget
        // to avoid a read-from-and-write-to mask.depth hazard.
        boolean useDepthPool = IrisCompat.isShaderActive()
                && cn.spectra.gallium.glowoutline.shader.DepthMinPoolPipeline.isReady();
        GpuTexture pooledDepth = null;
        for (GlowCaptureState state : activeStates) {
            if (state.capturedThisFrame && state.maskTarget != null && !state.firstPerson) {
                boolean exactTemporalReplay = canPrepareExactTemporalReplay(state, packProjection);
                if (clearsMaskDepthForReplay(state.firstPerson, IrisCompat.isShaderActive(),
                        exactTemporalReplay)) continue;
                if (useDepthPool && !exactTemporalReplay) {
                    if (pooledDepth == null) {
                        if (cn.spectra.gallium.glowoutline.shader.DepthMinPoolPipeline.pool(
                                encoder,
                                sceneDepthTarget.getDepthTextureView(),
                                state.maskTarget.getColorTextureView(),
                                state.maskTarget.getDepthTextureView())) {
                            pooledDepth = state.maskTarget.getDepthTexture();
                            recordRenderMaskDepthPrefill(state);
                            continue;
                        }
                        useDepthPool = false;
                    } else {
                        // Exact-size copy; any late skew invalidates this state below.
                        state.maskDepthPrepared = copyDepthBounded(encoder, pooledDepth,
                                state.maskTarget.getDepthTexture(), w, h, 1.0);
                        if (state.maskDepthPrepared) {
                            recordRenderMaskDepthPrefill(state);
                        } else invalidateCapture(state);
                        continue;
                    }
                }
                state.maskDepthPrepared = copyDepthBounded(encoder, srcDepth,
                        state.maskTarget.getDepthTexture(), w, h, 1.0);
                if (state.maskDepthPrepared) {
                    recordRenderMaskDepthPrefill(state);
                } else invalidateCapture(state);
            }
        }
        //#else
        //#if MC>=1_21_06
        //$$ // 1.21.6-1.21.11 use the same MAX-pool strategy as 26.1. Their pipeline API has
        //$$ // no ALWAYS depth function, so DepthMinPoolPipeline clears the destination to 1.0
        //$$ // and writes through LEQUAL instead. The result is equivalent for normalized
        //$$ // forward-Z depth and removes the need for projection z-bias.
        //$$ boolean useDepthPool = IrisCompat.isShaderActive()
        //$$         && cn.spectra.gallium.glowoutline.shader.DepthMinPoolPipeline.isReady();
        //$$ GpuTexture pooledDepth = null;
        //$$ for (GlowCaptureState state : activeStates) {
        //$$     if (state.capturedThisFrame && state.maskTarget != null && !state.firstPerson) {
        //$$         boolean exactTemporalReplay = canPrepareExactTemporalReplay(state, packProjection);
        //$$         if (clearsMaskDepthForReplay(state.firstPerson, IrisCompat.isShaderActive(),
        //$$                 exactTemporalReplay)) continue;
        //$$         if (useDepthPool && !exactTemporalReplay) {
        //$$             if (pooledDepth == null) {
        //$$                 if (cn.spectra.gallium.glowoutline.shader.DepthMinPoolPipeline.pool(
        //$$                         encoder,
        //$$                         sceneDepthTarget.getDepthTextureView(),
        //$$                         state.maskTarget.getColorTextureView(),
        //$$                         state.maskTarget.getDepthTextureView())) {
        //$$                     pooledDepth = state.maskTarget.getDepthTexture();
        //$$                     recordRenderMaskDepthPrefill(state);
        //$$                     continue;
        //$$                 }
        //$$                 useDepthPool = false;
        //$$             } else {
        //$$                 // Exact-size copy; any late skew invalidates this state below.
        //$$                 state.maskDepthPrepared = copyDepthBounded(encoder, pooledDepth,
        //$$                         state.maskTarget.getDepthTexture(), w, h, 1.0);
        //$$                 if (state.maskDepthPrepared) recordRenderMaskDepthPrefill(state);
        //$$                 else invalidateCapture(state);
        //$$                 continue;
        //$$             }
        //$$         }
        //$$         state.maskDepthPrepared = copyDepthBounded(encoder, srcDepth,
        //$$                 state.maskTarget.getDepthTexture(), w, h, 1.0);
        //$$         if (state.maskDepthPrepared) recordRenderMaskDepthPrefill(state);
        //$$         else invalidateCapture(state);
        //$$     }
        //$$ }
        //#else
        //$$ // 1.21.5 has no GpuTextureView-based pool pass; retain its plain-copy fallback.
        //$$ for (GlowCaptureState state : activeStates) {
        //$$     if (state.capturedThisFrame && state.maskTarget != null) {
        //$$         boolean exactTemporalReplay = canPrepareExactTemporalReplay(state, packProjection);
        //$$         if (clearsMaskDepthForReplay(state.firstPerson, IrisCompat.isShaderActive(),
        //$$                 exactTemporalReplay)) continue;
        //$$         // [issue #1] bounded copy - see copyDepthBounded.
        //$$         state.maskDepthPrepared = copyDepthBounded(encoder, srcDepth,
        //$$                 state.maskTarget.getDepthTexture(), w, h, 1.0);
        //$$         if (state.maskDepthPrepared) recordRenderMaskDepthPrefill(state);
        //$$         else invalidateCapture(state);
        //$$     }
        //$$ }
        //#endif
        //#endif
        return snapshotResult;
        //#else
        //$$ if (mainTarget.getDepthTextureId() == -1) {
        //$$     return SceneDepthSnapshotResult.failed(
        //$$             false, sceneDepthGeneration, mainTarget.width, mainTarget.height);
        //$$ }
        //$$ if (sceneDepthCaptured && !replaceExisting) {
        //$$     return SceneDepthSnapshotResult.ready(
        //$$             false, sceneDepthGeneration,
        //$$             sceneDepthTarget != null ? sceneDepthTarget.width : 0,
        //$$             sceneDepthTarget != null ? sceneDepthTarget.height : 0);
        //$$ }
        //$$ int w = mainTarget.width, h = mainTarget.height;
        //$$ ShaderPackHint.ProjectionTransform packProjection =
        //$$         IrisCompat.getShaderProjectionTransform(w, h);
        //$$ TextureTarget previousSceneDepthTarget = sceneDepthTarget;
        //$$ boolean allocateReplacement = replaceExisting && sceneDepthCaptured
        //$$         || sceneDepthTarget == null
        //$$         || sceneDepthTarget.width != w || sceneDepthTarget.height != h;
        //$$ TextureTarget destination = sceneDepthTarget;
        //$$ if (allocateReplacement) {
        //#if MC>=1_21_02
        //$$     destination = new TextureTarget(w, h, true);
        //#else
        //$$     destination = new TextureTarget(w, h, true, net.minecraft.client.Minecraft.ON_OSX);
        //#endif
        //$$ }
        //$$ try {
        //$$     destination.copyDepthFrom(mainTarget);
        //$$ } catch (RuntimeException | Error failure) {
        //$$     if (destination != previousSceneDepthTarget) {
        //$$         try {
        //$$             destination.destroyBuffers();
        //$$         } catch (Throwable cleanupFailure) {
        //$$             failure.addSuppressed(cleanupFailure);
        //$$         }
        //$$     }
        //$$     abortPendingPayloads();
        //$$     mainTarget.bindWrite(true);
        //$$     throw failure;
        //$$ }
        //$$ if (destination != previousSceneDepthTarget) {
        //$$     sceneDepthTarget = destination;
        //$$     if (previousSceneDepthTarget != null) previousSceneDepthTarget.destroyBuffers();
        //$$ }
        //$$ sceneDepthCaptured = true;
        //$$ sceneDepthGeneration++;
        //$$ boolean generationSafe =
        //$$         invalidatePreparedMaskDepthForSceneGeneration(sceneDepthGeneration);
        //$$ SceneDepthSnapshotResult snapshotResult = SceneDepthSnapshotResult.ready(
        //$$         true, sceneDepthGeneration, w, h);
        //$$ if (!generationSafe) {
        //$$     mainTarget.bindWrite(true);
        //$$     return SceneDepthSnapshotResult.failed(
        //$$             true, sceneDepthGeneration, w, h);
        //$$ }
        //$$ if (policy == SceneDepthCapturePolicy.SNAPSHOT_ONLY) {
        //$$     mainTarget.bindWrite(true);
        //$$     return snapshotResult;
        //$$ }
        //$$ for (GlowCaptureState state : activeStates) {
        //$$     if (state.maskTarget != null) {
        //$$         boolean exactTemporalReplay = canPrepareExactTemporalReplay(state, packProjection);
        //$$         if (clearsMaskDepthForReplay(state.firstPerson, IrisCompat.isShaderActive(),
        //$$                 exactTemporalReplay)) continue;
        //$$         state.maskTarget.copyDepthFrom(mainTarget);
        //$$         recordRenderMaskDepthPrefill(state);
        //$$     }
        //$$ }
        //$$ // RenderTarget.copyDepthFrom() ends with `_glBindFramebuffer(GL_FRAMEBUFFER, 0)`,
        //$$ // leaving the default framebuffer bound. The vanilla code path that triggered our
        //$$ // capture (RenderSystem.clear(256) inside renderLevel before renderItemInHand)
        //$$ // assumes mainTarget is still bound — without this re-bind, clear(256) wipes the
        //$$ // default framebuffer's depth instead of mainTarget's, leaving stale world depth
        //$$ // in mainTarget. Hand items then fail LEQUAL against world depth and disappear
        //$$ // behind world geometry. Restore mainTarget binding so vanilla's downstream draws
        //$$ // and clears land on the right target.
        //$$ mainTarget.bindWrite(true);
        //$$ return snapshotResult;
        //#endif
    }

    /** Exact-size snapshot of the post-clear hand/foreground depth for SR output-space masks. */
    public static void captureForegroundDepth(RenderTarget mainTarget) {
        if (mainTarget == null) return;
        //#if MC>=1_21_05
        GpuTexture srcDepth = mainTarget.getDepthTexture();
        int w = mainTarget.width, h = mainTarget.height;
        // Preserve an exact earlier A/B/C hand snapshot, but allow the final fallback to replace a
        // render-size snapshot with the restored display-size target. Treating the flag alone as
        // idempotence made that last safe capture opportunity a no-op after a resize/skew.
        if (foregroundDepthCaptured
                && renderTargetSizeMatches(foregroundDepthTarget, w, h)) return;
        if (srcDepth == null || !renderTargetSizeMatches(mainTarget, w, h)) return;
        // Keep an earlier valid A/B/C snapshot when the restored display target is temporarily
        // unusable. Invalidate it only after the replacement source has passed every size check.
        foregroundDepthCaptured = false;
        if (!renderTargetSizeMatches(foregroundDepthTarget, w, h)) {
            if (foregroundDepthTarget != null) foregroundDepthTarget.destroyBuffers();
            foregroundDepthTarget = new TextureTarget("GlowForegroundDepth", w, h, true
                    //#if MC>=1_26_02
                    //$$ , GpuFormat.RGBA8_UNORM
                    //#endif
            );
            //#if MC<1_21_11
            //#if MC>=1_21_06
            //$$ foregroundDepthTarget.getDepthTexture().setUseMipmaps(false);
            //#endif
            //#endif
        }
        var encoder = RenderSystem.getDevice().createCommandEncoder();
        //#if MC>=1_26_02
        //$$ foregroundDepthCaptured = copyDepthBounded(encoder, srcDepth,
        //$$         foregroundDepthTarget.getDepthTexture(), w, h, 0.0);
        //#else
        foregroundDepthCaptured = copyDepthBounded(encoder, srcDepth,
                foregroundDepthTarget.getDepthTexture(), w, h, 1.0);
        //#endif
        if (foregroundDepthCaptured) {
            foregroundDepthGeneration++;
        }
        //#else
        //$$ if (mainTarget.getDepthTextureId() == -1) return;
        //$$ int w = mainTarget.width, h = mainTarget.height;
        //$$ if (foregroundDepthCaptured && foregroundDepthTarget != null
        //$$         && foregroundDepthTarget.width == w && foregroundDepthTarget.height == h) return;
        //$$ foregroundDepthCaptured = false;
        //$$ if (foregroundDepthTarget == null) {
        //#if MC>=1_21_02
        //$$     foregroundDepthTarget = new TextureTarget(w, h, true);
        //#else
        //$$     foregroundDepthTarget = new TextureTarget(w, h, true, net.minecraft.client.Minecraft.ON_OSX);
        //#endif
        //$$ } else if (foregroundDepthTarget.width != w || foregroundDepthTarget.height != h) {
        //#if MC>=1_21_02
        //$$     foregroundDepthTarget.resize(w, h);
        //#else
        //$$     foregroundDepthTarget.resize(w, h, net.minecraft.client.Minecraft.ON_OSX);
        //#endif
        //$$ }
        //$$ foregroundDepthTarget.copyDepthFrom(mainTarget);
        //$$ foregroundDepthCaptured = true;
        //$$ foregroundDepthGeneration++;
        //$$ mainTarget.bindWrite(true);
        //#endif
    }

    public static long getSceneDepthGeneration() {
        return sceneDepthGeneration;
    }

    /** Correctness validation uses only Gallium-owned snapshot state, never a third-party target. */
    public static boolean isSceneDepthSnapshotCurrent(
            long generation, int width, int height) {
        if (!sceneDepthCaptured || sceneDepthTarget == null
                || generation != sceneDepthGeneration) return false;
        //#if MC>=1_21_05
        return renderTargetSizeMatches(sceneDepthTarget, width, height);
        //#else
        //$$ return sceneDepthTarget.width == width && sceneDepthTarget.height == height;
        //#endif
    }

    public static long getForegroundDepthGeneration() {
        return foregroundDepthGeneration;
    }

    public static @Nullable TextureTarget getForegroundDepthTarget() {
        return foregroundDepthCaptured ? foregroundDepthTarget : null;
    }

    public static @Nullable TextureTarget getSceneDepthTarget() {
        // A target survives resource frames, but its contents are valid only after this frame's
        // pre-hand snapshot. Returning a prior frame here would make a missed capture hook turn
        // into a stale occluder rather than the live-depth fallback.
        return sceneDepthCaptured ? sceneDepthTarget : null;
    }

    //#if MC>=1_21_06
    /** Full display-space copy of the pre-upscale scene. Under Iris it already includes hand. */
    public static @Nullable TextureTarget getSuperResolutionDisplaySceneDepthTarget() {
        return srDisplaySceneDepthPrepared ? srDisplaySceneDepthTarget : null;
    }
    //#endif

    //#if MC>=1_26_02
    //$$ /** Lazily allocates or resizes the live-mainTarget forward-Z mirror. Caller is
    //$$  *  responsible for invoking {@link cn.spectra.gallium.glowoutline.shader.DepthFlipPipeline#flip}
    //$$  *  to fill it each frame — this method only manages the texture's allocation. */
    //$$ public static @Nullable TextureTarget ensureLiveSceneDepthForwardZTarget(int w, int h) {
    //$$     liveSceneDepthForwardZTarget = cn.spectra.gallium.glowoutline.shader.DepthFlipPipeline
    //$$             .ensureForwardZTarget(liveSceneDepthForwardZTarget,
    //$$                     "GlowLiveSceneDepthForwardZ", w, h);
    //$$     return liveSceneDepthForwardZTarget;
    //$$ }
    //#endif

    public static boolean beginItemCapture(ItemStack stack) {
        return beginItemCapture(stack, false);
    }

    public static boolean beginItemCapture(ItemStack stack, boolean firstPerson) {
        //#if MC<1_21_05
        //$$ if (stack.isEmpty()) return false;
        //$$ if (!ItemEffectsManager.isActive()) return false;
        //$$
        //$$ ItemEffectConfig cfg = ItemEffectsManager.getConfig(stack);
        //$$ if (cfg == null || cfg.shader().isEmpty()) return false;
        //$$ if (!SuperResolutionCompat.canBeginStreamingCapture(firstPerson)) {
        //$$     return false;
        //$$ }
        //$$
        //$$ // Probe the main target FIRST so a null result doesn't poison the pool slot.
        //$$ // If we set state.active=true before this check and then returned false, the
        //$$ // recycled slot would stay marked active without ever being added to
        //$$ // activeStates — allocateState() would skip it forever and beginFrame()
        //$$ // (which only iterates activeStates) would never reset it.
        //$$ Minecraft mc = Minecraft.getInstance();
        //$$ RenderTarget main = mc.getMainRenderTarget();
        //$$ if (main == null) return false;
        //$$
        //$$ GlowCaptureState state = allocateState();
        //$$ try {
        //$$ state.beginCaptureLifecycle(SuperResolutionCompat.currentFrameEpoch(), firstPerson);
        //$$ state.active = true;
        //$$ state.firstPerson = firstPerson;
        //$$ state.config = cfg;
        //$$ if (state.capturedModelViewMatrix == null) state.capturedModelViewMatrix = new Matrix4f();
        //$$ state.capturedModelViewMatrix.set(RenderSystem.getModelViewMatrix());
        //$$ state.capturedModelViewMatrixValid = true;
        //$$ state.capturedProjectionMatrix4f.set(RenderSystem.getProjectionMatrix());
        //#if MC>=1_21_02
        //$$ state.capturedProjectionType = RenderSystem.getProjectionType();
        //#else
        //$$ state.capturedProjectionType = RenderSystem.getVertexSorting();
        //#endif
        //$$ state.capturedProjectionMatrix4fValid = true;
        //$$
        //$$ if (!reserveCaptureTarget(state, main.width, main.height)) {
        //$$     warnCaptureBudget(main.width, main.height);
        //$$     state.resetFrame();
        //$$     return false;
        //$$ }
        //$$
        //$$ if (state.maskTarget == null) {
        //#if MC>=1_21_02
        //$$     state.maskTarget = new TextureTarget(main.width, main.height, true);
        //#else
        //$$     state.maskTarget = new TextureTarget(main.width, main.height, true, net.minecraft.client.Minecraft.ON_OSX);
        //#endif
        //$$ } else if (state.maskTarget.width != main.width || state.maskTarget.height != main.height) {
        //#if MC>=1_21_02
        //$$     state.maskTarget.resize(main.width, main.height);
        //#else
        //$$     state.maskTarget.resize(main.width, main.height, net.minecraft.client.Minecraft.ON_OSX);
        //#endif
        //$$ }
        //$$ activeStates.add(state);
        //$$ currentCapture = state;
        //$$ return true;
        //$$ } catch (RuntimeException | Error e) {
        //$$     state.resetFrame();
        //$$     throw e;
        //$$ }
        //#else
        if (stack.isEmpty()) return false;
        if (!ItemEffectsManager.isActive()) return false;

        ItemEffectConfig cfg = ItemEffectsManager.getConfig(stack);
        if (cfg == null || cfg.shader().isEmpty()) return false;
        if (!SuperResolutionCompat.canBeginStreamingCapture(firstPerson)) {
            return false;
        }

        // Probe the main target FIRST so a null result doesn't poison the pool slot.
        // If we set state.active=true before this check and then returned false, the
        // recycled slot would stay marked active without ever being added to
        // activeStates — allocateState() would skip it forever and beginFrame()
        // (which only iterates activeStates) would never reset it.
        Minecraft mc = Minecraft.getInstance();
        //#if MC>=1_26_02
        //$$ RenderTarget main = mc.gameRenderer.mainRenderTarget();
        //#else
        RenderTarget main = mc.getMainRenderTarget();
        //#endif
        if (main == null || !renderTargetSizeMatches(main, main.width, main.height)) return false;

        GlowCaptureState state = allocateState();
        try {
        state.beginCaptureLifecycle(SuperResolutionCompat.currentFrameEpoch(), firstPerson);
        state.active = true;
        state.firstPerson = firstPerson;
        state.config = cfg;
        if (state.capturedModelViewMatrix == null) state.capturedModelViewMatrix = new Matrix4f();
        //#if MC>=1_26_02
        //$$ state.capturedModelViewMatrix.set(RenderSystem.getModelViewMatrixCopy());
        //#else
        state.capturedModelViewMatrix.set(RenderSystem.getModelViewMatrix());
        //#endif
        state.capturedModelViewMatrixValid = true;
        //#if MC>=1_21_06
        // Snapshot via GpuBufferSlice for the deferred render pass (>=1.21.6 API).
        state.capturedProjectionMatrix = RenderSystem.getProjectionMatrixBuffer();
        state.capturedProjectionType = RenderSystem.getProjectionType();
        state.capturedProjectionMatrix4fValid = ProjectionMatrixTracker.lookupInto(
                state.capturedProjectionMatrix, state.capturedProjectionMatrix4f) != null;
        //#else
        //$$ // 1.21.5: capture projection as Matrix4f directly (no GpuBufferSlice API).
        //$$ state.capturedProjectionMatrix4f.set(RenderSystem.getProjectionMatrix());
        //$$ state.capturedProjectionType = RenderSystem.getProjectionType();
        //$$ state.capturedProjectionMatrix4fValid = true;
        //#endif

        if (!ownsAnySharedMaskFrame()) {
        if (!reserveCaptureTarget(state, main.width, main.height)) {
            warnCaptureBudget(main.width, main.height);
            state.resetFrame();
            return false;
        }

        if (state.maskTarget == null) {
            // identityHashCode (not a slot index) gives each state a stable, unique label
            // for the lifetime of the JVM — survives pool shrinks where a recycled slot
            // index would otherwise collide with a freshly allocated state's name.
            state.maskTarget = new TextureTarget("GlowMask_" + Integer.toHexString(System.identityHashCode(state)),
                    main.width, main.height, true
                    //#if MC>=1_26_02
                    //$$ , GpuFormat.RGBA8_UNORM
                    //#endif
            );
            //#if MC<1_21_11
            //#if MC>=1_21_06
            //$$ // 1.21.10's mask textures are mipLevels=1 but GpuTexture defaults
            //$$ // useMipmaps=true, which makes GL_TEXTURE_MIN_FILTER pick a mipmap
            //$$ // variant (e.g. GL_NEAREST_MIPMAP_LINEAR). Combined with
            //$$ // GL_TEXTURE_MAX_LEVEL=0 that leaves the sampler "incomplete" — driver
            //$$ // returns (1,1,1,1) on read, so the world outline shader read pure
            //$$ // white masks. 1.21.11+ moved sampler state onto GpuSampler so this
            //$$ // hack is unnecessary there (and the API is gone).
            //$$ state.maskTarget.getColorTexture().setUseMipmaps(false);
            //$$ state.maskTarget.getDepthTexture().setUseMipmaps(false);
            //#endif
            //#endif
        } else if (!renderTargetSizeMatches(state.maskTarget, main.width, main.height)) {
            state.maskTarget.resize(main.width, main.height);
            //#if MC<1_21_11
            //#if MC>=1_21_06
            //$$ state.maskTarget.getColorTexture().setUseMipmaps(false);
            //$$ state.maskTarget.getDepthTexture().setUseMipmaps(false);
            //#endif
            //#endif
        }

        // resize() is synchronous, but verify the real attachments as well as RenderTarget's
        // fields before committing this pool slot. If an external target transition is still in
        // flight, skip capture and leave the slot reusable instead of carrying stale dimensions
        // into captureSceneDepth.
        if (!renderTargetSizeMatches(state.maskTarget, main.width, main.height)) {
            state.resetFrame();
            return false;
        }
        }

        //#if MC>=1_21_09
        if (sharedCaptureBuffers == null) {
            sharedCaptureBuffers = new RenderBuffers(1);
        }

        //#if MC>=1_26_02
        //$$ // 26.2: FeatureRenderDispatcher no longer takes a SubmitNodeStorage/BufferSources —
        //$$ // its ctor is (RenderBuffers, ModelManager, AtlasManager, Font, GameRenderState) and
        //$$ // renderAllFeatures(SubmitNodeStorage) receives the storage per call. A successful
        //$$ // render drains it via drainPhases, so keep that clean storage across frames. Dirty
        //$$ // storage means a prior capture aborted or threw and must be replaced fail-closed.
        //$$ if (state.captureDispatcher == null) {
        //$$     var accessor = (FeatureRenderDispatcherAccessor) mc.gameRenderer.featureRenderDispatcher();
        //$$     var gameAccessor = (GameRendererAccessor) mc.gameRenderer;
        //$$     state.captureDispatcher = new FeatureRenderDispatcher(
        //$$             sharedCaptureBuffers,
        //$$             mc.getModelManager(),
        //$$             accessor.gallium$getAtlasManager(),
        //$$             mc.font,
        //$$             gameAccessor.gallium$getGameRenderState()
        //$$     );
        //$$ }
        //$$ if (state.captureStorage == null || !state.captureStorageClean) {
        //$$     state.captureStorage = new SubmitNodeStorage();
        //$$ }
        //$$ state.captureStorageClean = true;
        //#else
        if (state.captureDispatcher == null) {
            FeatureRenderDispatcher mainDispatcher = mc.gameRenderer.getFeatureRenderDispatcher();
            var accessor = (FeatureRenderDispatcherAccessor) mainDispatcher;
            //#if MC>=1_26_00
            var gameAccessor = (GameRendererAccessor) mc.gameRenderer;
            state.captureDispatcher = new FeatureRenderDispatcher(
                    new SubmitNodeStorage(),
                    mc.getModelManager(),
                    sharedCaptureBuffers.bufferSource(),
                    accessor.gallium$getAtlasManager(),
                    sharedCaptureBuffers.outlineBufferSource(),
                    sharedCaptureBuffers.crumblingBufferSource(),
                    mc.font,
                    gameAccessor.gallium$getGameRenderState()
            );
            //#else
            //$$ state.captureDispatcher = new FeatureRenderDispatcher(
            //$$         new SubmitNodeStorage(),
            //$$         mc.getBlockRenderer(),
            //$$         sharedCaptureBuffers.bufferSource(),
            //$$         accessor.gallium$getAtlasManager(),
            //$$         sharedCaptureBuffers.outlineBufferSource(),
            //$$         sharedCaptureBuffers.crumblingBufferSource(),
            //$$         mc.font
            //$$ );
            //#endif
        } else {
            state.captureDispatcher.getSubmitNodeStorage().clear();
        }
        //#endif
        //#endif

        activeStates.add(state);
        currentCapture = state;
        return true;
        } catch (RuntimeException | Error e) {
            // The prefix slot is not committed until activeStates.add above. Any constructor,
            // resize, or dispatcher failure before then must leave it reusable and evictable.
            state.resetFrame();
            throw e;
        }
        //#endif
    }

    static void beginItemCaptureScope() {
        if (captureScopeDepth == captureScopeParents.length) {
            int newLength = captureScopeDepth << 1;
            captureScopeParents = java.util.Arrays.copyOf(captureScopeParents, newLength);
            captureScopeStarted = java.util.Arrays.copyOf(captureScopeStarted, newLength);
        }
        captureScopeParents[captureScopeDepth] = currentCapture;
        captureScopeStarted[captureScopeDepth] = false;
        captureScopeDepth++;
    }

    static void markItemCaptureScopeStarted() {
        if (captureScopeDepth <= 0) {
            throw new IllegalStateException("No Gallium capture scope");
        }
        captureScopeStarted[captureScopeDepth - 1] = true;
    }

    static void cancelItemCaptureScope() {
        finishItemCaptureScope();
    }

    public static void endItemCapture() {
        if (captureScopeDepth > 0) {
            finishItemCaptureScope();
            return;
        }
        if (currentCapture != null && currentCapture.captureStage() == CaptureStage.CAPTURING) {
            discardPayload(currentCapture);
        }
        detachCaptureWrapper(currentCapture);
        currentCapture = null;
    }

    private static void finishItemCaptureScope() {
        if (captureScopeDepth <= 0) return;
        int index = --captureScopeDepth;
        GlowCaptureState parent = captureScopeParents[index];
        boolean started = captureScopeStarted[index];
        captureScopeParents[index] = null;
        captureScopeStarted[index] = false;
        if (started) {
            if (currentCapture != null
                    && currentCapture.captureStage() == CaptureStage.CAPTURING) {
                discardPayload(currentCapture);
            }
            detachCaptureWrapper(currentCapture);
        }
        currentCapture = parent;
    }

    private static void detachCaptureWrapper(@Nullable GlowCaptureState state) {
        if (state == null) return;
        state.finishCaptureScope();
    }

    private static void clearCaptureScopes() {
        detachCaptureWrapper(currentCapture);
        for (int i = 0; i < captureScopeDepth; i++) {
            GlowCaptureState parent = captureScopeParents[i];
            if (parent != null && parent != currentCapture) detachCaptureWrapper(parent);
            captureScopeParents[i] = null;
        }
        captureScopeDepth = 0;
    }

    public static void beginSuppress() { suppressDepth++; }
    public static void endSuppress() { if (suppressDepth > 0) suppressDepth--; }

    public static @Nullable GlowCaptureState currentCapture() { return currentCapture; }

    /** Unified idempotent payload cleanup used by invalidation, abort and teardown paths. */
    public static void discardPayload(@Nullable GlowCaptureState state) {
        if (state != null) state.discardPayload();
    }

    //#if MC>=1_21_09
    static @Nullable SubmitNodeStorage captureStorageFor(GlowCaptureState state) {
        if (state == null || suppressDepth > 0) return null;
        if (state.captureDispatcher == null) return null;
        //#if MC>=1_26_02
        //$$ return state.captureStorage;
        //#else
        return state.captureDispatcher.getSubmitNodeStorage();
        //#endif
    }

    public static @Nullable SubmitNodeStorage captureStorageForCurrent() {
        return captureStorageFor(currentCapture);
    }
    //#endif

    public static void renderCapturedNodes(GlowCaptureState state, Minecraft mc) {
        renderCapturedNodes(state, mc, false);
    }

    private static void renderCapturedNodes(GlowCaptureState state, Minecraft mc,
                                            boolean outputSpacePrepare) {
        renderCapturedNodes(state, mc, outputSpacePrepare, state.maskTarget);
    }

    private static void renderCapturedNodes(GlowCaptureState state, Minecraft mc,
                                            boolean outputSpacePrepare, TextureTarget mask) {
        //#if MC==1_21_11 || MC==1_26_01
        boolean lateReplay = SuperResolutionCompat.ownsLateReplayFrame();
        if (lateReplay && (!outputSpacePrepare || state.captureStage() != CaptureStage.SCHEDULED)) return;
        boolean sequentialReplay = ownsSequentialSharedMaskFrame();
        if (sequentialReplay && (outputSpacePrepare || !sequentialPayloadReady(state))) return;
        //#endif
        //#if MC>=1_21_09
        if (state.captureDispatcher == null || sharedCaptureBuffers == null || mask == null) {
            invalidateCapture(state);
            return;
        }

        //#if MC>=1_26_02
        //$$ RenderTarget frameTarget = mc.gameRenderer.mainRenderTarget();
        //#else
        RenderTarget frameTarget = mc.getMainRenderTarget();
        //#endif
        boolean frameInvalid = frameTarget == null
                || !renderTargetSizeMatches(frameTarget, frameTarget.width, frameTarget.height);
        boolean nativeSizeInvalid = !outputSpacePrepare && !frameInvalid
                && (!renderTargetSizeMatches(mask, frameTarget.width, frameTarget.height)
                || (sceneDepthCaptured
                && !renderTargetSizeMatches(sceneDepthTarget, frameTarget.width, frameTarget.height)));
        boolean preparedDepthInvalid = outputSpacePrepare && sceneDepthCaptured
                && sceneDepthTarget != null
                && !renderTargetSizeMatches(sceneDepthTarget,
                sceneDepthTarget.width, sceneDepthTarget.height);
        if (frameInvalid || nativeSizeInvalid || preparedDepthInvalid) {
            invalidateCapture(state);
            return;
        }

        var irisSnapshot = IrisCompat.setBypass(true);
        try {
        if (!canReplayWithShaderBypass(
                IrisCompat.isShaderActive(), irisSnapshot.shaderBypassEnabled())) {
            invalidateCapture(state);
            return;
        }
        var encoder = RenderSystem.getDevice().createCommandEncoder();
        //#if MC>=1_26_02
        //$$ encoder.clearColorTexture(mask.getColorTexture(), new org.joml.Vector4f(0.0F));
        //#else
        encoder.clearColorTexture(mask.getColorTexture(), 0);
        //#endif

        ShaderPackHint.ProjectionTransform sourceProjection =
                //#if MC==1_21_11 || MC==1_26_01
                lateReplay ? state.lateReplayProjection :
                //#endif
                IrisCompat.getShaderProjectionTransform(mask.width, mask.height);
        boolean shaderCompatDisplayReplay = !outputSpacePrepare
                && IrisCompat.isActiveSrRuntime()
                && SuperResolutionCompat.hasCompletedShaderCompatDispatch(
                mask.width, mask.height);
        if (shaderCompatDisplayReplay && !state.firstPerson
                && (!sceneDepthCaptured || !renderTargetSizeMatches(
                sceneDepthTarget, mask.width, mask.height))) {
            invalidateCapture(state);
            return;
        }
        if (shaderCompatDisplayReplay
                && (state.capturedProjectionMatrix == null
                || state.capturedProjectionType == null)) {
            invalidateCapture(state);
            return;
        }
        ShaderPackHint.ProjectionTransform packProjection = projectionForReplay(
                sourceProjection, outputSpacePrepare || shaderCompatDisplayReplay);
        ShaderPackHint.ProjectionTransform sceneProjection = shaderCompatDisplayReplay
                && !state.firstPerson ? sourceProjection : packProjection;
        boolean exactTemporalReplay = usesExactTemporalReplay(state, packProjection)
                && irisSnapshot.shaderBypassEnabled();
        if (outputSpacePrepare && !state.firstPerson) {
            if (!srDisplaySceneDepthPrepared || srDisplaySceneDepthTarget == null
                    //#if MC>=1_26_02
                    //$$ || !copyDepthBounded(encoder,
                    //$$ srDisplaySceneDepthTarget.getDepthTexture(),
                    //$$ mask.getDepthTexture(), mask.width,
                    //$$ mask.height, 0.0)
                    //#else
                    || !copyDepthBounded(encoder,
                    srDisplaySceneDepthTarget.getDepthTexture(),
                    mask.getDepthTexture(), mask.width,
                    mask.height, 1.0)
                    //#endif
            ) {
                invalidateCapture(state);
                return;
            }
            state.maskDepthPrepared = true;
            state.maskDepthSnapshotGeneration = sceneDepthGeneration;
        }
        boolean deferReverseZWorldOcclusion = false;
        //#if MC>=1_26_02
        //$$ deferReverseZWorldOcclusion = shouldDeferReverseZWorldOcclusion(
        //$$         state.firstPerson, IrisCompat.isShaderActive(),
        //$$         IrisCompat.usesForwardDepthCompatibility(), exactTemporalReplay);
        //#endif
        boolean clearDepthForReplay = (shaderCompatDisplayReplay && !state.firstPerson)
                || (outputSpacePrepare && state.firstPerson)
                || (!outputSpacePrepare && clearsMaskDepthForReplay(
                state.firstPerson, IrisCompat.isShaderActive(), exactTemporalReplay))
                || (!outputSpacePrepare && deferReverseZWorldOcclusion);

        if (clearDepthForReplay) {
            state.maskDepthSnapshotGeneration = -1L;
            //#if MC>=1_26_02
            //$$ // Pass the native reverse-Z far value. Iris/OpenGL's UndoReverseZ wrapper turns
            //$$ // this into forward-Z 1.0 while a pack is active; passing 1.0 here would be
            //$$ // inverted a second time. Iris/Vulkan keeps native reverse-Z and uses 0.0.
            //$$ encoder.clearDepthTexture(mask.getDepthTexture(), 0.0);
            //#else
            encoder.clearDepthTexture(mask.getDepthTexture(), 1.0);
            //#endif
        } else if (!state.maskDepthPrepared) {
            //#if MC>=1_26_02
            //$$ RenderTarget mainTarget = mc.gameRenderer.mainRenderTarget();
            //#else
            RenderTarget mainTarget = mc.getMainRenderTarget();
            //#endif
            RenderTarget sourceDepth = sceneDepthCaptured && sceneDepthTarget != null
                    ? sceneDepthTarget : mainTarget;
            //#if MC==1_21_11 || MC==1_26_01
            // The old prefill ran once into the first independent mask and copied its depth.
            // A shared mask is never a retained source: repeat the same existing pool from the
            // immutable scene snapshot before each unknown-Iris replay. No new depth algorithm.
            if (sequentialReplay && !state.firstPerson && IrisCompat.isShaderActive()
                    && !exactTemporalReplay && sceneDepthCaptured
                    && cn.spectra.gallium.glowoutline.shader.DepthMinPoolPipeline.isReady()) {
                state.maskDepthPrepared = cn.spectra.gallium.glowoutline.shader.DepthMinPoolPipeline.pool(
                        encoder, sceneDepthTarget.getDepthTextureView(),
                        mask.getColorTextureView(), mask.getDepthTextureView());
            }
            //#endif
            // Final exact-size guard for a target change after captureSceneDepth. Failure
            // invalidates this state below rather than issuing an out-of-bounds GPU copy.
            //#if MC>=1_26_02
            //$$ state.maskDepthPrepared = copyDepthBounded(encoder, sourceDepth.getDepthTexture(),
            //$$         mask.getDepthTexture(), sourceDepth.width, sourceDepth.height, 0.0);
            //#else
            if (!state.maskDepthPrepared) {
                state.maskDepthPrepared = copyDepthBounded(encoder, sourceDepth.getDepthTexture(),
                        mask.getDepthTexture(), sourceDepth.width, sourceDepth.height, 1.0);
            }
            //#endif
            if (!state.maskDepthPrepared) {
                invalidateCapture(state);
                return;
            }
            state.maskDepthSnapshotGeneration = sceneDepthCaptured
                    ? sceneDepthGeneration : -1L;
        }
        // Exact Iris world replays deliberately start at the far plane. Pre-filling scene depth
        // makes the bypassed vanilla shader compare against the pack's original item depth; tiny
        // differences in clip-space Z, alpha discard, or coverage then punch permanent holes in
        // the mask. The composite shader has both item and scene depth and performs the actual
        // world/item occlusion after the complete mask has been captured. Unknown pack transforms
        // retain the copied/pooled depth fallback above.

        var oldColor = RenderSystem.outputColorTextureOverride;
        var oldDepth = RenderSystem.outputDepthTextureOverride;

        // Recreate the pack's declared post-projection transform. For exact declarations this
        // includes the current temporal jitter, so the replay lands on the same depth samples as
        // the original Iris draw and does not need the silhouette-expanding depth pool.
        GpuBufferSlice maskProjectionSlice = state.capturedProjectionMatrix;
        float maskScaleX = 1.0f;
        float maskScaleY = 1.0f;
        float maskOffsetX = packProjection.viewportOriginX();
        float maskOffsetY = packProjection.viewportOriginY();
        float sceneOffsetX = sceneProjection.viewportOriginX();
        float sceneOffsetY = sceneProjection.viewportOriginY();
        boolean projectionApplied = !packProjection.changesProjection();
        float replayScaleX = packProjection.scaleX();
        float replayScaleY = packProjection.scaleY();
        // iterationRP deliberately suppresses hand jitter when DECREASE_HAND_GHOSTING is enabled;
        // Gallium has no portable way to query that pack option, so keep the hand replay stable.
        float jitterX = 2.0f * packProjection.viewportOriginX()
                + (exactTemporalReplay ? packProjection.jitterX() : 0.0f);
        float jitterY = 2.0f * packProjection.viewportOriginY()
                + (exactTemporalReplay ? packProjection.jitterY() : 0.0f);
        float zBias = 0.0f;
        boolean changesProjection = replayScaleX != 1.0f
                || replayScaleY != 1.0f
                || jitterX != 0.0f || jitterY != 0.0f || zBias != 0.0f;
        if (state.capturedProjectionMatrix4fValid && (changesProjection
                //#if MC==1_21_11 || MC==1_26_01
                || lateReplay || sequentialReplay
                //#endif
        )) {
            GpuBufferSlice scaled = uploadScaledProjection(
                    encoder, state,
                    state.capturedProjectionMatrix4f,
                    replayScaleX, replayScaleY,
                    jitterX, jitterY, zBias);
            //#if MC==1_21_11 || MC==1_26_01
            if ((lateReplay || sequentialReplay) && scaled == null) {
                invalidateCapture(state);
                return;
            }
            //#endif
            if (scaled != null) {
                maskProjectionSlice = scaled;
                maskScaleX = replayScaleX;
                maskScaleY = replayScaleY;
                if (exactTemporalReplay) {
                    // Projection jitter is expressed in NDC units; converting to UV units
                    // divides by two because NDC spans [-1, 1] while UV spans [0, 1].
                    maskOffsetX = packProjection.uvOffsetX();
                    maskOffsetY = packProjection.uvOffsetY();
                }
                projectionApplied = true;
            }
        }

        if (!state.firstPerson && IrisCompat.isShaderActive()
                && sceneProjection.exactTemporalJitter()) {
            // The pre-clear scene snapshot is the pack's internal, jittered depth image even
            // when the mask replay had to fall back. Keep its lookup in the same internal texel.
            sceneOffsetX = sceneProjection.uvOffsetX();
            sceneOffsetY = sceneProjection.uvOffsetY();
        }

        boolean restoreProjection = maskProjectionSlice != null && state.capturedProjectionType != null;
        boolean projectionBackedUp = false;
        boolean modelViewPushed = false;
        try {
            RenderSystem.outputColorTextureOverride = mask.getColorTextureView();
            RenderSystem.outputDepthTextureOverride = mask.getDepthTextureView();
            if (restoreProjection) {
                RenderSystem.backupProjectionMatrix();
                projectionBackedUp = true;
                RenderSystem.setProjectionMatrix(maskProjectionSlice, state.capturedProjectionType);
            }
            if (state.capturedModelViewMatrixValid && state.capturedModelViewMatrix != null) {
                RenderSystem.getModelViewStack().pushMatrix();
                modelViewPushed = true;
                RenderSystem.getModelViewStack().set(state.capturedModelViewMatrix);
            }
            //#if MC==1_21_11 || MC==1_26_01
            // The transition precedes dispatcher entry even if dispatch/flush throws.
            if (lateReplay && !state.beginStreamingReplayAttempt()) return;
            if (sequentialReplay && !state.beginOrdinaryReplayAttempt(SuperResolutionCompat.currentFrameEpoch())) return;
            //#endif
            //#if MC>=1_26_02
            //$$ // 26.2: renderAllFeatures takes the storage per call and drains it internally
            //$$ // (prepareFrame → drainPhases). RenderBuffers no longer exposes bufferSource/
            //$$ // outlineBufferSource — the staged vertex buffer flush happens inside the frame.
            //$$ try {
            //$$     state.captureDispatcher.renderAllFeatures(state.captureStorage);
            //$$     state.captureStorageClean = true;
            //$$ } catch (RuntimeException | Error e) {
            //$$     // PreparedFrame can remain "in use" when preparation throws before its
            //$$     // try-with-resources is established. Drop both storage and dispatcher so a
            //$$     // poisoned context cannot break every later capture state.
            //$$     closeOwnedResource(state.captureDispatcher);
            //$$     state.captureDispatcher = null;
            //$$     state.captureStorage = null;
            //$$     state.captureStorageClean = false;
            //$$     throw e;
            //$$ }
            //#else
            //#if MC==1_21_11 || MC==1_26_01
            try {
            //#endif
            state.captureDispatcher.renderAllFeatures();
            sharedCaptureBuffers.bufferSource().endBatch();
            sharedCaptureBuffers.outlineBufferSource().endOutlineBatch();
            //#if MC==1_21_11 || MC==1_26_01
            } catch (RuntimeException | Error failure) {
                // SubmitNodeStorage.clear cannot discard vertices already emitted into the
                // shared builders. Retire their owner without attempting another draw/flush.
                discardFailedReplayBuffers(failure);
                throw failure;
            }
            //#endif
            //#endif
        } finally {
            try {
                if (modelViewPushed) RenderSystem.getModelViewStack().popMatrix();
            } finally {
                try {
                    if (projectionBackedUp) RenderSystem.restoreProjectionMatrix();
                } finally {
                    RenderSystem.outputColorTextureOverride = oldColor;
                    RenderSystem.outputDepthTextureOverride = oldDepth;
                }
            }
        }

        state.lastMaskScaleX = maskScaleX;
        state.lastMaskScaleY = maskScaleY;
        state.lastSceneScaleX = state.firstPerson ? maskScaleX : sceneProjection.scaleX();
        state.lastSceneScaleY = state.firstPerson ? maskScaleY : sceneProjection.scaleY();
        state.lastMaskOffsetX = maskOffsetX;
        state.lastMaskOffsetY = maskOffsetY;
        state.lastSceneOffsetX = sceneOffsetX;
        state.lastSceneOffsetY = sceneOffsetY;
        state.exactDepthAlignment = state.firstPerson
                || !IrisCompat.isShaderActive()
                || (exactTemporalReplay && projectionApplied)
                || (shaderCompatDisplayReplay
                && sceneProjection.exactTemporalJitter()
                && projectionApplied && restoreProjection);
        state.capturedThisFrame = true;
        } finally {
            IrisCompat.restoreBypass(irisSnapshot);
        }
        //#elseif MC>=1_21_06
        //$$ // 1.21.6-1.21.8 capture vertices exclusively through DelayingMultiBufferSource.
        //$$ // captureBuffers is never allocated on this path, so it must not gate replay.
        //$$ if (state.customBufferSource == null || state.maskTarget == null) {
        //$$     invalidateCapture(state);
        //$$     return;
        //$$ }
        //$$ RenderTarget frameTarget = mc.getMainRenderTarget();
        //$$ if (frameTarget == null
        //$$         || !renderTargetSizeMatches(frameTarget, frameTarget.width, frameTarget.height)
        //$$         || !renderTargetSizeMatches(state.maskTarget, frameTarget.width, frameTarget.height)
        //$$         || (sceneDepthCaptured
        //$$         && !renderTargetSizeMatches(sceneDepthTarget, frameTarget.width, frameTarget.height))) {
        //$$     invalidateCapture(state);
        //$$     return;
        //$$ }
        //$$
        //$$ var irisSnapshot = IrisCompat.setBypass(true);
        //$$ try {
        //$$ if (!canReplayWithShaderBypass(
        //$$         IrisCompat.isShaderActive(), irisSnapshot.shaderBypassEnabled())) {
        //$$     invalidateCapture(state);
        //$$     return;
        //$$ }
        //$$ var encoder = RenderSystem.getDevice().createCommandEncoder();
        //$$ encoder.clearColorTexture(state.maskTarget.getColorTexture(), 0);
        //$$
        //$$ ShaderPackHint.ProjectionTransform sourceProjection =
        //$$         IrisCompat.getShaderProjectionTransform(state.maskTarget.width, state.maskTarget.height);
        //$$ boolean shaderCompatDisplayReplay = !outputSpacePrepare
        //$$         && IrisCompat.isActiveSrRuntime()
        //$$         && SuperResolutionCompat.hasCompletedShaderCompatDispatch(
        //$$         state.maskTarget.width, state.maskTarget.height);
        //$$ if (shaderCompatDisplayReplay && !state.firstPerson
        //$$         && (!sceneDepthCaptured || !renderTargetSizeMatches(
        //$$         sceneDepthTarget, state.maskTarget.width, state.maskTarget.height))) {
        //$$     invalidateCapture(state);
        //$$     return;
        //$$ }
        //$$ if (shaderCompatDisplayReplay
        //$$         && (state.capturedProjectionMatrix == null
        //$$         || state.capturedProjectionType == null)) {
        //$$     invalidateCapture(state);
        //$$     return;
        //$$ }
        //$$ ShaderPackHint.ProjectionTransform packProjection = projectionForReplay(
        //$$         sourceProjection, outputSpacePrepare || shaderCompatDisplayReplay);
        //$$ ShaderPackHint.ProjectionTransform sceneProjection = shaderCompatDisplayReplay
        //$$         && !state.firstPerson ? sourceProjection : packProjection;
        //$$ boolean exactTemporalReplay = usesExactTemporalReplay(state, packProjection)
        //$$         && irisSnapshot.shaderBypassEnabled();
        //$$ if (outputSpacePrepare && !state.firstPerson) {
        //$$     if (!srDisplaySceneDepthPrepared || srDisplaySceneDepthTarget == null
        //$$             || !copyDepthBounded(encoder,
        //$$             srDisplaySceneDepthTarget.getDepthTexture(),
        //$$             state.maskTarget.getDepthTexture(), state.maskTarget.width,
        //$$             state.maskTarget.height, 1.0)) {
        //$$         invalidateCapture(state);
        //$$         return;
        //$$     }
        //$$     state.maskDepthPrepared = true;
        //$$     state.maskDepthSnapshotGeneration = sceneDepthGeneration;
        //$$ }
        //$$ boolean clearDepthForReplay = (shaderCompatDisplayReplay && !state.firstPerson)
        //$$         || (outputSpacePrepare && state.firstPerson)
        //$$         || (!outputSpacePrepare && clearsMaskDepthForReplay(
        //$$         state.firstPerson, IrisCompat.isShaderActive(), exactTemporalReplay));
        //$$ if (clearDepthForReplay) {
        //$$     state.maskDepthSnapshotGeneration = -1L;
        //$$     encoder.clearDepthTexture(state.maskTarget.getDepthTexture(), 1.0);
        //$$ } else if (!state.maskDepthPrepared) {
        //$$     RenderTarget mainTarget = mc.getMainRenderTarget();
        //$$     RenderTarget sourceDepth = sceneDepthCaptured && sceneDepthTarget != null
        //$$             ? sceneDepthTarget : mainTarget;
        //$$     // [issue #1] bounded copy - see copyDepthBounded.
        //$$     state.maskDepthPrepared = copyDepthBounded(encoder, sourceDepth.getDepthTexture(),
        //$$             state.maskTarget.getDepthTexture(), sourceDepth.width, sourceDepth.height, 1.0);
        //$$     if (!state.maskDepthPrepared) {
        //$$         invalidateCapture(state);
        //$$         return;
        //$$     }
        //$$     state.maskDepthSnapshotGeneration = sceneDepthCaptured ? sceneDepthGeneration : -1L;
        //$$ }
        //$$ // Exact Iris replays intentionally defer world occlusion to the composite shader;
        //$$ // keeping the copied scene depth here would let tiny pack-vs-vanilla Z/coverage
        //$$ // differences discard the mask fragment before the composite can compare it.
        //$$
        //$$ var oldColor = RenderSystem.outputColorTextureOverride;
        //$$ var oldDepth = RenderSystem.outputDepthTextureOverride;
        //$$ RenderSystem.outputColorTextureOverride = state.maskTarget.getColorTextureView();
        //$$ RenderSystem.outputDepthTextureOverride = state.maskTarget.getDepthTextureView();
        //$$
        //$$ GpuBufferSlice maskProjectionSlice = state.capturedProjectionMatrix;
        //$$ float maskScaleX = 1.0f;
        //$$ float maskScaleY = 1.0f;
        //$$ float maskOffsetX = packProjection.viewportOriginX();
        //$$ float maskOffsetY = packProjection.viewportOriginY();
        //$$ float sceneOffsetX = sceneProjection.viewportOriginX();
        //$$ float sceneOffsetY = sceneProjection.viewportOriginY();
        //$$ boolean projectionApplied = !packProjection.changesProjection();
        //$$ float jitterX = 2.0f * packProjection.viewportOriginX()
        //$$         + (exactTemporalReplay ? packProjection.jitterX() : 0.0f);
        //$$ float jitterY = 2.0f * packProjection.viewportOriginY()
        //$$         + (exactTemporalReplay ? packProjection.jitterY() : 0.0f);
        //$$ float replayScaleX = packProjection.scaleX();
        //$$ float replayScaleY = packProjection.scaleY();
        //$$ float zBias = 0.0f;
        //$$ boolean changesProjection = replayScaleX != 1.0f
        //$$         || replayScaleY != 1.0f
        //$$         || jitterX != 0.0f || jitterY != 0.0f || zBias != 0.0f;
        //$$ if (state.capturedProjectionMatrix4fValid && changesProjection) {
        //$$     GpuBufferSlice scaled = uploadScaledProjection(
        //$$             encoder, state,
        //$$             state.capturedProjectionMatrix4f,
        //$$             replayScaleX, replayScaleY,
        //$$             jitterX, jitterY, zBias);
        //$$     if (scaled != null) {
        //$$         maskProjectionSlice = scaled;
        //$$         maskScaleX = replayScaleX;
        //$$         maskScaleY = replayScaleY;
        //$$         if (exactTemporalReplay) {
        //$$             maskOffsetX = packProjection.uvOffsetX();
        //$$             maskOffsetY = packProjection.uvOffsetY();
        //$$         }
        //$$         projectionApplied = true;
        //$$     }
        //$$ }
        //$$ if (!state.firstPerson && IrisCompat.isShaderActive()
        //$$         && sceneProjection.exactTemporalJitter()) {
        //$$     sceneOffsetX = sceneProjection.uvOffsetX();
        //$$     sceneOffsetY = sceneProjection.uvOffsetY();
        //$$ }
        //$$
        //$$ boolean restoreProjection = maskProjectionSlice != null && state.capturedProjectionType != null;
        //$$ if (restoreProjection) {
        //$$     RenderSystem.backupProjectionMatrix();
        //$$     RenderSystem.setProjectionMatrix(maskProjectionSlice, state.capturedProjectionType);
        //$$ }
        //$$
        //$$ if (state.capturedModelViewMatrixValid && state.capturedModelViewMatrix != null) {
        //$$     RenderSystem.getModelViewStack().pushMatrix();
        //$$     RenderSystem.getModelViewStack().set(state.capturedModelViewMatrix);
        //$$ }
        //$$
        //$$ try {
        //$$     state.customBufferSource.flush();
        //$$ } finally {
        //$$     if (state.capturedModelViewMatrixValid && state.capturedModelViewMatrix != null) {
        //$$         RenderSystem.getModelViewStack().popMatrix();
        //$$     }
        //$$     if (restoreProjection) {
        //$$         RenderSystem.restoreProjectionMatrix();
        //$$     }
        //$$     RenderSystem.outputColorTextureOverride = oldColor;
        //$$     RenderSystem.outputDepthTextureOverride = oldDepth;
        //$$ }
        //$$
        //$$ state.lastMaskScaleX = maskScaleX;
        //$$ state.lastMaskScaleY = maskScaleY;
        //$$ state.lastSceneScaleX = state.firstPerson ? maskScaleX : sceneProjection.scaleX();
        //$$ state.lastSceneScaleY = state.firstPerson ? maskScaleY : sceneProjection.scaleY();
        //$$ state.lastMaskOffsetX = maskOffsetX;
        //$$ state.lastMaskOffsetY = maskOffsetY;
        //$$ state.lastSceneOffsetX = sceneOffsetX;
        //$$ state.lastSceneOffsetY = sceneOffsetY;
        //$$ state.exactDepthAlignment = state.firstPerson
        //$$         || !IrisCompat.isShaderActive()
        //$$         || (exactTemporalReplay && projectionApplied)
        //$$         || (shaderCompatDisplayReplay
        //$$         && sceneProjection.exactTemporalJitter()
        //$$         && projectionApplied && restoreProjection);
        //$$ state.capturedThisFrame = true;
        //$$ } finally {
        //$$     IrisCompat.restoreBypass(irisSnapshot);
        //$$ }
        //#elseif MC>=1_21_05
        //$$ // 1.21.5: no outputColorTextureOverride. DelayingMultiBufferSource.flushToTarget()
        //$$ // manually uploads meshes and opens a RenderPass targeting the mask textures.
        //$$ // 1.21.5 capture vertices exclusively through DelayingMultiBufferSource.
        //$$ // captureBuffers is never allocated on this path, so it must not gate replay.
        //$$ if (state.customBufferSource == null || state.maskTarget == null) {
        //$$     invalidateCapture(state);
        //$$     return;
        //$$ }
        //$$ RenderTarget frameTarget = mc.getMainRenderTarget();
        //$$ if (frameTarget == null
        //$$         || !renderTargetSizeMatches(frameTarget, frameTarget.width, frameTarget.height)
        //$$         || !renderTargetSizeMatches(state.maskTarget, frameTarget.width, frameTarget.height)
        //$$         || (sceneDepthCaptured
        //$$         && !renderTargetSizeMatches(sceneDepthTarget, frameTarget.width, frameTarget.height))) {
        //$$     invalidateCapture(state);
        //$$     return;
        //$$ }
        //$$
        //$$ var irisSnapshot = IrisCompat.setBypass(true);
        //$$ try {
        //$$ if (!canReplayWithShaderBypass(
        //$$         IrisCompat.isShaderActive(), irisSnapshot.shaderBypassEnabled())) {
        //$$     invalidateCapture(state);
        //$$     return;
        //$$ }
        //$$ var encoder = RenderSystem.getDevice().createCommandEncoder();
        //$$ encoder.clearColorTexture(state.maskTarget.getColorTexture(), 0);
        //$$
        //$$ // Mask depth strategy mirrors the >=1_21_06 branch above; one local twist:
        //$$ // 1.21.5's GameRenderer.renderLevel issues clearDepthTexture(mainDepth, 1.0)
        //$$ // right after levelRenderer.renderLevel and BEFORE renderItemInHand — so by
        //$$ // the time the renderLevel TAIL hook runs, mainTarget.getDepthTexture() is
        //$$ // the *cleared* depth (1.0 + held item), not world depth. Copying from it
        //$$ // would defeat occlusion entirely. captureSceneDepth handles this by copying the
        //$$ // pre-clear snapshot into every active state's mask depth ahead of time. Under Iris,
        //$$ // that snapshot also contains the custom hand rendered inside LevelRenderer.
        //$$ ShaderPackHint.ProjectionTransform sourceProjection =
        //$$         IrisCompat.getShaderProjectionTransform(state.maskTarget.width, state.maskTarget.height);
        //$$ boolean shaderCompatDisplayReplay = !outputSpacePrepare
        //$$         && IrisCompat.isActiveSrRuntime()
        //$$         && SuperResolutionCompat.hasCompletedShaderCompatDispatch(
        //$$         state.maskTarget.width, state.maskTarget.height);
        //$$ if (shaderCompatDisplayReplay && !state.firstPerson
        //$$         && (!sceneDepthCaptured || !renderTargetSizeMatches(
        //$$         sceneDepthTarget, state.maskTarget.width, state.maskTarget.height))) {
        //$$     invalidateCapture(state);
        //$$     return;
        //$$ }
        //$$ if (shaderCompatDisplayReplay
        //$$         && (!state.capturedProjectionMatrix4fValid
        //$$         || state.capturedProjectionType == null)) {
        //$$     invalidateCapture(state);
        //$$     return;
        //$$ }
        //$$ ShaderPackHint.ProjectionTransform packProjection = projectionForReplay(
        //$$         sourceProjection, outputSpacePrepare || shaderCompatDisplayReplay);
        //$$ ShaderPackHint.ProjectionTransform sceneProjection = shaderCompatDisplayReplay
        //$$         && !state.firstPerson ? sourceProjection : packProjection;
        //$$ boolean exactTemporalReplay = usesExactTemporalReplay(state, packProjection)
        //$$         && irisSnapshot.shaderBypassEnabled();
        //$$ if (outputSpacePrepare && !state.firstPerson) {
        //$$     if (!sceneDepthCaptured || sceneDepthTarget == null) {
        //$$         invalidateCapture(state);
        //$$         return;
        //$$     }
        //$$     // Both are Gallium TextureTargets with the same depth format. GL's NEAREST
        //$$     // depth blit safely scales the render-size snapshot into the screen-size mask.
        //$$     state.maskTarget.copyDepthFrom(sceneDepthTarget);
        //$$     state.maskDepthPrepared = true;
        //$$     state.maskDepthSnapshotGeneration = sceneDepthGeneration;
        //$$ }
        //$$ boolean clearDepthForReplay = (shaderCompatDisplayReplay && !state.firstPerson)
        //$$         || (outputSpacePrepare && state.firstPerson)
        //$$         || (!outputSpacePrepare && clearsMaskDepthForReplay(
        //$$         state.firstPerson, IrisCompat.isShaderActive(), exactTemporalReplay));
        //$$ if (clearDepthForReplay) {
        //$$     state.maskDepthSnapshotGeneration = -1L;
        //$$     encoder.clearDepthTexture(state.maskTarget.getDepthTexture(), 1.0);
        //$$ } else if (!state.maskDepthPrepared) {
        //$$     RenderTarget mainTarget = mc.getMainRenderTarget();
        //$$     RenderTarget sourceDepth = sceneDepthCaptured && sceneDepthTarget != null
        //$$             ? sceneDepthTarget : mainTarget;
        //$$     // [issue #1] bounded copy - see copyDepthBounded.
        //$$     state.maskDepthPrepared = copyDepthBounded(encoder, sourceDepth.getDepthTexture(),
        //$$             state.maskTarget.getDepthTexture(), sourceDepth.width, sourceDepth.height, 1.0);
        //$$     if (!state.maskDepthPrepared) {
        //$$         invalidateCapture(state);
        //$$         return;
        //$$     }
        //$$     state.maskDepthSnapshotGeneration = sceneDepthCaptured ? sceneDepthGeneration : -1L;
        //$$ }
        //$$ // Exact Iris world replays intentionally defer world occlusion to the composite
        //$$ // shader. Retaining the pre-clear depth here would let tiny pack-vs-vanilla Z or
        //$$ // alpha-coverage differences discard mask pixels before that comparison.
        //$$
        //$$ if (state.capturedModelViewMatrixValid && state.capturedModelViewMatrix != null) {
        //$$     RenderSystem.getModelViewStack().pushMatrix();
        //$$     RenderSystem.getModelViewStack().set(state.capturedModelViewMatrix);
        //$$ }
        //$$
        //$$ // 1.21.5 lacks GpuBufferSlice/Std140Builder, so apply the declared scale and exact
        //$$ // temporal offset directly through the Matrix4f projection overload.
        //$$ float maskScaleX = 1.0f;
        //$$ float maskScaleY = 1.0f;
        //$$ float maskOffsetX = packProjection.viewportOriginX();
        //$$ float maskOffsetY = packProjection.viewportOriginY();
        //$$ float sceneOffsetX = sceneProjection.viewportOriginX();
        //$$ float sceneOffsetY = sceneProjection.viewportOriginY();
        //$$ Matrix4f maskProjection = state.capturedProjectionMatrix4f;
        //$$ boolean projectionApplied = !packProjection.changesProjection();
        //$$ float jitterX = 2.0f * packProjection.viewportOriginX()
        //$$         + (exactTemporalReplay ? packProjection.jitterX() : 0.0f);
        //$$ float jitterY = 2.0f * packProjection.viewportOriginY()
        //$$         + (exactTemporalReplay ? packProjection.jitterY() : 0.0f);
        //$$ float replayScaleX = packProjection.scaleX();
        //$$ float replayScaleY = packProjection.scaleY();
        //$$ float zBias = IrisCompat.isShaderActive() && !state.firstPerson
        //$$         && !shaderCompatDisplayReplay && !exactTemporalReplay
        //$$         ? IRIS_TAA_Z_BIAS : 0.0f;
        //$$ boolean changesProjection = replayScaleX != 1.0f
        //$$         || replayScaleY != 1.0f
        //$$         || jitterX != 0.0f || jitterY != 0.0f || zBias != 0.0f;
        //$$ if (state.capturedProjectionMatrix4fValid && changesProjection) {
        //$$     maskProjection = computeScaledProjection(state.capturedProjectionMatrix4f,
        //$$             replayScaleX, replayScaleY,
        //$$             jitterX, jitterY, zBias, SCRATCH_SCALED_PROJECTION);
        //$$     maskScaleX = replayScaleX;
        //$$     maskScaleY = replayScaleY;
        //$$     if (exactTemporalReplay) {
        //$$         maskOffsetX = packProjection.uvOffsetX();
        //$$         maskOffsetY = packProjection.uvOffsetY();
        //$$     }
        //$$     projectionApplied = true;
        //$$ }
        //$$ if (!state.firstPerson && IrisCompat.isShaderActive()
        //$$         && sceneProjection.exactTemporalJitter()) {
        //$$     sceneOffsetX = sceneProjection.uvOffsetX();
        //$$     sceneOffsetY = sceneProjection.uvOffsetY();
        //$$ }
        //$$ boolean restoreProj = state.capturedProjectionMatrix4fValid
        //$$         && state.capturedProjectionType != null;
        //$$ if (restoreProj) {
        //$$     RenderSystem.backupProjectionMatrix();
        //$$     RenderSystem.setProjectionMatrix(maskProjection,
        //$$             state.capturedProjectionType);
        //$$ }
        //$$
        //$$ try {
        //$$     state.customBufferSource.flushToTarget(state.maskTarget);
        //$$ } finally {
        //$$     if (restoreProj) {
        //$$         RenderSystem.restoreProjectionMatrix();
        //$$     }
        //$$ }
        //$$
        //$$ if (state.capturedModelViewMatrixValid && state.capturedModelViewMatrix != null) {
        //$$     RenderSystem.getModelViewStack().popMatrix();
        //$$ }
        //$$
        //$$ state.lastMaskScaleX = maskScaleX;
        //$$ state.lastMaskScaleY = maskScaleY;
        //$$ state.lastSceneScaleX = state.firstPerson ? maskScaleX : sceneProjection.scaleX();
        //$$ state.lastSceneScaleY = state.firstPerson ? maskScaleY : sceneProjection.scaleY();
        //$$ state.lastMaskOffsetX = maskOffsetX;
        //$$ state.lastMaskOffsetY = maskOffsetY;
        //$$ state.lastSceneOffsetX = sceneOffsetX;
        //$$ state.lastSceneOffsetY = sceneOffsetY;
        //$$ state.exactDepthAlignment = state.firstPerson
        //$$         || !IrisCompat.isShaderActive()
        //$$         || (exactTemporalReplay && projectionApplied)
        //$$         || (shaderCompatDisplayReplay
        //$$         && sceneProjection.exactTemporalJitter()
        //$$         && projectionApplied && restoreProj);
        //$$ state.capturedThisFrame = true;
        //$$ } finally {
        //$$     IrisCompat.restoreBypass(irisSnapshot);
        //$$ }
        //#else
        //$$ if (state.maskTarget == null) return;
        //$$ var irisSnapshot = IrisCompat.setBypass(true);
        //$$ try {
        //$$ if (!canReplayWithShaderBypass(
        //$$         IrisCompat.isShaderActive(), irisSnapshot.shaderBypassEnabled())) {
        //$$     invalidateCapture(state);
        //$$     return;
        //$$ }
        //$$ mask.setClearColor(0.0F, 0.0F, 0.0F, 0.0F);
        //#if MC>=1_21_02
        //$$ mask.clear();
        //#else
        //$$ mask.clear(net.minecraft.client.Minecraft.ON_OSX);
        //#endif
        //$$ // Mask depth strategy mirrors the >=1_21_06 branch above; see comments there.
        //$$ // Local difference: 1.21.4 uses RenderTarget.copyDepthFrom(sceneDepthTarget) instead
        //$$ // of CommandEncoder.copyTextureToTexture (no GpuTexture API on this version).
        //$$ ShaderPackHint.ProjectionTransform sourceProjection =
        //$$         IrisCompat.getShaderProjectionTransform(state.maskTarget.width, state.maskTarget.height);
        //$$ boolean shaderCompatDisplayReplay = !outputSpacePrepare
        //$$         && IrisCompat.isActiveSrRuntime()
        //$$         && SuperResolutionCompat.hasCompletedShaderCompatDispatch(
        //$$         state.maskTarget.width, state.maskTarget.height);
        //$$ if (shaderCompatDisplayReplay && !state.firstPerson
        //$$         && (!sceneDepthCaptured || sceneDepthTarget == null
        //$$         || sceneDepthTarget.width != state.maskTarget.width
        //$$         || sceneDepthTarget.height != state.maskTarget.height)) {
        //$$     invalidateCapture(state);
        //$$     return;
        //$$ }
        //$$ if (shaderCompatDisplayReplay
        //$$         && (!state.capturedProjectionMatrix4fValid
        //$$         || state.capturedProjectionType == null)) {
        //$$     invalidateCapture(state);
        //$$     return;
        //$$ }
        //$$ ShaderPackHint.ProjectionTransform packProjection = projectionForReplay(
        //$$         sourceProjection, outputSpacePrepare || shaderCompatDisplayReplay);
        //$$ ShaderPackHint.ProjectionTransform sceneProjection = shaderCompatDisplayReplay
        //$$         && !state.firstPerson ? sourceProjection : packProjection;
        //$$ boolean exactTemporalReplay = usesExactTemporalReplay(state, packProjection)
        //$$         && irisSnapshot.shaderBypassEnabled();
        //$$ if (outputSpacePrepare && !state.firstPerson) {
        //$$     if (!sceneDepthCaptured || sceneDepthTarget == null) {
        //$$         invalidateCapture(state);
        //$$         return;
        //$$     }
        //$$     // Both are Gallium TextureTargets with the same depth format. GL's NEAREST
        //$$     // depth blit safely scales the render-size snapshot into the screen-size mask.
        //$$     mask.copyDepthFrom(sceneDepthTarget);
        //$$     state.maskDepthPrepared = true;
        //$$     state.maskDepthSnapshotGeneration = sceneDepthGeneration;
        //$$ }
        //$$ boolean clearDepthForReplay = (shaderCompatDisplayReplay && !state.firstPerson)
        //$$         || (outputSpacePrepare && state.firstPerson)
        //$$         || (!outputSpacePrepare && clearsMaskDepthForReplay(
        //$$         state.firstPerson, IrisCompat.isShaderActive(), exactTemporalReplay));
        //$$ if (clearDepthForReplay) {
        //$$     state.maskDepthSnapshotGeneration = -1L;
        //$$     // mask.clear() already leaves the forward-Z depth at the far plane.
        //$$     // Exact Iris world replays keep it there so the composite owns occlusion.
        //$$ } else if (state.maskDepthPrepared) {
        //$$     // captureSceneDepth already populated mask depth for the fallback replay.
        //$$ } else if (sceneDepthCaptured && sceneDepthTarget != null) {
        //$$     // Real world depth captured before the renderItemInHand pass; works for both
        //$$     // no-Iris and Iris paths (Iris's finalPass binds only color, leaving depth alone).
        //$$     mask.copyDepthFrom(sceneDepthTarget);
        //$$     state.maskDepthPrepared = true;
        //$$     state.maskDepthSnapshotGeneration = sceneDepthGeneration;
        //$$ } else {
        //$$     RenderTarget mainTarget = mc.getMainRenderTarget();
        //$$     mask.copyDepthFrom(mainTarget);
        //$$     state.maskDepthPrepared = true;
        //$$     state.maskDepthSnapshotGeneration = -1L;
        //$$ }
        //$$
        //$$ float maskScaleX = 1.0f;
        //$$ float maskScaleY = 1.0f;
        //$$ float maskOffsetX = packProjection.viewportOriginX();
        //$$ float maskOffsetY = packProjection.viewportOriginY();
        //$$ float sceneOffsetX = sceneProjection.viewportOriginX();
        //$$ float sceneOffsetY = sceneProjection.viewportOriginY();
        //$$ Matrix4f maskProjection = state.capturedProjectionMatrix4f;
        //$$ boolean projectionApplied = !packProjection.changesProjection();
        //$$ float jitterX = 2.0f * packProjection.viewportOriginX()
        //$$         + (exactTemporalReplay ? packProjection.jitterX() : 0.0f);
        //$$ float jitterY = 2.0f * packProjection.viewportOriginY()
        //$$         + (exactTemporalReplay ? packProjection.jitterY() : 0.0f);
        //$$ float scaleX = packProjection.scaleX();
        //$$ float scaleY = packProjection.scaleY();
        //$$ float zBias = IrisCompat.isShaderActive() && !state.firstPerson
        //$$         && !shaderCompatDisplayReplay && !exactTemporalReplay
        //$$         ? IRIS_TAA_Z_BIAS : 0.0f;
        //$$ boolean changesProjection = scaleX != 1.0f || scaleY != 1.0f
        //$$         || jitterX != 0.0f || jitterY != 0.0f || zBias != 0.0f;
        //$$ if (state.capturedProjectionMatrix4fValid && changesProjection) {
        //$$     maskProjection = computeScaledProjection(state.capturedProjectionMatrix4f,
        //$$             scaleX, scaleY, jitterX, jitterY, zBias, SCRATCH_SCALED_PROJECTION);
        //$$     maskScaleX = scaleX;
        //$$     maskScaleY = scaleY;
        //$$     if (exactTemporalReplay) {
        //$$         maskOffsetX = packProjection.uvOffsetX();
        //$$         maskOffsetY = packProjection.uvOffsetY();
        //$$     }
        //$$     projectionApplied = true;
        //$$ }
        //$$ if (!state.firstPerson && IrisCompat.isShaderActive()
        //$$         && sceneProjection.exactTemporalJitter()) {
        //$$     sceneOffsetX = sceneProjection.uvOffsetX();
        //$$     sceneOffsetY = sceneProjection.uvOffsetY();
        //$$ }
        //$$ boolean shouldRestoreProj = state.capturedProjectionMatrix4fValid
        //$$         && state.capturedProjectionType != null;
        //$$ boolean shouldPushModelView = state.capturedModelViewMatrixValid
        //$$         && state.capturedModelViewMatrix != null;
        //$$
        //$$ // Track which side-effects actually fired so the finally block only undoes
        //$$ // what succeeded — if setProjectionMatrix throws after backupProjectionMatrix,
        //$$ // we still want to restoreProjectionMatrix; if pushMatrix succeeds but
        //$$ // setProjectionMatrix throws, we still want to popMatrix. Keeping push and
        //$$ // backup inside the try ensures their popMatrix/restore counterparts run on
        //$$ // every unwind path.
        //$$ boolean pushedModelView = false;
        //$$ boolean backedUpProj = false;
        //$$ try {
        //$$     if (shouldPushModelView) {
        //$$         RenderSystem.getModelViewStack().pushMatrix();
        //$$         pushedModelView = true;
        //$$         RenderSystem.getModelViewStack().set(state.capturedModelViewMatrix);
        //$$         // 1.21.1 (and possibly other pre-1.21.5 versions): RenderSystem's
        //$$         // modelViewMatrix is a SEPARATE cached field that is only synced from
        //$$         // the PoseStack during shader.apply(). flushToTarget ->
        //$$         // BufferUploader.drawWithShader reads getModelViewMatrix() directly,
        //$$         // so we must sync it manually after manipulating the stack. Without
        //$$         // this, the mask renders with a stale (entity-transform-free) modelview
        //$$         // and the glow outline shifts off the item as the camera rotates.
        //$$         RenderSystem.getModelViewMatrix().set(state.capturedModelViewMatrix);
        //$$     }
        //$$     if (shouldRestoreProj) {
        //$$         RenderSystem.backupProjectionMatrix();
        //$$         backedUpProj = true;
        //$$         RenderSystem.setProjectionMatrix(maskProjection, state.capturedProjectionType);
        //$$     }
        //$$     if (state.customBufferSource != null) {
        //$$         state.customBufferSource.flushToTarget(mask);
        //$$     }
        //$$ } finally {
        //$$     if (backedUpProj) RenderSystem.restoreProjectionMatrix();
        //$$     if (pushedModelView) RenderSystem.getModelViewStack().popMatrix();
        //$$ }
        //$$ state.lastMaskScaleX = maskScaleX;
        //$$ state.lastMaskScaleY = maskScaleY;
        //$$ state.lastSceneScaleX = state.firstPerson ? maskScaleX : sceneProjection.scaleX();
        //$$ state.lastSceneScaleY = state.firstPerson ? maskScaleY : sceneProjection.scaleY();
        //$$ state.lastMaskOffsetX = maskOffsetX;
        //$$ state.lastMaskOffsetY = maskOffsetY;
        //$$ state.lastSceneOffsetX = sceneOffsetX;
        //$$ state.lastSceneOffsetY = sceneOffsetY;
        //$$ state.exactDepthAlignment = state.firstPerson
        //$$         || !IrisCompat.isShaderActive()
        //$$         || (exactTemporalReplay && projectionApplied)
        //$$         || (shaderCompatDisplayReplay
        //$$         && sceneProjection.exactTemporalJitter()
        //$$         && projectionApplied && shouldRestoreProj);
        //$$ state.capturedThisFrame = true;
        //$$ } finally {
        //$$     IrisCompat.restoreBypass(irisSnapshot);
        //$$ }
        //#endif
    }

    /**
     * Pre-multiplies the passed projection by the matrix equivalent of the shader pack's
     * internal scale and declared temporal offset.
     * Returns a slice into the state-owned buffer; the contents remain valid while that state is
     * alive, so deferred backends cannot observe a later item's projection upload.
     */
    //#if MC>=1_21_06
    private static @Nullable GpuBufferSlice uploadScaledProjection(
            CommandEncoder encoder, GlowCaptureState state,
            Matrix4f baseProjection, float scaleX, float scaleY,
            float jitterX, float jitterY, float zBias) {
        if (state.scaledProjectionBuffer == null) {
            state.scaledProjectionBuffer = RenderSystem.getDevice().createBuffer(
                    () -> "Glow Scaled Projection",
                    GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_COPY_DST,
                    RenderSystem.PROJECTION_MATRIX_UBO_SIZE);
            // Buffer offsets stayed `int` in 1.21.10 and were widened to `long` in 1.21.11.
            // Passing `0` (a literal int) is accepted by both signatures via implicit widening.
            state.scaledProjectionSlice = state.scaledProjectionBuffer.slice(
                    0, RenderSystem.PROJECTION_MATRIX_UBO_SIZE);
        }
        Matrix4f result = computeScaledProjection(
                baseProjection, scaleX, scaleY, jitterX, jitterY, zBias,
                SCRATCH_SCALED_PROJECTION);

        try (MemoryStack stack = MemoryStack.stackPush()) {
            ByteBuffer data = Std140Builder.onStack(stack, RenderSystem.PROJECTION_MATRIX_UBO_SIZE)
                    .putMat4f(result).get();
            encoder.writeToBuffer(state.scaledProjectionSlice, data);
        }
        return state.scaledProjectionSlice;
    }
    //#endif

    /**
     * Writes {@code S * baseProjection} into {@code dest}, where {@code S} combines:
     * <ul>
     *   <li>Internal scale + jitter: {@code x' = scaleX*x + (scaleX-1+jitterX)*w}
     *       and the corresponding Y transform.</li>
     *   <li>Depth-adaptive clip-space z-bias: {@code NDC.z' = (1 + zBias) * NDC.z - zBias}.
     *       Positive {@code zBias} pulls geometry toward the camera in forward-Z, but the pull is
     *       strongest at the near plane ({@code NDC.z = 0} -> shift {@code -zBias}) and tapers to
     *       zero at the far plane ({@code NDC.z = 1} -> no shift).</li>
     * </ul>
     * The bias must be depth-adaptive because sub-pixel TAA jitter maps to an NDC-depth error of
     * {@code ~2n·θ/z}: large on near geometry, negligible at the far plane. A <em>constant</em>
     * NDC bias therefore over-pulls far geometry by the same amount it needs near, letting an item
     * sitting just behind a wall at distance win the replay's LEQUAL and draw an x-ray outline
     * through the wall. Tapering the pull to zero at the far plane keeps the near silhouette stable
     * (LEQUAL tolerates jitter) while restoring correct occlusion at distance.
     *
     * <p>Using the full 4x4 form keeps us robust against non-standard projection matrices.
     * Exposed package-private for tests.
     */
    static Matrix4f computeScaledProjection(
            Matrix4f baseProjection, float scaleX, float scaleY,
            float jitterX, float jitterY, float zBias, Matrix4f dest) {
        float tx = scaleX - 1.0f + jitterX;
        float ty = scaleY - 1.0f + jitterY;
        // S * baseProjection. JOML stores column-major, so the constructor below lists columns.
        // Layout reads as a transform with diag(scaleX, scaleY, 1+zBias, 1), translation
        // (tx, ty, -zBias) applied after the perspective basis — equivalent to
        // xy *= scale; xy += (scale - 1 + jitter) * w.
        // z' = (1+zBias) * z - zBias * w  =>  NDC.z' = (1+zBias) * NDC.z - zBias.
        dest.set(
                scaleX, 0,      0,       0,
                0,     scaleY,  0,       0,
                0,     0,      (1 + zBias), 0,
                tx,    ty,     -zBias,  1);
        return dest.mul(baseProjection);
    }

    /** Backwards-compatible overload with isotropic scale and no temporal offset. */
    static Matrix4f computeScaledProjection(
            Matrix4f baseProjection, float scale, float zBias, Matrix4f dest) {
        return computeScaledProjection(baseProjection, scale, scale,
                0.0f, 0.0f, zBias, dest);
    }

    /** Backwards-compatible overload used by existing tests; defaults zBias = 0. */
    static Matrix4f computeScaledProjection(Matrix4f baseProjection, float scale, Matrix4f dest) {
        return computeScaledProjection(baseProjection, scale, 0.0f, dest);
    }

    /** Returns true only when a world replay can use the pack's exact current temporal transform. */
    static boolean usesExactTemporalReplay(
            boolean firstPerson, boolean capturedProjectionValid,
            ShaderPackHint.ProjectionTransform transform) {
        return !firstPerson
                && capturedProjectionValid
                && transform != null
                && transform.exactTemporalJitter();
    }

    /**
     * SR output masks are rendered after the source frame has been promoted to display space.
     * Reapplying the input texture's scale/origin/jitter would put the mask back into the
     * low-resolution active sub-region and no longer match the full-size depth resample.
     */
    static ShaderPackHint.ProjectionTransform projectionForReplay(
            ShaderPackHint.ProjectionTransform transform, boolean outputSpacePrepare) {
        if (outputSpacePrepare) return ShaderPackHint.ProjectionTransform.IDENTITY;
        return transform == null ? ShaderPackHint.ProjectionTransform.IDENTITY : transform;
    }

    /** Iris replay is safe only when the real shader-selection bypass was enabled. */
    static boolean canReplayWithShaderBypass(
            boolean irisActive, boolean shaderBypassEnabled) {
        return !irisActive || shaderBypassEnabled;
    }

    /**
     * captureSceneDepth runs before renderCapturedNodes can attempt the bypass. Only skip its
     * conservative scene-depth pre-fill when Iris exposes the actual shader-selection bypass;
     * the extended-vertex-format switch by itself does not make an unmodified replay exact.
     */
    private static boolean canPrepareExactTemporalReplay(
            GlowCaptureState state, ShaderPackHint.ProjectionTransform transform) {
        return IrisCompat.isShaderBypassAvailable()
                && usesExactTemporalReplay(state, transform);
    }

    /**
     * Returns whether mask depth must start at the far plane and leave world occlusion to the
     * composite shader. A vanilla/unknown-pack replay still needs the copied or pooled scene
     * depth as a GPU-side guard; an exact Iris replay does not, because its scene and mask UVs are
     * aligned and the fragment shader compares both depths after capture.
     */
    static boolean clearsMaskDepthForReplay(
            boolean firstPerson, boolean shaderActive, boolean exactTemporalReplay) {
        return firstPerson || (shaderActive && exactTemporalReplay);
    }

    /**
     * Native 26.2 reverse-Z cannot safely use a shader pack's jittered scene depth as the depth
     * attachment for an un-jittered fallback replay. Start that Iris/Vulkan world replay at the
     * far plane and let the composite shader's conservative 3x3 scene-depth lookup perform the
     * occlusion instead. Forward-depth Iris/OpenGL and exact temporal replays retain their more
     * precise paths; vanilla has no shader-pack jitter to compensate.
     */
    static boolean shouldDeferReverseZWorldOcclusion(
            boolean firstPerson, boolean irisActive,
            boolean forwardDepthCompatibility, boolean exactTemporalReplay) {
        return !firstPerson
                && irisActive
                && !forwardDepthCompatibility
                && !exactTemporalReplay;
    }

    private static boolean usesExactTemporalReplay(
            GlowCaptureState state, ShaderPackHint.ProjectionTransform transform) {
        return usesExactTemporalReplay(
                state.firstPerson, state.capturedProjectionMatrix4fValid, transform);
    }

    private static GlowCaptureState allocateState() {
        // Successful captures always commit to activeStates in allocation order. Failed setup
        // attempts are not committed, so activeStates.size() remains the reusable prefix index.
        // This makes allocation O(1) without changing the extensive activeStates iteration API.
        int index = activeStates.size();
        if (index < pool.size()) return pool.get(index);
        GlowCaptureState state = new GlowCaptureState();
        pool.add(state);
        return state;
    }

    private static void releaseState(GlowCaptureState state) {
        releaseCaptureTargets(state);
        state.resetFrame();
        //#if MC>=1_21_06
        if (state.scaledProjectionBuffer != null) {
            state.scaledProjectionBuffer.close();
            state.scaledProjectionBuffer = null;
            state.scaledProjectionSlice = null;
        }
        //#endif
        //#if MC>=1_21_09
        if (state.duplicatingStorage != null) state.duplicatingStorage.detach();
        //#if MC>=1_26_02
        //$$ if (!closeOwnedResource(state.captureDispatcher)) {
        //$$     cn.spectra.gallium.Gallium.LOGGER.warn(
        //$$             "Failed to close a Gallium capture FeatureRenderDispatcher");
        //$$ }
        //$$ state.captureDispatcher = null;
        //$$ state.captureStorage = null;
        //$$ state.captureStorageClean = false;
        //#else
        state.captureDispatcher = null;
        //#endif
        // Shared RenderBuffers lives across all states — don't null it out or allocate per-state.
        //#else
        //$$ if (state.reusableTee != null) {
        //$$     state.reusableTee.detach();
        //$$     state.reusableTee = null;
        //$$ }
        //$$ // Release the retained off-heap capture buffers (pooled across frames on 1.21.8).
        //$$ if (state.customBufferSource != null) {
        //$$     state.customBufferSource.free();
        //$$     state.customBufferSource = null;
        //$$ }
        //#endif
    }

    public static void clearAll() {
        //#if MC==1_21_11 || MC==1_26_01
        sharedMask.close();
        sharedOwnershipActive = false;
        sequentialMaskPolicy.reset();
        sequentialFrame = null;
        //#endif
        for (GlowCaptureState state : pool) {
            releaseState(state);
        }
        if (sceneDepthTarget != null) {
            sceneDepthTarget.destroyBuffers();
            sceneDepthTarget = null;
        }
        if (foregroundDepthTarget != null) {
            foregroundDepthTarget.destroyBuffers();
            foregroundDepthTarget = null;
        }
        //#if MC>=1_21_06
        if (srDisplaySceneDepthTarget != null) {
            srDisplaySceneDepthTarget.destroyBuffers();
            srDisplaySceneDepthTarget = null;
        }
        srDisplaySceneDepthPrepared = false;
        //#endif
        //#if MC>=1_26_02
        //$$ if (liveSceneDepthForwardZTarget != null) {
        //$$     liveSceneDepthForwardZTarget.destroyBuffers();
        //$$     liveSceneDepthForwardZTarget = null;
        //$$ }
        //#endif
        //#if MC>=1_21_09
        releaseSharedBuffers();
        //#endif
        sceneDepthCaptured = false;
        sceneDepthGeneration = 0L;
        worldCaptureSeenThisFrame = false;
        foregroundDepthCaptured = false;
        foregroundDepthGeneration = 0L;
        //#if MC>=1_21_06
        srDisplaySceneDepthSourceGeneration = -1L;
        //#endif
        captureTargetBytesReserved = 0L;
        captureBudgetWarningLogged = false;
        activeStates.clear();
        clearCaptureScopes();
        currentCapture = null;
    }

    //#if MC>=1_21_09
    /** Iterate RenderBuffers' declared fields and close any {@link ByteBufferBuilder}
     *  instances found — RenderBuffers has no {@code close()} API and vanilla never
     *  discards one, but we recreate it on resource reload so the old builders must be
     *  freed.  Reflection is safe here because this runs only on reload/teardown,
     *  not per-frame. */
    private static void releaseSharedBuffers() {
        //#if MC==1_21_11 || MC==1_26_01
        releaseSharedBuffers(null);
        //#else
        if (sharedCaptureBuffers == null) return;
        RenderBuffers rb = sharedCaptureBuffers;
        sharedCaptureBuffers = null;
        //#if MC>=1_26_02
        //$$ if (!closeOwnedResource(rb)) {
        //$$     cn.spectra.gallium.Gallium.LOGGER.warn(
        //$$             "Failed to close Gallium shared capture RenderBuffers");
        //$$ }
        //#else
        try {
            for (java.lang.reflect.Field field : RenderBuffers.class.getDeclaredFields()) {
                field.setAccessible(true);
                Object value;
                try {
                    value = field.get(rb);
                } catch (IllegalAccessException e) {
                    continue;
                }
                if (value instanceof com.mojang.blaze3d.vertex.ByteBufferBuilder bb) {
                    bb.close();
                } else if (value instanceof java.util.Map<?, ?> map) {
                    for (Object v : map.values()) {
                        if (v instanceof com.mojang.blaze3d.vertex.ByteBufferBuilder bbb) {
                            bbb.close();
                        }
                    }
                }
            }
        } catch (Exception e) {
            cn.spectra.gallium.Gallium.LOGGER.warn("Failed to close shared capture buffers: {}", e.toString());
        }
        //#endif
        //#endif
    }
    //#endif

    //#if MC==1_21_11 || MC==1_26_01
    /** Retire a partially emitted batch while preserving per-payload attempts and frame policy. */
    static void discardFailedReplayBuffers(Throwable failure) {
        try {
            for (GlowCaptureState state : pool) {
                try {
                    state.invalidateCapture();
                } catch (Throwable cleanupFailure) {
                    reportBufferCleanupFailure(failure, cleanupFailure);
                } finally {
                    // Every dispatcher retains the same BufferSources, including cold slots.
                    state.captureDispatcher = null;
                }
            }
            // A throwing storage.clear may have interrupted invalidation before its flags were
            // committed. With dispatcher references gone this second pass cannot revisit it.
            for (GlowCaptureState state : pool) {
                try {
                    state.invalidateCapture();
                } catch (Throwable cleanupFailure) {
                    reportBufferCleanupFailure(failure, cleanupFailure);
                }
            }
        } finally {
            releaseSharedBuffers(failure);
        }
    }

    private static void releaseSharedBuffers(@Nullable Throwable failure) {
        RenderBuffers retired = sharedCaptureBuffers;
        sharedCaptureBuffers = null;
        // A pack's ByteBufferBuilders are also referenced by BufferSource.fixedBuffers.
        // Identity deduplication is temporary disposal state, not retained telemetry.
        var visited = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<Object, Boolean>());
        closeCaptureBufferStorage(retired, visited, failure);
    }

    /** Walk only the known capture-owned buffer graph, never RenderTypes, models or GPU state. */
    private static void closeCaptureBufferStorage(Object owned, java.util.Set<Object> visited,
                                                  @Nullable Throwable failure) {
        if (owned == null || !visited.add(owned)) return;
        if (owned instanceof com.mojang.blaze3d.vertex.ByteBufferBuilder bytes) {
            try {
                bytes.close();
            } catch (Throwable cleanupFailure) {
                reportBufferCleanupFailure(failure, cleanupFailure);
            }
            return;
        }
        if (owned instanceof java.util.Map<?, ?> map) {
            for (Object value : map.values()) closeCaptureBufferStorage(value, visited, failure);
            return;
        }
        if (owned instanceof Iterable<?> values) {
            for (Object value : values) closeCaptureBufferStorage(value, visited, failure);
            return;
        }
        Class<?> fields;
        if (owned instanceof RenderBuffers) fields = RenderBuffers.class;
        else if (owned instanceof net.minecraft.client.renderer.MultiBufferSource.BufferSource)
            fields = net.minecraft.client.renderer.MultiBufferSource.BufferSource.class;
        else if (owned instanceof net.minecraft.client.renderer.OutlineBufferSource)
            fields = net.minecraft.client.renderer.OutlineBufferSource.class;
        else if (owned instanceof net.minecraft.client.renderer.SectionBufferBuilderPack)
            fields = net.minecraft.client.renderer.SectionBufferBuilderPack.class;
        else if (owned instanceof net.minecraft.client.renderer.SectionBufferBuilderPool)
            fields = net.minecraft.client.renderer.SectionBufferBuilderPool.class;
        else return;
        for (java.lang.reflect.Field field : fields.getDeclaredFields()) {
            if (java.lang.reflect.Modifier.isStatic(field.getModifiers())) continue;
            try {
                field.setAccessible(true);
                closeCaptureBufferStorage(field.get(owned), visited, failure);
            } catch (Throwable cleanupFailure) {
                reportBufferCleanupFailure(failure, cleanupFailure);
            }
        }
    }

    private static void reportBufferCleanupFailure(@Nullable Throwable failure, Throwable cleanupFailure) {
        if (failure != null) {
            if (cleanupFailure != failure) failure.addSuppressed(cleanupFailure);
        } else {
            cn.spectra.gallium.Gallium.LOGGER.warn("Failed to close shared capture buffers: {}", cleanupFailure.toString());
        }
    }
    //#endif

    /** Closes one Gallium-owned resource without letting teardown failure escape a reload. */
    static boolean closeOwnedResource(@Nullable AutoCloseable resource) {
        if (resource == null) return true;
        try {
            resource.close();
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }
}
