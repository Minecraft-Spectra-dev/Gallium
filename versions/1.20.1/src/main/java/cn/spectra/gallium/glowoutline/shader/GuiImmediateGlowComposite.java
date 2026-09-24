package cn.spectra.gallium.glowoutline.shader;

import cn.spectra.gallium.glowoutline.ItemEffectConfig;
import cn.spectra.gallium.glowoutline.ShaderParam;
import cn.spectra.gallium.glowoutline.capture.CaptureSites;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ShaderInstance;

public final class GuiImmediateGlowComposite {
    // Reused native buffer; held statically to avoid per-frame malloc. Registered with
    // GlowResources so the native allocation is released on resource reload (re-allocated
    // lazily on next use after dispose).
    private static BufferBuilder QUAD_BUF;

    static {
        GlowResources.register(GuiImmediateGlowComposite::disposeQuadBuf);
    }

    private GuiImmediateGlowComposite() {}

    private static BufferBuilder quadBuf() {
        if (QUAD_BUF == null) {
            // 4 vertices × 24B (POSITION_TEX_COLOR) ≈ 96B; 1 KiB amortizes alignment slack.
            QUAD_BUF = new BufferBuilder(1024);
        }
        return QUAD_BUF;
    }

    private static void disposeQuadBuf() {
        if (QUAD_BUF != null) {
            org.lwjgl.system.MemoryUtil.memFree(((cn.spectra.gallium.glowoutline.mixin.accessor.LegacyBufferBuilderAccessor) QUAD_BUF).gallium$buffer());
            QUAD_BUF = null;
        }
    }

    public static void composeForItem(ItemEffectConfig cfg,
                                       CaptureSites.DelayingMultiBufferSource buf,
                                       int itemX, int itemY) {
        if (cfg == null || buf == null) return;
        Minecraft mc = Minecraft.getInstance();
        RenderTarget mainTarget = mc.getMainRenderTarget();
        if (mainTarget == null || mainTarget.getColorTextureId() == -1) return;
        int guiScale = (int) Math.max(1, Math.round(mc.getWindow().getGuiScale()));
        TextureTarget tile = GuiImmediateGlowTile.ensureTile(guiScale);
        if (tile == null) return;
        try {
            GuiImmediateGlowTile.renderMeshToTile(buf, itemX, itemY);
            drawGlowQuad(cfg, tile, itemX, itemY, mainTarget, mc);
        } finally {
            // renderMeshToTile binds the tile target then unbinds (FB=0). If drawGlowQuad
            // returns early (e.g. program failed to compile), no path re-binds mainTarget,
            // and the next GUI element rendered after this item lands on the default
            // framebuffer (window backbuffer) instead of mainTarget — invisible until
            // the next vanilla bindWrite. Restore here so every composeForItem call is
            // a no-op on the surrounding GUI framebuffer state.
            mainTarget.bindWrite(true);
        }
    }

    private static void drawGlowQuad(ItemEffectConfig cfg, TextureTarget tile,
                                      int itemX, int itemY, RenderTarget mainTarget,
                                      Minecraft mc) {
        ShaderInstance program = GuiImmediateGlowPipeline.getOrCreate(cfg);
        if (program == null) return;
        int margin = GuiImmediateGlowTile.MASK_QUAD_MARGIN_GUI_PX;
        int slot = GuiImmediateGlowTile.ITEM_SLOT_GUI_PX;
        int x0 = itemX - margin;
        int x1 = itemX + slot + margin;
        int y0 = itemY - margin;
        int y1 = itemY + slot + margin;

        // BufferUploader.drawWithShader → VertexBuffer.drawWithShader → setDefaultUniforms
        // unconditionally calls bindSampler("Sampler" + i, RenderSystem.getShaderTexture(i))
        // for i=0..11, OVERWRITING any prior explicit Sampler0 binding. Setting the tile
        // via RenderSystem.setShaderTexture(0, ...) means setDefaultUniforms re-binds
        // Sampler0 to the tile (not whatever was last in slot 0). The named-sampler bindings
        // we use for the world shader (DiffuseSampler / MaskSampler / ...) escape this because
        // setDefaultUniforms only resets the Sampler0..11 slots, not arbitrary names.
        RenderSystem.setShaderTexture(0, tile.getColorTextureId());
        program.safeGetUniform("ColorModulator").set(1f, 1f, 1f, 1f);
        program.safeGetUniform("FrameTimeCounter").set(GlowTime.guiSecondsFloat());
        program.safeGetUniform("ScreenSize").set((float) mc.getWindow().getWidth(), (float) mc.getWindow().getHeight());
        program.safeGetUniform("ShaderAlign").set(1f, 1f, 0f, 0f);
        for (ShaderParam p : cfg.params()) {
            java.util.Objects.requireNonNull(p);
            if (p instanceof ShaderParam.Float f) program.safeGetUniform(f.name()).set(f.value());
            else if (p instanceof ShaderParam.Vec2 v) program.safeGetUniform(v.name()).set(v.x(), v.y());
            else if (p instanceof ShaderParam.Vec3 v) program.safeGetUniform(v.name()).set(v.x(), v.y(), v.z());
            else if (p instanceof ShaderParam.Vec4 v) program.safeGetUniform(v.name()).set(v.x(), v.y(), v.z(), v.w());
        }

        RenderSystem.bindTexture(tile.getColorTextureId());
        com.mojang.blaze3d.platform.GlStateManager._texParameter(3553, 10241, 9729);
        com.mojang.blaze3d.platform.GlStateManager._texParameter(3553, 10240, 9729);
        com.mojang.blaze3d.platform.GlStateManager._texParameter(3553, 10242, 33071);
        com.mojang.blaze3d.platform.GlStateManager._texParameter(3553, 10243, 33071);

        mainTarget.bindWrite(true);
        RenderSystem.disableDepthTest();
        RenderSystem.depthMask(false);
        RenderSystem.disableCull();
        RenderSystem.enableBlend();
        RenderSystem.blendFuncSeparate(1, 1, 1, 0);
        final ShaderInstance shaderRef = program;
        RenderSystem.setShader(() -> shaderRef);
        try {
            var mesh = buildQuad(x0, y0, x1, y1);
            BufferUploader.drawWithShader(mesh);
        } finally {
            RenderSystem.defaultBlendFunc();
            RenderSystem.disableBlend();
            RenderSystem.enableCull();
            RenderSystem.depthMask(true);
            RenderSystem.enableDepthTest();
        }
    }

    private static com.mojang.blaze3d.vertex.BufferBuilder.RenderedBuffer buildQuad(int x0, int y0, int x1, int y1) {
        BufferBuilder bb = quadBuf();
        bb.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX_COLOR);
        bb.vertex(x0, y0, 0.0f).uv(0f, 1f).color(0xFFFFFFFF).endVertex();
        bb.vertex(x0, y1, 0.0f).uv(0f, 0f).color(0xFFFFFFFF).endVertex();
        bb.vertex(x1, y1, 0.0f).uv(1f, 0f).color(0xFFFFFFFF).endVertex();
        bb.vertex(x1, y0, 0.0f).uv(1f, 1f).color(0xFFFFFFFF).endVertex();
        return bb.end();
    }
}
