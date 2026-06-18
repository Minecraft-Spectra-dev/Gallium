package cn.spectra.gallium.glowoutline.shader;

import cn.spectra.gallium.glowoutline.ItemEffectConfig;
import cn.spectra.gallium.glowoutline.capture.GuiGlowCapture;
import cn.spectra.gallium.glowoutline.capture.GuiGlowCaptureManager;
import cn.spectra.gallium.glowoutline.capture.GuiGlowElementRenderState;
import cn.spectra.gallium.glowoutline.capture.GuiItemRenderStateAccessor;
//#if MC>=1_21_06
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.textures.FilterMode;
import net.minecraft.client.Minecraft;
//#if MC>=1_26_00
import net.minecraft.client.gui.render.GuiItemAtlas;
//#endif
import net.minecraft.client.gui.render.TextureSetup;
import net.minecraft.client.gui.navigation.ScreenRectangle;
//#if MC>=1_26_00
import net.minecraft.client.renderer.state.gui.GuiItemRenderState;
import net.minecraft.client.renderer.state.gui.GuiRenderState;
//#else
//$$ import net.minecraft.client.gui.render.state.GuiItemRenderState;
//$$ import net.minecraft.client.gui.render.state.GuiRenderState;
//#endif
import org.joml.Matrix3x2f;
//#endif

/**
 * GUI-side glue between item rendering and the glow mask pipeline. Pulled out of
 * {@code GuiRendererMixin} so the mixin stays a thin hook over Mojang code.
 * <p>
 * All versions share the slot-packed mask flow: each captured item is assigned a unique
 * cell in a grid laid out across the shared mask target. The item's 16x16 atlas region is
 * copied into the cell's padded interior (see {@link GuiGlowRenderer#renderMaskOnly}); the
 * glow quad samples that cell's UVs. Because each cell carries a transparent guard band on
 * every side, the fragment shader's ring sampling can never read a neighbouring item's
 * mask — eliminating the cross-item outline bleed that plagued the old screen-space layout.
 */
//#if MC>=1_21_06
public final class GuiGlowDispatcher {

    /** GUI-px margin around the 16x16 item slot. Both the glow quad and each mask cell's
     *  guard band use this, so ring sampling at the quad edge stays inside the cell padding. */
    private static final int MASK_QUAD_MARGIN = 4;
    private static final int ITEM_SLOT_SIZE = 16;
    private static final int VERTEX_PASSTHROUGH_COLOR = 0xFFFFFFFF;

    private GuiGlowDispatcher() {}

    //#if MC>=1_26_00
    public static void onItemBlit(GuiRenderState renderState, GuiItemRenderState itemState, GuiItemAtlas.SlotView slotView) {
        ItemEffectConfig cfg = ((GuiItemRenderStateAccessor) (Object) itemState).gallium$getEffectConfig();
        if (cfg == null) return;
        // SlotView atlas UVs are y-up: u0 = slot left, v1 = slot bottom. That corner plus the
        // slot's framebuffer size (derived in emitGlow) fully describes the atlas source rect.
        emitGlow(renderState, cfg, slotView.textureView(), itemState.pose(),
                itemState.x(), itemState.y(), itemState.scissorArea(),
                slotView.u0(), slotView.v1());
    }
    //#else
    //$$ public static void onItemBlit(GuiRenderState renderState, GuiItemRenderState itemState,
    //$$                                GpuTextureView atlasView,
    //$$                                float f, float g, int slotPx, int atlasPx) {
    //$$     ItemEffectConfig cfg = ((GuiItemRenderStateAccessor) (Object) itemState).gallium$getEffectConfig();
    //$$     if (cfg == null) return;
    //$$     // Vanilla submitBlitFromItemAtlas: u0=f, v0=g (slot top, atlas-uv y-up),
    //$$     // v1 = g - slot/atlas (slot bottom). Same convention as 26.1's SlotView.
    //$$     float uvSlot = (float) slotPx / atlasPx;
    //$$     emitGlow(renderState, cfg, atlasView, itemState.pose(),
    //$$             itemState.x(), itemState.y(), itemState.scissorArea(),
    //$$             f, g - uvSlot);
    //$$ }
    //#endif

    /**
     * Shared emitter. Assigns the capture a mask grid cell, records the atlas source corner
     * (for the later atlas→mask copy in {@link GuiGlowRenderer}), then queues the glow quad
     * element with the cell's mask UVs. {@code atlasU0} is the slot's left edge and
     * {@code atlasV1} its bottom edge in atlas-uv (y-up) space.
     */
    private static void emitGlow(GuiRenderState renderState, ItemEffectConfig cfg, GpuTextureView atlasView,
                                 Matrix3x2f pose, int itemX, int itemY, ScreenRectangle scissorArea,
                                 float atlasU0, float atlasV1) {
        Minecraft mc = Minecraft.getInstance();
        var mainTarget = mc.getMainRenderTarget();
        if (mainTarget == null) return;

        int screenW = mainTarget.width;
        int screenH = mainTarget.height;
        double guiScale = mc.getWindow().getGuiScale();

        TextureTarget maskTarget = GuiGlowRenderer.ensureMaskTarget(screenW, screenH);
        if (maskTarget == null || maskTarget.getColorTextureView() == null) return;

        // Lay out a grid of (24 GUI px) cells across the mask. Each cell holds the item's
        // 16x16 interior plus a 4 GUI-px guard band on every side. Sizing in framebuffer
        // pixels keeps the atlas copy 1:1 with the GUI-scaled atlas slot.
        int slotFbSize = (int) Math.round((ITEM_SLOT_SIZE + 2 * MASK_QUAD_MARGIN) * guiScale);
        int padFb = (int) Math.round(MASK_QUAD_MARGIN * guiScale);
        int interiorFbSize = slotFbSize - 2 * padFb;
        int cols = Math.max(1, screenW / slotFbSize);
        int rows = Math.max(1, screenH / slotFbSize);

        // Prospective grid index = current active count (the slot this acquire would take).
        // Check the grid bound BEFORE acquiring so an overflowed item leaves no half-set
        // capture behind for renderMaskOnly to process. Realistic GUIs never approach the
        // cell count (840+ at 4K/guiScale 4).
        int idx = GuiGlowCaptureManager.getActive().size();
        if (idx >= cols * rows) {
            return;
        }

        int sx = (idx % cols) * slotFbSize;
        int sy = (idx / cols) * slotFbSize;
        int interiorFbX = sx + padFb;
        int interiorFbY = sy + padFb;

        // Cell UVs into the mask. (sx, sy) is the cell's bottom-left in framebuffer y-up
        // coords — the same convention copyTextureToTexture's dstY uses — so the cell's top
        // edge is the higher V. The glow quad's top vertices (screen y0) take the higher V so
        // the copied item appears upright. CLAMP_TO_EDGE on the guard band keeps out-of-cell
        // ring taps at alpha=0.
        float mu0 = (float) sx / screenW;
        float mu1 = (float) (sx + slotFbSize) / screenW;
        float mv0 = (float) (sy + slotFbSize) / screenH; // top edge (higher V) → quad y0
        float mv1 = (float) sy / screenH;                // bottom edge (lower V) → quad y1

        GuiGlowCapture capture = GuiGlowCaptureManager.acquire();
        capture.set(atlasView, atlasU0, atlasV1);
        capture.setSlot(interiorFbX, interiorFbY, interiorFbSize, interiorFbSize);

        TextureSetup texSetup = SamplerHelper.singleTextureClampToEdge(maskTarget.getColorTextureView(), FilterMode.LINEAR);

        int qx0 = itemX - MASK_QUAD_MARGIN;
        int qy0 = itemY - MASK_QUAD_MARGIN;
        int qx1 = itemX + ITEM_SLOT_SIZE + MASK_QUAD_MARGIN;
        int qy1 = itemY + ITEM_SLOT_SIZE + MASK_QUAD_MARGIN;

        GuiGlowElementRenderState element = GuiGlowElementRenderState.create(
                GuiGlowElementPipeline.getOrCreate(cfg),
                texSetup,
                pose,
                qx0, qy0, qx1, qy1,
                mu0, mu1, mv0, mv1,
                VERTEX_PASSTHROUGH_COLOR,
                scissorArea
        );
        //#if MC>=1_26_00
        renderState.addGlyphToCurrentLayer(element);
        //#else
        //$$ renderState.submitGlyphToCurrentLayer(element);
        //#endif
    }

    public static void onPrepareItemElements() {
        if (GuiGlowCaptureManager.getActive().isEmpty()) return;
        Minecraft mc = Minecraft.getInstance();
        var mainTarget = mc.getMainRenderTarget();
        if (mainTarget == null) return;

        int screenW = mainTarget.width;
        int screenH = mainTarget.height;

        GuiGlowRenderer.renderMaskOnly(screenW, screenH);
        GuiGlowElementPipeline.updateAllForFrame(screenW, screenH, GlowTime.guiSecondsFloat());
    }
}
//#else
//$$ public final class GuiGlowDispatcher {
//$$     private GuiGlowDispatcher() {}
//$$ }
//#endif
