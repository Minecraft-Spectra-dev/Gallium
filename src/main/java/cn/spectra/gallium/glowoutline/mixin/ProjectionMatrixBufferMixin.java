package cn.spectra.gallium.glowoutline.mixin;

//#if MC>=1_26_00
import cn.spectra.gallium.glowoutline.capture.ProjectionMatrixTracker;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import net.minecraft.client.renderer.ProjectionMatrixBuffer;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
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
    @Inject(method = "writeBuffer", at = @At("RETURN"))
    private void galliumRememberMatrix(Matrix4f projectionMatrix,
                                        CallbackInfoReturnable<GpuBufferSlice> cir) {
        GpuBufferSlice returned = cir.getReturnValue();
        if (returned != null) ProjectionMatrixTracker.remember(returned, projectionMatrix);
    }
}
//#else
//$$ public class ProjectionMatrixBufferMixin {}
//#endif
