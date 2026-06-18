package cn.spectra.gallium.glowoutline.capture;

//#if MC>=1_21_06
import com.mojang.blaze3d.textures.GpuTextureView;
//#endif

/**
 * Per-item record for the GUI glow mask pass. {@link GuiGlowDispatcher} fills one of these
 * for every glowing item, then {@link cn.spectra.gallium.glowoutline.shader.GuiGlowRenderer}
 * copies the item's atlas region (top-left at atlas UV {@code (u0, v1)}) into the capture's
 * pre-assigned mask cell interior.
 */
public class GuiGlowCapture {
    //#if MC>=1_21_06
    public GpuTextureView atlasTextureView;
    /** Atlas source corner: u0 = slot left edge, v1 = slot bottom edge (atlas-uv y-up). */
    public float u0, v1;

    // Pre-assigned cell interior in the shared mask target (framebuffer px, y-up origin to
    // match copyTextureToTexture). Each capture owns a unique grid cell with a transparent
    // guard band, so the fragment shader's ring sampling can never read a neighbouring item.
    public int slotInteriorFbX, slotInteriorFbY, slotInteriorFbW, slotInteriorFbH;

    public void set(GpuTextureView atlasTextureView, float u0, float v1) {
        this.atlasTextureView = atlasTextureView;
        this.u0 = u0;
        this.v1 = v1;
    }

    public void setSlot(int interiorFbX, int interiorFbY, int interiorFbW, int interiorFbH) {
        this.slotInteriorFbX = interiorFbX;
        this.slotInteriorFbY = interiorFbY;
        this.slotInteriorFbW = interiorFbW;
        this.slotInteriorFbH = interiorFbH;
    }

    public void reset() {
        this.atlasTextureView = null;
    }
    //#endif
}
