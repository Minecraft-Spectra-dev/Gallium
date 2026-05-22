package cn.spectra.gallium.glowoutline.mixin;

import cn.spectra.gallium.glowoutline.capture.GlowCaptureManager;
import cn.spectra.gallium.glowoutline.capture.GlowCaptureState;
import cn.spectra.gallium.glowoutline.shader.GlowPipeline;
import cn.spectra.gallium.glowoutline.shader.GlowUniformBuffer;
import cn.spectra.gallium.glowoutline.GlowOutlineConfig;
import cn.spectra.gallium.glowoutline.ItemEffectsManager;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import java.util.OptionalInt;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GameRenderer.class)
public class GameRendererMixin {

    @Shadow @Final private Minecraft minecraft;

    @Unique private TextureTarget glowTempColorTarget;
    @Unique private GlowUniformBuffer glowUniformBuffer;
    @Unique private float glowAccumulatedTime;

    @Inject(method = "renderLevel", at = @At("HEAD"))
    private void galliumGlowFrameStart(DeltaTracker deltaTracker, CallbackInfo ci) {
        if (cn.spectra.gallium.glowoutline.IrisCompat.isShadowPass()) return;
        LocalPlayer player = minecraft.player;
        GlowCaptureManager.beginFrame(minecraft, player);
    }

    @Inject(method = "renderLevel", at = @At(value = "INVOKE",
            target = "Lcom/mojang/blaze3d/systems/CommandEncoder;clearDepthTexture(Lcom/mojang/blaze3d/textures/GpuTexture;D)V"))
    private void galliumCaptureSceneDepth(DeltaTracker deltaTracker, CallbackInfo ci) {
        if (cn.spectra.gallium.glowoutline.IrisCompat.isShadowPass()) return;
        if (!ItemEffectsManager.isActive()) return;
        if (minecraft.options.getCameraType().isFirstPerson()) return;

        RenderTarget mainTarget = minecraft.getMainRenderTarget();
        if (mainTarget == null || mainTarget.getDepthTexture() == null) return;

        GlowCaptureManager.captureSceneDepth(mainTarget);
    }

    @Inject(method = "renderLevel", at = @At("TAIL"))
    private void galliumGlowComposite(DeltaTracker deltaTracker, CallbackInfo ci) {
        if (!ItemEffectsManager.isActive()) return;
        if (!GlowOutlineConfig.isEnabled()) return;
        if (cn.spectra.gallium.glowoutline.IrisCompat.isShadowPass()) return;

        RenderTarget mainTarget = minecraft.getMainRenderTarget();
        if (mainTarget == null || mainTarget.getColorTexture() == null) return;
        int w = mainTarget.width, h = mainTarget.height;

        if (glowTempColorTarget == null || glowTempColorTarget.width != w || glowTempColorTarget.height != h) {
            if (glowTempColorTarget != null) glowTempColorTarget.destroyBuffers();
            glowTempColorTarget = new TextureTarget("GlowColor", w, h, false);
        }
        if (glowUniformBuffer == null) glowUniformBuffer = new GlowUniformBuffer();

        glowAccumulatedTime += deltaTracker.getGameTimeDeltaTicks() / 20.0f;
        float globalIntensity = GlowOutlineConfig.getIntensity();

        drawHandGlow(GlowCaptureManager.MAIN, mainTarget, globalIntensity);
        drawHandGlow(GlowCaptureManager.OFF, mainTarget, globalIntensity);
    }

    @Unique
    private void drawHandGlow(GlowCaptureState state, RenderTarget mainTarget, float globalIntensity) {
        if (!state.capturedThisFrame || state.config == null || state.maskTarget == null) return;

        GlowCaptureManager.renderCapturedNodes(state, minecraft);

        TextureTarget mask = state.maskTarget;
        if (mask.getColorTextureView() == null || mask.getDepthTextureView() == null) return;

        RenderPipeline pipeline = GlowPipeline.get(state.config.shader());
        if (pipeline == null) return;

        int w = mainTarget.width, h = mainTarget.height;
        RenderSystem.getDevice().createCommandEncoder().copyTextureToTexture(
                mainTarget.getColorTexture(), glowTempColorTarget.getColorTexture(),
                0, 0, 0, 0, 0, w, h);

        var cfg = state.config.withIntensity(state.config.intensity() * globalIntensity);
        glowUniformBuffer.update(glowAccumulatedTime, w, h, 0.001f, cfg);

        try (RenderPass pass = RenderSystem.getDevice()
                .createCommandEncoder()
                .createRenderPass(() -> "Glow_" + state.hand.name(), mainTarget.getColorTextureView(), OptionalInt.empty())) {
            pass.setPipeline(pipeline);
            pass.setUniform("GlowUniforms", glowUniformBuffer.getSlice());
            pass.bindTexture("DiffuseSampler",
                    glowTempColorTarget.getColorTextureView(),
                    RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR));
            pass.bindTexture("MaskSampler",
                    mask.getColorTextureView(),
                    RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST));
            pass.bindTexture("MaskDepthSampler",
                    mask.getDepthTextureView(),
                    RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST));
            TextureTarget sceneDepth = GlowCaptureManager.getSceneDepthTarget();
            pass.bindTexture("SceneDepthSampler",
                    sceneDepth != null ? sceneDepth.getDepthTextureView() : mask.getDepthTextureView(),
                    RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST));
            pass.draw(0, 3);
        }
    }
}
