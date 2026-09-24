package cn.spectra.gallium.glowoutline.sr;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL30;

/** Reports legacy depth storage precisely before SR duplicates it with glTexStorage2D. */
public final class SuperResolutionLegacyDepthFormat {
    private SuperResolutionLegacyDepthFormat() {}

    public static int resolve(int target, int texture, int internalFormat) {
        if (target != GL11.GL_TEXTURE_2D || texture <= 0
                || internalFormat != GL11.GL_DEPTH_COMPONENT) return internalFormat;

        // SR's getter has already restored the caller's binding. Preserve that binding again
        // while querying the actual storage; do not change the source texture or its precision.
        int previous = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        try {
            GL11.glBindTexture(target, texture);
            int bits = GL11.glGetTexLevelParameteri(target, 0, GL14.GL_TEXTURE_DEPTH_SIZE);
            int type = GL11.glGetTexLevelParameteri(target, 0, GL30.GL_TEXTURE_DEPTH_TYPE);
            return sizedFormat(internalFormat, bits, type);
        } finally {
            GL11.glBindTexture(target, previous);
        }
    }

    static int sizedFormat(int original, int bits, int type) {
        if (original != GL11.GL_DEPTH_COMPONENT) return original;
        if (type == GL11.GL_FLOAT && bits == 32) return GL30.GL_DEPTH_COMPONENT32F;
        if (type == GL30.GL_UNSIGNED_NORMALIZED) {
            if (bits == 24) return GL14.GL_DEPTH_COMPONENT24;
            if (bits == 32) return GL14.GL_DEPTH_COMPONENT32;
        }
        // SR 0.9.1 represents these three sized depth formats. Do not guess a different
        // precision for an unknown layout (or a format its TextureFormat enum cannot encode).
        return original;
    }
}
