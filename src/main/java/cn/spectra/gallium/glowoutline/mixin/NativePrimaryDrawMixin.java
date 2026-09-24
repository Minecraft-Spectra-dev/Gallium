package cn.spectra.gallium.glowoutline.mixin;
//#if MC==1_21_11 || MC==1_26_01
import cn.spectra.gallium.glowoutline.capture.NativePrimarySources;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.*;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.systems.*;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.vertex.MeshData;
import net.minecraft.client.renderer.DynamicUniforms;
import net.minecraft.client.renderer.rendertype.RenderType;
import org.joml.*;
import java.util.*;
import java.util.function.Supplier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
@Mixin(RenderType.class)
public class NativePrimaryDrawMixin {
 //#if MC==1_21_11
 //$$ @WrapOperation(method="draw",at=@At(value="INVOKE",target="Lcom/mojang/blaze3d/vertex/VertexFormat;uploadImmediateIndexBuffer(Ljava/nio/ByteBuffer;)Lcom/mojang/blaze3d/buffers/GpuBuffer;",remap=false))
 //$$ private com.mojang.blaze3d.buffers.GpuBuffer gallium$sortedIndices(com.mojang.blaze3d.vertex.VertexFormat format,java.nio.ByteBuffer bytes,Operation<com.mojang.blaze3d.buffers.GpuBuffer> original,MeshData mesh){
 //$$  var result=original.call(format,bytes);NativePrimarySources.indexUploaded((RenderType)(Object)this,mesh,bytes,result);return result;
 //$$ }
 //#endif
 @WrapMethod(method="draw")
 private void gallium$deliveredSource(MeshData mesh,Operation<Void> original){original.call(mesh);NativePrimarySources.delivered(mesh);}
 @WrapOperation(method="draw",at=@At(value="INVOKE",target="Lnet/minecraft/client/renderer/DynamicUniforms;writeTransform(Lorg/joml/Matrix4fc;Lorg/joml/Vector4fc;Lorg/joml/Vector3fc;Lorg/joml/Matrix4fc;)Lcom/mojang/blaze3d/buffers/GpuBufferSlice;"))
 private GpuBufferSlice gallium$sourceTransform(DynamicUniforms uniforms,Matrix4fc modelView,Vector4fc color,Vector3fc offset,Matrix4fc texture,Operation<GpuBufferSlice> original,MeshData mesh){
  //#if MC==1_21_11
  //$$ var source=NativePrimarySources.current();
  //$$ if(source!=null && ((RenderType)(Object)this).pipeline().getBlendFunction().isPresent() && !(color.w()>=0 && color.w()<=1))
  //$$  source.invalidate("unnormalized source alpha modulator");
  //#endif
  NativePrimarySources.transformed((RenderType)(Object)this,mesh,modelView);return original.call(uniforms,modelView,color,offset,texture);
 }
 @WrapOperation(method="draw",at=@At(value="INVOKE",target="Lcom/mojang/blaze3d/systems/CommandEncoder;createRenderPass(Ljava/util/function/Supplier;Lcom/mojang/blaze3d/textures/GpuTextureView;Ljava/util/OptionalInt;Lcom/mojang/blaze3d/textures/GpuTextureView;Ljava/util/OptionalDouble;)Lcom/mojang/blaze3d/systems/RenderPass;",remap=false))
 private RenderPass gallium$sourceAttachments(CommandEncoder encoder,Supplier<String> name,GpuTextureView color,OptionalInt clearColor,GpuTextureView depth,OptionalDouble clearDepth,Operation<RenderPass> original,MeshData mesh){
  var pass=original.call(encoder,name,color,clearColor,depth,clearDepth);
  NativePrimarySources.attachments(pass,color,depth,clearColor.isPresent() || clearDepth.isPresent(),mesh);return pass;
 }
 @WrapOperation(method="draw",at=@At(value="INVOKE",target="Lcom/mojang/blaze3d/systems/RenderPass;drawIndexed(IIII)V",remap=false))
 private void gallium$retainSource(RenderPass pass,int base,int first,int count,int instances,Operation<Void> original,MeshData mesh){
  NativePrimarySources.drawing(pass,(RenderType)(Object)this,mesh,base,first,count,instances);original.call(pass,base,first,count,instances);
 }
}
//#else
//$$ public class NativePrimaryDrawMixin {}
//#endif
