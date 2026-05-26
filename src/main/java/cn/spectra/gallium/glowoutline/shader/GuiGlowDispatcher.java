package cn.spectra.gallium.glowoutline.shader;

import cn.spectra.gallium.glowoutline.ItemEffectConfig;
import cn.spectra.gallium.glowoutline.capture.GuiGlowCapture;
import cn.spectra.gallium.glowoutline.capture.GuiGlowCaptureManager;
import cn.spectra.gallium.glowoutline.capture.GuiGlowElementRenderState;
import cn.spectra.gallium.glowoutline.capture.GuiItemRenderStateAccessor;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.systems.RenderSystem;
//#if MC<1_26_00
//$$ import com.mojang.blaze3d.textures.GpuTextureView;
//#endif
import com.mojang.blaze3d.textures.FilterMode;
import net.minecraft.client.Minecraft;
//#if MC>=1_26_00
import net.minecraft.client.gui.render.GuiItemAtlas;
//#endif
import net.minecraft.client.gui.render.TextureSetup;
//#if MC>=1_26_00
import net.minecraft.client.renderer.state.gui.GuiItemRenderState;
import net.minecraft.client.renderer.state.gui.GuiRenderState;
//#else
//$$ import net.minecraft.client.gui.render.state.GuiItemRenderState;
//$$ import net.minecraft.client.gui.render.state.GuiRenderState;
//#endif
import org.joml.Vector2f;

/**
 * GUI-side glue between item rendering and the glow mask pipeline. Pulled out of
 * {@code GuiRendererMixin} so the mixin stays a thin hook over Mojang code.
 */
public final class GuiGlowDispatcher {

    /** Pixels of margin around the 16x16 item slot when sampling the mask texture. */
    private static final int MASK_QUAD_MARGIN = 4;
    private static final int ITEM_SLOT_SIZE = 16;
    private static final int VERTEX_PASSTHROUGH_COLOR = 0xFFFFFFFF;

    private GuiGlowDispatcher() {}

    //#if MC>=1_26_00
    public static void onItemBlit(GuiRenderState renderState, GuiItemRenderState itemState, GuiItemAtlas.SlotView slotView) {
        ItemEffectConfig cfg = ((GuiItemRenderStateAccessor) (Object) itemState).gallium$getEffectConfig();
        if (cfg == null) return;

        GuiGlowCapture capture = GuiGlowCaptureManager.acquire();
        capture.set(cfg, slotView.textureView(), itemState.pose(),
                itemState.x(), itemState.y(),
                slotView.u0(), slotView.u1(), slotView.v0(), slotView.v1(),
                itemState.scissorArea());

        Minecraft mc = Minecraft.getInstance();
        var mainTarget = mc.getMainRenderTarget();
        if (mainTarget == null) return;

        int screenW = mainTarget.width;
        int screenH = mainTarget.height;
        double guiScale = mc.getWindow().getGuiScale();

        TextureTarget maskTarget = GuiGlowRenderer.ensureMaskTarget(screenW, screenH);
        if (maskTarget == null || maskTarget.getColorTextureView() == null) return;

        TextureSetup texSetup = TextureSetup.singleTexture(maskTarget.getColorTextureView(),
                RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR));

        int qx0 = itemState.x() - MASK_QUAD_MARGIN;
        int qy0 = itemState.y() - MASK_QUAD_MARGIN;
        int qx1 = itemState.x() + ITEM_SLOT_SIZE + MASK_QUAD_MARGIN;
        int qy1 = itemState.y() + ITEM_SLOT_SIZE + MASK_QUAD_MARGIN;

        Vector2f topLeft = itemState.pose().transformPosition(new Vector2f(qx0, qy0));
        Vector2f bottomRight = itemState.pose().transformPosition(new Vector2f(qx1, qy1));

        float fbX0 = (float) (topLeft.x * guiScale);
        float fbY0 = (float) (topLeft.y * guiScale);
        float fbX1 = (float) (bottomRight.x * guiScale);
        float fbY1 = (float) (bottomRight.y * guiScale);
        float u0 = fbX0 / screenW;
        float u1 = fbX1 / screenW;
        float v0 = 1.0f - fbY0 / screenH;
        float v1 = 1.0f - fbY1 / screenH;

        GuiGlowElementRenderState element = GuiGlowElementRenderState.create(
                GuiGlowElementPipeline.getOrCreate(cfg),
                texSetup,
                itemState.pose(),
                qx0, qy0, qx1, qy1,
                u0, u1, v0, v1,
                VERTEX_PASSTHROUGH_COLOR,
                itemState.scissorArea()
        );
        renderState.addGlyphToCurrentLayer(element);
    }
    //#else
    //$$ /**
    //$$  * 1.21.11 path. Sample the items atlas directly: the mask-target detour used on
    //$$  * 26.1 hits coordinate-system / texture-orientation issues with 1.21.11's
    //$$  * GpuTexture+RenderTarget primitives, and is unnecessary because the atlas
    //$$  * already contains the rasterised item we want to outline. We just hand the
    //$$  * atlas view + UVs straight to BlitRenderState; the fragment shader walks
    //$$  * texCoord0 to test centre/neighbour alpha for the outline.
    //$$  *
    //$$  * Vanilla's submitBlitFromItemAtlas: u0=f, v0=g (slot top in atlas-uv y-up),
    //$$  *                                    u1=f+slot/atlas, v1=g-slot/atlas.
    //$$  * The quad geometry matches the slot exactly (no margin) so neighbour
    //$$  * sampling can never cross into the adjacent atlas slot, which would
    //$$  * produce visual artefacts when the next slot also holds an item.
    //$$  * The trade-off: outline only appears inside the slot border, so a 1-px
    //$$  * inset glow rather than a 4-px outer glow. Acceptable for parity until
    //$$  * we set up a per-slot mini render target on this version.
    //$$  */
    //$$ public static void onItemBlit(GuiRenderState renderState, GuiItemRenderState itemState,
    //$$                                GpuTextureView atlasView,
    //$$                                float f, float g, int slotPx, int atlasPx) {
    //$$     ItemEffectConfig cfg = ((GuiItemRenderStateAccessor) (Object) itemState).gallium$getEffectConfig();
    //$$     if (cfg == null) return;
    //$$
    //$$     float uvSlot = (float) slotPx / atlasPx;
    //$$
    //$$     // Atlas UV is y-up; v0 (slot top, larger V), v1 = v0 - uvSlot (slot bottom, smaller V).
    //$$     float u0 = f;
    //$$     float u1 = f + uvSlot;
    //$$     float v0 = g;
    //$$     float v1 = g - uvSlot;
    //$$
    //$$     GuiGlowCapture capture = GuiGlowCaptureManager.acquire();
    //$$     capture.set(cfg, atlasView, itemState.pose(),
    //$$             itemState.x(), itemState.y(),
    //$$             u0, u1, v0, v1,
    //$$             itemState.scissorArea());
    //$$
    //$$     TextureSetup texSetup = TextureSetup.singleTexture(atlasView,
    //$$             RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST));
    //$$
    //$$     int qx0 = itemState.x();
    //$$     int qy0 = itemState.y();
    //$$     int qx1 = itemState.x() + ITEM_SLOT_SIZE;
    //$$     int qy1 = itemState.y() + ITEM_SLOT_SIZE;
    //$$
    //$$     GuiGlowElementRenderState element = GuiGlowElementRenderState.create(
    //$$             GuiGlowElementPipeline.getOrCreate(cfg),
    //$$             texSetup,
    //$$             itemState.pose(),
    //$$             qx0, qy0, qx1, qy1,
    //$$             u0, u1, v0, v1,
    //$$             VERTEX_PASSTHROUGH_COLOR,
    //$$             itemState.scissorArea()
    //$$     );
    //$$     renderState.submitGlyphToCurrentLayer(element);
    //$$ }
    //#endif

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
