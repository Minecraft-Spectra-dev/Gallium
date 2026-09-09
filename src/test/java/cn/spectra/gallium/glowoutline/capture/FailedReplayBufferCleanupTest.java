package cn.spectra.gallium.glowoutline.capture;

//#if MC==1_21_11 || MC==1_26_01
import cn.spectra.gallium.glowoutline.ItemEffectConfig;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.OutlineBufferSource;
import net.minecraft.client.renderer.RenderBuffers;
import net.minecraft.client.renderer.SectionBufferBuilderPack;
import net.minecraft.client.renderer.SubmitNodeStorage;
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class FailedReplayBufferCleanupTest {
    private static Field field(Class<?> owner, String name) throws Exception {
        Field field = owner.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    private static <T> T uninitialized(Class<T> type) throws Exception {
        var unsafe = (sun.misc.Unsafe) field(sun.misc.Unsafe.class, "theUnsafe").get(null);
        return type.cast(unsafe.allocateInstance(type));
    }

    static final class DirtyBytes extends ByteBufferBuilder {
        int pendingBytes = 128;
        int closes;
        Runnable beforeClose;
        RuntimeException closeFailure;
        private DirtyBytes() { super(0); }
        @Override public void close() {
            if (beforeClose != null) beforeClose.run();
            closes++;
            pendingBytes = 0;
            if (closeFailure != null) throw closeFailure;
        }
    }

    static final class NeverFlushSource extends MultiBufferSource.BufferSource {
        NeverFlushSource(DirtyBytes shared, DirtyBytes fixed) throws Exception {
            super(shared, new LinkedHashMap<>());
            fixedBuffers.put(null, fixed);
            startedBuilders.put(null, uninitialized(BufferBuilder.class));
        }
        @Override public void endBatch() { fail("failure cleanup must not flush partial vertices"); }
        @Override public void endLastBatch() { fail("failure cleanup must not submit another draw"); }
    }

    static final class BrokenStorage extends SubmitNodeStorage {
        final RuntimeException failure = new IllegalStateException("payload clear failed");
        int clears;
        @Override public void clear() { clears++; throw failure; }
    }

    private static GlowCaptureState state(boolean attempted, SubmitNodeStorage storage) throws Exception {
        GlowCaptureState state = new GlowCaptureState();
        state.beginCaptureLifecycle(10, false);
        state.config = new ItemEffectConfig("test", List.of());
        state.markPayloadCaptured();
        state.capturedThisFrame = true;
        state.finishCaptureScope();
        if (attempted) assertTrue(state.beginOrdinaryReplayAttempt(10));
        state.captureDispatcher = uninitialized(FeatureRenderDispatcher.class);
        field(FeatureRenderDispatcher.class, "submitNodeStorage").set(state.captureDispatcher, storage);
        return state;
    }

    @Test
    @SuppressWarnings("unchecked")
    void dropsAllDispatcherReferencesBeforeDisposalAndNeverRetriesPartialVertices() throws Exception {
        var pool = (List<GlowCaptureState>) field(GlowCaptureManager.class, "pool").get(null);
        var savedPool = new ArrayList<>(pool);
        var sharedField = field(GlowCaptureManager.class, "sharedCaptureBuffers");
        Object savedBuffers = sharedField.get(null);
        var attempted = state(true, new SubmitNodeStorage());
        var cold = state(false, new SubmitNodeStorage());
        var bytes = uninitialized(DirtyBytes.class);
        bytes.pendingBytes = 128;
        var source = new NeverFlushSource(bytes, bytes);
        var retired = uninitialized(RenderBuffers.class);
        field(RenderBuffers.class, "bufferSource").set(retired, source);
        field(RenderBuffers.class, "crumblingBufferSource").set(retired, source);
        var outline = uninitialized(OutlineBufferSource.class);
        field(OutlineBufferSource.class, "outlineBufferSource").set(outline, source);
        field(RenderBuffers.class, "outlineBufferSource").set(retired, outline);
        var pack = uninitialized(SectionBufferBuilderPack.class);
        field(SectionBufferBuilderPack.class, "buffers").set(pack, java.util.Map.of("alias", bytes));
        field(RenderBuffers.class, "fixedBufferPack").set(retired, pack);
        bytes.beforeClose = () -> {
            assertNull(attempted.captureDispatcher);
            assertNull(cold.captureDispatcher);
            assertFalse(attempted.capturedThisFrame);
            assertFalse(cold.capturedThisFrame);
        };
        var failure = new IllegalStateException("dispatcher emitted vertices then failed");
        try {
            pool.clear(); pool.add(attempted); pool.add(cold);
            sharedField.set(null, retired);
            GlowCaptureManager.discardFailedReplayBuffers(failure);
            assertNull(sharedField.get(null));
            assertEquals(1, bytes.closes, "aliased storage has one owner and one close");
            assertEquals(0, bytes.pendingBytes);
            assertEquals(0, failure.getSuppressed().length);
            assertTrue(attempted.hasPayloadReplayAttempted());
            assertFalse(attempted.beginOrdinaryReplayAttempt(10));
            assertFalse(cold.beginOrdinaryReplayAttempt(10));
            GlowCaptureManager.discardFailedReplayBuffers(failure);
            assertEquals(1, bytes.closes, "repeated abort cannot close retired bytes again");
            assertTrue(attempted.hasPayloadReplayAttempted());
        } finally {
            pool.clear(); pool.addAll(savedPool); sharedField.set(null, savedBuffers);
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void payloadClearAndCloseFailuresAreSuppressedWhileOtherStorageStillRetires() throws Exception {
        var pool = (List<GlowCaptureState>) field(GlowCaptureManager.class, "pool").get(null);
        var savedPool = new ArrayList<>(pool);
        var sharedField = field(GlowCaptureManager.class, "sharedCaptureBuffers");
        Object savedBuffers = sharedField.get(null);
        var brokenStorage = new BrokenStorage();
        var attempted = state(true, brokenStorage);
        var other = state(false, new SubmitNodeStorage());
        var brokenBytes = uninitialized(DirtyBytes.class);
        brokenBytes.closeFailure = new IllegalStateException("native close failed");
        var healthyBytes = uninitialized(DirtyBytes.class);
        healthyBytes.pendingBytes = 256;
        var source = new NeverFlushSource(brokenBytes, healthyBytes);
        var retired = uninitialized(RenderBuffers.class);
        field(RenderBuffers.class, "bufferSource").set(retired, source);
        var failure = new IllegalStateException("original dispatcher failure");
        try {
            pool.clear(); pool.add(attempted); pool.add(other);
            sharedField.set(null, retired);
            GlowCaptureManager.discardFailedReplayBuffers(failure);
            assertNull(sharedField.get(null));
            assertNull(attempted.captureDispatcher);
            assertNull(other.captureDispatcher);
            assertFalse(attempted.capturedThisFrame, "a throwing clear cannot leave payload eligible");
            assertFalse(other.capturedThisFrame);
            assertTrue(attempted.hasPayloadReplayAttempted());
            assertFalse(attempted.beginOrdinaryReplayAttempt(10));
            assertEquals(1, brokenStorage.clears);
            assertEquals(1, brokenBytes.closes);
            assertEquals(1, healthyBytes.closes);
            assertEquals(0, healthyBytes.pendingBytes);
            assertArrayEquals(new Throwable[] {brokenStorage.failure, brokenBytes.closeFailure}, failure.getSuppressed());
        } finally {
            pool.clear(); pool.addAll(savedPool); sharedField.set(null, savedBuffers);
        }
    }
}
//#endif
