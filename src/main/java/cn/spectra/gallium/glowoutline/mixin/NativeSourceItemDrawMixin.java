package cn.spectra.gallium.glowoutline.mixin;
//#if MC==1_21_11 || MC==1_26_01
import net.minecraft.client.renderer.*;
import net.minecraft.client.renderer.feature.ItemFeatureRenderer;
import org.spongepowered.asm.mixin.Mixin;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import cn.spectra.gallium.glowoutline.capture.NativePrimarySources;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
@Mixin(ItemFeatureRenderer.class)
public class NativeSourceItemDrawMixin {
 //#if MC>=1_26_00
 @WrapMethod(method="renderItem")
 private void gallium$sourceScope(MultiBufferSource.BufferSource buffers,OutlineBufferSource outlines,SubmitNodeStorage.ItemSubmit submit,Operation<Void> original){
  try(var scope=NativePrimarySources.enter(submit,null,null)){original.call(buffers,outlines,submit);}
 }

 @Inject(method="renderItem",at=@At("RETURN"))private void gallium$observed(MultiBufferSource.BufferSource buffers,OutlineBufferSource outlines,SubmitNodeStorage.ItemSubmit submit,CallbackInfo ci){cn.spectra.gallium.glowoutline.capture.NativeSourceCoverage.observed(submit);}
 //#else
 //$$ @com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation(method="render",at=@At(value="INVOKE",target="Lnet/minecraft/client/renderer/entity/ItemRenderer;renderItem(Lnet/minecraft/world/item/ItemDisplayContext;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;II[ILjava/util/List;Lnet/minecraft/client/renderer/rendertype/RenderType;Lnet/minecraft/client/renderer/item/ItemStackRenderState$FoilType;)V",ordinal=0))
 //$$ private void gallium$sourceScope(net.minecraft.world.item.ItemDisplayContext context,com.mojang.blaze3d.vertex.PoseStack pose,MultiBufferSource buffers,int light,int overlay,int[] tint,java.util.List<net.minecraft.client.renderer.block.model.BakedQuad> quads,net.minecraft.client.renderer.rendertype.RenderType type,net.minecraft.client.renderer.item.ItemStackRenderState.FoilType foil,Operation<Void> original,@com.llamalad7.mixinextras.sugar.Local SubmitNodeStorage.ItemSubmit submit){
 //$$  if(submit.outlineColor()!=0)cn.spectra.gallium.glowoutline.capture.NativeSourceCoverage.reject(cn.spectra.gallium.glowoutline.capture.NativeSourceCoverage.owner(submit));
 //$$  try(var scope=NativePrimarySources.enter(submit,null,null)){original.call(context,pose,buffers,light,overlay,tint,quads,type,foil);}
 //$$  cn.spectra.gallium.glowoutline.capture.NativeSourceCoverage.observed(submit);
 //$$ }
 //#endif
}
//#else
//$$ public class NativeSourceItemDrawMixin {}
//#endif
