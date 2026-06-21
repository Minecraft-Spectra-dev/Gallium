package cn.spectra.gallium.glowoutline.capture;

import java.util.ArrayList;
import java.util.Collections;
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
        if (activeCount < pool.size()) {
            return pool.get(activeCount++);
        }
        GuiGlowCapture c = new GuiGlowCapture();
        pool.add(c);
        activeCount++;
        return c;
    }

    public static List<GuiGlowCapture> getActive() {
        // Defensive copy: subList is a live view backed by pool — if a nested emitGlow
        // call appends to pool via acquire(), ConcurrentModificationException would fire
        // during iteration of the outer getActive(). Copy is bounded by
        // POOL_HIGH_WATER_MARK (256) so the allocation is negligible.
        return activeCount == 0 ? Collections.emptyList()
                : List.copyOf(pool.subList(0, activeCount));
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
