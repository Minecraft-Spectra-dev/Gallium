package cn.spectra.gallium.platform.neoforge;

//#if NEOFORGE
//$$
//$$ import cn.spectra.gallium.Gallium;
//$$ import cn.spectra.gallium.compat.sodium.SodiumConfigCompat;
//$$ import cn.spectra.gallium.glowoutline.ItemEffectsManager;
//$$ import net.minecraft.client.Minecraft;
//$$ import net.neoforged.api.distmarker.Dist;
//$$ import net.neoforged.bus.api.IEventBus;
//$$ import net.neoforged.fml.common.Mod;
//$$ import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
//$$ import net.neoforged.neoforge.client.event.ClientTickEvent;
//$$ import net.neoforged.neoforge.client.event.RegisterClientReloadListenersEvent;
//$$ import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
//$$ import net.neoforged.neoforge.client.event.ScreenEvent;
//$$ import net.neoforged.neoforge.common.NeoForge;
//$$
//$$ @Mod(value = Gallium.MOD_ID, dist = Dist.CLIENT)
//$$ public final class NeoForgeEntrypoint {
//$$     public NeoForgeEntrypoint(IEventBus modBus) {
//$$         modBus.addListener(this::onClientSetup);
//$$         modBus.addListener(this::registerReloadListeners);
//$$         modBus.addListener(this::registerKeyMappings);
//$$         NeoForge.EVENT_BUS.addListener(this::onClientTick);
//$$         NeoForge.EVENT_BUS.addListener(this::onScreenInit);
//$$     }
//$$
//$$     private void onClientSetup(FMLClientSetupEvent event) {
//$$         // The mod constructor runs on a loading worker; GL initialization belongs on the main thread.
//$$         event.enqueueWork(Gallium::initializeClient);
//$$     }
//$$
//$$     private void registerReloadListeners(RegisterClientReloadListenersEvent event) {
//$$         event.registerReloadListener(new ItemEffectsManager());
//$$     }
//$$
//$$     private void registerKeyMappings(RegisterKeyMappingsEvent event) {
//$$         event.register(Gallium.createReloadKeyMapping());
//$$     }
//$$
//$$     private void onClientTick(ClientTickEvent.Post event) {
//$$         Gallium.onEndClientTick(Minecraft.getInstance());
//$$     }
//$$
//$$     private void onScreenInit(ScreenEvent.Init.Pre event) {
//$$         // Sodium 0.8 discovers the TOML entry itself; 0.6 needs its legacy pages added before init.
//$$         if (!SodiumConfigCompat.hasConfigApi()) SodiumConfigCompat.attachLegacyPages(event.getScreen());
//$$     }
//$$ }
//$$
//#else
public final class NeoForgeEntrypoint { private NeoForgeEntrypoint() {} }
//#endif
