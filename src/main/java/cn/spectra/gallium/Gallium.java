package cn.spectra.gallium;

import cn.spectra.gallium.config.GalliumConfigIO;
import cn.spectra.gallium.glowoutline.shader.GlowPipeline;
import cn.spectra.gallium.glowoutline.shader.GlowResources;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
//#if MC>=1_21_09
import net.minecraft.resources.Identifier;
//#endif
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Shared client lifecycle; loader event registration lives in the platform entrypoints. */
public final class Gallium {
    public static final String MOD_ID = "gallium";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);
    public static volatile KeyMapping RELOAD_RESOURCE_PACK_KEY;
    private static boolean initialized;

    private Gallium() {}

    /** Must run on the client thread with a current graphics context. */
    public static void initializeClient() {
        if (initialized) return;
        GalliumConfigIO.load();
        GlowPipeline.init();
        GlowResources.eagerInit();
        initialized = true;
        LOGGER.info("Gallium initialized.");
    }

    public static KeyMapping createReloadKeyMapping() {
        if (RELOAD_RESOURCE_PACK_KEY != null) return RELOAD_RESOURCE_PACK_KEY;
        //#if MC>=1_21_09
        KeyMapping.Category category = KeyMapping.Category.register(Identifier.fromNamespaceAndPath(MOD_ID, "main"));
        //#else
        //$$ String category = "key.category.gallium.main";
        //#endif
        RELOAD_RESOURCE_PACK_KEY = new KeyMapping("key.gallium.reload_resource_pack", GLFW.GLFW_KEY_UNKNOWN, category);
        return RELOAD_RESOURCE_PACK_KEY;
    }

    public static void onEndClientTick(Minecraft client) {
        KeyMapping key = RELOAD_RESOURCE_PACK_KEY;
        if (key == null) return;
        while (key.consumeClick()) reloadResourcePack(client);
    }

	private static void reloadResourcePack(Minecraft client) {
		//#if MC>=1_26_02
		//$$ 	Screen previous = client.gui.screen();
		//#else
		Screen previous = client.screen;
		//#endif
		client.reloadResourcePacks().thenAcceptAsync(aVoid -> {
			LOGGER.info("Resource pack reloaded.");
			// Reopen the prior screen only if reload itself dismissed it. If the user navigated
			// elsewhere meanwhile, leave their current screen alone.
			//#if MC>=1_26_02
			//$$ 		if (previous != null && client.gui.screen() == null) {
			//$$ 			client.gui.setScreen(previous);
			//#else
			if (previous != null && client.screen == null) {
				client.setScreen(previous);
			//#endif
			}
		}, client).exceptionally(e -> {
			LOGGER.error("Failed to reload resource packs", e);
			return null;
		});
	}
}