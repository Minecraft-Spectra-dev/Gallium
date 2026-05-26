package cn.spectra.gallium.glowoutline.capture;

import cn.spectra.gallium.glowoutline.IrisCompat;
import cn.spectra.gallium.glowoutline.ItemEffectConfig;
import cn.spectra.gallium.glowoutline.ItemEffectsManager;
import cn.spectra.gallium.glowoutline.mixin.accessor.FeatureRenderDispatcherAccessor;
import cn.spectra.gallium.glowoutline.mixin.accessor.GameRendererAccessor;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTexture;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderBuffers;
import net.minecraft.client.renderer.SubmitNodeStorage;
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher;
import net.minecraft.world.item.ItemStack;
import org.joml.Matrix4f;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

public final class GlowCaptureManager {

    /** Hard cap on world capture states. Each holds a screen-sized TextureTarget plus RenderBuffers,
     *  so without this cap a brief peak (many on-screen item entities + armor pieces in one frame)
     *  pins VRAM forever. Surplus states beyond the mark are released at frame end. */
    private static final int POOL_HIGH_WATER_MARK = 16;

    private static final List<GlowCaptureState> pool = new ArrayList<>();
    private static final List<GlowCaptureState> activeStates = new ArrayList<>();

    private static @Nullable GlowCaptureState currentCapture;
    private static int suppressDepth;
    private static boolean sceneDepthCaptured;
    private static @Nullable TextureTarget sceneDepthTarget;

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
        state.capturedModelViewMatrix = new Matrix4f(RenderSystem.getModelViewMatrix());
        state.capturedProjectionMatrix = RenderSystem.getProjectionMatrixBuffer();
        state.capturedProjectionType = RenderSystem.getProjectionType();

        Minecraft mc = Minecraft.getInstance();
        RenderTarget main = mc.getMainRenderTarget();
        if (state.maskTarget == null) {
            state.maskTarget = new TextureTarget("GlowMask_" + state.slot, main.width, main.height, true);
        } else if (state.maskTarget.width != main.width || state.maskTarget.height != main.height) {
            state.maskTarget.resize(main.width, main.height);
        }

        if (state.captureBuffers == null) {
            state.captureBuffers = new RenderBuffers(1);
        }

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

    public static @Nullable SubmitNodeStorage captureStorageForCurrent() {
        if (currentCapture == null || suppressDepth > 0) return null;
        if (currentCapture.captureDispatcher == null) return null;
        return currentCapture.captureDispatcher.getSubmitNodeStorage();
    }

    public static void renderCapturedNodes(GlowCaptureState state, Minecraft mc) {
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

        boolean restoreProjection = state.capturedProjectionMatrix != null && state.capturedProjectionType != null;
        if (restoreProjection) {
            RenderSystem.backupProjectionMatrix();
            RenderSystem.setProjectionMatrix(state.capturedProjectionMatrix, state.capturedProjectionType);
        }

        if (state.capturedModelViewMatrix != null) {
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
            if (state.capturedModelViewMatrix != null) {
                RenderSystem.getModelViewStack().popMatrix();
            }
            if (restoreProjection) {
                RenderSystem.restoreProjectionMatrix();
            }
            RenderSystem.outputColorTextureOverride = oldColor;
            RenderSystem.outputDepthTextureOverride = oldDepth;
        }

        state.capturedThisFrame = true;
    }

    private static GlowCaptureState allocateState() {
        for (GlowCaptureState state : pool) {
            if (!state.active) return state;
        }
        GlowCaptureState state = new GlowCaptureState();
        state.slot = pool.size();
        pool.add(state);
        return state;
    }

    private static void releaseState(GlowCaptureState state) {
        state.resetFrame();
        if (state.maskTarget != null) {
            state.maskTarget.destroyBuffers();
            state.maskTarget = null;
        }
        state.captureDispatcher = null;
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
        sceneDepthCaptured = false;
        activeStates.clear();
        currentCapture = null;
    }
}