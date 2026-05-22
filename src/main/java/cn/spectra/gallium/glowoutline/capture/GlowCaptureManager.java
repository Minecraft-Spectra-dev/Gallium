package cn.spectra.gallium.glowoutline.capture;

import cn.spectra.gallium.glowoutline.IrisCompat;
import cn.spectra.gallium.glowoutline.ItemEffectConfig;
import cn.spectra.gallium.glowoutline.ItemEffectsManager;
import cn.spectra.gallium.glowoutline.mixin.accessor.FeatureRenderDispatcherAccessor;
import cn.spectra.gallium.glowoutline.mixin.accessor.GameRendererAccessor;
import com.mojang.blaze3d.ProjectionType;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTexture;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.RenderBuffers;
import net.minecraft.client.renderer.SubmitNodeStorage;
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import org.joml.Matrix4f;
import org.jspecify.annotations.Nullable;

public final class GlowCaptureManager {

    public static final GlowCaptureState MAIN = new GlowCaptureState(InteractionHand.MAIN_HAND);
    public static final GlowCaptureState OFF = new GlowCaptureState(InteractionHand.OFF_HAND);

    private static @Nullable InteractionHand activeHand;
    private static int suppressDepth;
    private static boolean sceneDepthCaptured;
    private static @Nullable TextureTarget sceneDepthTarget;

    private GlowCaptureManager() {}

    public static void beginFrame(Minecraft mc, LocalPlayer player) {
        MAIN.resetFrame();
        OFF.resetFrame();
        activeHand = null;
        suppressDepth = 0;
        sceneDepthCaptured = false;

        if (!ItemEffectsManager.isActive() || player == null) return;

        setupState(mc, MAIN, player.getMainHandItem());
        setupState(mc, OFF, player.getOffhandItem());
    }

    public static void captureSceneDepth(RenderTarget mainTarget) {
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
            if (isActive(MAIN) && MAIN.maskTarget != null) {
                encoder.copyTextureToTexture(srcDepth, MAIN.maskTarget.getDepthTexture(), 0, 0, 0, 0, 0, w, h);
            }
            if (isActive(OFF) && OFF.maskTarget != null) {
                encoder.copyTextureToTexture(srcDepth, OFF.maskTarget.getDepthTexture(), 0, 0, 0, 0, 0, w, h);
            }
        }
        sceneDepthCaptured = true;
    }

    public static @Nullable TextureTarget getSceneDepthTarget() {
        return sceneDepthTarget;
    }

    private static void setupState(Minecraft mc, GlowCaptureState state, ItemStack stack) {
        if (stack.isEmpty()) return;
        ItemEffectConfig cfg = ItemEffectsManager.getConfig(stack);
        if (cfg.intensity() <= 0) return;

        state.item = stack.copy();
        state.config = cfg;

        RenderTarget main = mc.getMainRenderTarget();
        if (state.maskTarget == null) {
            state.maskTarget = new TextureTarget("GlowMask_" + state.hand.name(), main.width, main.height, true);
        } else if (state.maskTarget.width != main.width || state.maskTarget.height != main.height) {
            state.maskTarget.resize(main.width, main.height);
        }

        if (state.captureBuffers == null) {
            state.captureBuffers = new RenderBuffers(1);
        }

        if (state.captureDispatcher == null) {
            FeatureRenderDispatcher main_dispatcher = mc.gameRenderer.getFeatureRenderDispatcher();
            var accessor = (FeatureRenderDispatcherAccessor) main_dispatcher;
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
        } else {
            state.captureDispatcher.getSubmitNodeStorage().clear();
        }
    }

    public static boolean isActive(GlowCaptureState state) {
        return state.config != null && state.maskTarget != null && state.captureDispatcher != null;
    }

    public static void beginHandSubmit(InteractionHand hand, ItemStack stack) {
        activeHand = stack.isEmpty() ? null : hand;
        if (activeHand != null) {
            GlowCaptureState state = stateFor(activeHand);
            state.capturedModelViewMatrix = new Matrix4f(RenderSystem.getModelViewMatrix());
            state.capturedProjectionMatrix = RenderSystem.getProjectionMatrixBuffer();
            state.capturedProjectionType = RenderSystem.getProjectionType();
        }
    }

    public static void endHandSubmit() {
        activeHand = null;
    }

    public static void beginSuppress() { suppressDepth++; }
    public static void endSuppress() { if (suppressDepth > 0) suppressDepth--; }

    public static @Nullable InteractionHand activeHand() { return activeHand; }

    public static @Nullable SubmitNodeStorage captureStorageForActiveHand() {
        if (activeHand == null || suppressDepth > 0) return null;
        GlowCaptureState state = stateFor(activeHand);
        if (!isActive(state)) return null;
        return state.captureDispatcher.getSubmitNodeStorage();
    }

    public static void renderCapturedNodes(GlowCaptureState state, Minecraft mc) {
        if (state.captureDispatcher == null || state.captureBuffers == null || state.maskTarget == null) return;

        var encoder = RenderSystem.getDevice().createCommandEncoder();
        encoder.clearColorTexture(state.maskTarget.getColorTexture(), 0);

        if (!sceneDepthCaptured) {
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

        boolean irisOldBypass = IrisCompat.setBypass(true);
        try {
            state.captureDispatcher.renderAllFeatures();
            state.captureBuffers.bufferSource().endBatch();
            state.captureBuffers.outlineBufferSource().endOutlineBatch();
        } finally {
            IrisCompat.restoreBypass(irisOldBypass);
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

    public static GlowCaptureState stateFor(InteractionHand hand) {
        return hand == InteractionHand.MAIN_HAND ? MAIN : OFF;
    }
}
