package cn.spectra.gallium.glowoutline.mixin;
//#if MC==1_21_11 || MC==1_26_01
import cn.spectra.gallium.glowoutline.capture.NativePrimarySources;
import com.mojang.blaze3d.vertex.*;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
@Mixin(value=BufferBuilder.class,priority=500)
public class NativePrimaryBuilderMixin {
 @Inject(method="build",at=@At("HEAD"))
 private void gallium$closeSourceRange(CallbackInfoReturnable<MeshData> ci){NativePrimarySources.building((BufferBuilder)(Object)this);}
 @Inject(method="build",at=@At("RETURN"))
 private void gallium$tagSourceRanges(CallbackInfoReturnable<MeshData> ci){NativePrimarySources.built((BufferBuilder)(Object)this,ci.getReturnValue());}
}
//#else
//$$ public class NativePrimaryBuilderMixin {}
//#endif
