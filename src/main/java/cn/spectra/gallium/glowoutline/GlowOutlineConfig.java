package cn.spectra.gallium.glowoutline;

public class GlowOutlineConfig {
    private static boolean enabled = true;
    private static float intensity = 1.0f;

    public static boolean isEnabled() { return enabled; }
    public static void setEnabled(boolean v) { enabled = v; }

    public static float getIntensity() { return intensity; }
    public static void setIntensity(float v) { intensity = v; }
}
