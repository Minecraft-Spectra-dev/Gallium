package cn.spectra.gallium.glowoutline.mixin;

//#if MC==1_21_01 || MC==1_21_11 || MC>=1_26_01
import cn.spectra.gallium.glowoutline.sr.SuperResolutionTextureFilter;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArgs;
import org.spongepowered.asm.mixin.injection.invoke.arg.Args;

/** Mipmaps are valid only for minification; FSR2 creates a mipmapped luminance texture. */
@Pseudo
@Mixin(targets = "io.homo.superresolution.core.graphics.opengl.texture.GlTexture2D", remap = false)
public abstract class SuperResolutionTextureFilterMixin {
    @ModifyArgs(method = "configureTextureParameters()V", at = @At(value = "INVOKE",
            target = "Lio/homo/superresolution/core/graphics/opengl/dsa/IGlDirectStateAccess;textureParameteri(III)V"),
            require = 0, remap = false)
    private void galliumLegalMagnificationFilter(Args args) {
        int value = args.get(2);
        int legal = SuperResolutionTextureFilter.legalParameter(args.get(1), value);
        if (legal != value) args.set(2, legal);
    }
}
//#else
//$$ public final class SuperResolutionTextureFilterMixin {}
//#endif
