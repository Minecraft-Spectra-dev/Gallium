package cn.spectra.gallium.glowoutline.capture;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class VertexPositionCopyTest {
    @Test void retainsPositionsAfterStagingStorageIsReused() {
        var source = ByteBuffer.allocate(112).order(ByteOrder.nativeOrder());
        source.position(4).limit(100);
        for (int i = 0; i < 3; i++) {
            source.putFloat(12 + i * 32, i + .25f);
            source.putFloat(16 + i * 32, i + .5f);
            source.putFloat(20 + i * 32, i + .75f);
        }
        var copy = new VertexPositionCopy();
        assertTrue(copy.append(source, 32, 3, 8));
        assertTrue(copy.append(source, 32, 3, 8));
        source.clear();
        for (int i = 0; i < source.capacity(); i++) source.put(i, (byte) 0);
        assertEquals(6, copy.count());
        for (int i = 0; i < 6; i++) {
            assertEquals(i % 3 + .25f, copy.buffer().getFloat(i * 12));
            assertEquals(i % 3 + .5f, copy.buffer().getFloat(i * 12 + 4));
            assertEquals(i % 3 + .75f, copy.buffer().getFloat(i * 12 + 8));
        }
        copy.reset();
        assertEquals(0, copy.count());
        assertTrue(copy.append(source, 12, 1, 0));
        assertEquals(0, copy.buffer().getFloat(0));
    }

    @Test void rejectsUnboundedOrInvalidMeshData() {
        var copy = new VertexPositionCopy();
        var source = ByteBuffer.allocate(65544).order(ByteOrder.nativeOrder());
        assertFalse(copy.append(source, 12, Integer.MAX_VALUE, 0));
        assertFalse(copy.append(source, 12, 5462, 0));
        assertFalse(copy.append(source, 12, 1, 4));
        assertTrue(copy.append(source, 12, 5461, 0));
        assertFalse(copy.append(source, 12, 1, 0));
        assertEquals(5461, copy.count());
        copy.reset();
        source.putFloat(0, Float.NaN);
        assertFalse(copy.append(source, 12, 1, 0));
        assertEquals(0, copy.count());
    }
}
