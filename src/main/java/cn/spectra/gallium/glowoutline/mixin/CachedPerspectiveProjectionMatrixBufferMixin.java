package cn.spectra.gallium.glowoutline.mixin;

//#if MC<1_26_00
//$$ import cn.spectra.gallium.glowoutline.capture.ProjectionMatrixTracker;
//$$ import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
//$$ import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
//$$ import com.mojang.blaze3d.buffers.GpuBufferSlice;
//$$ import net.minecraft.client.renderer.CachedPerspectiveProjectionMatrixBuffer;
//$$ import org.joml.Matrix4f;
//$$ import org.spongepowered.asm.mixin.Mixin;
//$$ import org.spongepowered.asm.mixin.injection.At;
//$$
//$$ /**
//$$  * 1.21.11 hud3d path: {@code CachedPerspectiveProjectionMatrixBuffer.getBuffer(int, int, float)}
//$$  * builds a fresh perspective matrix on cache miss via private {@code createProjectionMatrix}.
//$$  * Wrap that call so we capture the matrix regardless of whether the local survives to RETURN
//$$  * (it doesn't on every javac output, which broke a previous @Local-based approach with
//$$  * "Found 0 candidate variables"). On a cache hit createProjectionMatrix is skipped — the
//$$  * previous association still holds because the buffer's slice and contents are unchanged
//$$  * until the next miss rewrites them.
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
//$$         // Stash for the @Inject at RETURN below to associate with the slice the method
//$$         // returns. Stashed/cleared inside this same call so it can never leak across
//$$         // unrelated paths.
//$$         CachedPerspectiveProjectionMatrixBufferMixin.pending = matrix4f;
//$$         return matrix4f;
//$$     }
//$$
//$$     @org.spongepowered.asm.mixin.injection.Inject(
//$$             method = "getBuffer(IIF)Lcom/mojang/blaze3d/buffers/GpuBufferSlice;",
//$$             at = @At("RETURN"))
//$$     private void galliumAssociateSlice(int width, int height, float fov,
//$$                                         org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable<GpuBufferSlice> cir) {
//$$         Matrix4f matrix4f = pending;
//$$         pending = null;
//$$         GpuBufferSlice returned = cir.getReturnValue();
//$$         if (matrix4f != null && returned != null) {
//$$             ProjectionMatrixTracker.remember(returned, matrix4f);
//$$         }
//$$     }
//$$
//$$     // Plain static field, not ThreadLocal: vanilla render runs on a single thread and
//$$     // pending is set + cleared within a single getBuffer invocation, so there is no
//$$     // contention. Avoiding ThreadLocal saves a Map lookup on the hot path.
//$$     private static Matrix4f pending;
//$$ }
//#else
public class CachedPerspectiveProjectionMatrixBufferMixin {}
//#endif
