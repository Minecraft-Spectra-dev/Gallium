package cn.spectra.gallium.glowoutline.mixin;

//#if MC<1_21_05
//$$ import cn.spectra.gallium.glowoutline.shader.CachedGlowUniform;
//$$ import cn.spectra.gallium.glowoutline.shader.UniformUploadCache;
//$$ import com.mojang.blaze3d.shaders.Uniform;
//$$ import java.nio.FloatBuffer;
//$$ import java.nio.IntBuffer;
//$$ import org.spongepowered.asm.mixin.*;
//$$ import org.spongepowered.asm.mixin.injection.At;
//$$ import org.spongepowered.asm.mixin.injection.Inject;
//$$ import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
//$$
//$$ @Mixin(Uniform.class)
//$$ public abstract class GlowUniformUploadMixin implements CachedGlowUniform {
//$$     @Shadow private int location;
//#if MC<1_21_02
//$$     @Shadow private boolean dirty;
//#endif
//$$     @Shadow @Final private int count;
//$$     @Shadow @Final private FloatBuffer floatValues;
//$$     @Shadow @Final private IntBuffer intValues;
//$$     @Unique private UniformUploadCache gallium$uploads;
//$$
//$$     @Override public void gallium$enableUploadCache() {
//$$         if (gallium$uploads == null && count > 0 && count <= 64)
//$$             gallium$uploads = new UniformUploadCache(count);
//$$     }
//$$
//$$     @Inject(method = "setLocation", at = @At("HEAD"))
//$$     private void galliumInvalidateUpload(int nextLocation, CallbackInfo callback) {
//$$         if (gallium$uploads != null) gallium$uploads.invalidate();
//$$     }
//$$
//$$     @Inject(method = "upload", at = @At("HEAD"), cancellable = true)
//$$     private void galliumSkipIdenticalUpload(CallbackInfo callback) {
//$$         if (gallium$uploads == null) return;
//$$         boolean same = floatValues != null ? gallium$uploads.matches(location, floatValues)
//$$                 : intValues != null && gallium$uploads.matches(location, intValues);
//$$         if (same) {
//$$             // Vanilla rewinds the upload view even if callers changed its position.
//$$             if (floatValues != null) floatValues.rewind(); else intValues.rewind();
//#if MC<1_21_02
//$$             dirty = false;
//#endif
//$$             callback.cancel();
//$$         }
//$$     }
//$$
//$$     @Inject(method = "upload", at = @At("RETURN"))
//$$     private void galliumRememberUpload(CallbackInfo callback) {
//$$         if (gallium$uploads == null || location < 0) return;
//$$         if (floatValues != null) gallium$uploads.uploaded(location, floatValues);
//$$         else if (intValues != null) gallium$uploads.uploaded(location, intValues);
//$$     }
//$$ }
//#else
public abstract class GlowUniformUploadMixin {}
//#endif
