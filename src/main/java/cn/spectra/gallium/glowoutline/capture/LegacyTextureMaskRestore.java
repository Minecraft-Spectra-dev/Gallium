package cn.spectra.gallium.glowoutline.capture;

//#if MC<1_21_05
//$$ import com.mojang.blaze3d.pipeline.TextureTarget;
//$$ import cn.spectra.gallium.glowoutline.shader.GlowResources;
//$$ import com.mojang.blaze3d.systems.RenderSystem;
//$$ import org.lwjgl.opengl.*;
//$$ import org.lwjgl.system.MemoryStack;
//$$
//$$ /** Exact same-format restore without changing framebuffer or scissor bindings. */
//$$ final class LegacyTextureMaskRestore {
//$$     private static long checkedEpoch = Long.MIN_VALUE;
//$$     private static TextureTarget checkedMask, checkedSource;
//$$     private static int colorId, depthId, sourceId, width, height;
//$$     private static boolean compatible;
//$$     static { GlowResources.register(() -> { checkedEpoch = Long.MIN_VALUE; checkedMask = checkedSource = null; }); }
//$$     private LegacyTextureMaskRestore() {}
//$$
//$$     static boolean restore(TextureTarget mask, TextureTarget source, long epoch,
//$$                            MaskRestoreHistory.Region region) {
//$$         var caps = GL.getCapabilities();
//$$         if ((!caps.OpenGL44 && !caps.GL_ARB_clear_texture)
//$$                 || (!caps.OpenGL43 && !caps.GL_ARB_copy_image)) return false;
//$$         int sourceDepth = source == null ? -1 : source.getDepthTextureId();
//$$         if (checkedEpoch != epoch || checkedMask != mask || checkedSource != source
//$$                 || colorId != mask.getColorTextureId() || depthId != mask.getDepthTextureId()
//$$                 || sourceId != sourceDepth || width != mask.width || height != mask.height) {
//$$             checkedEpoch = epoch; checkedMask = mask; checkedSource = source;
//$$             colorId = mask.getColorTextureId(); depthId = mask.getDepthTextureId();
//$$             sourceId = sourceDepth; width = mask.width; height = mask.height;
//$$             int previous = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
//$$             try {
//$$                 int color = format(colorId), depth = format(depthId);
//$$                 compatible = color == GL11.GL_RGBA8
//$$                         && (depth == GL11.GL_DEPTH_COMPONENT || depth == GL14.GL_DEPTH_COMPONENT16 || depth == GL14.GL_DEPTH_COMPONENT24
//$$                         || depth == GL14.GL_DEPTH_COMPONENT32 || depth == GL30.GL_DEPTH_COMPONENT32F)
//$$                         && (source == null || depth == format(sourceDepth));
//$$             } finally { RenderSystem.bindTexture(previous); }
//$$         }
//$$         if (!compatible) return false;
//$$         int x = region == null ? 0 : region.x(), y = region == null ? 0 : region.y();
//$$         int w = region == null ? mask.width : region.width(), h = region == null ? mask.height : region.height();
//$$         if (w == 0 || h == 0) return true;
//$$         RenderSystem.clearColor(0, 0, 0, 0);
//$$         RenderSystem.depthMask(true);
//$$         if (region == null || source == null) RenderSystem.clearDepth(1.0);
//$$         // ClearTexSubImage consumes one constant value, independently of pixel-store,
//$$         // framebuffer color/depth masks and the enabled scissor. The caller has already
//$$         // checked that those masks permit the equivalent original clear.
//$$         GL44.glClearTexSubImage(colorId, 0, x, y, 0, w, h, 1,
//$$                 GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, (java.nio.ByteBuffer) null);
//$$         if (source == null) {
//$$             try (MemoryStack stack = MemoryStack.stackPush()) {
//$$                 GL44.glClearTexSubImage(depthId, 0, x, y, 0, w, h, 1,
//$$                         GL11.GL_DEPTH_COMPONENT, GL11.GL_FLOAT, stack.floats(1.0f));
//$$             }
//$$         } else {
//$$             GL43.glCopyImageSubData(sourceDepth, GL11.GL_TEXTURE_2D, 0, x, y, 0,
//$$                     depthId, GL11.GL_TEXTURE_2D, 0, x, y, 0, w, h, 1);
//$$         }
//$$         // Preserve the original helper's externally visible state: clear/copy leaves
//$$         // the default framebuffer bound, with the mask viewport selected.
//$$         RenderSystem.viewport(0, 0, mask.viewWidth, mask.viewHeight);
//$$         com.mojang.blaze3d.platform.GlStateManager._glBindFramebuffer(GL30.GL_FRAMEBUFFER, 0);
//$$         return true;
//$$     }
//$$
//$$     private static int format(int texture) {
//$$         RenderSystem.bindTexture(texture);
//$$         return GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_INTERNAL_FORMAT);
//$$     }
//$$ }
//#else
final class LegacyTextureMaskRestore { private LegacyTextureMaskRestore() {} }
//#endif
