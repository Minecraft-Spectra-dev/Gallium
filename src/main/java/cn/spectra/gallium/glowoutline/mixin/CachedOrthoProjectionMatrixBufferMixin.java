package cn.spectra.gallium.glowoutline.mixin;

//#if MC>=1_21_06 && MC<1_26_00
//$$ import cn.spectra.gallium.glowoutline.capture.ProjectionMatrixTracker;
//$$ import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
//$$ import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
//$$ import com.mojang.blaze3d.buffers.GpuBufferSlice;
//$$ import net.minecraft.client.renderer.CachedOrthoProjectionMatrixBuffer;
//$$ import org.joml.Matrix4f;
//$$ import org.spongepowered.asm.mixin.Mixin;
//$$ import org.spongepowered.asm.mixin.Unique;
//$$ import org.spongepowered.asm.mixin.injection.At;
//$$ import org.spongepowered.asm.mixin.injection.Inject;
//$$ import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
//$$
//$$ /** GUI PIP uses an orthographic UBO, not either of the world's perspective buffer classes. */
//$$ @Mixin(CachedOrthoProjectionMatrixBuffer.class)
//$$ public class CachedOrthoProjectionMatrixBufferMixin {
//$$     @Unique private Matrix4f gallium$pending;
//$$     @Unique private Matrix4f gallium$matrix;
//$$     @Unique private GpuBufferSlice gallium$slice;
//$$
//$$     @Inject(method = "getBuffer", at = @At("HEAD"))
//$$     private void galliumBegin(float width, float height, CallbackInfoReturnable<GpuBufferSlice> ci) {
//$$         gallium$pending = null;
//$$     }
//$$
//$$     @WrapOperation(method = "getBuffer", at = @At(value = "INVOKE",
//$$             target = "Lnet/minecraft/client/renderer/CachedOrthoProjectionMatrixBuffer;createProjectionMatrix(FF)Lorg/joml/Matrix4f;"))
//$$     private Matrix4f galliumSnapshot(CachedOrthoProjectionMatrixBuffer self, float width, float height,
//$$                                      Operation<Matrix4f> original) {
//$$         Matrix4f matrix = original.call(self, width, height);
//$$         ProjectionMatrixTracker.forget(gallium$slice);
//$$         gallium$slice = null;
//$$         gallium$matrix = null;
//$$         gallium$pending = new Matrix4f(matrix);
//$$         return matrix;
//$$     }
//$$
//$$     @Inject(method = "getBuffer", at = @At("RETURN"))
//$$     private void galliumRemember(float width, float height, CallbackInfoReturnable<GpuBufferSlice> ci) {
//$$         var returned = ci.getReturnValue();
//$$         if (returned == null) return;
//$$         if (gallium$pending != null) {
//$$             gallium$matrix = gallium$pending;
//$$             gallium$slice = returned;
//$$             gallium$pending = null;
//$$         }
//$$         if (returned == gallium$slice && gallium$matrix != null) {
//$$             ProjectionMatrixTracker.remember(returned, gallium$matrix);
//$$         }
//$$     }
//$$ }
//#else
public final class CachedOrthoProjectionMatrixBufferMixin {}
//#endif
