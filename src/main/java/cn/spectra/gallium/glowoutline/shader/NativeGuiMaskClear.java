package cn.spectra.gallium.glowoutline.shader;

//#if MC>=1_21_06 && MC<1_26_02
import cn.spectra.gallium.glowoutline.capture.OpenGlMaskOrdering;
import com.mojang.blaze3d.opengl.GlTexture;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTexture;
import org.lwjgl.opengl.*;
import org.lwjgl.system.MemoryStack;

/** Clears previously written GUI rows while preserving zeroes in the rest of the image. */
final class NativeGuiMaskClear {
    private NativeGuiMaskClear() {}

    static boolean clear(GpuTexture texture, int rows) {
        if (!RenderSystem.isOnRenderThread() || texture == null || texture.getClass() != GlTexture.class
                || texture.isClosed() || texture.getDepthOrLayers() != 1
                || (texture.usage() & GpuTexture.USAGE_CUBEMAP_COMPATIBLE) != 0
                || (texture.usage() & GpuTexture.USAGE_COPY_DST) == 0
                || rows < 0 || rows > texture.getHeight(0)) return false;
        var caps = GL.getCapabilities();
        if ((!caps.OpenGL44 && !caps.GL_ARB_clear_texture)
                || (!caps.OpenGL45 && !caps.GL_ARB_direct_state_access)
                || OpenGlMaskOrdering.observe() == null) return false;
        int id = ((GlTexture) texture).glId();
        int width = texture.getWidth(0), height = texture.getHeight(0);
        if (id <= 0 || width <= 0 || height <= 0
                || GL45.glGetTextureLevelParameteri(id, 0, GL11.GL_TEXTURE_INTERNAL_FORMAT) != GL11.GL_RGBA8
                || GL45.glGetTextureLevelParameteri(id, 0, GL11.GL_TEXTURE_WIDTH) != width
                || GL45.glGetTextureLevelParameteri(id, 0, GL11.GL_TEXTURE_HEIGHT) != height) return false;
        if (rows != 0) {
            //#if MC==1_21_11 || MC==1_26_01
            cn.spectra.gallium.glowoutline.capture.NativeVisibilityBatch.textureWritten(texture);
            //#endif
            try (var stack = MemoryStack.stackPush()) {
                GL44.glClearTexSubImage(id, 0, 0, 0, 0, width, rows, 1,
                        GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, stack.ints(0));
            }
        }
        return true;
    }
}
//#else
//$$ final class NativeGuiMaskClear { private NativeGuiMaskClear() {} }
//#endif
