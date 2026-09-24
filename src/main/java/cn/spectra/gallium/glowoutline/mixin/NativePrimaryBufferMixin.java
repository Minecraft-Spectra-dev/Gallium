package cn.spectra.gallium.glowoutline.mixin;
//#if MC==1_21_11 || MC==1_26_01
import cn.spectra.gallium.glowoutline.capture.NativePrimarySources;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.rendertype.RenderType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
@Mixin(MultiBufferSource.BufferSource.class)
public class NativePrimaryBufferMixin {
 @Inject(method="getBuffer",at=@At("RETURN"))
 private void gallium$sourceBuffer(RenderType type,CallbackInfoReturnable<VertexConsumer> ci){NativePrimarySources.buffer(ci.getReturnValue(),type);}
}
//#else
//$$ public class NativePrimaryBufferMixin {}
//#endif
