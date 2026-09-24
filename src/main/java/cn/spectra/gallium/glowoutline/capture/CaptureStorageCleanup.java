package cn.spectra.gallium.glowoutline.capture;

/** Tracks the exact storage last cleared; a failed or reentrant clear never consumes dirty work. */
final class CaptureStorageCleanup {
    private Object cleared;
    private long writes;

    void dirty() { writes++; cleared = null; }
    void cleared(Object storage) { cleared = storage; }
    boolean isClear(Object storage) { return storage != null && storage == cleared; }

    void clear(Object storage, Runnable action) {
        if (storage == null || isClear(storage)) return;
        long before = writes;
        cleared = null;
        action.run();
        if (writes == before) cleared = storage;
    }

    void forget() { dirty(); }
}
