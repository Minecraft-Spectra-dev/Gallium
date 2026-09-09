package cn.spectra.gallium.glowoutline.capture;

import java.util.ArrayList;
import java.util.List;

public final class GuiGlowCaptureManager {

    /**
     * Hard cap for the pooled capture entries — prevents the pool from being pinned at peak
     * frame size forever. Set higher than the world path's mark (32) because GUI items are far
     * more numerous and transient than glowing world entities (a packed inventory can submit
     * dozens of glowing slots in a single frame), so a larger pool avoids churning allocations.
     */
    private static final int POOL_HIGH_WATER_MARK = 256;

    private static final List<GuiGlowCapture> pool = new ArrayList<>();
    private static int activeCount = 0;

    private GuiGlowCaptureManager() {}

    public static GuiGlowCapture acquire() {
        GuiGlowCapture capture;
        if (activeCount < pool.size()) {
            capture = pool.get(activeCount++);
        } else {
            capture = new GuiGlowCapture();
            pool.add(capture);
            activeCount++;
        }
        return capture;
    }

    /** Number of captures in the active prefix of {@link #pool}. */
    public static int activeCount() {
        return activeCount;
    }

    /**
     * Returns one capture from the active prefix without allocating a snapshot list.
     * Callers that need snapshot iteration semantics must save {@link #activeCount()} into a
     * local limit before looping, so captures acquired by nested submissions are deferred.
     */
    public static GuiGlowCapture activeAt(int index) {
        if (index < 0 || index >= activeCount) {
            throw new IndexOutOfBoundsException(index);
        }
        return pool.get(index);
    }

    public static void clear() {
        // Drop strong references the captures held to GpuTextureView etc., so GC can reclaim them
        // even when the slot stays in the pool.
        for (int i = 0; i < activeCount; i++) {
            //#if MC>=1_21_06
            pool.get(i).reset();
            //#endif
        }
        // Shrink the pool when it has overgrown — keep up to the high-water mark of slots.
        if (pool.size() > POOL_HIGH_WATER_MARK) {
            pool.subList(POOL_HIGH_WATER_MARK, pool.size()).clear();
        }
        activeCount = 0;
    }
}
