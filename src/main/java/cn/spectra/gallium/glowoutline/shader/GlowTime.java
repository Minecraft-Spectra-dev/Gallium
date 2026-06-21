package cn.spectra.gallium.glowoutline.shader;

public final class GlowTime {

    private static double worldSeconds;
    private static final long GUI_EPOCH_NANOS = System.nanoTime();

    private GlowTime() {}

    public static void advanceWorld(float deltaTicks) {
        worldSeconds += deltaTicks / 20.0;
        // Keep the value bounded so double precision doesn't degrade over
        // ultra-long uptimes (only matters at geologic timescales).
        if (worldSeconds >= 7200.0) worldSeconds %= 3600.0;
    }

    public static float worldSecondsFloat() {
        return (float) (worldSeconds % 3600.0);
    }

    public static float guiSecondsFloat() {
        long elapsed = System.nanoTime() - GUI_EPOCH_NANOS;
        double s = elapsed / 1_000_000_000.0;
        return (float) (s % 3600.0);
    }
}
