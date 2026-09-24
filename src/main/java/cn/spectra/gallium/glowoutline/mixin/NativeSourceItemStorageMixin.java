package cn.spectra.gallium.glowoutline.mixin;
//#if MC==1_21_11 || MC==1_26_01
import net.minecraft.client.renderer.SubmitNodeCollection;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
@Mixin(SubmitNodeCollection.class)
public class NativeSourceItemStorageMixin {
 @Inject(method="submitItem",at=@At("RETURN"))private void gallium$source(CallbackInfo ci){var items=((SubmitNodeCollection)(Object)this).getItemSubmits();if(!items.isEmpty())cn.spectra.gallium.glowoutline.capture.NativeSourceCoverage.registered(items.getLast());}
}
//#else
//$$ public class NativeSourceItemStorageMixin {}
//#endif
