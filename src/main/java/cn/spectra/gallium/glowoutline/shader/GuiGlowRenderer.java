package cn.spectra.gallium.glowoutline.shader;

import cn.spectra.gallium.glowoutline.capture.GuiGlowCapture;
import cn.spectra.gallium.glowoutline.capture.GuiGlowCaptureManager;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTexture;
import net.minecraft.client.Minecraft;
import org.joml.Vector2f;

public final class GuiGlowRenderer {

    private static TextureTarget maskTarget;

    private GuiGlowRenderer() {}

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

        double guiScale = Minecraft.getInstance().getWindow().getGuiScale();
        int slotPixelSize = (int) Math.round(16 * guiScale);
        GpuTexture maskTex = maskTarget.getColorTexture();
        int actualMaskW = maskTex.getWidth(0);
        int actualMaskH = maskTex.getHeight(0);

        for (GuiGlowCapture c : GuiGlowCaptureManager.getActive()) {
            if (c.atlasTextureView == null) continue;

            GpuTexture atlasTex = c.atlasTextureView.texture();
            int atlasW = atlasTex.getWidth(0);
            int atlasH = atlasTex.getHeight(0);

            int srcX = Math.round(c.u0 * atlasW);
            int srcY = Math.round(c.v1 * atlasH);
            int copyW = slotPixelSize;
            int copyH = slotPixelSize;

            Vector2f topLeft = c.pose.transformPosition(new Vector2f(c.x, c.y));
            Vector2f bottomRight = c.pose.transformPosition(new Vector2f(c.x + 16, c.y + 16));
            int fbX0 = Math.round(topLeft.x * (float) guiScale);
            int fbY1 = Math.round(bottomRight.y * (float) guiScale);

            int dstX = fbX0;
            int dstY = actualMaskH - fbY1; // top-down to bottom-up

            // Skip if either source or destination region is out of bounds.
            if (srcX < 0 || srcY < 0 || srcX + copyW > atlasW || srcY + copyH > atlasH) continue;
            if (dstX < 0 || dstY < 0 || dstX + copyW > actualMaskW || dstY + copyH > actualMaskH) continue;

            try {
                // CommandEncoder.copyTextureToTexture signature: (src, dst, mip, destX, destY, srcX, srcY, w, h)
                encoder.copyTextureToTexture(atlasTex, maskTex, 0, dstX, dstY, srcX, srcY, copyW, copyH);
            } catch (IllegalArgumentException e) {
                cn.spectra.gallium.Gallium.LOGGER.warn(
                    "GuiGlow copy failed: u0={} v0={} -> atlas=({}x{}) src=({},{}) dst=({},{}) mask=({}x{}) err={}",
                    c.u0, c.v0, atlasW, atlasH, srcX, srcY, dstX, dstY, actualMaskW, actualMaskH, e.getMessage());
            }
        }

        return maskTarget;
    }

    public static TextureTarget getMaskTarget() {
        return maskTarget;
    }
}
