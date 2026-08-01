package cn.spectra.gallium.glowoutline.capture;

import cn.spectra.gallium.glowoutline.IrisCompat;
import cn.spectra.gallium.glowoutline.ItemEffectConfig;
import cn.spectra.gallium.glowoutline.ItemEffectsManager;
//#if MC>=1_21_09
import cn.spectra.gallium.glowoutline.mixin.accessor.FeatureRenderDispatcherAccessor;
import cn.spectra.gallium.glowoutline.mixin.accessor.GameRendererAccessor;
//#endif
//#if MC>=1_21_06
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.buffers.Std140Builder;
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
    private static @Nullable TextureTarget sceneDepthTarget;
    //#if MC>=1_26_02
    //$$ /** Forward-Z R32F mirror of {@link #sceneDepthTarget}'s reverse-Z depth, built each
    //$$  *  frame by {@link cn.spectra.gallium.glowoutline.shader.DepthFlipPipeline} after
    //$$  *  scene-depth capture. Bound as {@code SceneDepthSampler} on the Iris path so pack
    //$$  *  shaders see forward-Z values (pre-hand snapshot — Iris finalize would otherwise
    //$$  *  overwrite mainTarget.depth before composite). */
    //$$ private static @Nullable TextureTarget sceneDepthForwardZTarget;
    //$$ /** Forward-Z R32F mirror of vanilla {@code mainTarget.depth} as it stands AT composite
    //$$  *  time. Used on the no-Iris world path so held-item depth (written by
    //$$  *  {@code renderItemInHand}) is part of the sceneDepth sampled by the glow shader —
    //$$  *  this is what makes the player's hand correctly occlude glowing world items. Refreshed
    //$$  *  via {@link #ensureLiveSceneDepthForwardZTarget} + an explicit flip in
    //$$  *  {@link cn.spectra.gallium.glowoutline.shader.GlowComposite#drawGlow}. */
    //$$ private static @Nullable TextureTarget liveSceneDepthForwardZTarget;
    //#endif
    /** UBO holding our downscale-adjusted projection matrix when an Iris pack with internal
     *  scaling is active. Reused across captures within a frame; rewritten before each
     *  mask render. {@code null} until first use; disposed via the GlowResources hook.
     *  Only allocated on 1.21.6+ (GpuBuffer absent before 1.21.2; unused on 1.21.2–1.21.5,
     *  which set the scaled Matrix4f directly). */
    //#if MC>=1_21_06
    private static @Nullable GpuBuffer scaledProjectionBuffer;
    private static @Nullable GpuBufferSlice scaledProjectionSlice;
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

    public static List<GlowCaptureState> getActiveStates() {
        return activeStates;
    }

    public static void captureSceneDepth(RenderTarget mainTarget) {
        //#if MC>=1_21_05
        if (sceneDepthCaptured) return;
        GpuTexture srcDepth = mainTarget.getDepthTexture();
        if (srcDepth == null) return;

        int w = mainTarget.width, h = mainTarget.height;
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

        if (!IrisCompat.isShaderActive()) {
            for (GlowCaptureState state : activeStates) {
                if (state.maskTarget != null) {
                    encoder.copyTextureToTexture(srcDepth, state.maskTarget.getDepthTexture(), 0, 0, 0, 0, 0, w, h);
                }
            }
        }
        sceneDepthCaptured = true;
        //#if MC>=1_26_02
        //$$ // 26.2 reverse-Z: flip the just-captured sceneDepth into a Gallium-owned R32F
        //$$ // color target so the pack-author glow shader's SceneDepthSampler reads forward-Z
        //$$ // values. We pre-allocate (or resize) the target here so renderCapturedNodes can
        //$$ // skip allocation on the hot path; the flip writes (1 - reverseZdepth) per pixel.
        //$$ sceneDepthForwardZTarget = cn.spectra.gallium.glowoutline.shader.DepthFlipPipeline
        //$$         .ensureForwardZTarget(sceneDepthForwardZTarget, "GlowSceneDepthForwardZ", w, h);
        //$$ if (sceneDepthTarget.getDepthTextureView() != null) {
        //$$     cn.spectra.gallium.glowoutline.shader.DepthFlipPipeline.flip(
        //$$             sceneDepthTarget.getDepthTextureView(), sceneDepthForwardZTarget);
        //$$ }
        //#endif
        //#else
        //$$ if (sceneDepthCaptured) return;
        //$$ int w = mainTarget.width, h = mainTarget.height;
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
        //$$     if (state.maskTarget != null) state.maskTarget.copyDepthFrom(mainTarget);
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
        return sceneDepthTarget;
    }

    //#if MC>=1_26_02
    //$$ /** Forward-Z color target derived from {@link #sceneDepthTarget}. Bound as
    //$$  *  {@code SceneDepthSampler} on 26.2 so pack shaders read forward-Z values. */
    //$$ public static @Nullable TextureTarget getSceneDepthForwardZTarget() {
    //$$     return sceneDepthForwardZTarget;
    //$$ }
    //$$
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

        var encoder = RenderSystem.getDevice().createCommandEncoder();
        //#if MC>=1_26_02
        //$$ encoder.clearColorTexture(state.maskTarget.getColorTexture(), new org.joml.Vector4f(0.0F));
        //#else
        encoder.clearColorTexture(state.maskTarget.getColorTexture(), 0);
        //#endif

        if (state.firstPerson) {
            //#if MC>=1_26_02
            //$$ // 26.2 reverse-Z: 0.0 = far, 1.0 = near. Clear to far so no "implicit world"
            //$$ // pixels appear closer than the captured item depth.
            //$$ encoder.clearDepthTexture(state.maskTarget.getDepthTexture(), 0.0);
            //#else
            encoder.clearDepthTexture(state.maskTarget.getDepthTexture(), 1.0);
            //#endif
        } else if (!sceneDepthCaptured) {
            //#if MC>=1_26_02
            //$$ RenderTarget mainTarget = mc.gameRenderer.mainRenderTarget();
            //#else
            RenderTarget mainTarget = mc.getMainRenderTarget();
            //#endif
            encoder.copyTextureToTexture(
                    mainTarget.getDepthTexture(), state.maskTarget.getDepthTexture(),
                    0, 0, 0, 0, 0, mainTarget.width, mainTarget.height);
        } else if (IrisCompat.isShaderActive()) {
            //#if MC>=1_26_02
            //$$ encoder.clearDepthTexture(state.maskTarget.getDepthTexture(), 0.0);
            //#else
            encoder.clearDepthTexture(state.maskTarget.getDepthTexture(), 1.0);
            //#endif
        }

        var oldColor = RenderSystem.outputColorTextureOverride;
        var oldDepth = RenderSystem.outputDepthTextureOverride;
        RenderSystem.outputColorTextureOverride = state.maskTarget.getColorTextureView();
        RenderSystem.outputDepthTextureOverride = state.maskTarget.getDepthTextureView();

        // Decide what projection to use for the mask render. When an Iris pack with internal
        // scaling is active and we have the original Matrix4f, pre-multiply by the equivalent
        // of VertexDownscaling so the mask is rasterized into the same [0, scale]² subrect of
        // the depth buffer that the shader pack writes its world+entity output into. Without
        // that alignment, sceneDepth max-pool sampling on the body silhouette flips per
        // sub-pixel jitter and produces visible outline-edge wobble. With alignment our mask
        // and sceneDepth are pixel-coincident, so a 3x3 max-pool absorbs noise.
        GpuBufferSlice maskProjectionSlice = state.capturedProjectionMatrix;
        float maskScale = 1.0f;
        if (state.capturedProjectionMatrix4fValid
                && IrisCompat.isShaderActive()
                && IrisCompat.getShaderInternalScale() < 0.999f) {
            float scale = IrisCompat.getShaderInternalScale();
            GpuBufferSlice scaled = uploadScaledProjection(state.capturedProjectionMatrix4f, scale);
            if (scaled != null) {
                maskProjectionSlice = scaled;
                maskScale = scale;
            }
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

        var irisSnapshot = IrisCompat.setBypass(true);
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
            IrisCompat.restoreBypass(irisSnapshot);
            if (state.capturedModelViewMatrixValid && state.capturedModelViewMatrix != null) {
                RenderSystem.getModelViewStack().popMatrix();
            }
            if (restoreProjection) {
                RenderSystem.restoreProjectionMatrix();
            }
            RenderSystem.outputColorTextureOverride = oldColor;
            RenderSystem.outputDepthTextureOverride = oldDepth;
        }

        state.lastMaskScale = maskScale;
        state.capturedThisFrame = true;
        //#elseif MC>=1_21_06
        //$$ if (state.captureBuffers == null || state.maskTarget == null) return;
        //$$
        //$$ var encoder = RenderSystem.getDevice().createCommandEncoder();
        //$$ encoder.clearColorTexture(state.maskTarget.getColorTexture(), 0);
        //$$
        //$$ if (state.firstPerson) {
        //$$     encoder.clearDepthTexture(state.maskTarget.getDepthTexture(), 1.0);
        //$$ } else if (!sceneDepthCaptured) {
        //$$     RenderTarget mainTarget = mc.getMainRenderTarget();
        //$$     encoder.copyTextureToTexture(
        //$$             mainTarget.getDepthTexture(), state.maskTarget.getDepthTexture(),
        //$$             0, 0, 0, 0, 0, mainTarget.width, mainTarget.height);
        //$$ } else if (IrisCompat.isShaderActive()) {
        //$$     encoder.clearDepthTexture(state.maskTarget.getDepthTexture(), 1.0);
        //$$ }
        //$$
        //$$ var oldColor = RenderSystem.outputColorTextureOverride;
        //$$ var oldDepth = RenderSystem.outputDepthTextureOverride;
        //$$ RenderSystem.outputColorTextureOverride = state.maskTarget.getColorTextureView();
        //$$ RenderSystem.outputDepthTextureOverride = state.maskTarget.getDepthTextureView();
        //$$
        //$$ GpuBufferSlice maskProjectionSlice = state.capturedProjectionMatrix;
        //$$ float maskScale = 1.0f;
        //$$ if (state.capturedProjectionMatrix4fValid
        //$$         && IrisCompat.isShaderActive()
        //$$         && IrisCompat.getShaderInternalScale() < 0.999f) {
        //$$     float scale = IrisCompat.getShaderInternalScale();
        //$$     GpuBufferSlice scaled = uploadScaledProjection(state.capturedProjectionMatrix4f, scale);
        //$$     if (scaled != null) {
        //$$         maskProjectionSlice = scaled;
        //$$         maskScale = scale;
        //$$     }
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
        //$$ var irisSnapshot = IrisCompat.setBypass(true);
        //$$ try {
        //$$     if (state.customBufferSource != null) {
        //$$         state.customBufferSource.flush();
        //$$     } else {
        //$$         state.captureBuffers.bufferSource().endBatch();
        //$$     }
        //$$     state.captureBuffers.outlineBufferSource().endOutlineBatch();
        //$$ } finally {
        //$$     IrisCompat.restoreBypass(irisSnapshot);
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
        //$$ state.lastMaskScale = maskScale;
        //$$ state.capturedThisFrame = true;
        //#elseif MC>=1_21_05
        //$$ // 1.21.5: no outputColorTextureOverride. DelayingMultiBufferSource.flushToTarget()
        //$$ // manually uploads meshes and opens a RenderPass targeting the mask textures.
        //$$ if (state.captureBuffers == null || state.maskTarget == null) return;
        //$$
        //$$ var encoder = RenderSystem.getDevice().createCommandEncoder();
        //$$ encoder.clearColorTexture(state.maskTarget.getColorTexture(), 0);
        //$$
        //$$ // Mask depth strategy mirrors the >=1_21_06 branch above; one local twist:
        //$$ // 1.21.5's GameRenderer.renderLevel issues clearDepthTexture(mainDepth, 1.0)
        //$$ // right after levelRenderer.renderLevel and BEFORE renderItemInHand — so by
        //$$ // the time the renderLevel TAIL hook runs, mainTarget.getDepthTexture() is
        //$$ // the *cleared* depth (1.0 + held item), not world depth. Copying from it
        //$$ // would defeat occlusion entirely. captureSceneDepth handles this by copying
        //$$ // the pre-clear world depth into every active state's mask depth ahead of time.
        //$$ if (state.firstPerson) {
        //$$     encoder.clearDepthTexture(state.maskTarget.getDepthTexture(), 1.0);
        //$$ } else if (!sceneDepthCaptured) {
        //$$     RenderTarget mainTarget = mc.getMainRenderTarget();
        //$$     encoder.copyTextureToTexture(
        //$$             mainTarget.getDepthTexture(), state.maskTarget.getDepthTexture(),
        //$$             0, 0, 0, 0, 0, mainTarget.width, mainTarget.height);
        //$$ } else if (IrisCompat.isShaderActive()) {
        //$$     encoder.clearDepthTexture(state.maskTarget.getDepthTexture(), 1.0);
        //$$ }
        //$$ // else: captureSceneDepth already populated mask depth with the pre-clear world depth.
        //$$
        //$$ if (state.capturedModelViewMatrixValid && state.capturedModelViewMatrix != null) {
        //$$     RenderSystem.getModelViewStack().pushMatrix();
        //$$     RenderSystem.getModelViewStack().set(state.capturedModelViewMatrix);
        //$$ }
        //$$
        //$$ // Apply VertexDownscaling-equivalent scale; see >=1_21_06 branch above for full rationale.
        //$$ // 1.21.5 lacks GpuBufferSlice/Std140Builder so we can't share the >=1.21.6 UBO upload
        //$$ // path; instead we set the scaled Matrix4f directly via the (Matrix4f, ProjectionType)
        //$$ // overload of setProjectionMatrix.
        //$$ float maskScale = 1.0f;
        //$$ Matrix4f maskProjection = state.capturedProjectionMatrix4f;
        //$$ if (state.capturedProjectionMatrix4fValid
        //$$         && IrisCompat.isShaderActive()
        //$$         && IrisCompat.getShaderInternalScale() < 0.999f) {
        //$$     float scale = IrisCompat.getShaderInternalScale();
        //$$     maskProjection = computeScaledProjection(state.capturedProjectionMatrix4f,
        //$$             scale, SCRATCH_SCALED_PROJECTION);
        //$$     maskScale = scale;
        //$$ }
        //$$ boolean restoreProj = state.capturedProjectionMatrix4fValid
        //$$         && state.capturedProjectionType != null;
        //$$ if (restoreProj) {
        //$$     RenderSystem.backupProjectionMatrix();
        //$$     RenderSystem.setProjectionMatrix(maskProjection,
        //$$             state.capturedProjectionType);
        //$$ }
        //$$
        //$$ var irisSnapshot = IrisCompat.setBypass(true);
        //$$ try {
        //$$     if (state.customBufferSource != null) {
        //$$         state.customBufferSource.flushToTarget(state.maskTarget);
        //$$     } else {
        //$$         state.captureBuffers.bufferSource().endBatch();
        //$$     }
        //$$     state.captureBuffers.outlineBufferSource().endOutlineBatch();
        //$$ } finally {
        //$$     IrisCompat.restoreBypass(irisSnapshot);
        //$$     if (restoreProj) {
        //$$         RenderSystem.restoreProjectionMatrix();
        //$$     }
        //$$ }
        //$$
        //$$ if (state.capturedModelViewMatrixValid && state.capturedModelViewMatrix != null) {
        //$$     RenderSystem.getModelViewStack().popMatrix();
        //$$ }
        //$$
        //$$ state.lastMaskScale = maskScale;
        //$$ state.capturedThisFrame = true;
        //#else
        //$$ if (state.maskTarget == null) return;
        //$$ TextureTarget mask = state.maskTarget;
        //$$ mask.setClearColor(0.0F, 0.0F, 0.0F, 0.0F);
        //#if MC>=1_21_02
        //$$ mask.clear();
        //#else
        //$$ mask.clear(net.minecraft.client.Minecraft.ON_OSX);
        //#endif
        //$$ // Mask depth strategy mirrors the >=1_21_06 branch above; see comments there.
        //$$ // Local difference: 1.21.4 uses RenderTarget.copyDepthFrom(sceneDepthTarget) instead
        //$$ // of CommandEncoder.copyTextureToTexture (no GpuTexture API on this version).
        //$$ if (state.firstPerson || IrisCompat.isShaderActive()) {
        //$$     // depth already 1.0 from mask.clear()
        //$$ } else if (sceneDepthCaptured && sceneDepthTarget != null) {
        //$$     mask.copyDepthFrom(sceneDepthTarget);
        //$$ } else {
        //$$     RenderTarget mainTarget = mc.getMainRenderTarget();
        //$$     mask.copyDepthFrom(mainTarget);
        //$$ }
        //$$
        //$$ float maskScale = 1.0f;
        //$$ Matrix4f maskProjection = state.capturedProjectionMatrix4f;
        //$$ if (state.capturedProjectionMatrix4fValid
        //$$         && IrisCompat.isShaderActive()
        //$$         && IrisCompat.getShaderInternalScale() < 0.999f) {
        //$$     float scale = IrisCompat.getShaderInternalScale();
        //$$     maskProjection = computeScaledProjection(state.capturedProjectionMatrix4f,
        //$$             scale, SCRATCH_SCALED_PROJECTION);
        //$$     maskScale = scale;
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
        //$$ var irisSnapshot = IrisCompat.setBypass(true);
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
        //$$     IrisCompat.restoreBypass(irisSnapshot);
        //$$     if (backedUpProj) RenderSystem.restoreProjectionMatrix();
        //$$     if (pushedModelView) RenderSystem.getModelViewStack().popMatrix();
        //$$ }
        //$$ state.lastMaskScale = maskScale;
        //$$ state.capturedThisFrame = true;
        //#endif
    }

    /**
     * Pre-multiplies the passed projection by the matrix equivalent of shader-pack
     * {@code VertexDownscaling}: {@code gl_Position.xy = gl_Position.xy * scale - (1-scale) * gl_Position.w}.
     * Returns a slice into a reused buffer; the contents are valid until the next call.
     */
    //#if MC>=1_21_06
    private static @Nullable GpuBufferSlice uploadScaledProjection(Matrix4f baseProjection, float scale) {
        if (scaledProjectionBuffer == null) {
            scaledProjectionBuffer = RenderSystem.getDevice().createBuffer(
                    () -> "Glow Scaled Projection",
                    GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_COPY_DST,
                    RenderSystem.PROJECTION_MATRIX_UBO_SIZE);
            // Buffer offsets stayed `int` in 1.21.10 and were widened to `long` in 1.21.11.
            // Passing `0` (a literal int) is accepted by both signatures via implicit widening.
            scaledProjectionSlice = scaledProjectionBuffer.slice(0, RenderSystem.PROJECTION_MATRIX_UBO_SIZE);
        }
        Matrix4f result = computeScaledProjection(baseProjection, scale, SCRATCH_SCALED_PROJECTION);

        try (MemoryStack stack = MemoryStack.stackPush()) {
            ByteBuffer data = Std140Builder.onStack(stack, RenderSystem.PROJECTION_MATRIX_UBO_SIZE)
                    .putMat4f(result).get();
            RenderSystem.getDevice().createCommandEncoder().writeToBuffer(scaledProjectionBuffer.slice(), data);
        }
        return scaledProjectionSlice;
    }
    //#endif

    /**
     * Writes {@code S * baseProjection} into {@code dest} and returns it, where {@code S} is the
     * matrix equivalent of shader-pack {@code VertexDownscaling}. After the perspective divide
     * this maps NDC -> NDC * scale - (1 - scale), the same {@code [0, scale]²} subrect Iris
     * produces in the pack vsh. Using the full 4x4 form keeps us robust against non-standard
     * projection matrices (cubemaps, certain mods). Exposed package-private for tests.
     */
    static Matrix4f computeScaledProjection(Matrix4f baseProjection, float scale, Matrix4f dest) {
        float t = -(1.0f - scale);
        // S * baseProjection. JOML stores column-major, so the constructor below lists columns.
        // Layout reads as a transform with diag(scale, scale, 1, 1) and translation (t, t, 0)
        // applied after the perspective basis — equivalent to xy *= scale; xy += t * w.
        dest.set(
                scale, 0,     0, 0,
                0,     scale, 0, 0,
                0,     0,     1, 0,
                t,     t,     0, 1);
        return dest.mul(baseProjection);
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
        //$$ if (sceneDepthForwardZTarget != null) {
        //$$     sceneDepthForwardZTarget.destroyBuffers();
        //$$     sceneDepthForwardZTarget = null;
        //$$ }
        //$$ if (liveSceneDepthForwardZTarget != null) {
        //$$     liveSceneDepthForwardZTarget.destroyBuffers();
        //$$     liveSceneDepthForwardZTarget = null;
        //$$ }
        //#endif
        //#if MC>=1_21_06
        if (scaledProjectionBuffer != null) {
            scaledProjectionBuffer.close();
            scaledProjectionBuffer = null;
            scaledProjectionSlice = null;
        }
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