package cn.spectra.gallium.platform.fabric;

//#if FABRIC
import cn.spectra.gallium.Gallium;
import cn.spectra.gallium.compat.sodium.SodiumConfigCompat;
import cn.spectra.gallium.glowoutline.ItemEffectsManager;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
//#if MC>=1_26_00
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
//#else
//$$ import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
//#endif
//#if MC>=1_21_09
import net.fabricmc.fabric.api.resource.v1.ResourceLoader;
//#else
//$$ import net.fabricmc.fabric.api.resource.ResourceManagerHelper;
//$$ import net.fabricmc.fabric.api.resource.SimpleSynchronousResourceReloadListener;
//$$ import net.minecraft.resources.ResourceLocation;
//$$ import net.minecraft.server.packs.resources.ResourceManager;
//#endif
//#if MC<1_21_11
//$$ import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
//#endif
import net.minecraft.server.packs.PackType;

public final class FabricEntrypoint implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        Gallium.initializeClient();
        ItemEffectsManager reloader = new ItemEffectsManager();
        //#if MC>=1_26_00
        ResourceLoader.get(PackType.CLIENT_RESOURCES).registerReloadListener(ItemEffectsManager.RELOAD_ID, reloader);
        //#elseif MC>=1_21_09
        //$$ ResourceLoader.get(PackType.CLIENT_RESOURCES).registerReloader(ItemEffectsManager.RELOAD_ID, reloader);
        //#else
        //$$ ResourceManagerHelper.get(PackType.CLIENT_RESOURCES).registerReloadListener(new SimpleSynchronousResourceReloadListener() {
        //$$     @Override
        //$$     public ResourceLocation getFabricId() { return ItemEffectsManager.RELOAD_ID; }
        //$$     @Override
        //$$     public void onResourceManagerReload(ResourceManager manager) { reloader.onResourceManagerReload(manager); }
        //$$ });
        //#endif
        //#if MC>=1_26_00
        KeyMappingHelper.registerKeyMapping(Gallium.createReloadKeyMapping());
        //#else
        //$$ KeyBindingHelper.registerKeyBinding(Gallium.createReloadKeyMapping());
        //#endif
        ClientTickEvents.END_CLIENT_TICK.register(Gallium::onEndClientTick);
        //#if MC<1_21_11
        //$$ if (!SodiumConfigCompat.registerConfigApiEntryPoint()) {
        //$$     ScreenEvents.BEFORE_INIT.register((client, screen, width, height) -> SodiumConfigCompat.attachLegacyPages(screen));
        //$$ }
        //#endif
    }
}
//#else
//$$ public final class FabricEntrypoint { private FabricEntrypoint() {} }
//#endif
