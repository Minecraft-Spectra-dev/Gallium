package cn.spectra.gallium.glowoutline.capture;

//#if MC==1_21_11 || MC==1_26_01
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.MeshData;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.nio.ByteBuffer;
import org.junit.jupiter.api.Test;
import org.joml.Matrix4f;
import org.joml.Matrix4fStack;
import org.lwjgl.system.MemoryUtil;
import static org.junit.jupiter.api.Assertions.*;

class NativeMaskMeshReplayTest {
    @Test
    void nativeLayeringFailureUnwindsOnlySuccessfulOwnedPushes() throws Exception {
        var stack = new Matrix4fStack(4);
        stack.translate(1, 2, 3);
        var caller = new Matrix4f(stack);
        stack.pushMatrix(); stack.translate(4, 5, 6);
        var capture = new Matrix4f(stack);
        try {
            field("drawingNative").setBoolean(null, true);
            stack.pushMatrix(); NativeMaskMeshReplay.layerPushed();
            stack.scale(0.99f); // Simulated layering modifier; draw then throws before pop.
            NativeMaskMeshReplay.restoreNativeLayers(stack);
            assertEquals(capture, new Matrix4f(stack));
            stack.pushMatrix(); NativeMaskMeshReplay.layerPushed();
            stack.pushMatrix(); NativeMaskMeshReplay.layerPushed();
            assertThrows(IllegalStateException.class, stack::pushMatrix); // No success hook.
            NativeMaskMeshReplay.restoreNativeLayers(stack);
            assertEquals(capture, new Matrix4f(stack));
            assertEquals(0, field("nativeLayerDepth").getInt(null));
            // The normal return path must not be popped a second time.
            stack.pushMatrix(); NativeMaskMeshReplay.layerPushed();
            stack.popMatrix(); NativeMaskMeshReplay.layerPopped();
            NativeMaskMeshReplay.restoreNativeLayers(stack);
            assertEquals(capture, new Matrix4f(stack));
            stack.popMatrix(); assertEquals(caller, new Matrix4f(stack));
            assertThrows(IllegalStateException.class, stack::popMatrix);
            field("drawingNative").setBoolean(null, false);
            NativeMaskMeshReplay.layerPushed(); NativeMaskMeshReplay.layerPopped();
            assertEquals(0, field("nativeLayerDepth").getInt(null));
        } finally {
            field("drawingNative").setBoolean(null, false);
            field("nativeLayerDepth").setInt(null, 0);
        }
    }

    private static Field field(String name) throws Exception {
        var field = NativeMaskMeshReplay.class.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    @Test
    void copiesOwnExactVertexAndSortedIndexBytesAfterTheOriginalIsClosedAndReused() throws Exception {
        try (var original = new ByteBufferBuilder(64, 1024)) {
            var copy = NativeMaskMeshReplay.class.getDeclaredMethod("copy", ByteBuffer.class);
            copy.setAccessible(true);
            var owner = new ByteBufferBuilder(64, NativeMaskMeshReplay.MAX_BYTES);
            field("arena").set(null, owner);
            field("bytes").setInt(null, 0);
            try {
                long vertexPointer = original.reserve(12);
                for (int i = 0; i < 12; i++) MemoryUtil.memPutByte(vertexPointer + i, (byte) (i * 7));
                var originalVertices = original.build();
                long indexPointer = original.reserve(6);
                for (int i = 0; i < 6; i++) MemoryUtil.memPutByte(indexPointer + i, (byte) (5 - i));
                var originalIndices = original.build();
                var vertices = (ByteBufferBuilder.Result) copy.invoke(null, originalVertices.byteBuffer());
                var indices = (ByteBufferBuilder.Result) copy.invoke(null, originalIndices.byteBuffer());
                var mesh = new NativeMaskMeshReplay.OwnedMesh(vertices, indices, null);
                originalVertices.close(); originalIndices.close(); original.clear();
                MemoryUtil.memSet(original.reserve(64), 0xff, 64);
                assertEquals(18, field("bytes").getInt(null));
                for (int i = 0; i < 12; i++) assertEquals((byte) (i * 7), mesh.vertexBuffer().get(i));
                for (int i = 0; i < 6; i++) assertEquals((byte) (5 - i), mesh.indexBuffer().get(i));
                mesh.close(); mesh.close();
                assertNull(mesh.indexBuffer());
                assertNull(copy.invoke(null, new Object[] { null }));
                field("bytes").setInt(null, NativeMaskMeshReplay.MAX_BYTES);
                var failure = assertThrows(InvocationTargetException.class,
                        () -> copy.invoke(null, MemoryUtil.memByteBuffer(vertexPointer, 1)));
                var capacity = assertInstanceOf(NativeMaskMeshReplay.CapacityExceeded.class, failure.getCause());
                assertEquals(NativeMaskMeshReplay.CapacityExceeded.Limit.BYTES, capacity.limit());
            } finally { NativeMaskMeshReplay.dispose(); }
        }
    }

    static final class FailingMesh extends MeshData {
        int closes;
        RuntimeException failure;
        FailingMesh() { super(null, null); }
        @Override public void close() { closes++; if (failure != null) throw failure; }
    }

    @Test
    void abortClearsScopeAndEveryOwnedMeshEvenIfTheFirstCloseFails() throws Exception {
        var entryClass = Class.forName(NativeMaskMeshReplay.class.getName() + "$Entry");
        var ctor = entryClass.getDeclaredConstructor(); ctor.setAccessible(true);
        var meshField = entryClass.getDeclaredField("mesh"); meshField.setAccessible(true);
        var entries = (Object[]) field("entries").get(null);
        Object saved0 = entries[0], saved1 = entries[1];
        var first = new FailingMesh(); var second = new FailingMesh();
        first.failure = new IllegalStateException("first close failed");
        try {
            entries[0] = ctor.newInstance(); entries[1] = ctor.newInstance();
            meshField.set(entries[0], first); meshField.set(entries[1], second);
            field("count").setInt(null, 2); field("recording").setBoolean(null, true);
            assertSame(first.failure, assertThrows(IllegalStateException.class, NativeMaskMeshReplay::abort));
            assertFalse(field("recording").getBoolean(null));
            assertEquals(0, field("count").getInt(null));
            assertNull(meshField.get(entries[0])); assertNull(meshField.get(entries[1]));
            NativeMaskMeshReplay.abort();
            assertEquals(1, first.closes); assertEquals(1, second.closes);
        } finally { entries[0] = saved0; entries[1] = saved1; NativeMaskMeshReplay.dispose(); }
    }
}
//#endif
