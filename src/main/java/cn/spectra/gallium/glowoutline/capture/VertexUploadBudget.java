package cn.spectra.gallium.glowoutline.capture;

/** Computes aligned vertex ranges without consuming a live GPU upload cursor. */
final class VertexUploadBudget {
    private final int capacity;
    private long end;
    private boolean valid;

    VertexUploadBudget(int start, int capacity) {
        this.capacity = capacity;
        end = start;
        valid = start >= 0 && capacity >= start;
    }

    boolean append(int stride, int vertices) {
        if (!valid || stride <= 0 || vertices <= 0) return valid = false;
        long offset = ((end + stride - 1) / stride) * stride;
        long next = offset + (long) stride * vertices;
        if (next > capacity) return valid = false;
        end = next;
        return true;
    }

    boolean valid() { return valid; }
}
