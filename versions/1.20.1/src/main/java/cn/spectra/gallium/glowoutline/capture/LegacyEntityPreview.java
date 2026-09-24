package cn.spectra.gallium.glowoutline.capture;

import cn.spectra.gallium.Gallium;
import cn.spectra.gallium.glowoutline.GlowOutlineConfig;
import cn.spectra.gallium.glowoutline.ItemEffectsManager;
import cn.spectra.gallium.glowoutline.shader.GlowResources;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import org.joml.Matrix4f;
import org.jspecify.annotations.Nullable;
import org.lwjgl.opengl.GL11;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.Tesselator;

/** The immediate-mode inventory equivalent of vanilla's later PIP entity renderer. */
public final class LegacyEntityPreview {
    private static @Nullable GuiEntityGlowCapture active;
    private LegacyEntityPreview() {}
    public static @Nullable GuiEntityGlowCapture active() { return active; }

    public static void render(GuiGraphics graphics, float scale, Runnable original) {
        // A preview scope is established even when disabled so its equipment can never leak into
        // next frame's world capture queue. Pending ordinary GUI items are flushed before redirect.
        graphics.flush();
        Minecraft mc = Minecraft.getInstance();
        RenderTarget main = mc.getMainRenderTarget();
        var saved = new SavedState();
        PreviewGeometry.Region region = saved.region(main.width, main.height);
        boolean enabled = region.valid() && ItemEffectsManager.isActive() && GlowOutlineConfig.isEnabled()
                && (GlowOutlineConfig.isArmor() || GlowOutlineConfig.isThirdPerson() || GlowOutlineConfig.isOtherEntities());
        GuiEntityGlowCapture parent = active;
        // A disabled nested preview must not redirect its geometry into its parent.
        active = null;
        try (var preview = GuiEntityGlowCapture.begin(enabled ? region.width() : 0,
                enabled ? region.height() : 0, scale * (float) mc.getWindow().getGuiScale())) {
            if (!enabled) { original.run(); return; }
            TextureTarget target;
            try {
                target = preview.target();
            } catch (RuntimeException failure) {
                Gallium.LOGGER.warn("Unable to allocate entity preview target", failure);
                original.run();
                return;
            }
            RenderSystem.disableScissor();
            RenderSystem.setProjectionMatrix(PreviewGeometry.crop(saved.projection, region, main.width, main.height),
                    saved.projectionType);
            target.setClearColor(0, 0, 0, 0);
            target.clear(Minecraft.ON_OSX);
            active = preview;
            original.run();
            // The entity invocation has already flushed its complete geometry into target.
            try {
                preview.complete();
            } catch (RuntimeException failure) {
                Gallium.LOGGER.warn("Unable to composite legacy entity preview glow", failure);
            }
            saved.restore();
            blit(target, main, region);
        } finally {
            active = parent;
            saved.restore();
        }
    }

    private static void blit(TextureTarget target, RenderTarget main, PreviewGeometry.Region region) {
        Matrix4f projection = new Matrix4f(RenderSystem.getProjectionMatrix());
        var projectionType = RenderSystem.getVertexSorting();
        RenderSystem.setProjectionMatrix(new Matrix4f().setOrtho(0, main.width, main.height, 0, -1, 1),
                com.mojang.blaze3d.vertex.VertexSorting.ORTHOGRAPHIC_Z);
        cn.spectra.gallium.glowoutline.capture.LegacyModelView.push();
        cn.spectra.gallium.glowoutline.capture.LegacyModelView.identity();
        float x0 = region.x(), x1 = x0 + region.width();
        float y0 = main.height - region.y() - region.height(), y1 = y0 + region.height();
        try {
            main.bindWrite(true);
            RenderSystem.disableDepthTest();
            RenderSystem.depthMask(false);
            RenderSystem.disableCull();
            RenderSystem.enableBlend();
            RenderSystem.blendFuncSeparate(1, 771, 1, 771);
            var shader = RenderSystem.getShader();
            var program = cn.spectra.gallium.glowoutline.shader.GlowPipeline.getOrCreate(
                    new cn.spectra.gallium.glowoutline.ItemEffectConfig("internal/entity_preview_blit_gl", java.util.List.of()));
            if (program == null) return;
            int foreground = cn.spectra.gallium.glowoutline.shader.GlowComposite.previewForegroundTexture();
            program.setSampler("DiffuseSampler", target.getColorTextureId());
            program.setSampler(cn.spectra.gallium.glowoutline.shader.WorldGlowShader.FOREGROUND_SAMPLER, foreground);
            RenderSystem.setShader(() -> program);
            program.safeGetUniform("ScreenSize").set((float) main.width, (float) main.height);
            program.safeGetUniform("ShaderAlign").set(x0, y0, x1 - x0, y1 - y0);
            try {
                var builder = Tesselator.getInstance().getBuilder();
                builder.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.BLIT_SCREEN);
                builder.vertex(x0, y0, 0).uv(0f, 1f).color(255, 255, 255, 255).endVertex();
                builder.vertex(x0, y1, 0).uv(1f, 1f).color(255, 255, 255, 255).endVertex();
                builder.vertex(x1, y1, 0).uv(1f, 0f).color(255, 255, 255, 255).endVertex();
                builder.vertex(x1, y0, 0).uv(0f, 0f).color(255, 255, 255, 255).endVertex();
                main.bindWrite(true);
                BufferUploader.drawWithShader(builder.end());
            } finally {
                RenderSystem.setShader(() -> shader);
            }
        } finally {
            cn.spectra.gallium.glowoutline.capture.LegacyModelView.pop();
            RenderSystem.setProjectionMatrix(projection, projectionType);
        }
    }

    private static void quad(BufferBuilder builder, float x0, float y0, float x1, float y1) {
        builder.vertex(x0, y0, 0).uv(0, 1).color(-1).endVertex();
        builder.vertex(x0, y1, 0).uv(0, 0).color(-1).endVertex();
        builder.vertex(x1, y1, 0).uv(1, 0).color(-1).endVertex();
        builder.vertex(x1, y0, 0).uv(1, 1).color(-1).endVertex();
    }

    private static final class SavedState {
        final Matrix4f projection = new Matrix4f(RenderSystem.getProjectionMatrix());
        final com.mojang.blaze3d.vertex.VertexSorting projectionType = RenderSystem.getVertexSorting();
        final int readFramebuffer = GL11.glGetInteger(36010);
        final int drawFramebuffer = GL11.glGetInteger(36006);
        final int[] viewport = new int[4];
        final int[] scissor = new int[4];
        final boolean scissorEnabled;
        final boolean blend = GL11.glIsEnabled(GL11.GL_BLEND);
        final boolean depth = GL11.glIsEnabled(GL11.GL_DEPTH_TEST);
        final boolean cull = GL11.glIsEnabled(GL11.GL_CULL_FACE);
        final boolean depthWrite = GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK);
        final int sourceRgb = GL11.glGetInteger(32969), destRgb = GL11.glGetInteger(32968);
        final int sourceAlpha = GL11.glGetInteger(32971), destAlpha = GL11.glGetInteger(32970);

        SavedState() {
            GL11.glGetIntegerv(GL11.GL_VIEWPORT, viewport);
            scissorEnabled = GL11.glIsEnabled(GL11.GL_SCISSOR_TEST);
            GL11.glGetIntegerv(GL11.GL_SCISSOR_BOX, scissor);
        }

        PreviewGeometry.Region region(int width, int height) {
            return scissorEnabled
                    ? PreviewGeometry.clip(scissor[0], scissor[1], scissor[2], scissor[3], width, height)
                    : PreviewGeometry.clip(viewport[0], viewport[1], viewport[2], viewport[3], width, height);
        }

        void restore() {
            RenderSystem.setProjectionMatrix(projection, projectionType);
            if (scissorEnabled) RenderSystem.enableScissor(scissor[0], scissor[1], scissor[2], scissor[3]);
            else RenderSystem.disableScissor();
            GlStateManager._glBindFramebuffer(36008, readFramebuffer);
            GlStateManager._glBindFramebuffer(36009, drawFramebuffer);
            RenderSystem.viewport(viewport[0], viewport[1], viewport[2], viewport[3]);
            if (blend) RenderSystem.enableBlend(); else RenderSystem.disableBlend();
            if (depth) RenderSystem.enableDepthTest(); else RenderSystem.disableDepthTest();
            if (cull) RenderSystem.enableCull(); else RenderSystem.disableCull();
            RenderSystem.depthMask(depthWrite);
            RenderSystem.blendFuncSeparate(sourceRgb, destRgb, sourceAlpha, destAlpha);
        }
    }
}
