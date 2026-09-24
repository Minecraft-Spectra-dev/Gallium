package cn.spectra.gallium.platform;

import java.nio.file.Path;
import java.util.Optional;

/** Compile-time platform selection keeps other loaders out of the production classpath. */
public final class PlatformServices {
    private static final PlatformAccess ACCESS = createAccess();

    private PlatformServices() {}

    private static PlatformAccess createAccess() {
        //#if FABRIC
        return new cn.spectra.gallium.platform.fabric.FabricPlatformAccess();
        //#elseif NEOFORGE
        //$$ return new cn.spectra.gallium.platform.neoforge.NeoForgePlatformAccess();
        //#else
        //$$ throw new IllegalStateException("No Gallium platform implementation for this target");
        //#endif
    }

    public static Path configDirectory() { return ACCESS.configDirectory(); }
    public static boolean isModLoaded(String modId) { return ACCESS.isModLoaded(modId); }
    public static Optional<String> modVersion(String modId) { return ACCESS.modVersion(modId); }
}
