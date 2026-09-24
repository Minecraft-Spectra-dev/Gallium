package cn.spectra.gallium.glowoutline.shader;

import org.lwjgl.opengl.*;
import org.lwjgl.system.MemoryStack;

/** A raw GL scope does not alter the native encoder's cached state. */
final class OutlineTemporalGlState implements AutoCloseable {
    private final int program = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
    private final int vao = GL11.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING);
    private final int read = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
    private final int draw = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
    private final int active = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
    private final boolean depth = GL11.glIsEnabled(GL11.GL_DEPTH_TEST);
    private final boolean depthWrite = GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK);
    private final boolean cull = GL11.glIsEnabled(GL11.GL_CULL_FACE);
    private final boolean srgb = GL11.glIsEnabled(GL30.GL_FRAMEBUFFER_SRGB);
    private final boolean scissor = GL30.glIsEnabledi(GL11.GL_SCISSOR_TEST, 0);
    private final boolean[] blend = new boolean[2];
    private final int[] textures = new int[5], samplers = new int[5], color = new int[8], box = new int[4];
    private final float[] viewport = new float[4];

    OutlineTemporalGlState() {
        try (var stack = MemoryStack.stackPush()) {
            var integers = stack.mallocInt(4);
            var floats = stack.mallocFloat(4);
            for (int i = 0; i < 2; i++) {
                blend[i] = GL30.glIsEnabledi(GL11.GL_BLEND, i);
                GL30.glGetIntegeri_v(GL11.GL_COLOR_WRITEMASK, i, integers);
                for (int j = 0; j < 4; j++) color[i * 4 + j] = integers.get(j);
            }
            GL30.glGetIntegeri_v(GL11.GL_SCISSOR_BOX, 0, integers);
            GL41.glGetFloati_v(GL11.GL_VIEWPORT, 0, floats);
            for (int j = 0; j < 4; j++) { box[j] = integers.get(j); viewport[j] = floats.get(j); }
        }
        for (int i = 0; i < textures.length; i++) {
            GL13.glActiveTexture(GL13.GL_TEXTURE0 + i);
            textures[i] = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
            samplers[i] = GL30.glGetIntegeri(GL33.GL_SAMPLER_BINDING, i);
        }
        GL13.glActiveTexture(active);
    }

    @Override public void close() {
        GL20.glUseProgram(program);
        GL30.glBindVertexArray(vao);
        for (int i = 0; i < textures.length; i++) {
            GL13.glActiveTexture(GL13.GL_TEXTURE0 + i);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, textures[i]);
            GL33.glBindSampler(i, samplers[i]);
        }
        GL13.glActiveTexture(active);
        GL41.glViewportIndexedf(0, viewport[0], viewport[1], viewport[2], viewport[3]);
        GL41.glScissorIndexed(0, box[0], box[1], box[2], box[3]);
        if (scissor) GL30.glEnablei(GL11.GL_SCISSOR_TEST, 0); else GL30.glDisablei(GL11.GL_SCISSOR_TEST, 0);
        for (int i = 0; i < 2; i++) {
            GL30.glColorMaski(i, color[i*4]!=0, color[i*4+1]!=0, color[i*4+2]!=0, color[i*4+3]!=0);
            if (blend[i]) GL30.glEnablei(GL11.GL_BLEND, i); else GL30.glDisablei(GL11.GL_BLEND, i);
        }
        GL11.glDepthMask(depthWrite);
        enable(GL11.GL_DEPTH_TEST, depth);
        enable(GL11.GL_CULL_FACE, cull);
        enable(GL30.GL_FRAMEBUFFER_SRGB, srgb);
        GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, read);
        GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, draw);
    }

    private static void enable(int capability, boolean value) {
        if (value) GL11.glEnable(capability); else GL11.glDisable(capability);
    }
}
