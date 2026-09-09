package cn.spectra.gallium.glowoutline.capture;

//#if MC>=1_21_09
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.SubmitNodeStorage;
import net.minecraft.client.renderer.rendertype.RenderType;
//#else
//$$ import com.mojang.blaze3d.vertex.VertexConsumer;
//$$ import net.minecraft.client.renderer.MultiBufferSource;
//$$ import net.minecraft.client.renderer.RenderType;
//#endif
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ScopeSafeCaptureWrapperTest {

    //#if MC>=1_21_09
    @Test
    void invalidationBetweenRealSubmitsKeepsTheVanillaStorageAttachedUntilScopeEnd() {
        CountingSubmitNodeStorage vanilla = new CountingSubmitNodeStorage();
        GlowCaptureState state = new GlowCaptureState();
        state.beginCaptureLifecycle(11L, false);

        DuplicatingSubmitNodeStorage wrapper = new DuplicatingSubmitNodeStorage(vanilla, state);
        state.duplicatingStorage = wrapper;
        wrapper.submitCustomGeometry(null, null, null);

        state.invalidateCapture();
        wrapper.submitCustomGeometry(null, null, null);
        wrapper.submitCustomGeometry(null, null, null);

        assertEquals(3, vanilla.customGeometrySubmits,
                "capture invalidation must not interrupt later vanilla submits in the same scope");

        state.finishCaptureScope();
        assertThrows(IllegalStateException.class,
                () -> wrapper.submitCustomGeometry(null, null, null),
                "only scope end performs the full delegate detach");
    }

    private static final class CountingSubmitNodeStorage extends SubmitNodeStorage {
        private int customGeometrySubmits;

        @Override
        public void submitCustomGeometry(PoseStack poseStack, RenderType renderType,
                                         SubmitNodeCollector.CustomGeometryRenderer renderer) {
            customGeometrySubmits++;
        }
    }
    //#else
    //$$ @Test
    //$$ void invalidationInsideAVertexChainFinishesThatChainThenStopsMirroring() {
    //$$     RecordingVertexConsumer vanilla = new RecordingVertexConsumer();
    //$$     RecordingVertexConsumer capture = new RecordingVertexConsumer();
    //$$     RecordingCaptureSource captureSource = new RecordingCaptureSource(capture);
    //$$     MultiBufferSource vanillaSource = renderType -> vanilla;
    //$$     RenderType layer = null;
    //$$
    //$$     GlowCaptureState state = new GlowCaptureState();
    //$$     state.beginCaptureLifecycle(11L, false);
    //$$     CaptureSites.ReusableTeeMultiBufferSource tee =
    //$$             new CaptureSites.ReusableTeeMultiBufferSource();
    //$$     state.customBufferSource = captureSource;
    //$$     state.reusableTee = tee;
    //$$     tee.reset(vanillaSource, captureSource, state);
    //$$
    //$$     VertexConsumer activeVertex = tee.getBuffer(layer);
    //$$     activeVertex.addVertex(1.0f, 2.0f, 3.0f);
    //$$     state.invalidateCapture();
    //$$     activeVertex.setColor(1, 2, 3, 4).setUv(0.25f, 0.75f);
    //$$
    //$$     assertEquals(1, vanilla.vertices);
    //$$     assertEquals(1, capture.vertices);
    //$$     assertEquals(1, vanilla.colors);
    //$$     assertEquals(1, capture.colors,
    //$$             "the capture side of the already-started vertex must finish safely");
    //$$     assertEquals(1, vanilla.uvs);
    //$$     assertEquals(1, capture.uvs);
    //$$     assertEquals(0, captureSource.endFrames,
    //$$             "active capture buffers cannot be discarded inside the vertex chain");
    //$$
    //$$     VertexConsumer vanillaOnly = tee.getBuffer(layer);
    //$$     assertSame(vanilla, vanillaOnly,
    //$$             "new vertices after invalidation must bypass the capture consumer");
    //$$     vanillaOnly.addVertex(4.0f, 5.0f, 6.0f).setColor(5, 6, 7, 8).setUv(0.5f, 0.5f);
    //$$     assertEquals(2, vanilla.vertices);
    //$$     assertEquals(1, capture.vertices);
    //$$
    //$$     state.finishCaptureScope();
    //$$     assertEquals(1, captureSource.endFrames,
    //$$             "the deferred Gallium payload is discarded exactly at scope end");
    //$$     assertThrows(IllegalStateException.class, () -> tee.getBuffer(layer));
    //$$ }
    //$$
    //$$ private static final class RecordingCaptureSource
    //$$         extends CaptureSites.DelayingMultiBufferSource {
    //$$     private final VertexConsumer consumer;
    //$$     private int endFrames;
    //$$
    //$$     private RecordingCaptureSource(VertexConsumer consumer) {
    //$$         this.consumer = consumer;
    //$$     }
    //$$
    //$$     @Override
    //$$     public VertexConsumer getBuffer(RenderType renderType) {
    //$$         return consumer;
    //$$     }
    //$$
    //$$     @Override
    //$$     public void endFrame() {
    //$$         endFrames++;
    //$$     }
    //$$ }
    //$$
    //$$ private static final class RecordingVertexConsumer implements VertexConsumer {
    //$$     private int vertices;
    //$$     private int colors;
    //$$     private int uvs;
    //$$
    //$$     @Override public VertexConsumer addVertex(float x, float y, float z) {
    //$$         vertices++;
    //$$         return this;
    //$$     }
    //$$     @Override public VertexConsumer setColor(int r, int g, int b, int a) {
    //$$         colors++;
    //$$         return this;
    //$$     }
    //$$     @Override public VertexConsumer setUv(float u, float v) {
    //$$         uvs++;
    //$$         return this;
    //$$     }
    //$$     @Override public VertexConsumer setUv1(int u, int v) { return this; }
    //$$     @Override public VertexConsumer setUv2(int u, int v) { return this; }
    //$$     @Override public VertexConsumer setNormal(float x, float y, float z) { return this; }
    //$$ }
    //#endif
}
