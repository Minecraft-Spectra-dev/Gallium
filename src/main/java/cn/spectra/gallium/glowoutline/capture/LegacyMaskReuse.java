package cn.spectra.gallium.glowoutline.capture;

//#if MC<1_21_05
//$$ import cn.spectra.gallium.glowoutline.shader.GlowResources;
//$$ import com.mojang.blaze3d.pipeline.TextureTarget;
//$$ import com.mojang.blaze3d.platform.GlStateManager;
//$$ import com.mojang.blaze3d.systems.RenderSystem;
//$$ import net.minecraft.client.Minecraft;
//$$ import org.lwjgl.opengl.GL11;
//$$ import org.lwjgl.system.MemoryStack;
//$$
//$$ /** Restores the previous native draw's pixels, preserving the full logical mask contents. */
//$$ final class LegacyMaskReuse {
//$$     private static final MaskRestoreHistory history = new MaskRestoreHistory();
//$$     static { GlowResources.register(history::invalidate); }
//$$     private LegacyMaskReuse() {}
//$$     static void invalidate() { history.invalidate(); }
//$$
//$$     static boolean prepare(TextureTarget mask, TextureTarget source, long epoch, long generation) {
//$$         if (GL11.glIsEnabled(GL11.GL_SCISSOR_TEST)) { invalidate(); return false; }
//$$         try (MemoryStack stack = MemoryStack.stackPush()) {
//$$             var colorMask = stack.mallocInt(4);
//$$             GL11.glGetIntegerv(GL11.GL_COLOR_WRITEMASK, colorMask);
//$$             for (int i = 0; i < 4; i++) if (colorMask.get(i) == 0) { invalidate(); return false; }
//$$         var key = new MaskRestoreHistory.Key(mask, mask.getColorTextureId(), mask.getDepthTextureId(),
//$$                 mask.width, mask.height, epoch, source, source == null ? -1 : source.getDepthTextureId(), generation);
//$$         var restore = history.begin(key);
//$$         if (LegacyTextureMaskRestore.restore(mask, source, epoch, restore)) return true;
//$$         if (restore == null) {
//$$             mask.setClearColor(0, 0, 0, 0);
//#if MC>=1_21_02
//$$             mask.clear();
//#else
//$$             mask.clear(Minecraft.ON_OSX);
//#endif
//$$             if (source != null) mask.copyDepthFrom(source);
//$$             return true;
//$$         }
//$$         if (restore.width() == 0 || restore.height() == 0) return true;
//$$         var oldScissor = stack.mallocInt(4);
//$$         GL11.glGetIntegerv(GL11.GL_SCISSOR_BOX, oldScissor);
//$$         mask.bindWrite(true);
//$$         RenderSystem.clearColor(0, 0, 0, 0);
//$$         RenderSystem.depthMask(true);
//$$         if (source == null) RenderSystem.clearDepth(1.0);
//$$         RenderSystem.enableScissor(restore.x(), restore.y(), restore.width(), restore.height());
//$$         try {
//$$             int bits = GL11.GL_COLOR_BUFFER_BIT | (source == null ? GL11.GL_DEPTH_BUFFER_BIT : 0);
//#if MC>=1_21_02
//$$             RenderSystem.clear(bits);
//#else
//$$             RenderSystem.clear(bits, Minecraft.ON_OSX);
//#endif
//$$         } finally {
//$$             RenderSystem.disableScissor();
//$$             GlStateManager._scissorBox(oldScissor.get(0), oldScissor.get(1), oldScissor.get(2), oldScissor.get(3));
//$$             mask.unbindWrite();
//$$         }
//$$         if (source != null) {
//$$             int x = restore.x(), y = restore.y(), x1 = x + restore.width(), y1 = y + restore.height();
//$$             GlStateManager._glBindFramebuffer(36008, source.frameBufferId);
//$$             GlStateManager._glBindFramebuffer(36009, mask.frameBufferId);
//$$             try { GlStateManager._glBlitFrameBuffer(x, y, x1, y1, x, y, x1, y1, GL11.GL_DEPTH_BUFFER_BIT, GL11.GL_NEAREST); }
//$$             finally { GlStateManager._glBindFramebuffer(36160, 0); }
//$$         }
//$$         return true;
//$$         }
//$$     }
//$$
//$$     static void finish(ProjectedMaskBounds bounds) { history.finish(bounds); }
//$$ }
//#else
final class LegacyMaskReuse { private LegacyMaskReuse() {} }
//#endif
