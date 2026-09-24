package cn.spectra.gallium.glowoutline.capture;

//#if MC>=1_21_05 && MC<1_26_02
import cn.spectra.gallium.glowoutline.shader.GlowResources;
import com.mojang.blaze3d.opengl.GlTexture;
import com.mojang.blaze3d.pipeline.TextureTarget;
import org.lwjgl.opengl.*;
import org.lwjgl.system.MemoryStack;

/** Restores the exact previous draw region on the inspected immediate OpenGL backend. */
final class ModernMaskReuse {
    private static final MaskRestoreHistory history = new MaskRestoreHistory();
    private static TextureTarget checkedMask, checkedSource;
    private static long checkedEpoch = Long.MIN_VALUE;
    private static int checkedColor, checkedDepth, checkedSourceDepth, checkedWidth, checkedHeight;
    private static boolean compatible;
    static { GlowResources.register(() -> { invalidate(); checkedMask = checkedSource = null; }); }
    private ModernMaskReuse() {}
    static void invalidate() { history.invalidate(); checkedEpoch = Long.MIN_VALUE; }

    static boolean prepare(TextureTarget mask, TextureTarget source, long epoch, long generation) {
        var caps = GL.getCapabilities();
        if ((!caps.OpenGL44 && !caps.GL_ARB_clear_texture) || (!caps.OpenGL43 && !caps.GL_ARB_copy_image)
                || OpenGlMaskOrdering.observe() == null
                || !(mask.getColorTexture() instanceof GlTexture color)
                || !(mask.getDepthTexture() instanceof GlTexture depth)
                || (source != null && !(source.getDepthTexture() instanceof GlTexture))) return false;
        int colorId = color.glId(), depthId = depth.glId();
        int sourceId = source == null ? -1 : ((GlTexture) source.getDepthTexture()).glId();
        if (checkedMask != mask || checkedSource != source || checkedEpoch != epoch
                || checkedColor != colorId || checkedDepth != depthId || checkedSourceDepth != sourceId
                || checkedWidth != mask.width || checkedHeight != mask.height) {
            checkedMask = mask; checkedSource = source; checkedEpoch = epoch;
            checkedColor = colorId; checkedDepth = depthId; checkedSourceDepth = sourceId;
            checkedWidth = mask.width; checkedHeight = mask.height;
            int previous = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
            try {
                int colorFormat = format(colorId), depthFormat = format(depthId);
                compatible = colorFormat == GL11.GL_RGBA8
                        && (depthFormat == GL14.GL_DEPTH_COMPONENT16 || depthFormat == GL14.GL_DEPTH_COMPONENT24
                            || depthFormat == GL14.GL_DEPTH_COMPONENT32 || depthFormat == GL30.GL_DEPTH_COMPONENT32F)
                        && (source == null || (source.width == mask.width && source.height == mask.height
                            && depthFormat == format(sourceId)));
            } finally { GL11.glBindTexture(GL11.GL_TEXTURE_2D, previous); }
        }
        if (!compatible) return false;
        var region = history.begin(new MaskRestoreHistory.Key(mask, colorId, depthId,
                mask.width, mask.height, epoch, source, sourceId, generation));
        int x = region == null ? 0 : region.x(), y = region == null ? 0 : region.y();
        int w = region == null ? mask.width : region.width(), h = region == null ? mask.height : region.height();
        if (w == 0 || h == 0) return true;
        //#if MC==1_26_01
        if (source != null && NativeMaskSeed.restore(mask, source, x, y, w, h, region != null))
            return true;
        //#endif
        // A retained framebuffer can keep stale depth-test state after a partial texture
        // restore. Refresh its binding around the update; restore both targets exactly so
        // the native encoder's framebuffer cache still describes the real GL state.
        int previousDraw = 0, previousRead = 0;
        if (region != null) {
            previousDraw = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
            previousRead = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, 0);
        }
        try {
            //#if MC==1_21_08
            //$$ if (source == null && cn.spectra.gallium.glowoutline.IrisCompat.isShaderActive()
            //$$         && (caps.OpenGL41 || caps.GL_ARB_viewport_array)) {
            //$$     clearColorAndDepthRegion(mask, x, y, w, h);
            //$$ } else
            //#endif
            {
                GL44.glClearTexSubImage(colorId, 0, x, y, 0, w, h, 1,
                        GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, (java.nio.ByteBuffer) null);
                if (source == null) {
                    //#if MC==1_21_08
                    //$$ if (cn.spectra.gallium.glowoutline.IrisCompat.isShaderActive()) {
                    //$$     clearDepthRegion(mask, x, y, w, h);
                    //$$ } else
                    //#endif
                    {
                        try (MemoryStack stack = MemoryStack.stackPush()) {
                            GL44.glClearTexSubImage(depthId, 0, x, y, 0, w, h, 1,
                                    GL11.GL_DEPTH_COMPONENT, GL11.GL_FLOAT, stack.floats(1.0f));
                        }
                    }
                } else {
                    //#if MC==1_21_08
                    //$$ // Use the backend's framebuffer depth copy. A direct sub-image update can
                    //$$ // read back correctly yet leave fragmented coverage in the following draw.
                    //$$ com.mojang.blaze3d.systems.RenderSystem.getDevice().createCommandEncoder()
                    //$$         .copyTextureToTexture(source.getDepthTexture(), mask.getDepthTexture(),
                    //$$                 0, x, y, x, y, w, h);
                    //#else
                    //#if MC==1_21_10 || MC==1_21_11 || MC==1_26_01
                    if (cn.spectra.gallium.glowoutline.IrisCompat.isShaderActive()) {
                        // Keep the native framebuffer depth-copy route for Iris restores.
                        com.mojang.blaze3d.systems.RenderSystem.getDevice().createCommandEncoder()
                                .copyTextureToTexture(source.getDepthTexture(), mask.getDepthTexture(),
                                        0, x, y, x, y, w, h);
                    } else
                    //#endif
                    {
                        GL43.glCopyImageSubData(sourceId, GL11.GL_TEXTURE_2D, 0, x, y, 0,
                                depthId, GL11.GL_TEXTURE_2D, 0, x, y, 0, w, h, 1);
                    }
                    //#endif
                }
            }
        } finally {
            if (region != null) {
                restoreBindings(previousDraw, previousRead);
            }
        }
        return true;
    }

    //#if MC==1_21_08
    //$$ /** Clear both attachments while preserving every indexed state touched by the operation. */
    //$$ private static void clearColorAndDepthRegion(TextureTarget mask, int x, int y, int width, int height) {
    //$$     int draw = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
    //$$     int read = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
    //$$     boolean scissor = GL30.glIsEnabledi(GL11.GL_SCISSOR_TEST, 0);
    //$$     boolean depthWrite = GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK);
    //$$     try (MemoryStack stack = MemoryStack.stackPush()) {
    //$$         var box = stack.mallocInt(4);
    //$$         var colors = stack.malloc(4);
    //$$         GL30.glGetIntegeri_v(GL11.GL_SCISSOR_BOX, 0, box);
    //$$         GL30.glGetBooleani_v(GL11.GL_COLOR_WRITEMASK, 0, colors);
    //$$         try {
    //$$             var device = (com.mojang.blaze3d.opengl.GlDevice)
    //$$                     com.mojang.blaze3d.systems.RenderSystem.getDevice();
    //$$             int framebuffer = ((GlTexture) mask.getColorTexture())
    //$$                     .getFbo(device.directStateAccess(), mask.getDepthTexture());
    //$$             GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, framebuffer);
    //$$             GL30.glEnablei(GL11.GL_SCISSOR_TEST, 0);
    //$$             GL41.glScissorIndexed(0, x, y, width, height);
    //$$             GL30.glColorMaski(0, true, true, true, true);
    //$$             GL11.glDepthMask(true);
    //$$             GL30.glClearBufferfv(GL11.GL_COLOR, 0, stack.floats(0.0f, 0.0f, 0.0f, 0.0f));
    //$$             GL30.glClearBufferfv(GL11.GL_DEPTH, 0, stack.floats(1.0f));
    //$$         } finally {
    //$$             GL30.glColorMaski(0, colors.get(0) != 0, colors.get(1) != 0,
    //$$                     colors.get(2) != 0, colors.get(3) != 0);
    //$$             GL11.glDepthMask(depthWrite);
    //$$             GL41.glScissorIndexed(0, box.get(0), box.get(1), box.get(2), box.get(3));
    //$$             if (!scissor) GL30.glDisablei(GL11.GL_SCISSOR_TEST, 0);
    //$$             restoreBindings(draw, read);
    //$$         }
    //$$     }
    //$$ }
    //$$
    //$$ /** Keep the depth attachment coherent when masks are reused without an intervening sample. */
    //$$ private static void clearDepthRegion(TextureTarget mask, int x, int y, int width, int height) {
    //$$     int draw = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
    //$$     int read = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
    //$$     boolean scissor = GL11.glIsEnabled(GL11.GL_SCISSOR_TEST);
    //$$     boolean depthWrite = GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK);
    //$$     try (MemoryStack stack = MemoryStack.stackPush()) {
    //$$         var box = stack.mallocInt(4);
    //$$         GL11.glGetIntegerv(GL11.GL_SCISSOR_BOX, box);
    //$$         try {
    //$$             var device = (com.mojang.blaze3d.opengl.GlDevice)
    //$$                     com.mojang.blaze3d.systems.RenderSystem.getDevice();
    //$$             int framebuffer = ((GlTexture) mask.getColorTexture())
    //$$                     .getFbo(device.directStateAccess(), mask.getDepthTexture());
    //$$             GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, framebuffer);
    //$$             GL11.glEnable(GL11.GL_SCISSOR_TEST);
    //$$             GL11.glScissor(x, y, width, height);
    //$$             GL11.glDepthMask(true);
    //$$             // ClearTexSubImage can leave stale depth-test coverage on consecutive
    //$$             // replays, even when reading the texture back reports the cleared value.
    //$$             GL30.glClearBufferfv(GL11.GL_DEPTH, 0, stack.floats(1.0f));
    //$$         } finally {
    //$$             GL11.glDepthMask(depthWrite);
    //$$             GL11.glScissor(box.get(0), box.get(1), box.get(2), box.get(3));
    //$$             if (!scissor) GL11.glDisable(GL11.GL_SCISSOR_TEST);
    //$$             restoreBindings(draw, read);
    //$$         }
    //$$     }
    //$$ }
    //#endif

    /** Restore both bindings with one call when the caller used the same framebuffer. */
    static void restoreBindings(int draw, int read) {
        if (draw == read) {
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, draw);
        } else {
            GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, draw);
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, read);
        }
    }

    private static int format(int id) {
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, id);
        return GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_INTERNAL_FORMAT);
    }
    static void finish(ProjectedMaskBounds bounds) { history.finish(bounds); }
}
//#else
//$$ final class ModernMaskReuse { private ModernMaskReuse() {} }
//#endif
