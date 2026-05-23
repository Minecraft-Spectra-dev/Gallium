package cn.spectra.gallium.glowoutline.capture;

import java.util.ArrayList;
import java.util.List;

public final class GuiGlowCaptureManager {

    private static final List<GuiGlowCapture> pool = new ArrayList<>();
    private static final List<GuiGlowCapture> active = new ArrayList<>();

    private GuiGlowCaptureManager() {}

    public static GuiGlowCapture acquire() {
        for (GuiGlowCapture c : pool) {
            if (!active.contains(c)) {
                active.add(c);
                return c;
            }
        }
        GuiGlowCapture c = new GuiGlowCapture();
        pool.add(c);
        active.add(c);
        return c;
    }

    public static List<GuiGlowCapture> getActive() {
        return active;
    }

    public static void clear() {
        active.clear();
    }
}
