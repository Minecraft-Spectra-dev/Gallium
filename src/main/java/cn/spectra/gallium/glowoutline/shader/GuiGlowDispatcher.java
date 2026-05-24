package cn.spectra.gallium.glowoutline.shader;

import cn.spectra.gallium.glowoutline.ItemEffectConfig;
import cn.spectra.gallium.glowoutline.capture.GuiGlowCapture;
import cn.spectra.gallium.glowoutline.capture.GuiGlowCaptureManager;
import cn.spectra.gallium.glowoutline.capture.GuiGlowElementRenderState;
import cn.spectra.gallium.glowoutline.capture.GuiItemRenderStateAccessor;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.render.GuiItemAtlas;
import net.minecraft.client.gui.render.TextureSetup;
import net.minecraft.client.renderer.state.gui.GuiItemRenderState;
import net.minecraft.client.renderer.state.gui.GuiRenderState;
import org.joml.Vector2f;

/**
 * GUI-side glue between item rendering and the glow mask pipeline. Pulled out of
 * {@code GuiRendererMixin} so the mixin stays a thin hook over Mojang code.
 */
public final class GuiGlowDispatcher {

    /** Pixels of margin around the 16x16 item slot when sampling the mask texture. */
    private static final int MASK_QUAD_MARGIN = 4;
    private static final int VERTEX_PASSTHROUGH_COLOR = 0xFFFFFFFF;

    private GuiGlowDispatcher() {}

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
        int qx1 = itemState.x() + 16 + MASK_QUAD_MARGIN;
        int qy1 = itemState.y() + 16 + MASK_QUAD_MARGIN;

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

    public static void onPrepareItemElements() {
        if (GuiGlowCaptureManager.getActive().isEmpty()) return;
        Minecraft mc = Minecraft.getInstance();
        var mainTarget = mc.getMainRenderTarget();
        if (mainTarget == null) return;

        int screenW = mainTarget.width;
        int screenH = mainTarget.height;

        GuiGlowRenderer.renderMaskOnly(screenW, screenH);
        GuiGlowElementPipeline.updateAllForFrame(screenW, screenH, GlowTime.guiSecondsFloat(), 1.0f);
    }
}
