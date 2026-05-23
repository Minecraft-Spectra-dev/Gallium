package cn.spectra.gallium.glowoutline.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.opengl.DirectStateAccess;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(targets = "com.mojang.blaze3d.opengl.GlCommandEncoder")
public class GlCommandEncoderMixin {

    @WrapOperation(method = "copyTextureToTexture", at = @At(value = "INVOKE",
            target = "Lcom/mojang/blaze3d/opengl/DirectStateAccess;blitFrameBuffers(IIIIIIIIIIII)V"))
    private void galliumFixBlitCoords(DirectStateAccess directStateAccess,
                                       int source, int dest,
                                       int srcX, int srcY, int srcWidth, int srcHeight,
                                       int dstX, int dstY, int dstWidth, int dstHeight,
                                       int mask, int filter,
                                       Operation<Void> original) {
        original.call(directStateAccess,
                source, dest,
                srcX, srcY, srcX + srcWidth, srcY + srcHeight,
                dstX, dstY, dstX + dstWidth, dstY + dstHeight,
                mask, filter);
    }
}
