package cn.spectra.gallium.glowoutline.mixin;
//#if MC==1_21_11
//$$ import net.minecraft.client.renderer.feature.ModelPartFeatureRenderer;
//$$ import net.minecraft.client.renderer.SubmitNodeStorage;
//$$ import net.minecraft.client.renderer.rendertype.RenderType;
//$$ import org.spongepowered.asm.mixin.Mixin;
//$$ import org.spongepowered.asm.mixin.injection.*;
//$$ import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
//$$ @Mixin(ModelPartFeatureRenderer.Storage.class)
//$$ public class NativeSourcePartStorageMixin {
//$$  @Inject(method="add",at=@At("HEAD"))private void source(RenderType type,SubmitNodeStorage.ModelPartSubmit submit,CallbackInfo ci){
//$$   cn.spectra.gallium.glowoutline.capture.NativeSourceCoverage.registered(submit);
//$$  }
//$$ }
//#else
public class NativeSourcePartStorageMixin {}
//#endif
