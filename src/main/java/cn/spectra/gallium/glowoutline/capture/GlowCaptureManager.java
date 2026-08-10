package cn.spectra.gallium.glowoutline.capture;

import cn.spectra.gallium.glowoutline.IrisCompat;
import cn.spectra.gallium.glowoutline.ItemEffectConfig;
import cn.spectra.gallium.glowoutline.ItemEffectsManager;
import cn.spectra.gallium.glowoutline.ShaderPackHint;
//#if MC>=1_21_09
import cn.spectra.gallium.glowoutline.mixin.accessor.FeatureRenderDispatcherAccessor;
import cn.spectra.gallium.glowoutline.mixin.accessor.GameRendererAccessor;
//#endif
//#if MC>=1_21_06
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.buffers.Std140Builder;
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

    private static final List<GlowCaptureState> pool = new ArrayList<>();
    private static final List<GlowCaptureState> activeStates = new ArrayList<>();

    private static @Nullable GlowCaptureState currentCapture;
    private static int suppressDepth;
    private static boolean sceneDepthCaptured;
    /**
     * Snapshot taken immediately before vanilla clears the main depth attachment for its hand
     * stage. Without Iris this contains the completed world and vanilla writes the hand after the
     * clear. With an active Iris pack, Iris renders both hand phases inside LevelRenderer and
     * suppresses vanilla's later hand draw, so the same snapshot already contains world + hand.
     */
    private static @Nullable TextureTarget sceneDepthTarget;
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

    static {
        cn.spectra.gallium.glowoutline.shader.GlowResources.register(GlowCaptureManager::clearAll);
    }

    private GlowCaptureManager() {}

    public static void beginFrame() {
        for (GlowCaptureState state : activeStates) {
            state.resetFrame();
        }
        activeStates.clear();
        currentCapture = null;
        suppressDepth = 0;
        sceneDepthCaptured = false;

        if (pool.size() > POOL_HIGH_WATER_MARK) {
            for (int i = pool.size() - 1; i >= POOL_HIGH_WATER_MARK; i--) {
                releaseState(pool.remove(i));
            }
        }
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

    public static void captureSceneDepth(RenderTarget mainTarget) {
        //#if MC>=1_21_05
        if (sceneDepthCaptured) return;
        GpuTexture srcDepth = mainTarget.getDepthTexture();
        if (srcDepth == null) return;

        int w = mainTarget.width, h = mainTarget.height;
        ShaderPackHint.ProjectionTransform packProjection =
                IrisCompat.getShaderProjectionTransform(w, h);
        if (sceneDepthTarget == null || sceneDepthTarget.width != w || sceneDepthTarget.height != h) {
            if (sceneDepthTarget != null) sceneDepthTarget.destroyBuffers();
            sceneDepthTarget = new TextureTarget("GlowSceneDepth", w, h, true
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
            //$$ sceneDepthTarget.getDepthTexture().setUseMipmaps(false);
            //#endif
            //#endif
        }

        var encoder = RenderSystem.getDevice().createCommandEncoder();
        encoder.copyTextureToTexture(srcDepth, sceneDepthTarget.getDepthTexture(), 0, 0, 0, 0, 0, w, h);

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
        //$$     if (state.maskTarget != null && !state.firstPerson) {
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
        //$$                     state.maskDepthPrepared = true;
        //$$                     continue;
        //$$                 }
        //$$                 useDepthPool = false;
        //$$             } else {
        //$$                 encoder.copyTextureToTexture(pooledDepth, state.maskTarget.getDepthTexture(),
        //$$                         0, 0, 0, 0, 0, w, h);
        //$$                 state.maskDepthPrepared = true;
        //$$                 continue;
        //$$             }
        //$$         }
        //$$         encoder.copyTextureToTexture(srcDepth, state.maskTarget.getDepthTexture(),
        //$$                 0, 0, 0, 0, 0, w, h);
        //$$         state.maskDepthPrepared = true;
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
            if (state.maskTarget != null && !state.firstPerson) {
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
                            state.maskDepthPrepared = true;
                            continue;
                        }
                        useDepthPool = false;
                    } else {
                        encoder.copyTextureToTexture(pooledDepth, state.maskTarget.getDepthTexture(),
                                0, 0, 0, 0, 0, w, h);
                        state.maskDepthPrepared = true;
                        continue;
                    }
                }
                encoder.copyTextureToTexture(srcDepth, state.maskTarget.getDepthTexture(),
                        0, 0, 0, 0, 0, w, h);
                state.maskDepthPrepared = true;
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
        //$$     if (state.maskTarget != null && !state.firstPerson) {
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
        //$$                     state.maskDepthPrepared = true;
        //$$                     continue;
        //$$                 }
        //$$                 useDepthPool = false;
        //$$             } else {
        //$$                 encoder.copyTextureToTexture(pooledDepth, state.maskTarget.getDepthTexture(),
        //$$                         0, 0, 0, 0, 0, w, h);
        //$$                 state.maskDepthPrepared = true;
        //$$                 continue;
        //$$             }
        //$$         }
        //$$         encoder.copyTextureToTexture(srcDepth, state.maskTarget.getDepthTexture(),
        //$$                 0, 0, 0, 0, 0, w, h);
        //$$         state.maskDepthPrepared = true;
        //$$     }
        //$$ }
        //#else
        //$$ // 1.21.5 has no GpuTextureView-based pool pass; retain its plain-copy fallback.
        //$$ for (GlowCaptureState state : activeStates) {
        //$$     if (state.maskTarget != null) {
        //$$         boolean exactTemporalReplay = canPrepareExactTemporalReplay(state, packProjection);
        //$$         if (clearsMaskDepthForReplay(state.firstPerson, IrisCompat.isShaderActive(),
        //$$                 exactTemporalReplay)) continue;
        //$$         encoder.copyTextureToTexture(srcDepth, state.maskTarget.getDepthTexture(),
        //$$                 0, 0, 0, 0, 0, w, h);
        //$$         state.maskDepthPrepared = true;
        //$$     }
        //$$ }
        //#endif
        //#endif
        sceneDepthCaptured = true;
        //#else
        //$$ if (sceneDepthCaptured) return;
        //$$ int w = mainTarget.width, h = mainTarget.height;
        //$$ ShaderPackHint.ProjectionTransform packProjection =
        //$$         IrisCompat.getShaderProjectionTransform(w, h);
        //$$ if (sceneDepthTarget == null || sceneDepthTarget.width != w || sceneDepthTarget.height != h) {
        //$$     if (sceneDepthTarget != null) sceneDepthTarget.destroyBuffers();
        //#if MC>=1_21_02
        //$$     sceneDepthTarget = new TextureTarget(w, h, true);
        //#else
        //$$     sceneDepthTarget = new TextureTarget(w, h, true, net.minecraft.client.Minecraft.ON_OSX);
        //#endif
        //$$ }
        //$$ sceneDepthTarget.copyDepthFrom(mainTarget);
        //$$ for (GlowCaptureState state : activeStates) {
        //$$     if (state.maskTarget != null) {
        //$$         boolean exactTemporalReplay = canPrepareExactTemporalReplay(state, packProjection);
        //$$         if (clearsMaskDepthForReplay(state.firstPerson, IrisCompat.isShaderActive(),
        //$$                 exactTemporalReplay)) continue;
        //$$         state.maskTarget.copyDepthFrom(mainTarget);
        //$$         state.maskDepthPrepared = true;
        //$$     }
        //$$ }
        //$$ sceneDepthCaptured = true;
        //$$ // RenderTarget.copyDepthFrom() ends with `_glBindFramebuffer(GL_FRAMEBUFFER, 0)`,
        //$$ // leaving the default framebuffer bound. The vanilla code path that triggered our
        //$$ // capture (RenderSystem.clear(256) inside renderLevel before renderItemInHand)
        //$$ // assumes mainTarget is still bound — without this re-bind, clear(256) wipes the
        //$$ // default framebuffer's depth instead of mainTarget's, leaving stale world depth
        //$$ // in mainTarget. Hand items then fail LEQUAL against world depth and disappear
        //$$ // behind world geometry. Restore mainTarget binding so vanilla's downstream draws
        //$$ // and clears land on the right target.
        //$$ mainTarget.bindWrite(true);
        //#endif
    }

    public static @Nullable TextureTarget getSceneDepthTarget() {
        // A target survives resource frames, but its contents are valid only after this frame's
        // pre-hand snapshot. Returning a prior frame here would make a missed capture hook turn
        // into a stale occluder rather than the live-depth fallback.
        return sceneDepthCaptured ? sceneDepthTarget : null;
    }

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
        //#else
        if (stack.isEmpty()) return false;
        if (!ItemEffectsManager.isActive()) return false;

        ItemEffectConfig cfg = ItemEffectsManager.getConfig(stack);
        if (cfg == null || cfg.shader().isEmpty()) return false;

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
        if (main == null) return false;

        GlowCaptureState state = allocateState();
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
        } else if (state.maskTarget.width != main.width || state.maskTarget.height != main.height) {
            state.maskTarget.resize(main.width, main.height);
            //#if MC<1_21_11
            //#if MC>=1_21_06
            //$$ state.maskTarget.getColorTexture().setUseMipmaps(false);
            //$$ state.maskTarget.getDepthTexture().setUseMipmaps(false);
            //#endif
            //#endif
        }

        //#if MC>=1_21_09
        if (sharedCaptureBuffers == null) {
            sharedCaptureBuffers = new RenderBuffers(1);
        }

        //#if MC>=1_26_02
        //$$ // 26.2: FeatureRenderDispatcher no longer takes a SubmitNodeStorage/BufferSources —
        //$$ // its ctor is (RenderBuffers, ModelManager, AtlasManager, Font, GameRenderState) and
        //$$ // renderAllFeatures(SubmitNodeStorage) receives the storage per call. Each state owns
        //$$ // its own captureStorage; renderAllFeatures drains it via drainPhases, so a reused
        //$$ // state's storage is empty after the prior frame's render — re-instantiate defensively
        //$$ // only in case a capture began but never rendered.
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
        //$$     state.captureStorage = new SubmitNodeStorage();
        //$$ } else {
        //$$     state.captureStorage = new SubmitNodeStorage();
        //$$ }
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
        //#endif
    }

    public static void endItemCapture() {
        currentCapture = null;
    }

    public static void beginSuppress() { suppressDepth++; }
    public static void endSuppress() { if (suppressDepth > 0) suppressDepth--; }

    public static @Nullable GlowCaptureState currentCapture() { return currentCapture; }

    //#if MC>=1_21_09
    public static @Nullable SubmitNodeStorage captureStorageForCurrent() {
        if (currentCapture == null || suppressDepth > 0) return null;
        if (currentCapture.captureDispatcher == null) return null;
        //#if MC>=1_26_02
        //$$ return currentCapture.captureStorage;
        //#else
        return currentCapture.captureDispatcher.getSubmitNodeStorage();
        //#endif
    }
    //#endif

    public static void renderCapturedNodes(GlowCaptureState state, Minecraft mc) {
        //#if MC>=1_21_09
        if (state.captureDispatcher == null || sharedCaptureBuffers == null || state.maskTarget == null) return;

        var irisSnapshot = IrisCompat.setBypass(true);
        try {
        var encoder = RenderSystem.getDevice().createCommandEncoder();
        //#if MC>=1_26_02
        //$$ encoder.clearColorTexture(state.maskTarget.getColorTexture(), new org.joml.Vector4f(0.0F));
        //#else
        encoder.clearColorTexture(state.maskTarget.getColorTexture(), 0);
        //#endif

        ShaderPackHint.ProjectionTransform packProjection =
                IrisCompat.getShaderProjectionTransform(state.maskTarget.width, state.maskTarget.height);
        boolean exactTemporalReplay = usesExactTemporalReplay(state, packProjection)
                && irisSnapshot.shaderBypassEnabled();
        boolean clearDepthForReplay = clearsMaskDepthForReplay(
                state.firstPerson, IrisCompat.isShaderActive(), exactTemporalReplay);

        if (clearDepthForReplay) {
            //#if MC>=1_26_02
            //$$ // Pass the native reverse-Z far value. Iris/OpenGL's UndoReverseZ wrapper turns
            //$$ // this into forward-Z 1.0 while a pack is active; passing 1.0 here would be
            //$$ // inverted a second time. Iris/Vulkan keeps native reverse-Z and uses 0.0.
            //$$ encoder.clearDepthTexture(state.maskTarget.getDepthTexture(), 0.0);
            //#else
            encoder.clearDepthTexture(state.maskTarget.getDepthTexture(), 1.0);
            //#endif
        } else if (!state.maskDepthPrepared) {
            //#if MC>=1_26_02
            //$$ RenderTarget mainTarget = mc.gameRenderer.mainRenderTarget();
            //#else
            RenderTarget mainTarget = mc.getMainRenderTarget();
            //#endif
            RenderTarget sourceDepth = sceneDepthCaptured && sceneDepthTarget != null
                    ? sceneDepthTarget : mainTarget;
            encoder.copyTextureToTexture(
                    sourceDepth.getDepthTexture(), state.maskTarget.getDepthTexture(),
                    0, 0, 0, 0, 0, sourceDepth.width, sourceDepth.height);
            state.maskDepthPrepared = true;
        }
        // Exact Iris world replays deliberately start at the far plane. Pre-filling scene depth
        // makes the bypassed vanilla shader compare against the pack's original item depth; tiny
        // differences in clip-space Z, alpha discard, or coverage then punch permanent holes in
        // the mask. The composite shader has both item and scene depth and performs the actual
        // world/item occlusion after the complete mask has been captured. Unknown pack transforms
        // retain the copied/pooled depth fallback above.

        var oldColor = RenderSystem.outputColorTextureOverride;
        var oldDepth = RenderSystem.outputDepthTextureOverride;
        RenderSystem.outputColorTextureOverride = state.maskTarget.getColorTextureView();
        RenderSystem.outputDepthTextureOverride = state.maskTarget.getDepthTextureView();

        // Recreate the pack's declared post-projection transform. For exact declarations this
        // includes the current temporal jitter, so the replay lands on the same depth samples as
        // the original Iris draw and does not need the silhouette-expanding depth pool.
        GpuBufferSlice maskProjectionSlice = state.capturedProjectionMatrix;
        float maskScaleX = 1.0f;
        float maskScaleY = 1.0f;
        float maskOffsetX = 0.0f;
        float maskOffsetY = 0.0f;
        float sceneOffsetX = 0.0f;
        float sceneOffsetY = 0.0f;
        boolean projectionApplied = !packProjection.changesProjection();
        float replayScaleX = packProjection.scaleX();
        float replayScaleY = packProjection.scaleY();
        // iterationRP deliberately suppresses hand jitter when DECREASE_HAND_GHOSTING is enabled;
        // Gallium has no portable way to query that pack option, so keep the hand replay stable.
        float jitterX = exactTemporalReplay ? packProjection.jitterX() : 0.0f;
        float jitterY = exactTemporalReplay ? packProjection.jitterY() : 0.0f;
        float zBias = 0.0f;
        boolean changesProjection = replayScaleX != 1.0f
                || replayScaleY != 1.0f
                || jitterX != 0.0f || jitterY != 0.0f || zBias != 0.0f;
        if (state.capturedProjectionMatrix4fValid && changesProjection) {
            GpuBufferSlice scaled = uploadScaledProjection(
                    encoder, state,
                    state.capturedProjectionMatrix4f,
                    replayScaleX, replayScaleY,
                    jitterX, jitterY, zBias);
            if (scaled != null) {
                maskProjectionSlice = scaled;
                maskScaleX = replayScaleX;
                maskScaleY = replayScaleY;
                if (exactTemporalReplay) {
                    // Projection jitter is expressed in NDC units; converting to UV units
                    // divides by two because NDC spans [-1, 1] while UV spans [0, 1].
                    maskOffsetX = packProjection.jitterX() * 0.5f;
                    maskOffsetY = packProjection.jitterY() * 0.5f;
                }
                projectionApplied = true;
            }
        }

        if (!state.firstPerson && IrisCompat.isShaderActive()
                && packProjection.exactTemporalJitter()) {
            // The pre-clear scene snapshot is the pack's internal, jittered depth image even
            // when the mask replay had to fall back. Keep its lookup in the same internal texel.
            sceneOffsetX = packProjection.jitterX() * 0.5f;
            sceneOffsetY = packProjection.jitterY() * 0.5f;
        }

        boolean restoreProjection = maskProjectionSlice != null && state.capturedProjectionType != null;
        if (restoreProjection) {
            RenderSystem.backupProjectionMatrix();
            RenderSystem.setProjectionMatrix(maskProjectionSlice, state.capturedProjectionType);
        }

        if (state.capturedModelViewMatrixValid && state.capturedModelViewMatrix != null) {
            RenderSystem.getModelViewStack().pushMatrix();
            RenderSystem.getModelViewStack().set(state.capturedModelViewMatrix);
        }

        try {
            //#if MC>=1_26_02
            //$$ // 26.2: renderAllFeatures takes the storage per call and drains it internally
            //$$ // (prepareFrame → drainPhases). RenderBuffers no longer exposes bufferSource/
            //$$ // outlineBufferSource — the staged vertex buffer flush happens inside the frame.
            //$$ state.captureDispatcher.renderAllFeatures(state.captureStorage);
            //#else
            state.captureDispatcher.renderAllFeatures();
            sharedCaptureBuffers.bufferSource().endBatch();
            sharedCaptureBuffers.outlineBufferSource().endOutlineBatch();
            //#endif
        } finally {
            if (state.capturedModelViewMatrixValid && state.capturedModelViewMatrix != null) {
                RenderSystem.getModelViewStack().popMatrix();
            }
            if (restoreProjection) {
                RenderSystem.restoreProjectionMatrix();
            }
            RenderSystem.outputColorTextureOverride = oldColor;
            RenderSystem.outputDepthTextureOverride = oldDepth;
        }

        state.lastMaskScaleX = maskScaleX;
        state.lastMaskScaleY = maskScaleY;
        state.lastSceneScaleX = state.firstPerson ? maskScaleX : replayScaleX;
        state.lastSceneScaleY = state.firstPerson ? maskScaleY : replayScaleY;
        state.lastMaskOffsetX = maskOffsetX;
        state.lastMaskOffsetY = maskOffsetY;
        state.lastSceneOffsetX = sceneOffsetX;
        state.lastSceneOffsetY = sceneOffsetY;
        state.exactDepthAlignment = state.firstPerson
                || !IrisCompat.isShaderActive()
                || (exactTemporalReplay && projectionApplied);
        state.capturedThisFrame = true;
        } finally {
            IrisCompat.restoreBypass(irisSnapshot);
        }
        //#elseif MC>=1_21_06
        //$$ if (state.captureBuffers == null || state.maskTarget == null) return;
        //$$
        //$$ var irisSnapshot = IrisCompat.setBypass(true);
        //$$ try {
        //$$ var encoder = RenderSystem.getDevice().createCommandEncoder();
        //$$ encoder.clearColorTexture(state.maskTarget.getColorTexture(), 0);
        //$$
        //$$ ShaderPackHint.ProjectionTransform packProjection =
        //$$         IrisCompat.getShaderProjectionTransform(state.maskTarget.width, state.maskTarget.height);
        //$$ boolean exactTemporalReplay = usesExactTemporalReplay(state, packProjection)
        //$$         && irisSnapshot.shaderBypassEnabled();
        //$$ boolean clearDepthForReplay = clearsMaskDepthForReplay(
        //$$         state.firstPerson, IrisCompat.isShaderActive(), exactTemporalReplay);
        //$$ if (clearDepthForReplay) {
        //$$     encoder.clearDepthTexture(state.maskTarget.getDepthTexture(), 1.0);
        //$$ } else if (!state.maskDepthPrepared) {
        //$$     RenderTarget mainTarget = mc.getMainRenderTarget();
        //$$     RenderTarget sourceDepth = sceneDepthCaptured && sceneDepthTarget != null
        //$$             ? sceneDepthTarget : mainTarget;
        //$$     encoder.copyTextureToTexture(
        //$$             sourceDepth.getDepthTexture(), state.maskTarget.getDepthTexture(),
        //$$             0, 0, 0, 0, 0, sourceDepth.width, sourceDepth.height);
        //$$     state.maskDepthPrepared = true;
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
        //$$ float maskOffsetX = 0.0f;
        //$$ float maskOffsetY = 0.0f;
        //$$ float sceneOffsetX = 0.0f;
        //$$ float sceneOffsetY = 0.0f;
        //$$ boolean projectionApplied = !packProjection.changesProjection();
        //$$ float jitterX = exactTemporalReplay ? packProjection.jitterX() : 0.0f;
        //$$ float jitterY = exactTemporalReplay ? packProjection.jitterY() : 0.0f;
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
        //$$             maskOffsetX = packProjection.jitterX() * 0.5f;
        //$$             maskOffsetY = packProjection.jitterY() * 0.5f;
        //$$         }
        //$$         projectionApplied = true;
        //$$     }
        //$$ }
        //$$ if (!state.firstPerson && IrisCompat.isShaderActive()
        //$$         && packProjection.exactTemporalJitter()) {
        //$$     sceneOffsetX = packProjection.jitterX() * 0.5f;
        //$$     sceneOffsetY = packProjection.jitterY() * 0.5f;
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
        //$$     if (state.customBufferSource != null) {
        //$$         state.customBufferSource.flush();
        //$$     } else {
        //$$         state.captureBuffers.bufferSource().endBatch();
        //$$     }
        //$$     state.captureBuffers.outlineBufferSource().endOutlineBatch();
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
        //$$ state.lastSceneScaleX = state.firstPerson ? maskScaleX : replayScaleX;
        //$$ state.lastSceneScaleY = state.firstPerson ? maskScaleY : replayScaleY;
        //$$ state.lastMaskOffsetX = maskOffsetX;
        //$$ state.lastMaskOffsetY = maskOffsetY;
        //$$ state.lastSceneOffsetX = sceneOffsetX;
        //$$ state.lastSceneOffsetY = sceneOffsetY;
        //$$ state.exactDepthAlignment = state.firstPerson
        //$$         || !IrisCompat.isShaderActive()
        //$$         || (exactTemporalReplay && projectionApplied);
        //$$ state.capturedThisFrame = true;
        //$$ } finally {
        //$$     IrisCompat.restoreBypass(irisSnapshot);
        //$$ }
        //#elseif MC>=1_21_05
        //$$ // 1.21.5: no outputColorTextureOverride. DelayingMultiBufferSource.flushToTarget()
        //$$ // manually uploads meshes and opens a RenderPass targeting the mask textures.
        //$$ if (state.captureBuffers == null || state.maskTarget == null) return;
        //$$
        //$$ var irisSnapshot = IrisCompat.setBypass(true);
        //$$ try {
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
        //$$ ShaderPackHint.ProjectionTransform packProjection =
        //$$         IrisCompat.getShaderProjectionTransform(state.maskTarget.width, state.maskTarget.height);
        //$$ boolean exactTemporalReplay = usesExactTemporalReplay(state, packProjection)
        //$$         && irisSnapshot.shaderBypassEnabled();
        //$$ boolean clearDepthForReplay = clearsMaskDepthForReplay(
        //$$         state.firstPerson, IrisCompat.isShaderActive(), exactTemporalReplay);
        //$$ if (clearDepthForReplay) {
        //$$     encoder.clearDepthTexture(state.maskTarget.getDepthTexture(), 1.0);
        //$$ } else if (!state.maskDepthPrepared) {
        //$$     RenderTarget mainTarget = mc.getMainRenderTarget();
        //$$     RenderTarget sourceDepth = sceneDepthCaptured && sceneDepthTarget != null
        //$$             ? sceneDepthTarget : mainTarget;
        //$$     encoder.copyTextureToTexture(
        //$$             sourceDepth.getDepthTexture(), state.maskTarget.getDepthTexture(),
        //$$             0, 0, 0, 0, 0, sourceDepth.width, sourceDepth.height);
        //$$     state.maskDepthPrepared = true;
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
        //$$ float maskOffsetX = 0.0f;
        //$$ float maskOffsetY = 0.0f;
        //$$ float sceneOffsetX = 0.0f;
        //$$ float sceneOffsetY = 0.0f;
        //$$ Matrix4f maskProjection = state.capturedProjectionMatrix4f;
        //$$ boolean projectionApplied = !packProjection.changesProjection();
        //$$ float jitterX = exactTemporalReplay ? packProjection.jitterX() : 0.0f;
        //$$ float jitterY = exactTemporalReplay ? packProjection.jitterY() : 0.0f;
        //$$ float replayScaleX = packProjection.scaleX();
        //$$ float replayScaleY = packProjection.scaleY();
        //$$ float zBias = IrisCompat.isShaderActive() && !state.firstPerson && !exactTemporalReplay
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
        //$$         maskOffsetX = packProjection.jitterX() * 0.5f;
        //$$         maskOffsetY = packProjection.jitterY() * 0.5f;
        //$$     }
        //$$     projectionApplied = true;
        //$$ }
        //$$ if (!state.firstPerson && IrisCompat.isShaderActive()
        //$$         && packProjection.exactTemporalJitter()) {
        //$$     sceneOffsetX = packProjection.jitterX() * 0.5f;
        //$$     sceneOffsetY = packProjection.jitterY() * 0.5f;
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
        //$$     if (state.customBufferSource != null) {
        //$$         state.customBufferSource.flushToTarget(state.maskTarget);
        //$$     } else {
        //$$         state.captureBuffers.bufferSource().endBatch();
        //$$     }
        //$$     state.captureBuffers.outlineBufferSource().endOutlineBatch();
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
        //$$ state.lastSceneScaleX = state.firstPerson ? maskScaleX : replayScaleX;
        //$$ state.lastSceneScaleY = state.firstPerson ? maskScaleY : replayScaleY;
        //$$ state.lastMaskOffsetX = maskOffsetX;
        //$$ state.lastMaskOffsetY = maskOffsetY;
        //$$ state.lastSceneOffsetX = sceneOffsetX;
        //$$ state.lastSceneOffsetY = sceneOffsetY;
        //$$ state.exactDepthAlignment = state.firstPerson
        //$$         || !IrisCompat.isShaderActive()
        //$$         || (exactTemporalReplay && projectionApplied);
        //$$ state.capturedThisFrame = true;
        //$$ } finally {
        //$$     IrisCompat.restoreBypass(irisSnapshot);
        //$$ }
        //#else
        //$$ if (state.maskTarget == null) return;
        //$$ TextureTarget mask = state.maskTarget;
        //$$ var irisSnapshot = IrisCompat.setBypass(true);
        //$$ try {
        //$$ mask.setClearColor(0.0F, 0.0F, 0.0F, 0.0F);
        //#if MC>=1_21_02
        //$$ mask.clear();
        //#else
        //$$ mask.clear(net.minecraft.client.Minecraft.ON_OSX);
        //#endif
        //$$ // Mask depth strategy mirrors the >=1_21_06 branch above; see comments there.
        //$$ // Local difference: 1.21.4 uses RenderTarget.copyDepthFrom(sceneDepthTarget) instead
        //$$ // of CommandEncoder.copyTextureToTexture (no GpuTexture API on this version).
        //$$ ShaderPackHint.ProjectionTransform packProjection =
        //$$         IrisCompat.getShaderProjectionTransform(state.maskTarget.width, state.maskTarget.height);
        //$$ boolean exactTemporalReplay = usesExactTemporalReplay(state, packProjection)
        //$$         && irisSnapshot.shaderBypassEnabled();
        //$$ boolean clearDepthForReplay = clearsMaskDepthForReplay(
        //$$         state.firstPerson, IrisCompat.isShaderActive(), exactTemporalReplay);
        //$$ if (clearDepthForReplay) {
        //$$     // mask.clear() already leaves the forward-Z depth at the far plane.
        //$$     // Exact Iris world replays keep it there so the composite owns occlusion.
        //$$ } else if (state.maskDepthPrepared) {
        //$$     // captureSceneDepth already populated mask depth for the fallback replay.
        //$$ } else if (sceneDepthCaptured && sceneDepthTarget != null) {
        //$$     // Real world depth captured before the renderItemInHand pass; works for both
        //$$     // no-Iris and Iris paths (Iris's finalPass binds only color, leaving depth alone).
        //$$     mask.copyDepthFrom(sceneDepthTarget);
        //$$     state.maskDepthPrepared = true;
        //$$ } else {
        //$$     RenderTarget mainTarget = mc.getMainRenderTarget();
        //$$     mask.copyDepthFrom(mainTarget);
        //$$     state.maskDepthPrepared = true;
        //$$ }
        //$$
        //$$ float maskScaleX = 1.0f;
        //$$ float maskScaleY = 1.0f;
        //$$ float maskOffsetX = 0.0f;
        //$$ float maskOffsetY = 0.0f;
        //$$ float sceneOffsetX = 0.0f;
        //$$ float sceneOffsetY = 0.0f;
        //$$ Matrix4f maskProjection = state.capturedProjectionMatrix4f;
        //$$ boolean projectionApplied = !packProjection.changesProjection();
        //$$ float jitterX = exactTemporalReplay ? packProjection.jitterX() : 0.0f;
        //$$ float jitterY = exactTemporalReplay ? packProjection.jitterY() : 0.0f;
        //$$ float scaleX = packProjection.scaleX();
        //$$ float scaleY = packProjection.scaleY();
        //$$ float zBias = IrisCompat.isShaderActive() && !state.firstPerson && !exactTemporalReplay
        //$$         ? IRIS_TAA_Z_BIAS : 0.0f;
        //$$ boolean changesProjection = scaleX != 1.0f || scaleY != 1.0f
        //$$         || jitterX != 0.0f || jitterY != 0.0f || zBias != 0.0f;
        //$$ if (state.capturedProjectionMatrix4fValid && changesProjection) {
        //$$     maskProjection = computeScaledProjection(state.capturedProjectionMatrix4f,
        //$$             scaleX, scaleY, jitterX, jitterY, zBias, SCRATCH_SCALED_PROJECTION);
        //$$     maskScaleX = scaleX;
        //$$     maskScaleY = scaleY;
        //$$     if (exactTemporalReplay) {
        //$$         maskOffsetX = packProjection.jitterX() * 0.5f;
        //$$         maskOffsetY = packProjection.jitterY() * 0.5f;
        //$$     }
        //$$     projectionApplied = true;
        //$$ }
        //$$ if (!state.firstPerson && IrisCompat.isShaderActive()
        //$$         && packProjection.exactTemporalJitter()) {
        //$$     sceneOffsetX = packProjection.jitterX() * 0.5f;
        //$$     sceneOffsetY = packProjection.jitterY() * 0.5f;
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
        //$$ state.lastSceneScaleX = state.firstPerson ? maskScaleX : scaleX;
        //$$ state.lastSceneScaleY = state.firstPerson ? maskScaleY : scaleY;
        //$$ state.lastMaskOffsetX = maskOffsetX;
        //$$ state.lastMaskOffsetY = maskOffsetY;
        //$$ state.lastSceneOffsetX = sceneOffsetX;
        //$$ state.lastSceneOffsetY = sceneOffsetY;
        //$$ state.exactDepthAlignment = state.firstPerson
        //$$         || !IrisCompat.isShaderActive()
        //$$         || (exactTemporalReplay && projectionApplied);
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
            encoder.writeToBuffer(state.scaledProjectionBuffer.slice(), data);
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

    private static boolean usesExactTemporalReplay(
            GlowCaptureState state, ShaderPackHint.ProjectionTransform transform) {
        return usesExactTemporalReplay(
                state.firstPerson, state.capturedProjectionMatrix4fValid, transform);
    }

    private static GlowCaptureState allocateState() {
        for (GlowCaptureState state : pool) {
            if (!state.active) return state;
        }
        GlowCaptureState state = new GlowCaptureState();
        pool.add(state);
        return state;
    }

    private static void releaseState(GlowCaptureState state) {
        state.resetFrame();
        if (state.maskTarget != null) {
            state.maskTarget.destroyBuffers();
            state.maskTarget = null;
        }
        //#if MC>=1_21_06
        if (state.scaledProjectionBuffer != null) {
            state.scaledProjectionBuffer.close();
            state.scaledProjectionBuffer = null;
            state.scaledProjectionSlice = null;
        }
        //#endif
        //#if MC>=1_26_02
        //$$ if (state.maskDepthForwardZTarget != null) {
        //$$     state.maskDepthForwardZTarget.destroyBuffers();
        //$$     state.maskDepthForwardZTarget = null;
        //$$ }
        //#endif
        //#if MC>=1_21_09
        state.captureDispatcher = null;
        // Shared RenderBuffers lives across all states — don't null it out or allocate per-state.
        //#else
        //$$ // Release the retained off-heap capture buffers (pooled across frames on 1.21.8).
        //$$ if (state.customBufferSource != null) {
        //$$     state.customBufferSource.free();
        //$$     state.customBufferSource = null;
        //$$ }
        //#endif
        // captureBuffers is a per-state allocation only on the pre-1.21.9 immediate-mode paths;
        // on 1.21.9+ the shared CaptureBuffers lives in GlowCaptureManager.
        //#if MC<1_21_09
        //$$ state.captureBuffers = null;
        //#endif
    }

    public static void clearAll() {
        for (GlowCaptureState state : pool) {
            releaseState(state);
        }
        if (sceneDepthTarget != null) {
            sceneDepthTarget.destroyBuffers();
            sceneDepthTarget = null;
        }
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
        activeStates.clear();
        currentCapture = null;
    }

    //#if MC>=1_21_09
    /** Iterate RenderBuffers' declared fields and close any {@link ByteBufferBuilder}
     *  instances found — RenderBuffers has no {@code close()} API and vanilla never
     *  discards one, but we recreate it on resource reload so the old builders must be
     *  freed.  Reflection is safe here because this runs only on reload/teardown,
     *  not per-frame. */
    private static void releaseSharedBuffers() {
        if (sharedCaptureBuffers == null) return;
        RenderBuffers rb = sharedCaptureBuffers;
        sharedCaptureBuffers = null;
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
    }
    //#endif
}
