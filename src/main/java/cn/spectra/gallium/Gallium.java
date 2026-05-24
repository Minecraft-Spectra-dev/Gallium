package cn.spectra.gallium;

import cn.spectra.gallium.config.GalliumConfigIO;
import cn.spectra.gallium.glowoutline.ItemEffectsManager;
import cn.spectra.gallium.glowoutline.shader.GlowPipeline;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.resource.ResourceManagerHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.PackType;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Gallium implements ClientModInitializer {
	public static final String MOD_ID = "gallium";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	public static KeyMapping RELOAD_RESOURCE_PACK_KEY;

	@Override
	public void onInitializeClient() {
		GalliumConfigIO.load();
		GlowPipeline.init();
		ResourceManagerHelper.get(PackType.CLIENT_RESOURCES).registerReloadListener(new ItemEffectsManager());

		KeyMapping.Category category = KeyMapping.Category.register(Identifier.fromNamespaceAndPath(MOD_ID, "main"));

		RELOAD_RESOURCE_PACK_KEY = KeyMappingHelper.registerKeyMapping(new KeyMapping(
			"key.gallium.reload_resource_pack",
			GLFW.GLFW_KEY_UNKNOWN,
			category
		));

		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			while (RELOAD_RESOURCE_PACK_KEY.consumeClick()) {
				reloadResourcePack(client);
			}
		});

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
		}, client);
	}
}