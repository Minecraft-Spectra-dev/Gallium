package cn.spectra.gallium.glowoutline.capture;

//#if MC<1_21_05
//$$ import cn.spectra.gallium.glowoutline.IrisCompat;
//$$ import com.mojang.blaze3d.platform.GlStateManager;
//$$ import com.mojang.blaze3d.systems.RenderSystem;
//$$ import org.lwjgl.opengl.GL11;
//$$ import org.lwjgl.opengl.GL13;
//$$ import org.lwjgl.opengl.GL30;
//$$
//$$ /** TextureTarget allocation and deletion must not redirect the surrounding vanilla draw. */
//$$ public final class LegacyFramebufferBinding implements AutoCloseable {
//$$     private final int read = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
//$$     private final int draw = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
//$$     private final int[] viewport = new int[4];
//$$     private final int activeTexture = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
//$$     private final int texture = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
//$$     private final boolean depthTest = GL11.glIsEnabled(GL11.GL_DEPTH_TEST);
//$$     private final float[] clearColor = new float[4];
//$$     private final double clearDepth = GL11.glGetDouble(GL11.GL_DEPTH_CLEAR_VALUE);
//$$     private final IrisCompat.TargetBindingSnapshot irisBinding = IrisCompat.captureTargetBinding();
//$$
//$$     public LegacyFramebufferBinding() {
//$$         GL11.glGetIntegerv(GL11.GL_VIEWPORT, viewport);
//$$         GL11.glGetFloatv(GL11.GL_COLOR_CLEAR_VALUE, clearColor);
//$$     }
//$$
//$$     @Override
//$$     public void close() {
//$$         GlStateManager._glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, read);
//$$         GlStateManager._glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, draw);
//$$         RenderSystem.viewport(viewport[0], viewport[1], viewport[2], viewport[3]);
//$$         GlStateManager._activeTexture(activeTexture);
//$$         GlStateManager._bindTexture(texture);
//$$         if (depthTest) GlStateManager._enableDepthTest(); else GlStateManager._disableDepthTest();
//$$         GlStateManager._clearColor(clearColor[0], clearColor[1], clearColor[2], clearColor[3]);
//$$         GlStateManager._clearDepth(clearDepth);
//$$         // TextureTarget.clear() calls bindWrite(), which also tells Iris this is not main.
//$$         // Restoring only the FBO leaves Iris selecting vanilla shaders for the next entity.
//$$         IrisCompat.restoreTargetBinding(irisBinding);
//$$     }
//$$ }
//#else
final class LegacyFramebufferBinding { private LegacyFramebufferBinding() {} }
//#endif
