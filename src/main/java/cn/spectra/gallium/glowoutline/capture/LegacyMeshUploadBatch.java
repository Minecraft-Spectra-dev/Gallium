package cn.spectra.gallium.glowoutline.capture;

//#if MC<1_21_05
//$$ import cn.spectra.gallium.glowoutline.IrisCompat;
//$$ import cn.spectra.gallium.glowoutline.shader.GlowResources;
//$$ import com.mojang.blaze3d.systems.RenderSystem;
//$$ import com.mojang.blaze3d.vertex.*;
//$$ import java.util.ArrayList;
//$$ import java.util.List;
//$$ import org.lwjgl.opengl.GL32;
//$$ import org.lwjgl.system.MemoryUtil;
//$$
//$$ /** Uploads independent captured meshes together; native draws and their order stay separate. */
//$$ public final class LegacyMeshUploadBatch implements AutoCloseable {
//$$     private static final int MAX_BYTES = 16 * 1024 * 1024, MAX_GROUPS = 16;
//$$     private static final List<Group> groups = new ArrayList<>();
//$$     static { GlowResources.register(LegacyMeshUploadBatch::dispose); }
//$$     private final List<CaptureSites.DelayingMultiBufferSource> sources = new ArrayList<>();
//$$     private int bytes;
//$$
//$$     private static final class Group {
//$$         final VertexFormat format;
//$$         final VertexFormat.Mode mode;
//$$         final ByteBufferBuilder arena = new ByteBufferBuilder(65536);
//$$         final RangedBuffer buffer = new RangedBuffer();
//$$         int vertices;
//$$         boolean uploaded;
//$$         Group(VertexFormat format, VertexFormat.Mode mode) { this.format = format; this.mode = mode; }
//$$     }
//$$
//$$     public static final class Range {
//$$         private final Group group;
//$$         private final int baseVertex, indexCount;
//$$         private Range(Group group, int baseVertex, int indexCount) {
//$$             this.group = group; this.baseVertex = baseVertex; this.indexCount = indexCount;
//$$         }
//$$     }
//$$
//$$     private static final class RangedBuffer extends VertexBuffer {
//$$         Range range;
//$$         RangedBuffer() {
//#if MC>=1_21_02
//$$             super(com.mojang.blaze3d.buffers.BufferUsage.DYNAMIC_WRITE);
//#else
//$$             super(VertexBuffer.Usage.DYNAMIC);
//#endif
//$$         }
//$$         @Override public void draw() {
//$$             if (range == null) { super.draw(); return; }
//$$             // Vanilla's sequential index buffer can grow between this upload and draw.
//$$             // Query the same object's current index type, as VertexBuffer itself does.
//$$             var indices = RenderSystem.getSequentialBuffer(range.group.mode);
//$$             GL32.glDrawElementsBaseVertex(range.group.mode.asGLMode, range.indexCount,
//$$                     indices.type().asGLType, 0L, range.baseVertex);
//$$         }
//$$     }
//$$
//$$     public static LegacyMeshUploadBatch prepare(List<GlowCaptureState> states) {
//$$         var batch = new LegacyMeshUploadBatch();
//$$         if (IrisCompat.isShaderActive() || IrisCompat.isActiveSrRuntime()) return batch;
//$$         for (Group group : groups) { group.vertices = 0; group.uploaded = false; }
//$$         try {
//$$             for (var state : states) {
//$$                 var source = state.customBufferSource;
//$$                 if (source == null) continue;
//$$                 batch.sources.add(source);
//$$                 source.prepareMeshes(batch);
//$$             }
//$$             BufferUploader.invalidate();
//$$             try {
//$$                 for (Group group : groups) {
//$$                     if (group.vertices == 0) continue;
//$$                     var draw = new MeshData.DrawState(group.format, group.vertices,
//$$                             group.mode.indexCount(group.vertices), group.mode, VertexFormat.IndexType.least(group.vertices));
//$$                     try (var mesh = new MeshData(group.arena.build(), draw)) {
//$$                         group.buffer.bind(); group.buffer.upload(mesh); group.uploaded = true;
//$$                     }
//$$                 }
//$$             } finally { VertexBuffer.unbind(); }
//$$             return batch;
//$$         } catch (RuntimeException | Error failure) {
//$$             try { batch.close(); } catch (RuntimeException | Error cleanup) { failure.addSuppressed(cleanup); }
//$$             throw failure;
//$$         }
//$$     }
//$$
//$$     Range add(MeshData mesh) {
//$$         var draw = mesh.drawState();
//$$         var vertices = mesh.vertexBuffer();
//$$         if (mesh.indexBuffer() != null || (draw.mode() != VertexFormat.Mode.QUADS && draw.mode() != VertexFormat.Mode.TRIANGLES)
//$$                 || draw.vertexCount() % draw.mode().primitiveLength != 0
//$$                 || (long) bytes + vertices.remaining() > MAX_BYTES) return null;
//$$         Group group = null;
//$$         for (var candidate : groups) if (candidate.format == draw.format() && candidate.mode == draw.mode()) { group = candidate; break; }
//$$         if (group == null) {
//$$             if (groups.size() >= MAX_GROUPS) return null;
//$$             groups.add(group = new Group(draw.format(), draw.mode()));
//$$         }
//$$         var range = new Range(group, group.vertices, draw.indexCount());
//$$         MemoryUtil.memCopy(MemoryUtil.memAddress(vertices), group.arena.reserve(vertices.remaining()), vertices.remaining());
//$$         group.vertices += draw.vertexCount(); bytes += vertices.remaining();
//$$         return range;
//$$     }
//$$
//$$     static void draw(Range range, MeshData original) {
//$$         if (range == null || !range.group.uploaded || !MaskBoundsTracker.canUseBaseVertex()) {
//$$             BufferUploader.drawWithShader(original); return;
//$$         }
//$$         BufferUploader.invalidate();
//$$         var buffer = range.group.buffer;
//$$         try {
//$$             buffer.bind(); buffer.range = range;
//$$             buffer.drawWithShader(RenderSystem.getModelViewMatrix(), RenderSystem.getProjectionMatrix(), RenderSystem.getShader());
//$$         } finally { buffer.range = null; VertexBuffer.unbind(); }
//$$     }
//$$
//$$     @Override public void close() {
//$$         Throwable failure = null;
//$$         for (var source : sources) {
//$$             try { source.clearPreparedMeshes(); }
//$$             catch (RuntimeException | Error cleanup) { failure = collect(failure, cleanup); }
//$$         }
//$$         sources.clear();
//$$         for (var group : groups) {
//$$             group.uploaded = false; group.vertices = 0;
//$$             try { group.arena.clear(); }
//$$             catch (RuntimeException | Error cleanup) { failure = collect(failure, cleanup); }
//$$         }
//$$         rethrow(failure);
//$$     }
//$$
//$$     private static void dispose() {
//$$         Throwable failure = null;
//$$         for (var group : groups) {
//$$             try { group.buffer.close(); }
//$$             catch (RuntimeException | Error cleanup) { failure = collect(failure, cleanup); }
//$$             try { group.arena.close(); }
//$$             catch (RuntimeException | Error cleanup) { failure = collect(failure, cleanup); }
//$$         }
//$$         groups.clear(); rethrow(failure);
//$$     }
//$$     private static Throwable collect(Throwable first, Throwable next) {
//$$         if (first == null) return next;
//$$         first.addSuppressed(next); return first;
//$$     }
//$$     private static void rethrow(Throwable failure) {
//$$         if (failure instanceof RuntimeException runtime) throw runtime;
//$$         if (failure instanceof Error error) throw error;
//$$     }
//$$ }
//#else
public final class LegacyMeshUploadBatch { private LegacyMeshUploadBatch() {} }
//#endif
