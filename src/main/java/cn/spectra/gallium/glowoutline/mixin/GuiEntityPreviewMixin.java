package cn.spectra.gallium.glowoutline.mixin;

//#if MC>=1_21_06
import cn.spectra.gallium.glowoutline.capture.GuiEntityGlowCapture;
import cn.spectra.gallium.glowoutline.shader.GuiEntityBlitPipeline;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTexture;
import net.minecraft.client.gui.render.pip.PictureInPictureRenderer;
import net.minecraft.client.gui.render.pip.GuiEntityRenderer;
//#if MC>=1_26_00
import net.minecraft.client.renderer.state.gui.GuiRenderState;
import net.minecraft.client.renderer.state.gui.pip.GuiEntityRenderState;
import net.minecraft.client.renderer.state.gui.pip.PictureInPictureRenderState;
//#else
//$$ import net.minecraft.client.gui.render.state.GuiRenderState;
//$$ import net.minecraft.client.gui.render.state.pip.GuiEntityRenderState;
//$$ import net.minecraft.client.gui.render.state.pip.PictureInPictureRenderState;
//#endif
//#if MC>=1_26_02
//$$ import net.minecraft.client.renderer.feature.FeatureRenderDispatcher;
//#endif
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(PictureInPictureRenderer.class)
public class GuiEntityPreviewMixin {
    @Shadow private GpuTexture texture;
    @Shadow private GpuTexture depthTexture;

    // Vanilla PIP attachments omit a copy usage on some versions. The isolated
    // scene snapshot and final copy need both copy usages; other PIP renderers keep theirs.
    @ModifyArg(method = "prepareTexturesAndProjection", at = @At(value = "INVOKE",
            //#if MC>=1_26_02
            //$$ target = "Lcom/mojang/blaze3d/systems/GpuDevice;createTexture(Ljava/util/function/Supplier;ILcom/mojang/blaze3d/GpuFormat;IIII)Lcom/mojang/blaze3d/textures/GpuTexture;",
            //#else
            target = "Lcom/mojang/blaze3d/systems/GpuDevice;createTexture(Ljava/util/function/Supplier;ILcom/mojang/blaze3d/textures/TextureFormat;IIII)Lcom/mojang/blaze3d/textures/GpuTexture;",
            //#endif
            remap = false), index = 1, require = 2)
    private int galliumAllowPreviewSnapshot(int usage) {
        return (Object) this instanceof GuiEntityRenderer ? usage | GpuTexture.USAGE_COPY_SRC | GpuTexture.USAGE_COPY_DST : usage;
    }

    @WrapMethod(method = "prepare")
    private void galliumPreviewScope(PictureInPictureRenderState state, GuiRenderState gui,
                                    //#if MC>=1_26_02
                                    //$$ FeatureRenderDispatcher dispatcher,
                                    //#endif
                                    int guiScale, Operation<Void> original) {
        if (!(state instanceof GuiEntityRenderState)) {
            original.call(state, gui,
                    //#if MC>=1_26_02
                    //$$ dispatcher,
                    //#endif
                    guiScale);
            return;
        }
        var color = RenderSystem.outputColorTextureOverride;
        var depth = RenderSystem.outputDepthTextureOverride;
        var projection = RenderSystem.getProjectionMatrixBuffer();
        var projectionType = RenderSystem.getProjectionType();
        try (var preview = GuiEntityGlowCapture.begin((state.x1() - state.x0()) * guiScale,
                (state.y1() - state.y0()) * guiScale, state.scale() * guiScale)) {
            original.call(state, gui,
                    //#if MC>=1_26_02
                    //$$ dispatcher,
                    //#endif
                    guiScale);
        } finally {
            RenderSystem.outputColorTextureOverride = color;
            RenderSystem.outputDepthTextureOverride = depth;
            RenderSystem.setProjectionMatrix(projection, projectionType);
        }
    }

    @Inject(method = "prepare", at = @At(value = "INVOKE",
            //#if MC>=1_26_00
            target = "Lnet/minecraft/client/gui/render/pip/PictureInPictureRenderer;blitTexture(Lnet/minecraft/client/renderer/state/gui/pip/PictureInPictureRenderState;Lnet/minecraft/client/renderer/state/gui/GuiRenderState;)V"
            //#else
            //$$ target = "Lnet/minecraft/client/gui/render/pip/PictureInPictureRenderer;blitTexture(Lnet/minecraft/client/gui/render/state/pip/PictureInPictureRenderState;Lnet/minecraft/client/gui/render/state/GuiRenderState;)V"
            //#endif
    ))
    private void galliumFinishPreview(PictureInPictureRenderState state, GuiRenderState gui,
                                      //#if MC>=1_26_02
                                      //$$ FeatureRenderDispatcher dispatcher,
                                      //#endif
                                      int guiScale, CallbackInfo ci) {
        if (!(state instanceof GuiEntityRenderState)) return;
        var preview = GuiEntityGlowCapture.current();
        if (preview != null) preview.completePip(texture, depthTexture);
    }

    @ModifyExpressionValue(method = "blitTexture", at = @At(value = "FIELD",
            target = "Lnet/minecraft/client/renderer/RenderPipelines;GUI_TEXTURED_PREMULTIPLIED_ALPHA:Lcom/mojang/blaze3d/pipeline/RenderPipeline;"))
    private RenderPipeline galliumPreserveOuterGlow(RenderPipeline original,
                                                    PictureInPictureRenderState state, GuiRenderState gui) {
        var preview = GuiEntityGlowCapture.current();
        return state instanceof GuiEntityRenderState && preview != null && preview.hasGlowForBlit()
                ? GuiEntityBlitPipeline.get() : original;
    }
}
//#else
//$$ public final class GuiEntityPreviewMixin {}
//#endif
