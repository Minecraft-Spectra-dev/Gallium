package cn.spectra.gallium.platform.fabric;

//#if FABRIC
import cn.spectra.gallium.platform.PlatformAccess;
import net.fabricmc.loader.api.FabricLoader;
import java.nio.file.Path;
import java.util.Optional;

public final class FabricPlatformAccess implements PlatformAccess {
    @Override
    public Path configDirectory() { return FabricLoader.getInstance().getConfigDir(); }

    @Override
    public boolean isModLoaded(String modId) { return FabricLoader.getInstance().isModLoaded(modId); }

    @Override
    public Optional<String> modVersion(String modId) {
        return FabricLoader.getInstance().getModContainer(modId)
                .map(mod -> mod.getMetadata().getVersion().getFriendlyString());
    }
}
//#else
//$$ public final class FabricPlatformAccess { private FabricPlatformAccess() {} }
//#endif
