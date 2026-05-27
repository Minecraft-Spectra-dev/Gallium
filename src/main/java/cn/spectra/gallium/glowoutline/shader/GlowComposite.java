package cn.spectra.gallium.glowoutline.shader;

import cn.spectra.gallium.glowoutline.IrisCompat;
import cn.spectra.gallium.glowoutline.capture.GlowCaptureManager;
import cn.spectra.gallium.glowoutline.capture.GlowCaptureState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import java.util.OptionalInt;
import net.minecraft.client.Minecraft;
import org.jspecify.annotations.Nullable;

/**
 * Owns the GPU resources used for the world-space glow composite pass.
 * Centralizing them here lets {@link GlowResources} dispose them on resource reload.
 */
public final class GlowComposite {

    @Nullable private static TextureTarget tempColorTarget;
    @Nullable private static GlowUniformBuffer uniformBuffer;

    static {
        GlowResources.register(GlowComposite::dispose);
    }

    private GlowComposite() {}

    public static boolean hasAnyValidCapture() {
        for (GlowCaptureState state : GlowCaptureManager.getActiveStates()) {
            if (state.capturedThisFrame && state.config != null && state.maskTarget != null) return true;
        }
        return false;
    }

    public static void composite(Minecraft minecraft, RenderTarget mainTarget) {
        int w = mainTarget.width;
        int h = mainTarget.height;

        if (tempColorTarget == null || tempColorTarget.width != w || tempColorTarget.height != h) {
            if (tempColorTarget != null) tempColorTarget.destroyBuffers();
            tempColorTarget = new TextureTarget("GlowColor", w, h, false);
        }
        if (uniformBuffer == null) uniformBuffer = new GlowUniformBuffer("Glow Uniform Buffer");

        RenderSystem.getDevice().createCommandEncoder().copyTextureToTexture(
                mainTarget.getColorTexture(), tempColorTarget.getColorTexture(),
                0, 0, 0, 0, 0, w, h);

        for (GlowCaptureState state : GlowCaptureManager.getActiveStates()) {
            drawGlow(state, minecraft, mainTarget);
        }
    }

    private static void drawGlow(GlowCaptureState state, Minecraft minecraft, RenderTarget mainTarget) {
        if (!state.capturedThisFrame || state.config == null || state.maskTarget == null) return;

        GlowCaptureManager.renderCapturedNodes(state, minecraft);

        TextureTarget mask = state.maskTarget;
        if (mask.getColorTextureView() == null || mask.getDepthTextureView() == null) return;

        RenderPipeline pipeline = GlowPipeline.get(state.config.shader());
        if (pipeline == null) return;

        int w = mainTarget.width;
        int h = mainTarget.height;
        // ShaderAlign uniform: (mask uv multiplier, scene-depth uv multiplier, reserved,
        // reserved). When the mask was rasterized into the same [0, scale]² subrect Iris
        // writes its world+entity output into (state.lastMaskScale < 1), shader uvs must
        // be multiplied by that scale to read the right world ray. lastMaskScale==1
        // (no Iris, or 100% scale, or projection matrix recovery failed) keeps the
        // original 1:1 sampling.
        float maskScale = state.lastMaskScale;
        uniformBuffer.update(GlowTime.worldSecondsFloat(), w, h,
                maskScale, maskScale, state.config);

        com.mojang.blaze3d.textures.GpuTextureView sceneDepthView = selectSceneDepthView(state, mask, mainTarget);
        // Mask filter: bilinear in the sub-rect path softens sub-pixel noise that low-res
        // rasterization introduces, but at 1:1 it would blur the otherwise-pixel-exact 1px
        // outline silhouette. Pick per state.
        FilterMode maskFilter = maskScale < 1.0f ? FilterMode.LINEAR : FilterMode.NEAREST;

        try (RenderPass pass = RenderSystem.getDevice()
                .createCommandEncoder()
                .createRenderPass(() -> "Glow", mainTarget.getColorTextureView(), OptionalInt.empty())) {
            pass.setPipeline(pipeline);
            pass.setUniform("GlowUniforms", uniformBuffer.getSlice());
            pass.bindTexture("DiffuseSampler",
                    tempColorTarget.getColorTextureView(),
                    RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR));
            pass.bindTexture("MaskSampler",
                    mask.getColorTextureView(),
                    RenderSystem.getSamplerCache().getClampToEdge(maskFilter));
            pass.bindTexture("MaskDepthSampler",
                    mask.getDepthTextureView(),
                    RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST));
            pass.bindTexture("SceneDepthSampler",
                    sceneDepthView,
                    RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST));
            pass.draw(0, 3);
        }
    }

    private static com.mojang.blaze3d.textures.GpuTextureView selectSceneDepthView(
            GlowCaptureState state, TextureTarget mask, RenderTarget mainTarget) {
        // First-person uses mask self-compare: hud3d projection captured at the hand pass
        // doesn't match the level/entity projection used for sceneDepthTarget, so depth
        // comparison would be meaningless. Self-compare = no world occlusion in first-
        // person, but first-person doesn't show the player's own body anyway.
        if (state.firstPerson) return mask.getDepthTextureView();
        if (!IrisCompat.isShaderActive()) return mainTarget.getDepthTextureView();
        TextureTarget sceneDepth = GlowCaptureManager.getSceneDepthTarget();
        return sceneDepth != null ? sceneDepth.getDepthTextureView() : mask.getDepthTextureView();
    }

    private static void dispose() {
        if (tempColorTarget != null) {
            tempColorTarget.destroyBuffers();
            tempColorTarget = null;
        }
        if (uniformBuffer != null) {
            uniformBuffer.close();
            uniformBuffer = null;
        }
    }
}
