package cn.spectra.gallium.compat.sodium;

import cn.spectra.gallium.Gallium;
import net.minecraft.client.gui.screens.Screen;
import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/** Optional Sodium integration; the shared entrypoint never links its optional API types. */
public final class SodiumConfigCompat {
    private static final String CONFIG_MANAGER = "net.caffeinemc.mods.sodium.client.config.ConfigManager";
    private static final String LEGACY_SCREEN = "me.jellysquid.mods.sodium.client.gui.SodiumOptionsGUI";
    private static final Map<Screen, List<Object>> REGISTERED_PAGES = new WeakHashMap<>();
    private static Boolean configApiAvailable;
    private static boolean configApiRegistered;

    private SodiumConfigCompat() {}

    public static boolean hasConfigApi() {
        if (configApiAvailable == null) {
            try {
                Class.forName(CONFIG_MANAGER, false, SodiumConfigCompat.class.getClassLoader());
                configApiAvailable = true;
            } catch (ClassNotFoundException | LinkageError ignored) {
                configApiAvailable = false;
            }
        }
        return configApiAvailable;
    }

    /** Fabric 1.21.1 cannot declare the 0.8 entrypoint in its 0.6-compatible metadata. */
    public static boolean registerConfigApiEntryPoint() {
        if (configApiRegistered) return true;
        if (!hasConfigApi()) return false;
        try {
            Class<?> manager = Class.forName(CONFIG_MANAGER);
            manager.getMethod("registerConfigEntryPoint", String.class, String.class).invoke(null,
                    "cn.spectra.gallium.compat.sodium.GalliumSodiumConfig", Gallium.MOD_ID);
            configApiRegistered = true;
            return true;
        } catch (ReflectiveOperationException | LinkageError e) {
            Gallium.LOGGER.warn("Failed to register Sodium config API entrypoint", e);
            return false;
        }
    }

    public static void attachLegacyPages(Screen screen) {
        if (!screen.getClass().getName().equals(LEGACY_SCREEN)) return;
        try {
            Field field = screen.getClass().getDeclaredField("pages");
            field.setAccessible(true);
            @SuppressWarnings("unchecked")
            List<Object> pages = (List<Object>) field.get(screen);
            List<Object> previous = REGISTERED_PAGES.get(screen);
            if (previous != null && pages.containsAll(previous)) return;
            List<Object> additions = GalliumSodiumLegacyPage.buildReflective();
            pages.addAll(additions);
            REGISTERED_PAGES.put(screen, additions);
        } catch (ReflectiveOperationException | LinkageError e) {
            Gallium.LOGGER.warn("Failed to add legacy Sodium config pages", e);
        } catch (Exception e) {
            Gallium.LOGGER.warn("Failed to construct legacy Sodium config pages", e);
        }
    }
}
