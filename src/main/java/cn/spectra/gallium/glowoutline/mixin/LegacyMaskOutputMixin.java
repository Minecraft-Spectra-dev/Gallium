package cn.spectra.gallium.glowoutline.mixin;

//#if MC<1_21_05
//$$ import cn.spectra.gallium.glowoutline.capture.LegacyOutputBindings;
//$$ import net.minecraft.client.renderer.RenderStateShard;
//$$ import org.spongepowered.asm.mixin.Mixin;
//$$ import org.spongepowered.asm.mixin.injection.At;
//$$ import org.spongepowered.asm.mixin.injection.Inject;
//$$ import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
//$$
//$$ @Mixin(RenderStateShard.class)
//$$ public abstract class LegacyMaskOutputMixin {
//$$     @Inject(method = {"setupRenderState", "clearRenderState"}, at = @At("HEAD"), cancellable = true)
//$$     private void galliumKeepMaskOutput(CallbackInfo callback) {
//$$         if (LegacyOutputBindings.skip((RenderStateShard) (Object) this)) callback.cancel();
//$$     }
//$$ }
//#else
public abstract class LegacyMaskOutputMixin {}
//#endif
