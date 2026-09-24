package cn.spectra.gallium.glowoutline.capture;

//#if MC==1_21_08 || MC==1_26_01
import cn.spectra.gallium.glowoutline.shader.GlowResources;
import cn.spectra.gallium.glowoutline.shader.VertexPositionProof;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.buffers.Std140Builder;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.opengl.GlRenderPipeline;
import com.mojang.blaze3d.opengl.GlProgram;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.renderer.MappableRingBuffer;
import org.lwjgl.opengl.*;
import org.lwjgl.system.MemoryStack;
import java.util.IdentityHashMap;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector4f;
import org.joml.Vector4fc;
import org.joml.Vector3f;
import org.joml.Vector3fc;

/** Disjoint vertex ranges remove overwrites of the immediate buffer between native mask draws. */
public final class NativeVertexUploadBatch implements AutoCloseable {
    private static final int CAPACITY = 4 * 1024 * 1024;
    private static final IdentityHashMap<GlProgram, Boolean> programs = new IdentityHashMap<>();
    private static MappableRingBuffer buffers;
    private static NativeVertexUploadBatch current;
    private static final ProjectionSlot[] projections = new ProjectionSlot[16];
    private static long sequence;
    private final long serial = ++sequence;
    private final NativeVertexUploadBatch parent;
    private final OpenGlMaskOrdering.Stamp ordering;
    private GpuBuffer buffer;
    private int cursor, baseVertex;
    private MeshData pending;
    private boolean closed;
    private final TransformSlot[] transforms = new TransformSlot[32];
    private int transformCount;

    static final class TransformSlot {
        final Matrix4f modelView = new Matrix4f(), texture = new Matrix4f();
        final Vector4f color = new Vector4f();
        final Vector3f offset = new Vector3f();
        GpuBufferSlice slice;
        private int lineWidthBits;

        void set(Matrix4fc modelView, Vector4fc color, Vector3fc offset, Matrix4fc texture, float lineWidth) {
            this.modelView.set(modelView); this.color.set(color);
            this.offset.set(offset); this.texture.set(texture);
            lineWidthBits = Float.floatToRawIntBits(lineWidth);
        }

        boolean matches(Matrix4fc modelView, Vector4fc color, Vector3fc offset, Matrix4fc texture, float lineWidth) {
            return this.modelView.equals(modelView) && this.color.equals(color)
                    && this.offset.equals(offset) && this.texture.equals(texture)
                    && lineWidthBits == Float.floatToRawIntBits(lineWidth);
        }
    }

    private static final class ProjectionSlot {
        final Matrix4f matrix = new Matrix4f();
        final GpuBuffer buffer = RenderSystem.getDevice().createBuffer(
                () -> "Glow shared projection", GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_COPY_DST, 64);
        final GpuBufferSlice slice = buffer.slice();
        long lastUse;
        boolean valid;
    }

    static { GlowResources.register(() -> {
        if (buffers != null) buffers.close();
        buffers = null; programs.clear(); current = null;
        for (int i = 0; i < projections.length; i++) {
            var slot = projections[i];
            if (slot != null) { ProjectionMatrixTracker.remove(slot.slice); slot.buffer.close(); }
            projections[i] = null;
        }
    }); }

    public NativeVertexUploadBatch() {
        parent = current; current = this; ordering = OpenGlMaskOrdering.observe();
    }

    /** Non-consuming check before a caller replaces an entire capture's original geometry. */
    public static Preflight preflight() {
        var batch = current;
        return batch != null && batch.available() ? new Preflight(batch) : null;
    }

    private boolean available() {
        return current == this && parent == null && !closed && pending == null
                && ordering != null && ordering.current();
    }

    public static final class Preflight {
        private final NativeVertexUploadBatch owner;
        private final int initialCursor;
        private final VertexUploadBudget budget;
        private boolean supported = true;

        private Preflight(NativeVertexUploadBatch owner) {
            this.owner = owner;
            initialCursor = owner.cursor;
            budget = new VertexUploadBudget(initialCursor, CAPACITY);
        }

        public boolean include(RenderPipeline pipeline, VertexFormat format, int vertices) {
            if (!valid() || pipeline == null || format == null || pipeline.getVertexFormat() != format
                    || !supportsPipeline(pipeline) || !budget.append(format.getVertexSize(), vertices)) {
                supported = false;
                return false;
            }
            return true;
        }

        /** Any intervening upload or scope transition invalidates this non-consuming plan. */
        public boolean valid() {
            return supported && budget.valid() && owner.available() && owner.cursor == initialCursor;
        }
    }

    private static boolean supportsPipeline(RenderPipeline pipeline) {
        if (!(RenderSystem.getDevice().precompilePipeline(pipeline) instanceof GlRenderPipeline compiled)
                || !compiled.isValid()) return false;
        var program = compiled.program();
        if (programs.size() >= 128 && !programs.containsKey(program)) programs.clear();
        return programs.computeIfAbsent(program, value -> supportsBaseVertex(value.getProgramId()));
    }

    public static GpuBuffer upload(RenderPipeline pipeline, MeshData mesh) {
        var batch = current;
        if (batch == null || batch.parent != null || batch.pending != null
                || batch.ordering == null || !batch.ordering.current()
                || !ModernMaskBounds.observing(mesh)) return null;
        if (!supportsPipeline(pipeline)) return null;
        var bytes = mesh.vertexBuffer();
        int stride = pipeline.getVertexFormat().getVertexSize();
        if (stride <= 0 || mesh.drawState().format() != pipeline.getVertexFormat()
                || (long) mesh.drawState().vertexCount() * stride != bytes.remaining()) return null;
        int offset = ((batch.cursor + stride - 1) / stride) * stride;
        if ((long) offset + bytes.remaining() > CAPACITY) return null;
        if (buffers == null) buffers = new MappableRingBuffer(() -> "Glow native vertices",
                GpuBuffer.USAGE_VERTEX | GpuBuffer.USAGE_MAP_WRITE, CAPACITY);
        if (batch.buffer == null) batch.buffer = buffers.currentBuffer();
        try (var mapped = RenderSystem.getDevice().createCommandEncoder().mapBuffer(
                batch.buffer.slice(offset, bytes.remaining()), false, true)) {
            mapped.data().put(0, bytes, bytes.position(), bytes.remaining());
        }
        batch.cursor = offset + bytes.remaining();
        batch.baseVertex = offset / stride;
        batch.pending = mesh;
        return batch.buffer;
    }

    public static int consumeBaseVertex(MeshData mesh, int original) {
        var batch = current;
        if (batch == null || batch.pending != mesh) return original;
        batch.pending = null;
        return Math.addExact(original, batch.baseVertex);
    }

    /** Native DynamicUniformStorage keeps returned ranges immutable until endFrame. */
    public static GpuBufferSlice transform(Matrix4fc modelView, Vector4fc color, Vector3fc offset, Matrix4fc texture) {
        return transform(modelView, color, offset, texture, 0.0f);
    }

    public static GpuBufferSlice transform(Matrix4fc modelView, Vector4fc color, Vector3fc offset,
                                          Matrix4fc texture, float lineWidth) {
        var batch = current;
        if (batch == null || batch.closed || batch.ordering == null || !batch.ordering.current()
                || ModernMaskBounds.currentState() == null) return null;
        for (int i = 0; i < batch.transformCount; i++) {
            var slot = batch.transforms[i];
            if (slot.matches(modelView, color, offset, texture, lineWidth) && !slot.slice.buffer().isClosed()) return slot.slice;
        }
        return null;
    }

    public static void rememberTransform(Matrix4fc modelView, Vector4fc color, Vector3fc offset,
                                         Matrix4fc texture, GpuBufferSlice slice) {
        rememberTransform(modelView, color, offset, texture, 0.0f, slice);
    }

    public static void rememberTransform(Matrix4fc modelView, Vector4fc color, Vector3fc offset,
                                         Matrix4fc texture, float lineWidth, GpuBufferSlice slice) {
        var batch = current;
        if (batch == null || batch.closed || batch.ordering == null || !batch.ordering.current()
                || ModernMaskBounds.currentState() == null || batch.transformCount == batch.transforms.length) return;
        var slot = new TransformSlot();
        slot.set(modelView, color, offset, texture, lineWidth);
        slot.slice = slice;
        batch.transforms[batch.transformCount++] = slot;
    }

    /** Equal projections share immutable bytes; a slot used this frame is never overwritten. */
    public static GpuBufferSlice projection(CommandEncoder encoder, Matrix4f matrix) {
        var batch = current;
        if (batch == null || batch.ordering == null || !batch.ordering.current()) return null;
        ProjectionSlot candidate = null;
        for (int i = 0; i < projections.length; i++) {
            var slot = projections[i];
            if (slot == null) {
                if (candidate == null) projections[i] = candidate = new ProjectionSlot();
                continue;
            }
            if (slot.valid && slot.matrix.equals(matrix)) { slot.lastUse = batch.serial; return slot.slice; }
            if (slot.lastUse != batch.serial && (candidate == null || slot.lastUse < candidate.lastUse)) candidate = slot;
        }
        if (candidate == null) return null;
        candidate.valid = false;
        ProjectionMatrixTracker.forget(candidate.slice);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            encoder.writeToBuffer(candidate.slice, Std140Builder.onStack(stack, 64).putMat4f(matrix).get());
        }
        candidate.matrix.set(matrix); candidate.lastUse = batch.serial; candidate.valid = true;
        ProjectionMatrixTracker.remember(candidate.slice, matrix);
        return candidate.slice;
    }

    private static boolean supportsBaseVertex(int program) {
        if (GL20.glGetProgrami(program, GL20.GL_ATTACHED_SHADERS) != 2) return false;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            var count = stack.mallocInt(1); var shaders = stack.mallocInt(2);
            GL20.glGetAttachedShaders(program, count, shaders);
            if (count.get(0) != 2) return false;
            for (int i = 0; i < 2; i++) {
                int shader = shaders.get(i);
                if (GL20.glGetShaderi(shader, GL20.GL_SHADER_TYPE) == GL20.GL_VERTEX_SHADER) {
                    String source = GL20.glGetShaderSource(shader);
                    return VertexPositionProof.supportsBaseVertex(source);
                }
            }
        }
        return false;
    }

    @Override public void close() {
        if (closed) return;
        closed = true;
        try { if (buffer != null) buffers.rotate(); }
        finally { pending = null; if (current == this) current = parent; }
    }
}
//#else
//$$ public final class NativeVertexUploadBatch { private NativeVertexUploadBatch() {} }
//#endif
