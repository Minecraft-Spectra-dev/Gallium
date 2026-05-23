package cn.spectra.gallium.glowoutline.capture;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class GuiGlowCaptureManager {

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
        activeCount = 0;
    }
}
