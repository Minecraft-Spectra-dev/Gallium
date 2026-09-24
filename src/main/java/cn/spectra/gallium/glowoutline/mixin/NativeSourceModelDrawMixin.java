package cn.spectra.gallium.glowoutline.mixin;
//#if MC==1_21_11 || MC==1_26_01
import net.minecraft.client.renderer.*;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.client.renderer.rendertype.RenderType;
import com.mojang.blaze3d.vertex.VertexConsumer;
import org.spongepowered.asm.mixin.Mixin;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import cn.spectra.gallium.glowoutline.capture.NativePrimarySources;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
@Mixin(ModelFeatureRenderer.class)
public class NativeSourceModelDrawMixin {
 @WrapMethod(method="renderModel")
 private void gallium$sourceScope(SubmitNodeStorage.ModelSubmit<?> submit,RenderType type,VertexConsumer base,OutlineBufferSource outlines,MultiBufferSource.BufferSource buffers,Operation<Void> original){
  try(var scope=NativePrimarySources.enter(submit,base,type)){original.call(submit,type,base,outlines,buffers);}
 }

 @Inject(method="renderModel",at=@At("RETURN"))private void gallium$observed(SubmitNodeStorage.ModelSubmit<?> submit,RenderType type,VertexConsumer base,OutlineBufferSource outlines,MultiBufferSource.BufferSource buffers,CallbackInfo ci){cn.spectra.gallium.glowoutline.capture.NativeSourceCoverage.observed(submit);}
}
//#else
//$$ public class NativeSourceModelDrawMixin {}
//#endif
