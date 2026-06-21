package cn.spectra.gallium.glowoutline.mixin;

import cn.spectra.gallium.glowoutline.capture.GuiGlowCaptureManager;
//#if MC>=1_26_00
//#elseif MC>=1_21_06
//$$ import cn.spectra.gallium.glowoutline.mixin.accessor.GuiRendererAccessor;
//#endif
import cn.spectra.gallium.glowoutline.shader.GlowUniformBuffer;
import cn.spectra.gallium.glowoutline.shader.GuiGlowDispatcher;
import cn.spectra.gallium.glowoutline.shader.GuiGlowElementPipeline;
import com.llamalad7.mixinextras.sugar.Local;
//#if MC>=1_21_06
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
//#endif
//#if MC>=1_21_06
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.systems.RenderPass;
//#endif
//#if MC>=1_26_00
//#elseif MC>=1_21_06
//$$ import com.mojang.blaze3d.textures.GpuTextureView;
//#endif
import com.mojang.blaze3d.vertex.VertexFormat;
//#if MC>=1_26_00
import net.minecraft.client.gui.render.GuiItemAtlas;
//#endif
//#if MC>=1_21_06
import net.minecraft.client.gui.render.GuiRenderer;
//#endif
//#if MC>=1_26_00
import net.minecraft.client.renderer.state.gui.GuiItemRenderState;
import net.minecraft.client.renderer.state.gui.GuiRenderState;
//#elseif MC>=1_21_06
//$$ import net.minecraft.client.gui.render.state.GuiItemRenderState;
//$$ import net.minecraft.client.gui.render.state.GuiRenderState;
//#endif
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

//#if MC>=1_21_06
@Mixin(GuiRenderer.class)
public class GuiRendererMixin {

    @Shadow @Final private GuiRenderState renderState;

    @Inject(method = "executeDraw", at = @At(value = "INVOKE",
            target = "Lcom/mojang/blaze3d/systems/RenderPass;setPipeline(Lcom/mojang/blaze3d/pipeline/RenderPipeline;)V",
            shift = At.Shift.AFTER,
            // RenderPass is unobfuscated; see GameRendererMixin for rationale.
            remap = false))
    private void galliumBindGlowUbo(@Coerce Object draw,
                                     RenderPass renderPass,
                                     GpuBuffer indexBuffer,
                                     VertexFormat.IndexType indexType,
                                     CallbackInfo ci,
                                     // ordinal=0 pins to the first RenderPipeline local in executeDraw
                                     // (the one just bound on the line above). Future vanilla edits that
                                     // introduce another RenderPipeline local would otherwise make this
                                     // capture ambiguous; defaultRequire=1 would crash, but failing fast
                                     // at the right local is clearer.
                                     @Local(ordinal = 0) RenderPipeline pipeline) {
        GlowUniformBuffer ubo = GuiGlowElementPipeline.getUbo(pipeline);
        if (ubo != null) {
            renderPass.setUniform("GalliumGuiGlow", ubo.getSlice());
        }
    }

    //#if MC>=1_26_00
    @Inject(method = "submitBlitFromItemAtlas", at = @At("TAIL"))
    private void galliumCaptureForGlow(GuiItemRenderState itemState, GuiItemAtlas.SlotView slotView, CallbackInfo ci) {
        GuiGlowDispatcher.onItemBlit(this.renderState, itemState, slotView);
    }
    //#else
    //$$ @Inject(method = "submitBlitFromItemAtlas", at = @At("TAIL"))
    //$$ private void galliumCaptureForGlow(GuiItemRenderState itemState, float f, float g, int i, int j, CallbackInfo ci) {
    //$$     GuiRendererAccessor self = (GuiRendererAccessor) this;
    //$$     GpuTextureView atlasView = self.gallium$getItemsAtlasView();
    //$$     if (atlasView == null) return;
    //$$     GuiGlowDispatcher.onItemBlit(this.renderState, itemState, atlasView, f, g, i, j);
    //$$ }
    //#endif

    @Inject(method = "prepareItemElements", at = @At("TAIL"))
    private void galliumRenderMaskBuffer(CallbackInfo ci) {
        GuiGlowDispatcher.onPrepareItemElements();
    }

    @Inject(method = "render", at = @At("TAIL"))
    private void galliumClear(GpuBufferSlice fogBuffer, CallbackInfo ci) {
        GuiGlowCaptureManager.clear();
    }
}
//#else
//$$ public class GuiRendererMixin {}
//#endif
