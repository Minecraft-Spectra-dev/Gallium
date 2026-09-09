package cn.spectra.gallium.glowoutline.capture;

//#if MC==1_21_11 || MC==1_26_01
import cn.spectra.gallium.glowoutline.SuperResolutionCompat;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.systems.RenderSystem;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import net.minecraft.client.renderer.RenderBuffers;
import org.junit.jupiter.api.Test;
import static cn.spectra.gallium.glowoutline.sr.streaming.SrStreamingCoordinator.*;
import static org.junit.jupiter.api.Assertions.*;

/** Recorder entry used by the mixin -> capture cleanup/refusal -> real final sequence. */
class NativeMaskCapacityTest {
    private static Field field(Class<?> type, String name) throws Exception {
        var result = type.getDeclaredField(name); result.setAccessible(true); return result;
    }
    private static <T> T uninitialized(Class<T> type) throws Exception {
        var unsafe = (sun.misc.Unsafe) field(sun.misc.Unsafe.class, "theUnsafe").get(null);
        return type.cast(unsafe.allocateInstance(type));
    }
    static class InputMesh extends MeshData {
        final ByteBuffer vertices, indices;
        int closes;
        Throwable readFailure;
        Throwable closeFailure;
        InputMesh(int vertexBytes, int indexBytes) {
            super(null, null);
            vertices = ByteBuffer.allocateDirect(vertexBytes);
            indices = indexBytes == 0 ? null : ByteBuffer.allocateDirect(indexBytes);
        }
        @Override public ByteBuffer vertexBuffer() {
            if (readFailure instanceof RuntimeException failure) throw failure;
            if (readFailure instanceof Error failure) throw failure;
            return vertices;
        }
        @Override public ByteBuffer indexBuffer() { return indices; }
        @Override public void close() {
            closes++;
            if (closeFailure instanceof RuntimeException failure) throw failure;
            if (closeFailure instanceof Error failure) throw failure;
        }
    }
    private static List<GlowCaptureState> scheduledPair() {
        var states = List.of(new GlowCaptureState(), new GlowCaptureState());
        for (var state : states) {
            state.beginCaptureLifecycle(7, false); state.markPayloadCaptured(); state.capturedThisFrame = true;
            assertTrue(state.markStreamingEligible(new Eligibility(7, CaptureDomain.WORLD, 3, SnapshotAuthority.AUTHORITATIVE)));
            state.finishCaptureScope();
        }
        var factory = new PreparedFramePlanFactory(7, 7);
        assertTrue(factory.eligibilityPreflight(states.stream().map(GlowCaptureState::streamingEligibility).toList(), true));
        factory.selectExecutionMode(FrameExecutionMode.HACK_PER_STATE_OUTPUT);
        factory.prepareFrameResources(new PreparedFrameResources(ReplayTargetMode.STATE_MASK, 3, -1, -1, 854, 480), true);
        factory.validateFinalResources(true);
        var frame = factory.createFramePlan().orElseThrow();
        for (var state : states) assertTrue(state.scheduleStreamingReplay(frame.replayPlanFactory().createPlan(
                state.streamingEligibility(), currentHackOutputStateSpec(CaptureDomain.WORLD, true, true, false, true)).orElseThrow()));
        return states;
    }
    private static boolean sequence(List<GlowCaptureState> states, Predicate<GlowCaptureState> replay, Runnable abort) throws Exception {
        var method = SuperResolutionCompat.class.getDeclaredMethod("replayPreparedStates", List.class, Predicate.class, Predicate.class, Runnable.class);
        method.setAccessible(true);
        try {
            return (boolean) method.invoke(null, states, replay,
                    (Predicate<GlowCaptureState>) state -> { fail("Rejected capture must not composite"); return false; }, abort);
        } catch (InvocationTargetException wrapper) {
            if (wrapper.getCause() instanceof RuntimeException failure) throw failure;
            if (wrapper.getCause() instanceof Error failure) throw failure;
            throw wrapper;
        }
    }
    static final class Chain implements AutoCloseable {
        final List<GlowCaptureState> states = scheduledPair();
        final List<GlowCaptureState> pool, savedPool;
        final Object savedBuffers;
        final Object savedRenderThread;
        final FailedReplayBufferCleanupTest.DirtyBytes dirty;
        final List<String> events = new ArrayList<>();
        NativeMaskMeshReplay.CapacityExceeded rejection;
        int dispatches, aborts;
        @SuppressWarnings("unchecked")
        Chain() throws Exception {
            pool = (List<GlowCaptureState>) field(GlowCaptureManager.class, "pool").get(null);
            savedPool = new ArrayList<>(pool); savedBuffers = field(GlowCaptureManager.class, "sharedCaptureBuffers").get(null);
            savedRenderThread = field(RenderSystem.class, "renderThread").get(null);
            field(RenderSystem.class, "renderThread").set(null, Thread.currentThread());
            pool.clear(); pool.addAll(states);
            dirty = uninitialized(FailedReplayBufferCleanupTest.DirtyBytes.class);
            dirty.pendingBytes = 128;
            var buffers = uninitialized(RenderBuffers.class);
            field(RenderBuffers.class, "bufferSource").set(buffers, new FailedReplayBufferCleanupTest.NeverFlushSource(dirty, dirty));
            field(GlowCaptureManager.class, "sharedCaptureBuffers").set(null, buffers);
            field(NativeMaskMeshReplay.class, "arena").set(null, new ByteBufferBuilder(64, NativeMaskMeshReplay.MAX_BYTES));
            field(NativeMaskMeshReplay.class, "recording").setBoolean(null, true);
        }
        void fill(NativeMaskMeshReplay.CapacityExceeded.Limit limit) {
            if (limit == NativeMaskMeshReplay.CapacityExceeded.Limit.BYTES) {
                // Leave four bytes: the next vertex copy fits, but its index copy refuses.
                try (var prefix = new InputMesh(NativeMaskMeshReplay.MAX_BYTES - 4, 0)) { NativeMaskMeshReplay.record(null, prefix); }
            } else {
                for (int i = 0; i < NativeMaskMeshReplay.MAX_DRAWS; i++)
                    try (var prefix = new InputMesh(4, 0)) { NativeMaskMeshReplay.record(null, prefix); }
            }
        }
        boolean run(InputMesh input, Runnable restored) throws Exception {
            return sequence(states, state -> {
                dispatches++; assertTrue(state.beginStreamingReplayAttempt());
                try {
                    try {
                        try { NativeMaskMeshReplay.record(null, input); }
                        catch (RuntimeException | Error failure) { GlowCaptureManager.discardFailedReplayBuffers(failure); throw failure; }
                        finally { NativeMaskMeshReplay.abort(); }
                    } finally { events.add("restored"); restored.run(); }
                } catch (NativeMaskMeshReplay.CapacityExceeded capacity) {
                    rejection = capacity;
                    events.add("capacity boundary");
                    GlowCaptureManager.completeNativeCapacityRejection(capacity);
                }
                return state.capturedThisFrame;
            }, () -> { aborts++; states.forEach(GlowCaptureState::invalidateCapture); });
        }
        @Override public void close() throws Exception {
            NativeMaskMeshReplay.dispose(); pool.clear(); pool.addAll(savedPool);
            field(GlowCaptureManager.class, "sharedCaptureBuffers").set(null, savedBuffers);
            field(RenderSystem.class, "renderThread").set(null, savedRenderThread);
        }
    }
    private static void cleanRefusal(NativeMaskMeshReplay.CapacityExceeded.Limit limit) throws Exception {
        try (var chain = new Chain()) {
            chain.fill(limit);
            var input = new InputMesh(4, 4);
            assertFalse(chain.run(input, () -> {}));
            assertEquals(limit, chain.rejection.limit());
            assertEquals(1, input.closes); assertEquals(1, chain.dirty.closes);
            assertEquals(0, chain.dirty.pendingBytes);
            assertEquals(List.of("restored", "capacity boundary"), chain.events);
            assertEquals(1, chain.dispatches); assertEquals(1, chain.aborts);
            assertTrue(chain.states.getFirst().hasPayloadReplayAttempted());
            assertFalse(chain.states.get(1).hasPayloadReplayAttempted());
            assertTrue(chain.states.stream().allMatch(state -> state.captureStage() == CaptureStage.INVALID));
            assertFalse(chain.states.getFirst().beginStreamingReplayAttempt());
            assertFalse(sequence(chain.states, state -> { fail("No payload retry"); return true; }, () -> {}));
            assertEquals(0, field(NativeMaskMeshReplay.class, "count").getInt(null));
            assertEquals(0, field(NativeMaskMeshReplay.class, "bytes").getInt(null));
            assertFalse(field(NativeMaskMeshReplay.class, "recording").getBoolean(null));
            var arena = field(NativeMaskMeshReplay.class, "arena").get(null);
            assertEquals(0, field(ByteBufferBuilder.class, "resultCount").getInt(arena));
        }
    }
    @Test void byteRefusalClosesPartialCopyOriginalAndDirtyBuffersWithoutEscapingFinalReplay() throws Exception {
        cleanRefusal(NativeMaskMeshReplay.CapacityExceeded.Limit.BYTES);
    }
    @Test void drawRefusalStopsTheRemainingSequenceWithoutReconsumption() throws Exception {
        cleanRefusal(NativeMaskMeshReplay.CapacityExceeded.Limit.DRAWS);
    }
    @Test void originalMeshCloseFailureIsNotHiddenByCapacityClassification() throws Exception {
        try (var chain = new Chain()) {
            chain.fill(NativeMaskMeshReplay.CapacityExceeded.Limit.DRAWS);
            var input = new InputMesh(4, 0); input.closeFailure = new AssertionError("mesh close failed");
            var thrown = assertThrows(NativeMaskMeshReplay.CapacityExceeded.class, () -> chain.run(input, () -> {}));
            assertSame(input.closeFailure, thrown.getSuppressed()[0]);
            assertEquals(1, input.closes); assertEquals(1, chain.dirty.closes);
        }
    }
    @Test void partialBufferCloseFailureStillPropagatesWithTheOriginalRefusal() throws Exception {
        try (var chain = new Chain()) {
            chain.fill(NativeMaskMeshReplay.CapacityExceeded.Limit.DRAWS);
            chain.dirty.closeFailure = new IllegalStateException("buffer close failed");
            var thrown = assertThrows(NativeMaskMeshReplay.CapacityExceeded.class,
                    () -> chain.run(new InputMesh(4, 0), () -> {}));
            assertSame(chain.dirty.closeFailure, thrown.getSuppressed()[0]);
        }
    }
    @Test void rendererAndRestoreFailuresKeepTheirOriginalIdentity() throws Exception {
        for (Throwable readFailure : List.of(new IllegalStateException("Native mask mesh capacity exceeded: BYTES"),
                new AssertionError("renderer error"))) try (var chain = new Chain()) {
            var input = new InputMesh(4, 0);
            input.readFailure = readFailure;
            assertSame(readFailure, assertThrows(readFailure.getClass(), () -> chain.run(input, () -> {})));
            assertEquals(1, input.closes); assertNull(chain.rejection);
        }
        try (var chain = new Chain()) {
            chain.fill(NativeMaskMeshReplay.CapacityExceeded.Limit.DRAWS);
            var failure = new AssertionError("projection restore failed");
            assertSame(failure, assertThrows(AssertionError.class,
                    () -> chain.run(new InputMesh(4, 0), () -> { throw failure; })));
            assertNull(chain.rejection);
        }
    }

    @Test void copyCloseFailureBypassesTheCleanCapacityBoundary() throws Exception {
        try (var chain = new Chain()) {
            chain.fill(NativeMaskMeshReplay.CapacityExceeded.Limit.DRAWS);
            Object entry = ((Object[]) field(NativeMaskMeshReplay.class, "entries").get(null))[0];
            var meshField = field(entry.getClass(), "mesh");
            var owned = (MeshData) meshField.get(entry);
            var failure = new AssertionError("owned copy close failed");
            meshField.set(entry, new MeshData(null, null) {
                @Override public void close() { owned.close(); throw failure; }
            });
            assertSame(failure, assertThrows(AssertionError.class,
                    () -> chain.run(new InputMesh(4, 0), () -> {})));
            assertNull(chain.rejection);
            assertEquals(0, field(NativeMaskMeshReplay.class, "count").getInt(null));
        }
    }
}
//#endif
