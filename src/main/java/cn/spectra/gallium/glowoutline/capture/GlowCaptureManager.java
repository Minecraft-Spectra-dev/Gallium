package cn.spectra.gallium.glowoutline.capture;

import cn.spectra.gallium.glowoutline.IrisCompat;
import cn.spectra.gallium.glowoutline.ItemEffectConfig;
import cn.spectra.gallium.glowoutline.ItemEffectsManager;
//#if MC>=1_21_09
import cn.spectra.gallium.glowoutline.mixin.accessor.FeatureRenderDispatcherAccessor;
import cn.spectra.gallium.glowoutline.mixin.accessor.GameRendererAccessor;
//#endif
import com.mojang.blaze3d.buffers.GpuBuffer;
//#if MC>=1_21_06
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.buffers.Std140Builder;
//#endif
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
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

    /** Hard cap on world capture states. Each holds a screen-sized TextureTarget plus RenderBuffers,
     *  so without this cap a brief peak (many on-screen item entities + armor pieces in one frame)
     *  pins VRAM forever. Surplus states beyond the mark are released at frame end.
     *  <p>
     *  At 1080p one state's mask target is ~8MB; the cap is sized so a steady-state scene with
     *  hand items + armor + a handful of dropped items + frames stays in pool. Peaks beyond the
     *  cap (>{@value} simultaneously-glowing items in a single frame) still render correctly —
     *  surplus states are allocated transient and freed at the next {@link #beginFrame()}, paying
     *  an alloc/destroy round-trip but bounding VRAM growth. */
    private static final int POOL_HIGH_WATER_MARK = 32;

    private static final List<GlowCaptureState> pool = new ArrayList<>();
    private static final List<GlowCaptureState> activeStates = new ArrayList<>();

    private static @Nullable GlowCaptureState currentCapture;
    private static int suppressDepth;
    private static boolean sceneDepthCaptured;
    private static @Nullable TextureTarget sceneDepthTarget;
    /** UBO holding our downscale-adjusted projection matrix when an Iris pack with internal
     *  scaling is active. Reused across captures within a frame; rewritten before each
     *  mask render. {@code null} until first use; disposed via the GlowResources hook. */
    private static @Nullable GpuBuffer scaledProjectionBuffer;
    //#if MC>=1_21_06
    private static @Nullable GpuBufferSlice scaledProjectionSlice;
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
            sceneDepthTarget = new TextureTarget("GlowSceneDepth", w, h, true);
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
        //#else
        //$$ if (sceneDepthCaptured) return;
        //$$ int w = mainTarget.width, h = mainTarget.height;
        //$$ if (sceneDepthTarget == null || sceneDepthTarget.width != w || sceneDepthTarget.height != h) {
        //$$     if (sceneDepthTarget != null) sceneDepthTarget.destroyBuffers();
        //$$     sceneDepthTarget = new TextureTarget(w, h, true);
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
        //$$ state.capturedProjectionType = RenderSystem.getProjectionType();
        //$$ state.capturedProjectionMatrix4fValid = true;
        //$$
        //$$ if (state.maskTarget == null) {
        //$$     state.maskTarget = new TextureTarget(main.width, main.height, true);
        //$$ } else if (state.maskTarget.width != main.width || state.maskTarget.height != main.height) {
        //$$     state.maskTarget.resize(main.width, main.height);
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
        RenderTarget main = mc.getMainRenderTarget();
        if (main == null) return false;

        GlowCaptureState state = allocateState();
        state.active = true;
        state.firstPerson = firstPerson;
        state.config = cfg;
        if (state.capturedModelViewMatrix == null) state.capturedModelViewMatrix = new Matrix4f();
        state.capturedModelViewMatrix.set(RenderSystem.getModelViewMatrix());
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
                    main.width, main.height, true);
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

        if (state.captureBuffers == null) {
            state.captureBuffers = new RenderBuffers(1);
        }

        //#if MC>=1_21_09
        if (state.captureDispatcher == null) {
            FeatureRenderDispatcher mainDispatcher = mc.gameRenderer.getFeatureRenderDispatcher();
            var accessor = (FeatureRenderDispatcherAccessor) mainDispatcher;
            //#if MC>=1_26_00
            var gameAccessor = (GameRendererAccessor) mc.gameRenderer;
            state.captureDispatcher = new FeatureRenderDispatcher(
                    new SubmitNodeStorage(),
                    mc.getModelManager(),
                    state.captureBuffers.bufferSource(),
                    accessor.gallium$getAtlasManager(),
                    state.captureBuffers.outlineBufferSource(),
                    state.captureBuffers.crumblingBufferSource(),
                    mc.font,
                    gameAccessor.gallium$getGameRenderState()
            );
            //#else
            //$$ state.captureDispatcher = new FeatureRenderDispatcher(
            //$$         new SubmitNodeStorage(),
            //$$         mc.getBlockRenderer(),
            //$$         state.captureBuffers.bufferSource(),
            //$$         accessor.gallium$getAtlasManager(),
            //$$         state.captureBuffers.outlineBufferSource(),
            //$$         state.captureBuffers.crumblingBufferSource(),
            //$$         mc.font
            //$$ );
            //#endif
        } else {
            state.captureDispatcher.getSubmitNodeStorage().clear();
        }
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
        return currentCapture.captureDispatcher.getSubmitNodeStorage();
    }
    //#endif

    public static void renderCapturedNodes(GlowCaptureState state, Minecraft mc) {
        //#if MC>=1_21_09
        if (state.captureDispatcher == null || state.captureBuffers == null || state.maskTarget == null) return;

        var encoder = RenderSystem.getDevice().createCommandEncoder();
        encoder.clearColorTexture(state.maskTarget.getColorTexture(), 0);

        if (state.firstPerson) {
            encoder.clearDepthTexture(state.maskTarget.getDepthTexture(), 1.0);
        } else if (!sceneDepthCaptured) {
            RenderTarget mainTarget = mc.getMainRenderTarget();
            encoder.copyTextureToTexture(
                    mainTarget.getDepthTexture(), state.maskTarget.getDepthTexture(),
                    0, 0, 0, 0, 0, mainTarget.width, mainTarget.height);
        } else if (IrisCompat.isShaderActive()) {
            encoder.clearDepthTexture(state.maskTarget.getDepthTexture(), 1.0);
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
        // and sceneDepth are pixel-coincident, so a 3x3 max-pool absorbs noise as it did in
        // the pre-scale-support build.
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
            state.captureDispatcher.renderAllFeatures();
            state.captureBuffers.bufferSource().endBatch();
            state.captureBuffers.outlineBufferSource().endOutlineBatch();
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
        //$$ // Mask depth strategy on 1.21.5 mirrors the >=1.21.6 branch above:
        //$$ //   * firstPerson: clear to 1.0 (no occlusion within held item).
        //$$ //   * sceneDepthCaptured (the common world-item path): captureSceneDepth already
        //$$ //     copied the pre-clear world depth into every existing state's mask depth, so
        //$$ //     don't overwrite it here. Critically, on 1.21.5 GameRenderer.renderLevel issues
        //$$ //     a clearDepthTexture(mainDepth, 1.0) right after levelRenderer.renderLevel and
        //$$ //     before renderItemInHand — so by the time our renderLevel TAIL hook runs,
        //$$ //     mainTarget.getDepthTexture() is the *cleared* depth (1.0 + held-item), NOT
        //$$ //     world depth. Copying from it would defeat depth-test occlusion entirely.
        //$$ //   * Iris shader active: clear to 1.0 and rely on sceneDepthTarget for the
        //$$ //     composite-time depth comparison (Iris rewrites main depth via deferred
        //$$ //     pipeline so its post-pipeline value isn't comparable against item z).
        //$$ //   * Fallback (sceneDepth not yet captured this frame): copy from main as a
        //$$ //     last resort. This branch should not normally trigger on world items because
        //$$ //     the captureSceneDepth hook fires before world depth is wiped.
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
        //$$ // Restore the projection matrix that was active during capture so the
        //$$ // replayed geometry lands at the same screen position. When an Iris pack
        //$$ // with internal scaling (e.g. Kappa/Nostalgia VertexDownscaling) is active,
        //$$ // pre-multiply by the scale matrix so the mask is rasterized into the same
        //$$ // [0, scale]² subrect that the shader pack produces — otherwise the mask is
        //$$ // full-resolution while sceneDepthTarget (sampled by the composite shader as
        //$$ // SceneDepthSampler) is in shader-pack space, and the per-pixel depth lookup
        //$$ // reads from the wrong place, producing a misaligned outline.
        //$$ //
        //$$ // 1.21.5 lacks GpuBufferSlice/Std140Builder so we can't share the >=1.21.6
        //$$ // UBO upload path; instead we set the scaled Matrix4f directly via the
        //$$ // (Matrix4f, ProjectionType) overload of setProjectionMatrix.
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
        //$$ mask.clear();
        //$$ // Mask depth strategy mirrors the >=1.21.6 branch:
        //$$ //   firstPerson  → keep depth=1.0 from clear (no occlusion within held item)
        //$$ //   Iris active  → keep depth=1.0; Iris rewrites mainTarget depth in its deferred
        //$$ //                  pipeline so the pre-pipeline scene depth can't be LEQUAL-compared
        //$$ //                  against vanilla item z. Composite shader does occlusion via
        //$$ //                  SceneDepthSampler (which we wire to sceneDepthTarget on Iris path).
        //$$ //                  Without this branch the LEQUAL test in flushToTarget bounces in
        //$$ //                  sub-pixel against an unrelated scene depth → edge jitter every frame.
        //$$ //   else (vanilla, scene depth captured) → mask depth = pre-clear world depth, so
        //$$ //                  flushToTarget LEQUAL only writes item pixels that are in front of
        //$$ //                  world geometry → the silhouette gets occluded by walls correctly.
        //$$ //   else (fallback when capture didn't run this frame) → copy live mainTarget depth.
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
        //#if MC>=1_21_09
        state.captureDispatcher = null;
        //#else
        //$$ // Release the retained off-heap capture buffers (pooled across frames on 1.21.8).
        //$$ if (state.customBufferSource != null) {
        //$$     state.customBufferSource.free();
        //$$     state.customBufferSource = null;
        //$$ }
        //#endif
        state.captureBuffers = null;
    }

    public static void clearAll() {
        for (GlowCaptureState state : pool) {
            releaseState(state);
        }
        if (sceneDepthTarget != null) {
            sceneDepthTarget.destroyBuffers();
            sceneDepthTarget = null;
        }
        if (scaledProjectionBuffer != null) {
            scaledProjectionBuffer.close();
            scaledProjectionBuffer = null;
            //#if MC>=1_21_06
            scaledProjectionSlice = null;
            //#endif
        }
        sceneDepthCaptured = false;
        activeStates.clear();
        currentCapture = null;
    }
}