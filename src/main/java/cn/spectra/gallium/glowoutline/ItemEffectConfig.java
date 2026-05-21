package cn.spectra.gallium.glowoutline;

import org.joml.Vector3f;

public record ItemEffectConfig(
        String shader,
        Vector3f innerColor,
        Vector3f outerColor,
        float intensity,
        float pulseSpeed,
        float waveSpeed
) {
    public static final String DEFAULT_SHADER = "glow_outline";

    public static final ItemEffectConfig DEFAULT = new ItemEffectConfig(
            DEFAULT_SHADER,
            new Vector3f(0.25f, 0.75f, 1.0f),
            new Vector3f(0.12f, 0.55f, 1.0f),
            1.0f,
            2.2f,
            0.75f
    );

    public ItemEffectConfig withIntensity(float newIntensity) {
        return new ItemEffectConfig(shader, innerColor, outerColor, newIntensity, pulseSpeed, waveSpeed);
    }
}
