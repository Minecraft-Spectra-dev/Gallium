package cn.spectra.gallium.glowoutline.mixin;

//#if MC<1_26_00
//$$ import cn.spectra.gallium.glowoutline.capture.ProjectionMatrixTracker;
//$$ import com.mojang.blaze3d.buffers.GpuBufferSlice;
//$$ import net.minecraft.client.renderer.PerspectiveProjectionMatrixBuffer;
//$$ import org.joml.Matrix4f;
//$$ import org.spongepowered.asm.mixin.Mixin;
//$$ import org.spongepowered.asm.mixin.injection.At;
//$$ import org.spongepowered.asm.mixin.injection.Inject;
//$$ import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
//$$
//$$ /**
//$$  * 1.21.11 level / generic perspective path: {@code PerspectiveProjectionMatrixBuffer.getBuffer(Matrix4f)}
//$$  * uploads the matrix and returns its cached slice. We remember the (slice, matrix) pair
//$$  * so the capture site can recover the matrix from a slice obtained via
//$$  * {@code RenderSystem.getProjectionMatrixBuffer()}.
//$$  */
//$$ @Mixin(PerspectiveProjectionMatrixBuffer.class)
//$$ public class PerspectiveProjectionMatrixBufferMixin {
//$$     @Inject(method = "getBuffer", at = @At("RETURN"), remap = false)
//$$     private void galliumRememberMatrix(Matrix4f matrix4f,
//$$                                         CallbackInfoReturnable<GpuBufferSlice> cir) {
//$$         GpuBufferSlice returned = cir.getReturnValue();
//$$         if (returned != null) ProjectionMatrixTracker.remember(returned, matrix4f);
//$$     }
//$$ }
//#else
public class PerspectiveProjectionMatrixBufferMixin {}
//#endif
