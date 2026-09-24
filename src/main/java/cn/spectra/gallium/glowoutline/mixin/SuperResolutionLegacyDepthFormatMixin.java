package cn.spectra.gallium.glowoutline.mixin;

//#if MC==1_21_01
//$$ import cn.spectra.gallium.glowoutline.sr.SuperResolutionLegacyDepthFormat;
//$$ import org.spongepowered.asm.mixin.Mixin;
//$$ import org.spongepowered.asm.mixin.Pseudo;
//$$ import org.spongepowered.asm.mixin.injection.At;
//$$ import org.spongepowered.asm.mixin.injection.Inject;
//$$ import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
//$$
//$$ /** 1.21.1 can expose unsized depth enums, which SR cannot use for immutable storage. */
//$$ @Pseudo
//$$ @Mixin(targets = "io.homo.superresolution.shadercompat.GlTextureInfoGetter", remap = false)
//$$ public abstract class SuperResolutionLegacyDepthFormatMixin {
//$$     @Inject(method = "getInternalFormat(II)I", at = @At("RETURN"),
//$$             cancellable = true, require = 0, remap = false)
//$$     private static void galliumResolveDepthStorage(int target, int texture,
//$$                                                    CallbackInfoReturnable<Integer> result) {
//$$         int original = result.getReturnValueI();
//$$         int resolved = SuperResolutionLegacyDepthFormat.resolve(target, texture, original);
//$$         if (resolved != original) result.setReturnValue(resolved);
//$$     }
//$$ }
//#else
public final class SuperResolutionLegacyDepthFormatMixin {}
//#endif
