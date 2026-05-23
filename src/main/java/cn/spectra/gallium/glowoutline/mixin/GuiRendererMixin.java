package cn.spectra.gallium.glowoutline.mixin;

import cn.spectra.gallium.glowoutline.ItemEffectConfig;
import cn.spectra.gallium.glowoutline.ShaderParam;
import cn.spectra.gallium.glowoutline.capture.GuiGlowCapture;
import cn.spectra.gallium.glowoutline.capture.GuiGlowCaptureManager;
import cn.spectra.gallium.glowoutline.capture.GuiGlowElementRenderState;
import cn.spectra.gallium.glowoutline.capture.GuiItemRenderStateAccessor;
import cn.spectra.gallium.glowoutline.shader.GuiGlowElementPipeline;
import cn.spectra.gallium.glowoutline.shader.GuiGlowRenderer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.render.GuiRenderer;
import net.minecraft.client.gui.render.GuiItemAtlas;
import net.minecraft.client.gui.render.TextureSetup;
import net.minecraft.client.renderer.state.gui.GuiItemRenderState;
import net.minecraft.client.renderer.state.gui.GuiRenderState;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GuiRenderer.class)
public class GuiRendererMixin {

    @Shadow @Final private GuiRenderState renderState;

    @Inject(method = "executeDraw", at = @At(value = "INVOKE",
            target = "Lcom/mojang/blaze3d/systems/RenderPass;setPipeline(Lcom/mojang/blaze3d/pipeline/RenderPipeline;)V",
            shift = At.Shift.AFTER))
    private void galliumBindGlowUbo(@org.spongepowered.asm.mixin.injection.Coerce Object draw,
                                     com.mojang.blaze3d.systems.RenderPass renderPass,
                                     com.mojang.blaze3d.buffers.GpuBuffer indexBuffer,
                                     com.mojang.blaze3d.vertex.VertexFormat.IndexType indexType,
                                     CallbackInfo ci,
                                     @com.llamalad7.mixinextras.sugar.Local com.mojang.blaze3d.pipeline.RenderPipeline pipeline) {
        cn.spectra.gallium.glowoutline.shader.GuiGlowUniformBuffer ubo =
                cn.spectra.gallium.glowoutline.shader.GuiGlowElementPipeline.getUbo(pipeline);
        if (ubo != null) {
            renderPass.setUniform("GalliumGuiGlow", ubo.getSlice());
        }
    }

    @Inject(method = "submitBlitFromItemAtlas", at = @At("TAIL"))
    private void galliumCaptureForGlow(GuiItemRenderState itemState, GuiItemAtlas.SlotView slotView, CallbackInfo ci) {
        ItemEffectConfig cfg = ((GuiItemRenderStateAccessor) (Object) itemState).gallium$getEffectConfig();
        if (cfg == null) return;

        // Capture for mask render later
        GuiGlowCapture capture = GuiGlowCaptureManager.acquire();
        capture.set(cfg, slotView.textureView(), itemState.pose(),
                itemState.x(), itemState.y(),
                slotView.u0(), slotView.u1(), slotView.v0(), slotView.v1(),
                itemState.scissorArea());

        // Submit glow GUI element NOW (current is correct for this item's Node)
        Minecraft mc = Minecraft.getInstance();
        var mainTarget = mc.getMainRenderTarget();
        if (mainTarget == null) return;

        int screenW = mainTarget.width;
        int screenH = mainTarget.height;
        double guiScale = mc.getWindow().getGuiScale();

        // Ensure mask buffer is allocated; texture view is stable across frames
        TextureTarget maskTarget = GuiGlowRenderer.ensureMaskTarget(screenW, screenH);
        if (maskTarget == null || maskTarget.getColorTextureView() == null) return;

        TextureSetup texSetup = TextureSetup.singleTexture(maskTarget.getColorTextureView(),
                RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR));

        // Compute mask UVs with margin: extend quad by 4 logical pixels each side
        int margin = 4;
        int qx0 = itemState.x() - margin;
        int qy0 = itemState.y() - margin;
        int qx1 = itemState.x() + 16 + margin;
        int qy1 = itemState.y() + 16 + margin;

        // Apply pose to get final logical screen position (mask is rendered with pose applied)
        org.joml.Vector2f topLeft = itemState.pose().transformPosition(new org.joml.Vector2f(qx0, qy0));
        org.joml.Vector2f bottomRight = itemState.pose().transformPosition(new org.joml.Vector2f(qx1, qy1));

        float fbX0 = (float) (topLeft.x * guiScale);
        float fbY0 = (float) (topLeft.y * guiScale);
        float fbX1 = (float) (bottomRight.x * guiScale);
        float fbY1 = (float) (bottomRight.y * guiScale);
        float u0 = fbX0 / screenW;
        float u1 = fbX1 / screenW;
        float v0 = 1.0f - fbY0 / screenH;
        float v1 = 1.0f - fbY1 / screenH;

        // Color/intensity is sourced from UBO; vertex color is just a passthrough modulator.
        int color = 0xFFFFFFFF;

        GuiGlowElementRenderState element = GuiGlowElementRenderState.create(
                GuiGlowElementPipeline.getOrCreate(cfg),
                texSetup,
                itemState.pose(),
                qx0, qy0, qx1, qy1,
                u0, u1, v0, v1,
                color,
                itemState.scissorArea()
        );
        this.renderState.addGlyphToCurrentLayer(element);
    }

    @Inject(method = "prepareItemElements", at = @At("TAIL"))
    private void galliumRenderMaskBuffer(CallbackInfo ci) {
        if (GuiGlowCaptureManager.getActive().isEmpty()) return;
        Minecraft mc = Minecraft.getInstance();
        var mainTarget = mc.getMainRenderTarget();
        if (mainTarget == null) return;

        int screenW = mainTarget.width;
        int screenH = mainTarget.height;
        float frameTime = (float) (System.nanoTime() / 1_000_000_000.0);

        GuiGlowRenderer.renderMaskOnly(screenW, screenH);
        GuiGlowElementPipeline.updateAllForFrame(screenW, screenH, frameTime, 1.0f);
    }

    @Inject(method = "render", at = @At("TAIL"))
    private void galliumClear(GpuBufferSlice fogBuffer, CallbackInfo ci) {
        GuiGlowCaptureManager.clear();
    }
}




