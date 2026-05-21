package cn.spectra.gallium.glowoutline.render;

import com.mojang.blaze3d.opengl.GlTexture;
import com.mojang.blaze3d.textures.GpuTexture;
import java.lang.reflect.Field;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL45;

public class DepthCopyHelper {
    private static Field glTextureIdField;

    public static int getGlId(GpuTexture texture) {
        if (texture == null) return -1;
        if (!(texture instanceof GlTexture glTex)) return -1;
        try {
            if (glTextureIdField == null) {
                glTextureIdField = GlTexture.class.getDeclaredField("id");
                glTextureIdField.setAccessible(true);
            }
            return glTextureIdField.getInt(glTex);
        } catch (Exception e) {
            return -1;
        }
    }

    public static void copyDepthRawGL(GpuTexture src, GpuTexture dst, int width, int height) {
        int srcId = getGlId(src);
        int dstId = getGlId(dst);
        if (srcId <= 0 || dstId <= 0) return;

        GL45.glCopyImageSubData(
                srcId, GL11.GL_TEXTURE_2D, 0, 0, 0, 0,
                dstId, GL11.GL_TEXTURE_2D, 0, 0, 0, 0,
                width, height, 1
        );
    }
}
