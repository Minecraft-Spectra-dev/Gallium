package cn.spectra.gallium.glowoutline.mixin;

//#if MC==1_21_11 || MC==1_26_01
import cn.spectra.gallium.glowoutline.capture.NativeVisibilityBatch;
//#if MC==1_21_11
//$$ import com.mojang.blaze3d.opengl.GlCommandEncoder;
//#endif
import com.mojang.blaze3d.opengl.GlRenderPipeline;
import com.mojang.blaze3d.platform.NativeImage;
//#if MC>=1_26_00
import com.mojang.blaze3d.systems.RenderPassBackend;
//#else
//$$ import com.mojang.blaze3d.systems.RenderPass;
//#endif
import com.mojang.blaze3d.textures.*;
import com.mojang.blaze3d.vertex.VertexFormat;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.*;
import java.nio.ByteBuffer;
import java.util.OptionalInt;
import java.util.OptionalDouble;
import java.util.function.Supplier;

/** Observes actual native texture write entry points, including render-pass attachments. */
//#if MC==1_21_11
//$$ @Mixin(GlCommandEncoder.class)
//#else
@Mixin(targets="com.mojang.blaze3d.opengl.GlCommandEncoder")
//#endif
public class NativeVisibilityTextureMixin {
    @Unique private GpuTexture gallium$color, gallium$depth;

    @Inject(method="writeToTexture(Lcom/mojang/blaze3d/textures/GpuTexture;Lcom/mojang/blaze3d/platform/NativeImage;IIIIIIII)V",at=@At("HEAD"))
    private void gallium$imageUpload(GpuTexture texture,NativeImage image,int a,int b,int c,int d,int e,int f,int g,int h,CallbackInfo ci){NativeVisibilityBatch.textureWritten(texture);}

    @Inject(method="writeToTexture(Lcom/mojang/blaze3d/textures/GpuTexture;Ljava/nio/ByteBuffer;Lcom/mojang/blaze3d/platform/NativeImage$Format;IIIIII)V",at=@At("HEAD"))
    private void gallium$bufferUpload(GpuTexture texture,ByteBuffer pixels,NativeImage.Format format,int a,int b,int c,int d,int e,int f,CallbackInfo ci){NativeVisibilityBatch.textureWritten(texture);}

    @Inject(method="copyTextureToTexture",at=@At("HEAD"))
    private void gallium$copy(GpuTexture source,GpuTexture destination,int a,int b,int c,int d,int e,int f,int g,CallbackInfo ci){NativeVisibilityBatch.textureWritten(destination);}

    @Inject(method="clearColorTexture",at=@At("HEAD"))
    private void gallium$clearColor(GpuTexture texture,int color,CallbackInfo ci){NativeVisibilityBatch.textureWritten(texture);}

    @Inject(method="clearDepthTexture",at=@At("HEAD"))
    private void gallium$clearDepth(GpuTexture texture,double depth,CallbackInfo ci){NativeVisibilityBatch.textureWritten(texture);}

    @Inject(method="clearColorAndDepthTextures(Lcom/mojang/blaze3d/textures/GpuTexture;ILcom/mojang/blaze3d/textures/GpuTexture;D)V",at=@At("HEAD"))
    private void gallium$clearBoth(GpuTexture color,int value,GpuTexture depth,double z,CallbackInfo ci){NativeVisibilityBatch.textureWritten(color);NativeVisibilityBatch.textureWritten(depth);}

    @Inject(method="clearColorAndDepthTextures(Lcom/mojang/blaze3d/textures/GpuTexture;ILcom/mojang/blaze3d/textures/GpuTexture;DIIII)V",at=@At("HEAD"))
    private void gallium$clearRegion(GpuTexture color,int value,GpuTexture depth,double z,int x,int y,int width,int height,CallbackInfo ci){NativeVisibilityBatch.textureWritten(color);NativeVisibilityBatch.textureWritten(depth);}

    //#if MC>=1_26_00
    @Inject(method="createRenderPass(Ljava/util/function/Supplier;Lcom/mojang/blaze3d/textures/GpuTextureView;Ljava/util/OptionalInt;Lcom/mojang/blaze3d/textures/GpuTextureView;Ljava/util/OptionalDouble;)Lcom/mojang/blaze3d/systems/RenderPassBackend;",at=@At("HEAD"))
    private void gallium$pass(Supplier<String> name,GpuTextureView color,OptionalInt clearColor,GpuTextureView depth,OptionalDouble clearDepth,CallbackInfoReturnable<RenderPassBackend> ci){
    //#else
    //$$ @Inject(method="createRenderPass(Ljava/util/function/Supplier;Lcom/mojang/blaze3d/textures/GpuTextureView;Ljava/util/OptionalInt;Lcom/mojang/blaze3d/textures/GpuTextureView;Ljava/util/OptionalDouble;)Lcom/mojang/blaze3d/systems/RenderPass;",at=@At("HEAD"))
    //$$ private void gallium$pass(Supplier<String> name,GpuTextureView color,OptionalInt clearColor,GpuTextureView depth,OptionalDouble clearDepth,CallbackInfoReturnable<RenderPass> ci){
    //#endif
        gallium$color=color==null?null:color.texture();gallium$depth=depth==null?null:depth.texture();
        if(clearColor.isPresent())NativeVisibilityBatch.textureWritten(gallium$color);
        if(clearDepth.isPresent())NativeVisibilityBatch.textureWritten(gallium$depth);
    }

    @Inject(method="drawFromBuffers",at=@At("HEAD"))
    private void gallium$draw(@Coerce Object pass,int a,int b,int c,VertexFormat.IndexType index,GlRenderPipeline pipeline,int instances,CallbackInfo ci){
        if(!NativeVisibilityBatch.observingInputs())return;
        var info=pipeline.info();
        //#if MC>=1_26_00
        if(info.getColorTargetState().writeMask()!=0)NativeVisibilityBatch.textureWritten(gallium$color);
        var depth=info.getDepthStencilState();
        if(depth!=null && depth.writeDepth())NativeVisibilityBatch.textureWritten(gallium$depth);
        //#else
        //$$ if(info.isWriteColor() || info.isWriteAlpha())NativeVisibilityBatch.textureWritten(gallium$color);
        //$$ if(info.isWriteDepth())NativeVisibilityBatch.textureWritten(gallium$depth);
        //#endif
    }

    @Inject(method="finishRenderPass",at=@At("HEAD"))
    private void gallium$finish(CallbackInfo ci){gallium$color=gallium$depth=null;}
}
//#else
//$$ public class NativeVisibilityTextureMixin {}
//#endif
