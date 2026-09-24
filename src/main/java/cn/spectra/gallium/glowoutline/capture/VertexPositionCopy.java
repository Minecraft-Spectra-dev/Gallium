package cn.spectra.gallium.glowoutline.capture;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** Small, reusable position-only copy for staging APIs that free CPU meshes before drawing. */
final class VertexPositionCopy {
    private static final int MAX_BYTES = 65536;
    private ByteBuffer bytes = ByteBuffer.allocate(1024).order(ByteOrder.nativeOrder());
    private int count;

    void reset() { count = 0; }
    int count() { return count; }
    ByteBuffer buffer() { return bytes.position(0).limit(count * 12); }

    boolean append(ByteBuffer source, int stride, int vertices, int offset) {
        if (source == null || vertices < 0 || stride < 12 || offset < 0 || offset > stride - 12
                || (long) vertices * stride > source.remaining()
                || ((long) count + vertices) * 12 > MAX_BYTES) return false;
        int required = (count + vertices) * 12;
        if (required > bytes.capacity()) {
            var grown = ByteBuffer.allocate(Math.min(MAX_BYTES, Math.max(required, bytes.capacity() * 2)))
                    .order(ByteOrder.nativeOrder());
            grown.put(0, bytes, 0, count * 12);
            bytes = grown;
        }
        bytes.limit(bytes.capacity());
        int src = source.position() + offset, dst = count * 12;
        for (int i = 0; i < vertices; i++, src += stride) {
            for (int component = 0; component < 12; component += 4) {
                float value = source.getFloat(src + component);
                if (!Float.isFinite(value)) return false;
                bytes.putFloat(dst, value); dst += 4;
            }
        }
        count += vertices;
        return true;
    }
}
