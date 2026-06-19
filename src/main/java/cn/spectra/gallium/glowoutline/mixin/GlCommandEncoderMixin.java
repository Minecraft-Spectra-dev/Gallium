package cn.spectra.gallium.glowoutline.mixin;

//#if MC>=1_21_05
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.opengl.DirectStateAccess;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Fixes a vanilla blit-coordinate bug in {@code GlCommandEncoder.copyTextureToTexture}:
 * it forwards {@code (srcX, srcY, width, height, dstX, dstY, width, height)} to
 * {@code DirectStateAccess.blitFrameBuffers}, but that method expects the source/dest
 * <em>opposite corners</em> {@code (x0, y0, x1, y1)} — so it should receive
 * {@code srcX+width / srcY+height / dstX+width / dstY+height}. The bug is harmless when the
 * copy starts at the origin (x1 == width when x0 == 0), which is why full-texture copies
 * (the world glow path) never tripped it. Sub-rectangle copies with non-zero offsets — the
 * GUI slot-packed mask copy — read/write the wrong region without this fix.
 * <p>
 * Verified present on 1.21.10, 1.21.11 and 26.1 (all currently-built versions). The wrap is a
 * strict no-op at zero offset, so applying it unconditionally is safe for vanilla's own
 * copyTextureToTexture callers too. If a future Minecraft version fixes the vanilla code,
 * this mixin must be re-gated (it would otherwise double-offset).
 * <p>
 * {@code GlCommandEncoder} is package-private on 26.1, so it is referenced by {@code targets=}
 * string rather than a class literal; this also keeps one shared source across all versions.
 */
@Mixin(targets = "com.mojang.blaze3d.opengl.GlCommandEncoder")
public class GlCommandEncoderMixin {

    @WrapOperation(method = "copyTextureToTexture", at = @At(value = "INVOKE",
            // blitFrameBuffers is package-private blaze3d → obfuscated on loom-remap runtimes,
            // must remap. (Public blaze3d members like RenderPass.setPipeline keep their names
            // and can use remap=false.)
            target = "Lcom/mojang/blaze3d/opengl/DirectStateAccess;blitFrameBuffers(IIIIIIIIIIII)V"))
    private void galliumFixBlitCoords(DirectStateAccess directStateAccess,
                                       int source, int dest,
                                       // NB: vanilla passes its (width, height) into these slots — that's the
                                       // bug. We bind them as srcWidth/srcHeight/dstWidth/dstHeight and convert
                                       // each to the absolute far corner (offset + size) below. The names reflect
                                       // the value vanilla *passes*, not blitFrameBuffers' (x1, y1) parameter.
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
//#else
//$$ public class GlCommandEncoderMixin {}
//#endif
