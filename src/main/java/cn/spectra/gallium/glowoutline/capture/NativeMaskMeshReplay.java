package cn.spectra.gallium.glowoutline.capture;

//#if MC==1_21_11 || MC==1_26_01
import com.mojang.blaze3d.ProjectionType;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.MeshData;
import java.nio.ByteBuffer;
import net.minecraft.client.renderer.rendertype.RenderType;
import org.joml.Matrix4f;
import org.joml.Matrix4fStack;
import org.lwjgl.system.MemoryUtil;

/**
 * One bounded, render-thread-only mesh copy scope, reused sequentially for WORLD masks.
 * SubmitNodeStorage and the original MeshData are consumed once by the source-grid draw.
 * The native pass owns different CPU bytes (including sorted indices); it never retains
 * the immediate-upload GPU buffers, which RenderType is allowed to overwrite between draws.
 */
public final class NativeMaskMeshReplay {
    static final int MAX_BYTES = 16 * 1024 * 1024;
    static final int MAX_DRAWS = 256;

    /** Expected capacity refusal, never a substitute for a renderer or cleanup failure. */
    public static final class CapacityExceeded extends RuntimeException {
        public enum Limit { BYTES, DRAWS }
        private final Limit limit;
        private CapacityExceeded(Limit limit) {
            super("Native mask mesh capacity exceeded: " + limit);
            this.limit = limit;
        }
        public Limit limit() { return limit; }
    }
    private static final Entry[] entries = new Entry[MAX_DRAWS];
    private static final Matrix4f savedNativeModelView = new Matrix4f();
    private static ByteBufferBuilder arena;
    private static boolean recording;
    private static boolean drawingNative;
    private static int nativeLayerDepth;
    private static int count;
    private static int bytes;

    private static final class Entry {
        final Matrix4f modelView = new Matrix4f();
        RenderType type;
        MeshData mesh;
        GpuBufferSlice projection;
        ProjectionType projectionType;
    }

    static final class OwnedMesh extends MeshData {
        private ByteBufferBuilder.Result indices;
        OwnedMesh(ByteBufferBuilder.Result vertices, ByteBufferBuilder.Result indices, DrawState state) {
            super(vertices, state);
            this.indices = indices;
        }
        @Override public ByteBuffer indexBuffer() { return indices == null ? null : indices.byteBuffer(); }
        @Override public void close() {
            var retired = indices;
            indices = null;
            try { super.close(); } finally { if (retired != null) retired.close(); }
        }
    }

    private NativeMaskMeshReplay() {}

    static void begin() {
        RenderSystem.assertOnRenderThread();
        if (recording || count != 0) throw new IllegalStateException("Nested native mask mesh replay");
        if (arena == null) arena = new ByteBufferBuilder(65536, MAX_BYTES);
        bytes = 0;
        recording = true;
    }

    private static ByteBufferBuilder.Result copy(ByteBuffer data) {
        if (data == null) return null;
        int size = data.remaining();
        if ((long) bytes + size > MAX_BYTES) throw new CapacityExceeded(CapacityExceeded.Limit.BYTES);
        MemoryUtil.memCopy(MemoryUtil.memAddress(data), arena.reserve(size), size);
        bytes += size;
        return arena.build();
    }

    /** Called before RenderType takes ownership of the original mesh. */
    public static void record(RenderType type, MeshData source) {
        if (!recording) return;
        try {
            recordMesh(type, source);
        } catch (RuntimeException | Error failure) {
            // The mixin calls us before RenderType's try-with-resources. Keep the original
            // mesh's close obligation here as well as ownership of the independent copies.
            try { source.close(); } catch (RuntimeException | Error closeFailure) {
                failure.addSuppressed(closeFailure);
            }
            throw failure;
        }
    }

    private static void recordMesh(RenderType type, MeshData source) {
        if (count == MAX_DRAWS) throw new CapacityExceeded(CapacityExceeded.Limit.DRAWS);
        // WORLD replay has no GUI scissor. Reject unexpected state rather than silently
        // replaying its source-grid pixel rectangle against native-size attachments.
        if (RenderSystem.getScissorStateForRenderTypeDraws().enabled()) {
            throw new IllegalStateException("Unexpected scissor in WORLD mask replay");
        }
        var vertices = copy(source.vertexBuffer());
        ByteBufferBuilder.Result indices = null;
        try {
            indices = copy(source.indexBuffer());
            Entry entry = entries[count];
            if (entry == null) entries[count] = entry = new Entry();
            entry.modelView.set(RenderSystem.getModelViewMatrix());
            entry.projection = RenderSystem.getProjectionMatrixBuffer();
            entry.projectionType = RenderSystem.getProjectionType();
            entry.type = type;
            entry.mesh = new OwnedMesh(vertices, indices, source.drawState());
            count++;
        } catch (RuntimeException | Error failure) {
            try { vertices.close(); } finally { if (indices != null) indices.close(); }
            throw failure;
        }
    }

    static void drawNative() {
        recording = false; // The copy draw must never record itself.
        savedNativeModelView.set(RenderSystem.getModelViewMatrix());
        var savedProjection = RenderSystem.getProjectionMatrixBuffer();
        var savedProjectionType = RenderSystem.getProjectionType();
        drawingNative = true;
        try {
            for (int i = 0; i < count; i++) {
                Entry entry = entries[i];
                RenderSystem.getModelViewStack().set(entry.modelView);
                RenderSystem.setProjectionMatrix(entry.projection, entry.projectionType);
                try { entry.type.draw(entry.mesh); }
                finally { restoreNativeLayers(RenderSystem.getModelViewStack()); }
            }
        } finally {
            drawingNative = false;
            try { RenderSystem.setProjectionMatrix(savedProjection, savedProjectionType); }
            finally { RenderSystem.getModelViewStack().set(savedNativeModelView); }
        }
    }

    // RenderType's layering pop is not in a vanilla finally. Observe only successful
    // pushes/pops in our independent draw, so an exception (including a failed push)
    // cannot leave an extra stack frame or pop a frame owned by the caller.
    public static void layerPushed() { if (drawingNative) nativeLayerDepth++; }
    public static void layerPopped() { if (drawingNative) nativeLayerDepth--; }
    static void restoreNativeLayers(Matrix4fStack stack) {
        while (nativeLayerDepth > 0) {
            nativeLayerDepth--;
            stack.popMatrix();
        }
    }

    /** Also handles partially recorded scopes and meshes already closed by RenderType. */
    static void abort() {
        recording = false;
        drawingNative = false;
        nativeLayerDepth = 0;
        int retiredCount = count;
        count = 0;
        bytes = 0;
        Throwable failure = null;
        for (int i = 0; i < retiredCount; i++) {
            Entry entry = entries[i];
            MeshData retired = entry.mesh;
            entry.mesh = null;
            entry.type = null;
            entry.projection = null;
            entry.projectionType = null;
            try { retired.close(); } catch (RuntimeException | Error closeFailure) {
                if (failure == null) failure = closeFailure; else failure.addSuppressed(closeFailure);
            }
        }
        try { if (arena != null) arena.clear(); } catch (RuntimeException | Error closeFailure) {
            if (failure == null) failure = closeFailure; else failure.addSuppressed(closeFailure);
        }
        if (failure instanceof RuntimeException runtime) throw runtime;
        if (failure instanceof Error error) throw error;
    }

    static void dispose() {
        try { abort(); } finally {
            var retired = arena;
            arena = null;
            if (retired != null) retired.close();
        }
    }
}
//#else
//$$ public final class NativeMaskMeshReplay { private NativeMaskMeshReplay() {} }
//#endif
