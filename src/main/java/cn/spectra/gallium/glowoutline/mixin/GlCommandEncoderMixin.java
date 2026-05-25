package cn.spectra.gallium.glowoutline.mixin;

import cn.spectra.gallium.Gallium;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.opengl.DirectStateAccess;
import net.minecraft.SharedConstants;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(targets = "com.mojang.blaze3d.opengl.GlCommandEncoder")
public class GlCommandEncoderMixin {

    /**
     * Vanilla {@code GlCommandEncoder.copyTextureToTexture} passes (width, height) where
     * {@code blitFrameBuffers} expects (x1, y1) absolute coords. This wrap converts size→absolute.
     * <p>
     * Verified against Minecraft 26.1.x. Disabled on other versions to avoid double-converting once
     * Mojang fixes upstream — set to {@link Boolean#TRUE} only when {@link SharedConstants#getCurrentVersion()}
     * reports a 26.1 version, lazily on first invocation.
     */
    private static volatile Boolean galliumApplyFix;

    private static boolean galliumShouldApply() {
        Boolean cached = galliumApplyFix;
        if (cached != null) return cached;
        try {
            String name = SharedConstants.getCurrentVersion().name();
            boolean apply = name.startsWith("26.1");
            if (!apply) {
                Gallium.LOGGER.info(
                    "GlCommandEncoder blit-coord fix disabled: detected version '{}' (expected 26.1.x)", name);
            }
            galliumApplyFix = apply;
            return apply;
        } catch (Throwable t) {
            galliumApplyFix = Boolean.FALSE;
            Gallium.LOGGER.warn("Failed to detect MC version, blit-coord fix disabled: {}", t.toString());
            return false;
        }
    }

    @WrapOperation(method = "copyTextureToTexture", at = @At(value = "INVOKE",
            target = "Lcom/mojang/blaze3d/opengl/DirectStateAccess;blitFrameBuffers(IIIIIIIIIIII)V"))
    private void galliumFixBlitCoords(DirectStateAccess directStateAccess,
                                       int source, int dest,
                                       int srcX, int srcY, int srcWidth, int srcHeight,
                                       int dstX, int dstY, int dstWidth, int dstHeight,
                                       int mask, int filter,
                                       Operation<Void> original) {
        if (!galliumShouldApply()) {
            original.call(directStateAccess, source, dest,
                    srcX, srcY, srcWidth, srcHeight, dstX, dstY, dstWidth, dstHeight, mask, filter);
            return;
        }
        original.call(directStateAccess,
                source, dest,
                srcX, srcY, srcX + srcWidth, srcY + srcHeight,
                dstX, dstY, dstX + dstWidth, dstY + dstHeight,
                mask, filter);
    }
}
