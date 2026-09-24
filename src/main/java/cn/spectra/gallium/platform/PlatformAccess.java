package cn.spectra.gallium.platform;

import java.nio.file.Path;
import java.util.Optional;

/** Loader services used by shared code; no game initialization or graphics work. */
public interface PlatformAccess {
    Path configDirectory();
    boolean isModLoaded(String modId);
    Optional<String> modVersion(String modId);
}
