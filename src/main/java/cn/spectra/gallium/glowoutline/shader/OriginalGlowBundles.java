package cn.spectra.gallium.glowoutline.shader;

import java.util.function.Function;
import net.minecraft.client.Minecraft;
//#if MC>=1_21_09
import net.minecraft.resources.Identifier;
//#else
//$$ import net.minecraft.resources.ResourceLocation;
//#endif

/** Checks every raw shader input before the engine considers a private source adaptation. */
final class OriginalGlowBundles {
    record Source(String pack, String text) {}
    private OriginalGlowBundles() {}

    static boolean matches(String shader) {
        if (shader == null || shader.isEmpty()) return false;
        // Shader compilation can precede reload disposal; never cache this source decision.
        try {
            var resources = Minecraft.getInstance().getResourceManager();
            return matchesSources(shader, path -> {
                //#if MC>=1_21_09
                var resource = resources.getResource(Identifier.parse(path));
                //#else
                //$$ var resource = resources.getResource(net.minecraft.resources.ResourceLocation.parse(path));
                //#endif
                if (resource.isEmpty()) return null;
                try (var reader = resource.get().openAsReader()) {
                    return new Source(resource.get().sourcePackId(), com.google.common.io.CharStreams.toString(reader));
                } catch (java.io.IOException unavailable) { return null; }
            });
        } catch (Exception unavailable) { return false; }
    }

    static boolean matchesSources(String shader, Function<String, Source> resources) {
        if (shader == null || shader.isEmpty()) return false;
        String base = "gallium:shaders/core/" + shader;
        Source fragment = resources.apply(base + ".fsh");
        Source vertex = resources.apply(base + ".vsh");
        Source common = resources.apply("gallium:shaders/include/glow_common.glsl");
        if (fragment == null || vertex == null || common == null || fragment.pack() == null
                || !fragment.pack().equals(vertex.pack()) || !fragment.pack().equals(common.pack())) return false;
        return OriginalGlowSource.bundle(fragment.text(), vertex.text(), common.text());
    }
}
