package cn.spectra.gallium.glowoutline.capture;

import cn.spectra.gallium.glowoutline.IrisCompat;
import cn.spectra.gallium.glowoutline.ItemEffectConfig;
import cn.spectra.gallium.glowoutline.ItemEffectsManager;
//#if MC>=1_21_09
import cn.spectra.gallium.glowoutline.mixin.accessor.FeatureRenderDispatcherAccessor;
import cn.spectra.gallium.glowoutline.mixin.accessor.GameRendererAccessor;
//#endif
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.buffers.Std140Builder;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTexture;
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
    private static @Nullable GpuBufferSlice scaledProjectionSlice;
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
        if (sceneDepthCaptured) return;
        GpuTexture srcDepth = mainTarget.getDepthTexture();
        if (srcDepth == null) return;

        int w = mainTarget.width, h = mainTarget.height;
        if (sceneDepthTarget == null || sceneDepthTarget.width != w || sceneDepthTarget.height != h) {
            if (sceneDepthTarget != null) sceneDepthTarget.destroyBuffers();
            sceneDepthTarget = new TextureTarget("GlowSceneDepth", w, h, true);
            //#if MC<1_21_11
            //$$ // Same sampler-completeness rationale as the mask depth target in
            //$$ // beginItemCapture: keep useMipmaps=false on the single-mip depth view
            //$$ // so the composite shader's SceneDepthSampler reads real depth values
            //$$ // rather than the (1,1,1,1) returned by an incomplete sampler.
            //$$ sceneDepthTarget.getDepthTexture().setUseMipmaps(false);
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
    }

    public static @Nullable TextureTarget getSceneDepthTarget() {
        return sceneDepthTarget;
    }

    public static boolean beginItemCapture(ItemStack stack) {
        return beginItemCapture(stack, false);
    }

    public static boolean beginItemCapture(ItemStack stack, boolean firstPerson) {
        if (stack.isEmpty()) return false;
        if (!ItemEffectsManager.isActive()) return false;

        ItemEffectConfig cfg = ItemEffectsManager.getConfig(stack);
        if (cfg == null || cfg.shader().isEmpty()) return false;

        GlowCaptureState state = allocateState();
        state.active = true;
        state.firstPerson = firstPerson;
        state.config = cfg;
        if (state.capturedModelViewMatrix == null) state.capturedModelViewMatrix = new Matrix4f();
        state.capturedModelViewMatrix.set(RenderSystem.getModelViewMatrix());
        state.capturedModelViewMatrixValid = true;
        // Snapshot the Matrix4f form too. Vanilla pipelines hand setProjectionMatrix only a
        // GpuBufferSlice (already serialized), so we reconstruct via ProjectionMatrixTracker
        // which the per-version mixins keep up to date.
        state.capturedProjectionMatrix = RenderSystem.getProjectionMatrixBuffer();
        state.capturedProjectionType = RenderSystem.getProjectionType();
        state.capturedProjectionMatrix4fValid = ProjectionMatrixTracker.lookupInto(
                state.capturedProjectionMatrix, state.capturedProjectionMatrix4f) != null;

        Minecraft mc = Minecraft.getInstance();
        RenderTarget main = mc.getMainRenderTarget();
        if (main == null) return false;
        if (state.maskTarget == null) {
            // identityHashCode (not a slot index) gives each state a stable, unique label
            // for the lifetime of the JVM — survives pool shrinks where a recycled slot
            // index would otherwise collide with a freshly allocated state's name.
            state.maskTarget = new TextureTarget("GlowMask_" + Integer.toHexString(System.identityHashCode(state)),
                    main.width, main.height, true);
            //#if MC<1_21_11
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
        } else if (state.maskTarget.width != main.width || state.maskTarget.height != main.height) {
            state.maskTarget.resize(main.width, main.height);
            //#if MC<1_21_11
            //$$ state.maskTarget.getColorTexture().setUseMipmaps(false);
            //$$ state.maskTarget.getDepthTexture().setUseMipmaps(false);
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
        //#else
        //$$ if (state.captureBuffers == null || state.maskTarget == null) return;
        //#endif

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
            //#if MC>=1_21_09
            state.captureDispatcher.renderAllFeatures();
            state.captureBuffers.bufferSource().endBatch();
            state.captureBuffers.outlineBufferSource().endOutlineBatch();
            //#else
            //$$ if (state.customBufferSource != null) {
            //$$     state.customBufferSource.flush();
            //$$ } else {
            //$$     state.captureBuffers.bufferSource().endBatch();
            //$$ }
            //$$ state.captureBuffers.outlineBufferSource().endOutlineBatch();
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
    }

    /**
     * Pre-multiplies the passed projection by the matrix equivalent of shader-pack
     * {@code VertexDownscaling}: {@code gl_Position.xy = gl_Position.xy * scale - (1-scale) * gl_Position.w}.
     * Returns a slice into a reused buffer; the contents are valid until the next call.
     */
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
            scaledProjectionSlice = null;
        }
        sceneDepthCaptured = false;
        activeStates.clear();
        currentCapture = null;
    }
}