package cn.spectra.gallium.glowoutline.mixin;

import cn.spectra.gallium.glowoutline.capture.GuiGlowCaptureManager;
import cn.spectra.gallium.glowoutline.shader.GlowUniformBuffer;
import cn.spectra.gallium.glowoutline.shader.GuiGlowDispatcher;
import cn.spectra.gallium.glowoutline.shader.GuiGlowElementPipeline;
import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.gui.render.GuiItemAtlas;
import net.minecraft.client.gui.render.GuiRenderer;
import net.minecraft.client.renderer.state.gui.GuiItemRenderState;
import net.minecraft.client.renderer.state.gui.GuiRenderState;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GuiRenderer.class)
public class GuiRendererMixin {

    @Shadow @Final private GuiRenderState renderState;

    @Inject(method = "executeDraw", at = @At(value = "INVOKE",
            target = "Lcom/mojang/blaze3d/systems/RenderPass;setPipeline(Lcom/mojang/blaze3d/pipeline/RenderPipeline;)V",
            shift = At.Shift.AFTER))
    private void galliumBindGlowUbo(@Coerce Object draw,
                                     RenderPass renderPass,
                                     GpuBuffer indexBuffer,
                                     VertexFormat.IndexType indexType,
                                     CallbackInfo ci,
                                     @Local RenderPipeline pipeline) {
        GlowUniformBuffer ubo = GuiGlowElementPipeline.getUbo(pipeline);
        if (ubo != null) {
            renderPass.setUniform("GalliumGuiGlow", ubo.getSlice());
        }
    }

    @Inject(method = "submitBlitFromItemAtlas", at = @At("TAIL"))
    private void galliumCaptureForGlow(GuiItemRenderState itemState, GuiItemAtlas.SlotView slotView, CallbackInfo ci) {
        GuiGlowDispatcher.onItemBlit(this.renderState, itemState, slotView);
    }

    @Inject(method = "prepareItemElements", at = @At("TAIL"))
    private void galliumRenderMaskBuffer(CallbackInfo ci) {
        GuiGlowDispatcher.onPrepareItemElements();
    }

    @Inject(method = "render", at = @At("TAIL"))
    private void galliumClear(GpuBufferSlice fogBuffer, CallbackInfo ci) {
        GuiGlowCaptureManager.clear();
    }
}
