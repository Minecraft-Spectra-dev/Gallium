package cn.spectra.gallium.glowoutline.shader;

/** Last byte read by a reflected scalar, vector, matrix, or array; excludes trailing layout padding. */
public final class UniformReadExtent {
    private UniformReadExtent() {}

    public static long end(long offset, int componentBytes, int columns, int rows, long count,
                           long arrayStride, long matrixStride, boolean rowMajor) {
        if (offset < 0 || (componentBytes != 4 && componentBytes != 8)
                || columns < 1 || columns > 4 || rows < 1 || rows > 4 || count < 1
                || arrayStride < 0 || matrixStride < 0 || columns > 1 && rows < 2) return -1;
        try {
            long element = (long) componentBytes * rows;
            if (columns > 1) {
                int major = rowMajor ? rows : columns, minor = rowMajor ? columns : rows;
                long last = (long) minor * componentBytes;
                if (matrixStride < last) return -1;
                element = Math.addExact(Math.multiplyExact(major - 1L, matrixStride), last);
            }
            if (count > 1 && arrayStride < element) return -1;
            return Math.addExact(offset, Math.addExact(Math.multiplyExact(count - 1, arrayStride), element));
        } catch (ArithmeticException overflow) {
            return -1;
        }
    }
}
