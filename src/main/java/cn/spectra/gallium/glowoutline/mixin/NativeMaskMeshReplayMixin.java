package cn.spectra.gallium.glowoutline.mixin;

//#if MC>=1_21_06 && MC<1_26_02
//#if MC>=1_21_11
import cn.spectra.gallium.glowoutline.capture.NativeMaskMeshReplay;
//#endif
import cn.spectra.gallium.glowoutline.capture.ModernMaskBounds;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import net.minecraft.client.renderer.DynamicUniforms;
import org.joml.Matrix4fc;
import org.joml.Vector4fc;
import org.joml.Vector3fc;
import com.mojang.blaze3d.vertex.MeshData;
//#if MC>=1_21_11
import net.minecraft.client.renderer.rendertype.RenderType;
//#else
//$$ import net.minecraft.client.renderer.RenderType;
//#endif
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

//#if MC>=1_21_11
@Mixin(RenderType.class)
//#else
//$$ @Mixin(RenderType.CompositeRenderType.class)
//#endif
public abstract class NativeMaskMeshReplayMixin {
    //#if MC==1_21_08
    //$$ @org.spongepowered.asm.mixin.Shadow @org.spongepowered.asm.mixin.Final
    //$$ private com.mojang.blaze3d.pipeline.RenderPipeline renderPipeline;
    //#endif
    //#if MC==1_21_08 || MC==1_26_01
    @WrapOperation(method = "draw", at = @At(value = "INVOKE", target =
            "Lcom/mojang/blaze3d/vertex/VertexFormat;uploadImmediateVertexBuffer(Ljava/nio/ByteBuffer;)Lcom/mojang/blaze3d/buffers/GpuBuffer;", remap = false))
    private com.mojang.blaze3d.buffers.GpuBuffer galliumUploadNativeRange(
            com.mojang.blaze3d.vertex.VertexFormat format, java.nio.ByteBuffer data,
            Operation<com.mojang.blaze3d.buffers.GpuBuffer> original, MeshData mesh) {
        var uploaded = cn.spectra.gallium.glowoutline.capture.NativeVertexUploadBatch.upload(
                //#if MC==1_21_08
                //$$ renderPipeline, mesh);
                //#else
                ((RenderType) (Object) this).pipeline(), mesh);
                //#endif
        return uploaded != null ? uploaded : original.call(format, data);
    }

    @WrapOperation(method = "draw", at = @At(value = "INVOKE", target =
            "Lcom/mojang/blaze3d/systems/RenderPass;drawIndexed(IIII)V", remap = false))
    private void galliumDrawNativeRange(com.mojang.blaze3d.systems.RenderPass pass,
            int baseVertex, int firstIndex, int indexCount, int instances, Operation<Void> original, MeshData mesh) {
        original.call(pass, cn.spectra.gallium.glowoutline.capture.NativeVertexUploadBatch.consumeBaseVertex(mesh, baseVertex),
                firstIndex, indexCount, instances);
    }
    //#endif
    //#if MC>=1_21_11
    @WrapOperation(method = "draw", at = @At(value = "INVOKE", target =
            "Lnet/minecraft/client/renderer/DynamicUniforms;writeTransform(Lorg/joml/Matrix4fc;Lorg/joml/Vector4fc;Lorg/joml/Vector3fc;Lorg/joml/Matrix4fc;)Lcom/mojang/blaze3d/buffers/GpuBufferSlice;"))
    private GpuBufferSlice galliumObserveTransform(DynamicUniforms uniforms, Matrix4fc modelView,
            Vector4fc color, Vector3fc offset, Matrix4fc texture, Operation<GpuBufferSlice> original, MeshData mesh) {
        //#if MC==1_26_01
        GpuBufferSlice result = cn.spectra.gallium.glowoutline.capture.NativeVertexUploadBatch.transform(
                modelView, color, offset, texture);
        if (result == null) {
            result = original.call(uniforms, modelView, color, offset, texture);
            cn.spectra.gallium.glowoutline.capture.NativeVertexUploadBatch.rememberTransform(
                    modelView, color, offset, texture, result);
        }
        //#else
        //$$ GpuBufferSlice result = original.call(uniforms, modelView, color, offset, texture);
        //#endif
        ModernMaskBounds.transformed(mesh, modelView);
        return result;
    }
    //#else
    //$$ @WrapOperation(method = "draw", at = @At(value = "INVOKE", target =
    //$$         "Lnet/minecraft/client/renderer/DynamicUniforms;writeTransform(Lorg/joml/Matrix4fc;Lorg/joml/Vector4fc;Lorg/joml/Vector3fc;Lorg/joml/Matrix4fc;F)Lcom/mojang/blaze3d/buffers/GpuBufferSlice;"))
    //$$ private GpuBufferSlice galliumObserveTransform(DynamicUniforms uniforms, Matrix4fc modelView,
    //$$         Vector4fc color, Vector3fc offset, Matrix4fc texture, float lineWidth,
    //$$         Operation<GpuBufferSlice> original, MeshData mesh) {
    //#if MC==1_21_08
    //$$     GpuBufferSlice result = cn.spectra.gallium.glowoutline.capture.NativeVertexUploadBatch.transform(
    //$$             modelView, color, offset, texture, lineWidth);
    //$$     if (result == null) {
    //$$         result = original.call(uniforms, modelView, color, offset, texture, lineWidth);
    //$$         cn.spectra.gallium.glowoutline.capture.NativeVertexUploadBatch.rememberTransform(
    //$$                 modelView, color, offset, texture, lineWidth, result);
    //$$     }
    //#else
    //$$     GpuBufferSlice result = original.call(uniforms, modelView, color, offset, texture, lineWidth);
    //#endif
    //$$     ModernMaskBounds.transformed(mesh, modelView);
    //$$     return result;
    //$$ }
    //#endif

    @Inject(method = "draw", at = @At(value = "INVOKE", target =
            "Lcom/mojang/blaze3d/systems/RenderPass;drawIndexed(IIII)V", shift = At.Shift.AFTER, remap = false))
    private void galliumObserveDraw(MeshData mesh, CallbackInfo callback) {
        ModernMaskBounds.drawn(mesh, ((RenderType) (Object) this).format());
    }

    //#if MC>=1_21_11
    @Inject(method = "draw", at = @At(value = "INVOKE",
            target = "Lorg/joml/Matrix4fStack;pushMatrix()Lorg/joml/Matrix4fStack;", shift = At.Shift.AFTER, remap = false))
    private void galliumNativeLayerPushed(MeshData mesh, CallbackInfo callback) {
        NativeMaskMeshReplay.layerPushed();
    }

    @Inject(method = "draw", at = @At(value = "INVOKE",
            target = "Lorg/joml/Matrix4fStack;popMatrix()Lorg/joml/Matrix4fStack;", shift = At.Shift.AFTER, remap = false))
    private void galliumNativeLayerPopped(MeshData mesh, CallbackInfo callback) {
        NativeMaskMeshReplay.layerPopped();
    }

    @Inject(method = "draw", at = @At("HEAD"), cancellable = true)
    private void galliumRecordNativeMesh(MeshData mesh, CallbackInfo callback) {
        //#if MC==1_26_01
        if (cn.spectra.gallium.glowoutline.shader.NativeMaskChannels.canOmit(((RenderType) (Object) this).pipeline())) {
            mesh.close();
            callback.cancel();
            return;
        }
        //#endif
        NativeMaskMeshReplay.record((RenderType) (Object) this, mesh);
    }
    //#endif
}
//#else
//$$ public abstract class NativeMaskMeshReplayMixin {}
//#endif
