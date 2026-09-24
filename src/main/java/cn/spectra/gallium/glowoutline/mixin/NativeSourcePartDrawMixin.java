package cn.spectra.gallium.glowoutline.mixin;
//#if MC==1_21_11
//$$ import net.minecraft.client.renderer.*;
//$$ import net.minecraft.client.renderer.feature.ModelPartFeatureRenderer;
//$$ import net.minecraft.client.renderer.rendertype.RenderType;
//$$ import com.mojang.blaze3d.vertex.*;
//$$ import net.minecraft.client.model.geom.ModelPart;
//$$ import cn.spectra.gallium.glowoutline.capture.*;
//$$ import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
//$$ import com.llamalad7.mixinextras.injector.wrapoperation.*;
//$$ import com.llamalad7.mixinextras.sugar.Local;
//$$ import org.spongepowered.asm.mixin.*;
//$$ import org.spongepowered.asm.mixin.injection.*;
//$$ import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
//$$ @Mixin(ModelPartFeatureRenderer.class)
//$$ public class NativeSourcePartDrawMixin {
//$$  @Unique private NativePrimarySources.Scope gallium$source;
//$$  @Unique private void gallium$close(){if(gallium$source!=null){gallium$source.close();gallium$source=null;}}
//$$  @WrapMethod(method="render")private void frame(SubmitNodeCollection nodes,MultiBufferSource.BufferSource buffers,OutlineBufferSource outline,MultiBufferSource.BufferSource crumbling,Operation<Void> original){
//$$   try{original.call(nodes,buffers,outline,crumbling);}finally{gallium$close();}
//$$  }
//$$  @Inject(method="render",at=@At(value="INVOKE",target="Lnet/minecraft/client/renderer/SubmitNodeStorage$ModelPartSubmit;sprite()Lnet/minecraft/client/renderer/texture/TextureAtlasSprite;",ordinal=0))
//$$  private void begin(SubmitNodeCollection nodes,MultiBufferSource.BufferSource buffers,OutlineBufferSource outline,MultiBufferSource.BufferSource crumbling,CallbackInfo ci,@Local SubmitNodeStorage.ModelPartSubmit submit,@Local RenderType type,@Local VertexConsumer base){
//$$   gallium$close();gallium$source=NativePrimarySources.enter(submit,base,type);
//$$  }
//$$  @WrapOperation(method="render",at=@At(value="INVOKE",target="Lnet/minecraft/client/model/geom/ModelPart;render(Lcom/mojang/blaze3d/vertex/PoseStack;Lcom/mojang/blaze3d/vertex/VertexConsumer;III)V",ordinal=0))
//$$  private void draw(ModelPart part,PoseStack pose,VertexConsumer consumer,int light,int overlay,int color,Operation<Void> original,@Local SubmitNodeStorage.ModelPartSubmit submit){
//$$   try{original.call(part,pose,consumer,light,overlay,color);NativeSourceCoverage.observed(submit);}finally{gallium$close();}
//$$  }
//$$ }
//#else
public class NativeSourcePartDrawMixin {}
//#endif
