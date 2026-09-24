package cn.spectra.gallium.glowoutline.shader;

import java.util.HashMap;
import java.util.HashSet;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class OriginalGlowBundlesTest {
    private static final String BASE = "gallium:shaders/core/test";
    private static String resource(String file) throws Exception {
        try (var input = OriginalGlowBundlesTest.class.getResourceAsStream("/cn/spectra/gallium/verified-shaders/" + file)) {
            assertNotNull(input); return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
    private static HashMap<String, OriginalGlowBundles.Source> sources() throws Exception {
        var result = new HashMap<String, OriginalGlowBundles.Source>();
        result.put(BASE + ".fsh", new OriginalGlowBundles.Source("pack", resource("fragment.fsh")));
        result.put(BASE + ".vsh", new OriginalGlowBundles.Source("pack", resource("vertex.vsh")));
        result.put("gallium:shaders/include/glow_common.glsl", new OriginalGlowBundles.Source("pack", resource("common.glsl")));
        return result;
    }
    @Test void verifiesOnlyShaderInputsWithoutRequestingAuthorMetadata() throws Exception {
        var inputs = sources(); var requested = new HashSet<String>();
        assertTrue(OriginalGlowBundles.matchesSources("test", path -> {
            assertTrue(inputs.containsKey(path), "Engine must not consult extra author metadata: " + path);
            requested.add(path); return inputs.get(path);
        }));
        assertEquals(inputs.keySet(), requested);
    }
    @Test void externalClaimsCannotAuthorizeChangedShaderCode() throws Exception {
        var inputs = sources();
        inputs.put(BASE + ".bounds.json", new OriginalGlowBundles.Source("pack", "{\"version\":1,\"zeroRgbOutsideMask\":true,\"worldRadius\":0,\"fallbackPixels\":0,\"paddingPixels\":0,\"constantAlpha\":0.5,\"maskStorage\":\"gallium:atlas-v1\",\"maskUniforms\":\"gallium:instance-v1\",\"maskChannels\":\"gallium:alpha-depth-v1\"}"));
        inputs.put(BASE + ".fsh", new OriginalGlowBundles.Source("pack", resource("fragment.fsh").replace("1.0 / 64.0", "1.0 / 32.0")));
        assertFalse(OriginalGlowBundles.matchesSources("test", inputs::get));
    }
    @Test void rejectsMissingOrCrossPackShaderDependencies() throws Exception {
        var inputs = sources();
        for (String path : new HashSet<>(inputs.keySet())) {
            var original = inputs.remove(path);
            assertFalse(OriginalGlowBundles.matchesSources("test", inputs::get));
            inputs.put(path, new OriginalGlowBundles.Source("override", original.text()));
            assertFalse(OriginalGlowBundles.matchesSources("test", inputs::get));
            inputs.put(path, original);
        }
    }
    @Test void rechecksChangesInsteadOfReusingAStaleSourceDecision() throws Exception {
        var inputs = sources(); var original = inputs.get(BASE + ".fsh");
        assertTrue(OriginalGlowBundles.matchesSources("test", inputs::get));
        inputs.put(BASE + ".fsh", new OriginalGlowBundles.Source("pack", "void main() {}"));
        assertFalse(OriginalGlowBundles.matchesSources("test", inputs::get));
        inputs.put(BASE + ".fsh", original);
        assertTrue(OriginalGlowBundles.matchesSources("test", inputs::get));
    }
}
