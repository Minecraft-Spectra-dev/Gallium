package cn.spectra.gallium.glowoutline.capture;

import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ScopeSafeCaptureWrapperTest {

    @Test
    void invalidationInsideAVertexChainFinishesThatChainThenStopsMirroring() {
        RecordingVertexConsumer vanilla = new RecordingVertexConsumer();
        RecordingVertexConsumer capture = new RecordingVertexConsumer();
        RecordingCaptureSource captureSource = new RecordingCaptureSource(capture);
        MultiBufferSource vanillaSource = renderType -> vanilla;
        RenderType layer = null;

        GlowCaptureState state = new GlowCaptureState();
        state.beginCaptureLifecycle(11L, false);
        CaptureSites.ReusableTeeMultiBufferSource tee =
                new CaptureSites.ReusableTeeMultiBufferSource();
        state.customBufferSource = captureSource;
        state.reusableTee = tee;
        tee.reset(vanillaSource, captureSource, state);

        VertexConsumer activeVertex = tee.getBuffer(layer);
        activeVertex.vertex(1.0f, 2.0f, 3.0f);
        state.invalidateCapture();
        activeVertex.color(1, 2, 3, 4).uv(0.25f, 0.75f).endVertex();
        assertEquals(1, capture.ended);
        assertEquals(1, vanilla.ended);

        assertEquals(1, vanilla.vertices);
        assertEquals(1, capture.vertices);
        assertEquals(1, vanilla.colors);
        assertEquals(1, capture.colors,
                "the capture side of the already-started vertex must finish safely");
        assertEquals(1, vanilla.uvs);
        assertEquals(1, capture.uvs);
        assertEquals(0, captureSource.endFrames,
                "active capture buffers cannot be discarded inside the vertex chain");

        VertexConsumer vanillaOnly = tee.getBuffer(layer);
        assertSame(vanilla, vanillaOnly,
                "new vertices after invalidation must bypass the capture consumer");
        vanillaOnly.vertex(4.0f, 5.0f, 6.0f).color(5, 6, 7, 8).uv(0.5f, 0.5f);
        assertEquals(2, vanilla.vertices);
        assertEquals(1, capture.vertices);

        state.finishCaptureScope();
        assertEquals(1, captureSource.endFrames,
                "the deferred Gallium payload is discarded exactly at scope end");
        assertThrows(IllegalStateException.class, () -> tee.getBuffer(layer));
    }

    private static final class RecordingCaptureSource
            extends CaptureSites.DelayingMultiBufferSource {
        private final VertexConsumer consumer;
        private int endFrames;

        private RecordingCaptureSource(VertexConsumer consumer) {
            this.consumer = consumer;
        }

        @Override
        public VertexConsumer getBuffer(RenderType renderType) {
            return consumer;
        }

        @Override
        public void endFrame() {
            endFrames++;
        }
    }

    private static final class RecordingVertexConsumer implements VertexConsumer {
        private int vertices;
        private int colors;
        private int uvs;
        private int ended;
        @Override public void endVertex() { ended++; }
        @Override public void defaultColor(int r, int g, int b, int a) {}
        @Override public void unsetDefaultColor() {}

        @Override public VertexConsumer vertex(double x, double y, double z) {
            vertices++;
            return this;
        }
        @Override public VertexConsumer color(int r, int g, int b, int a) {
            colors++;
            return this;
        }
        @Override public VertexConsumer uv(float u, float v) {
            uvs++;
            return this;
        }
        @Override public VertexConsumer overlayCoords(int u, int v) { return this; }
        @Override public VertexConsumer uv2(int u, int v) { return this; }
        @Override public VertexConsumer normal(float x, float y, float z) { return this; }
    }
}
