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
        return activeCount == 0 ? Collections.emptyList() : pool.subList(0, activeCount);
    }

    public static void clear() {
        // Drop strong references the captures held to GpuTextureView etc., so GC can reclaim them
        // even when the slot stays in the pool.
        for (int i = 0; i < activeCount; i++) {
            pool.get(i).reset();
        }
        // Shrink the pool when it has overgrown — keep up to the high-water mark of slots.
        if (pool.size() > POOL_HIGH_WATER_MARK) {
            pool.subList(POOL_HIGH_WATER_MARK, pool.size()).clear();
        }
        activeCount = 0;
    }
}
