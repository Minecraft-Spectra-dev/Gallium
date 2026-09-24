package cn.spectra.gallium.glowoutline.shader;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexBuffer;
import com.mojang.blaze3d.vertex.VertexFormat;

/** The legacy composite's four vertices change only when the output size changes. */
final class LegacyFullscreenQuad {
    private static VertexBuffer vertices;
    private static int width, height;

    private LegacyFullscreenQuad() {}

    static void draw(int w, int h) {
        RenderSystem.assertOnRenderThread();
        // Direct VAO binding must invalidate BufferUploader's cached binding, including on
        // allocation/upload failure. The next ordinary item draw must bind its own buffer.
        BufferUploader.invalidate();
        try {
            if (vertices == null) vertices = new VertexBuffer(
                    VertexBuffer.Usage.STATIC);
            vertices.bind();
            if (width != w || height != h) {
                var builder = Tesselator.getInstance().getBuilder();
                builder.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.BLIT_SCREEN);
                builder.vertex(0.0f, 0.0f, 500.0f).uv(0f, 1f).color(255, 255, 255, 255).endVertex();
                builder.vertex((float) w, 0.0f, 500.0f).uv(1f, 1f).color(255, 255, 255, 255).endVertex();
                builder.vertex((float) w, (float) h, 500.0f).uv(1f, 0f).color(255, 255, 255, 255).endVertex();
                builder.vertex(0.0f, (float) h, 500.0f).uv(0f, 0f).color(255, 255, 255, 255).endVertex();
                // upload owns and closes MeshData, just like BufferUploader.upload.
                vertices.upload(builder.end());
                width = w;
                height = h;
            }
            vertices.drawWithShader(RenderSystem.getModelViewMatrix(),
                    RenderSystem.getProjectionMatrix(), RenderSystem.getShader());
        } finally {
            VertexBuffer.unbind();
        }
    }

    static void dispose() {
        var retired = vertices;
        vertices = null;
        width = height = 0;
        if (retired != null) retired.close();
    }
}
