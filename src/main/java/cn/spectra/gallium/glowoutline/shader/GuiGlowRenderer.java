package cn.spectra.gallium.glowoutline.shader;

//#if MC>=1_21_06
import cn.spectra.gallium.glowoutline.capture.GuiGlowCapture;
import cn.spectra.gallium.glowoutline.capture.GuiGlowCaptureManager;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTexture;

public final class GuiGlowRenderer {

    private static TextureTarget maskTarget;
    private static long lastCopyErrorLogNanos;
    private static final long COPY_ERROR_LOG_INTERVAL_NANOS = 5_000_000_000L; // 5s

    static {
        GlowResources.register(GuiGlowRenderer::dispose);
    }

    private GuiGlowRenderer() {}

    private static void dispose() {
        if (maskTarget != null) {
            maskTarget.destroyBuffers();
            maskTarget = null;
        }
    }

    public static TextureTarget ensureMaskTarget(int screenW, int screenH) {
        if (maskTarget == null || maskTarget.width != screenW || maskTarget.height != screenH) {
            if (maskTarget != null) maskTarget.destroyBuffers();
            maskTarget = new TextureTarget("GuiGlowMask", screenW, screenH, false);
        }
        return maskTarget;
    }

    public static TextureTarget renderMaskOnly(int screenW, int screenH) {
        if (GuiGlowCaptureManager.getActive().isEmpty()) return null;
        ensureMaskTarget(screenW, screenH);

        var encoder = RenderSystem.getDevice().createCommandEncoder();
        encoder.clearColorTexture(maskTarget.getColorTexture(), 0);

        GpuTexture maskTex = maskTarget.getColorTexture();
        int actualMaskW = maskTex.getWidth(0);
        int actualMaskH = maskTex.getHeight(0);

        for (GuiGlowCapture c : GuiGlowCaptureManager.getActive()) {
            if (c.atlasTextureView == null) continue;

            GpuTexture atlasTex = c.atlasTextureView.texture();
            int atlasW = atlasTex.getWidth(0);
            int atlasH = atlasTex.getHeight(0);

            // Atlas source rect. u0 is the slot's left, v1 its bottom in atlas-uv y-up
            // (v0 > v1), so the bottom maps to the smaller pixel row — matching the upright
            // copy that the original 26.1 path used.
            int srcX = Math.round(c.u0 * atlasW);
            int srcY = Math.round(c.v1 * atlasH);

            // Destination is the capture's pre-assigned cell interior (framebuffer px, y-up
            // origin to match copyTextureToTexture / glBlitFramebuffer). The cell layout
            // guarantees a transparent guard band around this interior.
            int dstX = c.slotInteriorFbX;
            int dstY = c.slotInteriorFbY;
            int copyW = c.slotInteriorFbW;
            int copyH = c.slotInteriorFbH;

            // Skip degenerate cells. copyW/copyH derive from the cell-layout rounding
            // (interiorFbSize = slotFbSize - 2*padFb); a non-positive size means a layout
            // or guiScale regression, so surface it loudly rather than issuing a silent
            // no-op / corrupt blit.
            if (copyW <= 0 || copyH <= 0) {
                long now = System.nanoTime();
                if (now - lastCopyErrorLogNanos > COPY_ERROR_LOG_INTERVAL_NANOS) {
                    lastCopyErrorLogNanos = now;
                    cn.spectra.gallium.Gallium.LOGGER.warn(
                        "GuiGlow skip: non-positive copy size ({}x{}) for cell at dst=({},{}); check cell-layout rounding",
                        copyW, copyH, dstX, dstY);
                }
                continue;
            }

            // Skip if either source or destination region is out of bounds.
            if (srcX < 0 || srcY < 0 || srcX + copyW > atlasW || srcY + copyH > atlasH) continue;
            if (dstX < 0 || dstY < 0 || dstX + copyW > actualMaskW || dstY + copyH > actualMaskH) continue;

            try {
                // CommandEncoder.copyTextureToTexture signature: (src, dst, mip, destX, destY, srcX, srcY, w, h)
                encoder.copyTextureToTexture(atlasTex, maskTex, 0, dstX, dstY, srcX, srcY, copyW, copyH);
            } catch (IllegalArgumentException e) {
                long now = System.nanoTime();
                if (now - lastCopyErrorLogNanos > COPY_ERROR_LOG_INTERVAL_NANOS) {
                    lastCopyErrorLogNanos = now;
                    cn.spectra.gallium.Gallium.LOGGER.warn(
                        "GuiGlow copy failed: u0={} v1={} -> atlas=({}x{}) src=({},{}) dst=({},{}) copy=({}x{}) mask=({}x{}) err={}",
                        c.u0, c.v1, atlasW, atlasH, srcX, srcY, dstX, dstY, copyW, copyH, actualMaskW, actualMaskH, e.getMessage());
                }
            }
        }

        return maskTarget;
    }

    public static TextureTarget getMaskTarget() {
        return maskTarget;
    }
}
//#else
//$$ import com.mojang.blaze3d.pipeline.TextureTarget;
//$$
//$$ public final class GuiGlowRenderer {
//$$     private GuiGlowRenderer() {}
//$$     static { GlowResources.register(() -> {}); }
//$$     public static TextureTarget ensureMaskTarget(int w, int h) { return null; }
//$$     public static TextureTarget getMaskTarget() { return null; }
//$$ }
//#endif
