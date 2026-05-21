package cn.spectra.gallium.glowoutline.mixin;

import cn.spectra.gallium.glowoutline.GlowOutlineConfig;
import cn.spectra.gallium.glowoutline.ItemEffectConfig;
import cn.spectra.gallium.glowoutline.ItemEffectsManager;
import cn.spectra.gallium.glowoutline.render.GlowState;
import cn.spectra.gallium.glowoutline.shader.GlowPipeline;
import cn.spectra.gallium.glowoutline.shader.GlowUniformBuffer;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import java.util.OptionalInt;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
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
    private void glowFrameStart(DeltaTracker deltaTracker, CallbackInfo ci) {
        GlowState.resetFrame();
    }

    @Inject(method = "renderLevel", at = @At("TAIL"))
    private void glowDrawOutline(DeltaTracker deltaTracker, CallbackInfo ci) {
        if (!ItemEffectsManager.isActive()) return;
        if (!GlowOutlineConfig.isEnabled()) return;
        if (!minecraft.options.getCameraType().isFirstPerson()) return;
        if (!GlowState.isActive() || !GlowState.isCaptured()) return;

        TextureTarget depthBefore = GlowState.getItemBefore();
        TextureTarget depthAfter = GlowState.getItemAfter();
        if (depthBefore == null || depthAfter == null) return;
        if (depthBefore.getDepthTextureView() == null || depthAfter.getDepthTextureView() == null) return;

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

        ItemEffectConfig mainCfg = ItemEffectsManager.getConfig(GlowState.getMainHandItem());
        ItemEffectConfig offCfg = ItemEffectsManager.getConfig(GlowState.getOffHandItem());

        // Pick the active config: higher intensity wins; tie goes to main hand
        ItemEffectConfig activeCfg = (offCfg.intensity() > mainCfg.intensity()) ? offCfg : mainCfg;
        if (activeCfg.intensity() <= 0) return;

        ItemEffectConfig finalCfg = activeCfg.withIntensity(activeCfg.intensity() * globalIntensity);
        drawGlowPass(finalCfg, activeCfg.shader(), mainTarget, depthBefore, depthAfter);
    }

    @Unique
    private void drawGlowPass(ItemEffectConfig cfg, String shaderName,
                               RenderTarget mainTarget, TextureTarget depthBefore, TextureTarget depthAfter) {
        RenderPipeline pipeline = GlowPipeline.get(shaderName);
        if (pipeline == null) return;

        int w = mainTarget.width, h = mainTarget.height;
        RenderSystem.getDevice().createCommandEncoder().copyTextureToTexture(
                mainTarget.getColorTexture(), glowTempColorTarget.getColorTexture(),
                0, 0, 0, 0, 0, w, h);

        glowUniformBuffer.update(glowAccumulatedTime, w, h, 0.001f, cfg);

        try (RenderPass renderPass = RenderSystem.getDevice()
                .createCommandEncoder()
                .createRenderPass(() -> "Glow Outline", mainTarget.getColorTextureView(), OptionalInt.empty())) {
            renderPass.setPipeline(pipeline);
            renderPass.setUniform("GlowUniforms", glowUniformBuffer.getSlice());
            renderPass.bindTexture("DiffuseSampler",
                    glowTempColorTarget.getColorTextureView(),
                    RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR));
            renderPass.bindTexture("SolidBeforeSampler",
                    depthBefore.getDepthTextureView(),
                    RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST));
            renderPass.bindTexture("SolidAfterSampler",
                    depthAfter.getDepthTextureView(),
                    RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST));
            renderPass.bindTexture("TransBeforeSampler",
                    depthBefore.getDepthTextureView(),
                    RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST));
            renderPass.bindTexture("TransAfterSampler",
                    depthAfter.getDepthTextureView(),
                    RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST));
            renderPass.draw(0, 3);
        }
    }
}
