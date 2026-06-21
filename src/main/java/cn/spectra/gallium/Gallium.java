package cn.spectra.gallium;

import cn.spectra.gallium.config.GalliumConfigIO;
import cn.spectra.gallium.glowoutline.ItemEffectsManager;
import cn.spectra.gallium.glowoutline.shader.GlowPipeline;
import cn.spectra.gallium.glowoutline.shader.GlowResources;
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
//#endif
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
//#if MC>=1_21_09
import net.minecraft.resources.Identifier;
//#else
//$$ import net.minecraft.resources.ResourceLocation;
//#endif
import net.minecraft.server.packs.PackType;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Gallium implements ClientModInitializer {
	public static final String MOD_ID = "gallium";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	// volatile so other threads (e.g. mod menu integrations that probe key bindings) observe
	// the post-init reference rather than a stale null. Written once inside onInitializeClient.
	public static volatile KeyMapping RELOAD_RESOURCE_PACK_KEY;

	@Override
	public void onInitializeClient() {
		GalliumConfigIO.load();
		GlowPipeline.init();
		GlowResources.eagerInit();
		// Fabric API renamed registerReloader -> registerReloadListener for MC 26.1+ support.
		//#if MC>=1_26_00
		ResourceLoader.get(PackType.CLIENT_RESOURCES).registerReloadListener(ItemEffectsManager.RELOAD_ID, new ItemEffectsManager());
		//#elseif MC>=1_21_09
		//$$ ResourceLoader.get(PackType.CLIENT_RESOURCES).registerReloader(ItemEffectsManager.RELOAD_ID, new ItemEffectsManager());
		//#else
		//$$ // 1.21.6–1.21.8 fabric-api predates the ResourceLoader facade; register the
		//$$ // listener through ResourceManagerHelper. ItemEffectsManager implements
		//$$ // SimpleSynchronousResourceReloadListener (carries its own getFabricId()).
		//$$ ResourceManagerHelper.get(PackType.CLIENT_RESOURCES).registerReloadListener(new ItemEffectsManager());
		//#endif

		//#if MC>=1_21_09
		KeyMapping.Category category = KeyMapping.Category.register(Identifier.fromNamespaceAndPath(MOD_ID, "main"));
		//#else
		//$$ // 1.21.8 KeyMapping takes a plain String category. Fabric's KeyBindingHelper
		//$$ // auto-registers unknown category strings into the controls-screen sort order, so we
		//$$ // pass the mod's own category (translated by "key.category.gallium.main" in lang/) to
		//$$ // match the dedicated category the >=1.21.9 path registers, rather than landing in Misc.
		//$$ String category = "key.category.gallium.main";
		//#endif

		//#if MC>=1_26_00
		RELOAD_RESOURCE_PACK_KEY = KeyMappingHelper.registerKeyMapping(new KeyMapping(
		//#else
		//$$ RELOAD_RESOURCE_PACK_KEY = KeyBindingHelper.registerKeyBinding(new KeyMapping(
		//#endif
			"key.gallium.reload_resource_pack",
			GLFW.GLFW_KEY_UNKNOWN,
			category
		));

		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			KeyMapping key = RELOAD_RESOURCE_PACK_KEY;
			if (key == null) return;
			while (key.consumeClick()) {
				reloadResourcePack(client);
			}
		});

		//#if MC<1_21_11
		// --- Sodium config: 0.8 path (ConfigEntryPoint, 1.21.1 only) ---
		// On 1.21.1 the compile classpath has Sodium 0.8 but runtime may be 0.6;
		// on 1.21.3–1.21.10 only Sodium 0.6/0.7 is present. The reflective 0.8
		// attempt is harmless on 1.21.3+ (ClassNotFoundException → fallback).
		boolean sodium06Fallback = true;
		try {
			Class<?> cfgMgr = Class.forName(
					"net.caffeinemc.mods.sodium.client.config.ConfigManager");
			java.lang.reflect.Method register = cfgMgr.getMethod(
					"registerConfigEntryPoint", String.class, String.class);
			register.invoke(null,
					"cn.spectra.gallium.compat.sodium.GalliumSodiumConfig",
					"gallium");
			LOGGER.debug("Gallium registered Sodium 0.8 config via reflective ConfigManager call");
			sodium06Fallback = false;
		} catch (ClassNotFoundException ignored) {
			LOGGER.debug("Sodium 0.8 ConfigManager not found, using legacy ScreenEvents path");
		} catch (ReflectiveOperationException e) {
			LOGGER.warn("Gallium Sodium 0.8 config registration failed, falling back to legacy path", e);
		}

		// --- Sodium config: legacy path (ScreenEvents + GalliumSodiumLegacyPage) ---
		// Used on all versions without the Sodium 0.8 public config API (1.21.1–1.21.10).
		// Replaces the former SodiumOptionsGUIMixin; avoids Mixin target-class conflicts
		// with Iris on 1.21.1 and keeps injection logic in one place.
		if (sodium06Fallback) {
			net.fabricmc.fabric.api.client.screen.v1.ScreenEvents.BEFORE_INIT.register(
					(client, screen, scaledWidth, scaledHeight) -> {
				if (!screen.getClass().getName().equals(
						"net.caffeinemc.mods.sodium.client.gui.SodiumOptionsGUI")) {
					return;
				}
				try {
					java.lang.reflect.Field pagesField = screen.getClass()
							.getDeclaredField("pages");
					pagesField.setAccessible(true);
					@SuppressWarnings("unchecked")
					java.util.List<Object> pages =
							(java.util.List<Object>) pagesField.get(screen);
					java.util.List<Object> ourPages = cn.spectra.gallium.compat.sodium
							.GalliumSodiumLegacyPage.buildReflective();
					pages.addAll(ourPages);
					LOGGER.debug("Gallium injected {} option pages into SodiumOptionsGUI (legacy path)",
							ourPages.size());
				} catch (Exception e) {
					LOGGER.warn("Gallium failed to inject legacy Sodium option pages", e);
				}
			});
		}
		//#endif

		LOGGER.info("Gallium initialized.");
	}

	private void reloadResourcePack(Minecraft client) {
		Screen previous = client.screen;
		client.reloadResourcePacks().thenAcceptAsync(aVoid -> {
			LOGGER.info("Resource pack reloaded.");
			// Reopen the prior screen only if reload itself dismissed it. If the user navigated
			// elsewhere meanwhile, leave their current screen alone.
			if (previous != null && client.screen == null) {
				client.setScreen(previous);
			}
		}, client).exceptionally(e -> {
			LOGGER.error("Failed to reload resource packs", e);
			return null;
		});
	}
}