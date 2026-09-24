package cn.spectra.gallium.glowoutline.capture;

//#if MC>=1_21_06 && MC<1_26_02
import com.mojang.blaze3d.opengl.GlTexture;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTexture;
import org.lwjgl.opengl.*;

/** Copies matching native depth storage without preparing framebuffer attachments. */
final class NativeDepthCopy {
    private NativeDepthCopy() {}

    static boolean copy(GpuTexture source, GpuTexture destination, int width, int height) {
        if (!RenderSystem.isOnRenderThread() || source == null || destination == null || source == destination
                || source.getClass() != GlTexture.class || destination.getClass() != GlTexture.class
                || source.isClosed() || destination.isClosed() || width <= 0 || height <= 0
                || !source.getFormat().hasDepthAspect() || !destination.getFormat().hasDepthAspect()
                || source.getDepthOrLayers() != 1 || destination.getDepthOrLayers() != 1
                || (source.usage() & GpuTexture.USAGE_CUBEMAP_COMPATIBLE) != 0
                || (destination.usage() & GpuTexture.USAGE_CUBEMAP_COMPATIBLE) != 0
                || (source.usage() & GpuTexture.USAGE_COPY_SRC) == 0
                || (destination.usage() & GpuTexture.USAGE_COPY_DST) == 0
                || source.getWidth(0) != width || source.getHeight(0) != height
                || destination.getWidth(0) != width || destination.getHeight(0) != height) return false;
        var caps = GL.getCapabilities();
        if ((!caps.OpenGL43 && !caps.GL_ARB_copy_image)
                || (!caps.OpenGL45 && !caps.GL_ARB_direct_state_access)
                || OpenGlMaskOrdering.observe() == null) return false;
        int src = ((GlTexture) source).glId(), dst = ((GlTexture) destination).glId();
        if (src <= 0 || dst <= 0 || src == dst) return false;
        // The native DEPTH32 label does not identify the physical storage format.
        // Storage can also be mutable, so validate the actual images on every copy.
        int format = GL45.glGetTextureLevelParameteri(src, 0, GL11.GL_TEXTURE_INTERNAL_FORMAT);
        if ((format != GL14.GL_DEPTH_COMPONENT16 && format != GL14.GL_DEPTH_COMPONENT24
                && format != GL14.GL_DEPTH_COMPONENT32 && format != GL30.GL_DEPTH_COMPONENT32F)
                || format != GL45.glGetTextureLevelParameteri(dst, 0, GL11.GL_TEXTURE_INTERNAL_FORMAT)
                || GL45.glGetTextureLevelParameteri(src, 0, GL11.GL_TEXTURE_WIDTH) != width
                || GL45.glGetTextureLevelParameteri(src, 0, GL11.GL_TEXTURE_HEIGHT) != height
                || GL45.glGetTextureLevelParameteri(dst, 0, GL11.GL_TEXTURE_WIDTH) != width
                || GL45.glGetTextureLevelParameteri(dst, 0, GL11.GL_TEXTURE_HEIGHT) != height) return false;
        // Copying identical formats preserves depth bits and leaves native GL bindings intact.
        //#if MC==1_21_11 || MC==1_26_01
        cn.spectra.gallium.glowoutline.capture.NativeVisibilityBatch.textureWritten(destination);
        //#endif
        GL43.glCopyImageSubData(src, GL11.GL_TEXTURE_2D, 0, 0, 0, 0,
                dst, GL11.GL_TEXTURE_2D, 0, 0, 0, 0, width, height, 1);
        return true;
    }
}
//#else
//$$ final class NativeDepthCopy { private NativeDepthCopy() {} }
//#endif
