package cn.spectra.gallium.glowoutline.mixin;

//#if MC>=1_26_00
import cn.spectra.gallium.glowoutline.capture.ProjectionMatrixTracker;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import net.minecraft.client.renderer.ProjectionMatrixBuffer;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
//#if MC==1_26_01
import org.spongepowered.asm.mixin.Unique;
//#endif
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 26.1 path: every projection-matrix UBO upload funnels through
 * {@code ProjectionMatrixBuffer.writeBuffer(Matrix4f)}, so a single hook remembers
 * the slice→matrix association no matter which {@code getBuffer(...)} overload was
 * called.
 */
@Mixin(ProjectionMatrixBuffer.class)
public class ProjectionMatrixBufferMixin {
    //#if MC==1_26_01
    /** Survives tracker reloads exactly as long as this vanilla buffer and its cached contents. */
    @Unique private Matrix4f gallium$lastUploadedMatrix;
    @Unique private GpuBufferSlice gallium$lastUploadedSlice;
    /** Reusable CPU storage; only lastUploadedMatrix/lastUploadedSlice publish valid proof. */
    @Unique private Matrix4f gallium$matrixStorage;

    @Inject(method = "writeBuffer", at = @At("HEAD"))
    private void galliumBeginUpload(CallbackInfoReturnable<GpuBufferSlice> cir) {
        ProjectionMatrixTracker.forget(gallium$lastUploadedSlice);
        gallium$lastUploadedSlice = null;
        gallium$lastUploadedMatrix = null;
    }
    //#endif

    @Inject(method = "writeBuffer", at = @At("RETURN"))
    private void galliumRememberMatrix(Matrix4f projectionMatrix,
                                        CallbackInfoReturnable<GpuBufferSlice> cir) {
        GpuBufferSlice returned = cir.getReturnValue();
        if (returned != null) {
            //#if MC==1_26_01
            if (gallium$matrixStorage == null) gallium$matrixStorage = new Matrix4f(projectionMatrix);
            else gallium$matrixStorage.set(projectionMatrix);
            gallium$lastUploadedMatrix = gallium$matrixStorage;
            gallium$lastUploadedSlice = returned;
            //#endif
            ProjectionMatrixTracker.remember(returned, projectionMatrix);
        }
    }

    //#if MC==1_26_01
    /** Cache hits skip writeBuffer even after Gallium's resource reload cleared the tracker. */
    @Inject(method = "getBuffer(Lnet/minecraft/client/renderer/Projection;)Lcom/mojang/blaze3d/buffers/GpuBufferSlice;",
            at = @At("RETURN"))
    private void galliumRestoreCachedMatrix(CallbackInfoReturnable<GpuBufferSlice> cir) {
        GpuBufferSlice returned = cir.getReturnValue();
        if (returned != null && returned == gallium$lastUploadedSlice && gallium$lastUploadedMatrix != null) {
            ProjectionMatrixTracker.remember(returned, gallium$lastUploadedMatrix);
        }
    }
    //#endif
}
//#else
//$$ public class ProjectionMatrixBufferMixin {}
//#endif
