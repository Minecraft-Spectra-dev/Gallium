package cn.spectra.gallium.glowoutline.mixin;

/**
 * Stub — Sodium config injection on all legacy versions (MC &lt; 1.21.11) is now
 * handled through Fabric ScreenEvents + {@link cn.spectra.gallium.compat.sodium.GalliumSodiumLegacyPage}
 * in {@link cn.spectra.gallium.Gallium#onInitializeClient()}. This class is
 * stripped from the runtime mixin config at build time.
 * <p>
 * Formerly this class carried a {@code @Mixin(SodiumOptionsGUI.class)} on
 * 1.21.3–1.21.10. The ScreenEvents approach was adopted everywhere to avoid
 * Mixin target-class conflicts with Iris on 1.21.1 and to reduce maintenance
 * surface (one injection path instead of two).
 */
public final class SodiumOptionsGUIMixin {
    private SodiumOptionsGUIMixin() {}
}
