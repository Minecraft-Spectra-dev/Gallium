package cn.spectra.gallium;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.resources.Identifier;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Gallium implements ClientModInitializer {
	public static final String MOD_ID = "gallium";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	// 重载资源包快捷键
	public static KeyMapping RELOAD_RESOURCE_PACK_KEY;

    @Override
	public void onInitializeClient() {
		// 注册分类
        KeyMapping.Category CATEGORY = KeyMapping.Category.register(Identifier.fromNamespaceAndPath(MOD_ID, "main"));

		// 注册按键绑定
		RELOAD_RESOURCE_PACK_KEY = KeyMappingHelper.registerKeyMapping(new KeyMapping(
			"key.gallium.reload_resource_pack",
			GLFW.GLFW_KEY_UNKNOWN,
                CATEGORY
		));

		// 注册按键按下事件
		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			while (RELOAD_RESOURCE_PACK_KEY.consumeClick()) {
				reloadResourcePack(client);
			}
		});

		LOGGER.info("Gallium initialized with reload resource pack keybind (F4)");
	}

	/**
	 * 重载资源包
	 */
	private void reloadResourcePack(Minecraft client) {
		Screen screen = client.screen;
		client.schedule(() -> {
			client.reloadResourcePacks().thenAcceptAsync(aVoid -> {
				LOGGER.info("Resource pack reloaded successfully!");
				if (screen != null && client.screen == null) {
					client.schedule(() -> client.setScreen(screen));
				}
			});
		});
	}
}