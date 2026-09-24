package cn.spectra.gallium.platform.neoforge;

//#if NEOFORGE
//$$
//$$ import cn.spectra.gallium.platform.PlatformAccess;
//$$ import net.neoforged.fml.ModList;
//$$ import net.neoforged.fml.loading.FMLPaths;
//$$ import java.nio.file.Path;
//$$ import java.util.Optional;
//$$
//$$ public final class NeoForgePlatformAccess implements PlatformAccess {
//$$     @Override
//$$     public Path configDirectory() { return FMLPaths.CONFIGDIR.get(); }
//$$
//$$     @Override
//$$     public boolean isModLoaded(String modId) {
//$$         ModList mods = ModList.get();
//$$         return mods != null && mods.isLoaded(modId);
//$$     }
//$$
//$$     @Override
//$$     public Optional<String> modVersion(String modId) {
//$$         ModList mods = ModList.get();
//$$         if (mods == null) return Optional.empty();
//$$         return mods.getModContainerById(modId)
//$$                 .map(mod -> mod.getModInfo().getVersion().toString());
//$$     }
//$$ }
//$$
//#else
public final class NeoForgePlatformAccess { private NeoForgePlatformAccess() {} }
//#endif
