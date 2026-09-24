package cn.spectra.gallium.glowoutline.mixin;
//#if MC==1_21_11 || MC==1_26_01
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.client.renderer.SubmitNodeStorage;
import net.minecraft.client.renderer.rendertype.RenderType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
@Mixin(ModelFeatureRenderer.Storage.class)
public class NativeSourceModelStorageMixin {
 @Inject(method="add",at=@At("HEAD"))private void gallium$source(RenderType type,SubmitNodeStorage.ModelSubmit<?> submit,CallbackInfo ci){cn.spectra.gallium.glowoutline.capture.NativeSourceCoverage.registered(submit);}
}
//#else
//$$ public class NativeSourceModelStorageMixin {}
//#endif
