package cn.spectra.gallium.glowoutline.shader;

//#if MC>=1_21_06 && MC<1_26_02
import cn.spectra.gallium.glowoutline.capture.OpenGlMaskOrdering;
import com.mojang.blaze3d.opengl.GlTexture;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTexture;
import org.lwjgl.opengl.*;

/** One GUI preparation's copies, with storage checks shared by slots in the same atlas. */
final class NativeGuiMaskCopy {
    private final GlTexture destination;
    private final int destinationWidth, destinationHeight;
    private final OpenGlMaskOrdering.Stamp ordering;
    private GlTexture source;
    private int sourceWidth, sourceHeight;

    private NativeGuiMaskCopy(GlTexture destination, OpenGlMaskOrdering.Stamp ordering) {
        this.destination = destination;
        destinationWidth = destination.getWidth(0);
        destinationHeight = destination.getHeight(0);
        this.ordering = ordering;
    }

    static NativeGuiMaskCopy begin(GpuTexture destination) {
        if (!RenderSystem.isOnRenderThread() || !texture(destination, GpuTexture.USAGE_COPY_DST)) return null;
        var caps = GL.getCapabilities();
        if ((!caps.OpenGL43 && !caps.GL_ARB_copy_image)
                || (!caps.OpenGL45 && !caps.GL_ARB_direct_state_access)) return null;
        var ordering = OpenGlMaskOrdering.observe();
        if (ordering == null) return null;
        var image = (GlTexture) destination;
        if (!storage(image, image.getWidth(0), image.getHeight(0))) return null;
        return new NativeGuiMaskCopy(image, ordering);
    }

    private static boolean texture(GpuTexture texture, int usage) {
        return texture != null && texture.getClass() == GlTexture.class && !texture.isClosed()
                && texture.getDepthOrLayers() == 1
                && (texture.usage() & GpuTexture.USAGE_CUBEMAP_COMPATIBLE) == 0
                && (texture.usage() & usage) != 0;
    }

    private static boolean storage(GlTexture texture, int width, int height) {
        return texture.glId() > 0 && width > 0 && height > 0
                && GL45.glGetTextureLevelParameteri(texture.glId(), 0, GL11.GL_TEXTURE_INTERNAL_FORMAT) == GL11.GL_RGBA8
                && GL45.glGetTextureLevelParameteri(texture.glId(), 0, GL11.GL_TEXTURE_WIDTH) == width
                && GL45.glGetTextureLevelParameteri(texture.glId(), 0, GL11.GL_TEXTURE_HEIGHT) == height;
    }

    boolean copy(GpuTexture from, int dx, int dy, int sx, int sy, int width, int height) {
        if (!ordering.current() || !texture(from, GpuTexture.USAGE_COPY_SRC)
                || from == destination || destination.isClosed()
                || destination.getWidth(0) != destinationWidth || destination.getHeight(0) != destinationHeight
                || sx < 0 || sy < 0 || dx < 0 || dy < 0 || width <= 0 || height <= 0) return false;
        var image = (GlTexture) from;
        if (image.glId() == destination.glId()) return false;
        // This object lives only through the copy loop; no atlas rendering or allocation
        // occurs inside it. Do not reuse this storage proof in a later GUI preparation.
        if (source != image || sourceWidth != from.getWidth(0) || sourceHeight != from.getHeight(0)) {
            int w = from.getWidth(0), h = from.getHeight(0);
            if (!storage(image, w, h)) return false;
            source = image; sourceWidth = w; sourceHeight = h;
        }
        if ((long) sx + width > sourceWidth || (long) sy + height > sourceHeight
                || (long) dx + width > destinationWidth || (long) dy + height > destinationHeight) return false;
        //#if MC==1_21_11 || MC==1_26_01
        cn.spectra.gallium.glowoutline.capture.NativeVisibilityBatch.textureWritten(destination);
        //#endif
        GL43.glCopyImageSubData(image.glId(), GL11.GL_TEXTURE_2D, 0, sx, sy, 0,
                destination.glId(), GL11.GL_TEXTURE_2D, 0, dx, dy, 0, width, height, 1);
        return true;
    }
}
//#else
//$$ final class NativeGuiMaskCopy { private NativeGuiMaskCopy() {} }
//#endif
