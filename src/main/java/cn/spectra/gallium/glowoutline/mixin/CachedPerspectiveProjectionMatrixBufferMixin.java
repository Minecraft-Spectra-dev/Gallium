package cn.spectra.gallium.glowoutline.mixin;

//#if MC>=1_26_00
public class CachedPerspectiveProjectionMatrixBufferMixin {}
//#elseif MC>=1_21_06
//$$ import cn.spectra.gallium.glowoutline.capture.ProjectionMatrixTracker;
//$$ import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
//$$ import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
//$$ import com.mojang.blaze3d.buffers.GpuBufferSlice;
//$$ import net.minecraft.client.renderer.CachedPerspectiveProjectionMatrixBuffer;
//$$ import org.joml.Matrix4f;
//$$ import org.spongepowered.asm.mixin.Mixin;
//#if MC==1_21_11
//$$ import org.spongepowered.asm.mixin.Unique;
//#endif
//$$ import org.spongepowered.asm.mixin.injection.At;
//$$
//$$ /**
//$$  * 1.21.11 hud3d path: {@code CachedPerspectiveProjectionMatrixBuffer.getBuffer(int, int, float)}
//$$  * builds a fresh perspective matrix on cache miss via private {@code createProjectionMatrix}.
//$$  * Wrap that call so we capture the matrix regardless of whether the local survives to RETURN
//$$  * (it doesn't on every javac output, which broke a previous @Local-based approach with
//$$  * "Found 0 candidate variables"). 1.21.11 retains the last successfully uploaded matrix on
//$$  * this buffer instance so cache hits can restore the association after a tracker reload.
//$$  */
//$$ @Mixin(CachedPerspectiveProjectionMatrixBuffer.class)
//$$ public class CachedPerspectiveProjectionMatrixBufferMixin {
//$$     // No remap=false: getBuffer/createProjectionMatrix are Mojang-mapped Minecraft
//$$     // methods and the 1.21.11 runtime is obfuscated. Refmap must translate both.
//$$     @WrapOperation(method = "getBuffer(IIF)Lcom/mojang/blaze3d/buffers/GpuBufferSlice;",
//$$             at = @At(value = "INVOKE",
//$$                     target = "Lnet/minecraft/client/renderer/CachedPerspectiveProjectionMatrixBuffer;createProjectionMatrix(IIF)Lorg/joml/Matrix4f;"))
//$$     private Matrix4f galliumCaptureMatrix(CachedPerspectiveProjectionMatrixBuffer self,
//$$                                            int width, int height, float fov,
//$$                                            Operation<Matrix4f> original) {
//$$         Matrix4f matrix4f = original.call(self, width, height, fov);
//#if MC==1_21_11
//$$         // The following native upload can mutate the buffer before throwing.
//$$         ProjectionMatrixTracker.forget(gallium$lastUploadedSlice);
//$$         gallium$lastUploadedSlice = null;
//$$         gallium$lastUploadedMatrix = null;
//$$         gallium$pendingMatrix = matrix4f;
//#else
//$$         // Stash for the @Inject at RETURN below to associate with the slice the method
//$$         // returns. Stashed/cleared inside this same call so it can never leak across
//$$         // unrelated paths.
//$$         CachedPerspectiveProjectionMatrixBufferMixin.pending = matrix4f;
//#endif
//$$         return matrix4f;
//$$     }
//$$
//$$     @org.spongepowered.asm.mixin.injection.Inject(
//$$             method = "getBuffer(IIF)Lcom/mojang/blaze3d/buffers/GpuBufferSlice;",
//$$             at = @At("RETURN"))
//$$     private void galliumAssociateSlice(int width, int height, float fov,
//$$                                         org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable<GpuBufferSlice> cir) {
//#if MC==1_21_11
//$$         Matrix4f matrix4f = gallium$pendingMatrix;
//$$         gallium$pendingMatrix = null;
//$$         GpuBufferSlice returned = cir.getReturnValue();
//$$         if (returned == null) return;
//$$         // RETURN proves createProjectionMatrix was followed by a successful vanilla upload.
//$$         if (matrix4f != null) {
//$$             if (gallium$matrixStorage == null) gallium$matrixStorage = new Matrix4f(matrix4f);
//$$             else gallium$matrixStorage.set(matrix4f);
//$$             gallium$lastUploadedMatrix = gallium$matrixStorage;
//$$             gallium$lastUploadedSlice = returned;
//$$         }
//$$         if (returned == gallium$lastUploadedSlice && gallium$lastUploadedMatrix != null) {
//$$             ProjectionMatrixTracker.remember(returned, gallium$lastUploadedMatrix);
//$$         }
//#else
//$$         Matrix4f matrix4f = pending;
//$$         pending = null;
//$$         GpuBufferSlice returned = cir.getReturnValue();
//$$         if (matrix4f != null && returned != null) {
//$$             ProjectionMatrixTracker.remember(returned, matrix4f);
//$$         }
//#endif
//$$     }
//$$
//#if MC==1_21_11
//$$     @Unique private Matrix4f gallium$pendingMatrix;
//$$     @Unique private Matrix4f gallium$lastUploadedMatrix;
//$$     @Unique private GpuBufferSlice gallium$lastUploadedSlice;
//$$     @Unique private Matrix4f gallium$matrixStorage;
//$$
//$$     @org.spongepowered.asm.mixin.injection.Inject(
//$$             method = "getBuffer(IIF)Lcom/mojang/blaze3d/buffers/GpuBufferSlice;",
//$$             at = @At("HEAD"))
//$$     private void galliumBeginCachedProjection(
//$$             org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable<GpuBufferSlice> cir) {
//$$         // A prior upload may have thrown after matrix creation but before RETURN.
//$$         gallium$pendingMatrix = null;
//$$     }
//#else
//$$     // Plain static field, not ThreadLocal: vanilla render runs on a single thread and
//$$     // pending is set + cleared within a single getBuffer invocation, so there is no
//$$     // contention. Avoiding ThreadLocal saves a Map lookup on the hot path.
//$$     private static Matrix4f pending;
//#endif
//$$ }
//#else
//$$ public class CachedPerspectiveProjectionMatrixBufferMixin {}
//#endif
