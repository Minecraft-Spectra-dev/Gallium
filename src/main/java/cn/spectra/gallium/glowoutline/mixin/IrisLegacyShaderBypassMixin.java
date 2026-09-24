package cn.spectra.gallium.glowoutline.mixin;

//#if MC<1_21_05
//$$ import cn.spectra.gallium.glowoutline.IrisCompat;
//$$ import org.spongepowered.asm.mixin.Mixin;
//$$ import org.spongepowered.asm.mixin.Pseudo;
//$$ import org.spongepowered.asm.mixin.injection.At;
//$$ import org.spongepowered.asm.mixin.injection.Inject;
//$$ import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
//$$
//$$ /** Older Iris releases select vanilla entity shaders through this gate. */
//$$ @Pseudo
//$$ @Mixin(targets = "net.irisshaders.iris.pipeline.IrisRenderingPipeline", remap = false)
//$$ public abstract class IrisLegacyShaderBypassMixin {
//$$     @Inject(method = "shouldOverrideShaders()Z", at = @At("HEAD"), cancellable = true, require = 0)
//$$     private void galliumLegacyShaderBypass(CallbackInfoReturnable<Boolean> callback) {
//$$         if (IrisCompat.legacyShaderBypassForCall()) callback.setReturnValue(false);
//$$     }
//$$ }
//#else
public abstract class IrisLegacyShaderBypassMixin {}
//#endif
