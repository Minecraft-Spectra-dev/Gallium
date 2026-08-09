package cn.spectra.gallium.glowoutline;

import java.io.StringReader;
import java.util.Map;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ShaderPackHintTest {

    private static float resolve(String json, Map<String, String> options) {
        Function<String, String> lookup = ShaderPackHint.mapLookup(options);
        return ShaderPackHint.resolveFromHintReader(new StringReader(json), lookup);
    }

    private static ShaderPackHint.ProjectionTransform resolveProjection(
            String json, Map<String, String> options,
            int frameCounter, int width, int height) {
        return ShaderPackHint.resolveProjectionFromHintReader(
                new StringReader(json), ShaderPackHint.mapLookup(options),
                frameCounter, width, height);
    }

    private static String iterationHint() {
        return "{"
                + "\"internal_resolution_scale\":{"
                + "\"option\":\"FSR2_SCALE\","
                + "\"values\":{\"-1\":1.0,\"1\":0.6667,\"3\":0.5},"
                + "\"pixel_rounding\":\"floor\"},"
                + "\"temporal_jitter\":{"
                + "\"sequence\":\"r2\","
                + "\"units\":\"ndc_per_view_size\","
                + "\"transform_order\":\"after_internal_scale\","
                + "\"phase\":0.5,"
                + "\"x_multiplier\":0.754877666,"
                + "\"y_multiplier\":0.569840291,"
                + "\"index_offset\":1,"
                + "\"requires\":{\"RENDERING_MODE\":\"false\","
                + "\"CUSTOM_RENDER_RESOLUTION\":\"false\","
                + "\"DISABLE_PLAYER_TAA_MOTION_BLUR\":\"false\"},"
                + "\"period\":{"
                + "\"option\":\"FSR2_SCALE\","
                + "\"values\":{\"1\":18,\"3\":32}}}}";
    }

    private static Map<String, String> iterationOptions(String scale, String renderingMode) {
        return Map.of(
                "FSR2_SCALE", scale,
                "RENDERING_MODE", renderingMode,
                "CUSTOM_RENDER_RESOLUTION", "false",
                "DISABLE_PLAYER_TAA_MOTION_BLUR", "false");
    }

    @Test
    void plainOption_parsedAsFloat() {
        String hint = "{ \"internal_resolution_scale\": { \"option\": \"ResolutionScale\" } }";
        float result = resolve(hint, Map.of("ResolutionScale", "0.75"));
        assertEquals(0.75f, result, 1e-6f);
    }

    @Test
    void enumOption_mappedThroughValuesTable() {
        String hint = "{ \"internal_resolution_scale\": {"
                + "  \"option\": \"FSR2_SCALE\","
                + "  \"values\": { \"0\": 0.75, \"1\": 0.6667, \"2\": 0.5882, \"3\": 0.5 }"
                + "} }";
        assertEquals(0.6667f, resolve(hint, Map.of("FSR2_SCALE", "1")), 1e-4f);
        assertEquals(0.5f, resolve(hint, Map.of("FSR2_SCALE", "3")), 1e-6f);
    }

    @Test
    void enumOption_unmappedValueFallsBackToOne() {
        String hint = "{ \"internal_resolution_scale\": {"
                + "  \"option\": \"FSR2_SCALE\","
                + "  \"values\": { \"0\": 0.75 }"
                + "} }";
        assertEquals(1.0f, resolve(hint, Map.of("FSR2_SCALE", "9")), 1e-6f);
    }

    @Test
    void missingOption_returnsOne() {
        // Iris's getStringValueOrDefault returns the pack's default value, but we still defend
        // against the case where the option name is misspelled in the hint and the lookup maps
        // to null.
        String hint = "{ \"internal_resolution_scale\": { \"option\": \"DoesNotExist\" } }";
        assertEquals(1.0f, resolve(hint, Map.of()), 1e-6f);
    }

    @Test
    void missingScaleKey_returnsOne() {
        String hint = "{ \"unrelated\": 42 }";
        assertEquals(1.0f, resolve(hint, Map.of("ResolutionScale", "0.5")), 1e-6f);
    }

    @Test
    void malformedJson_returnsOne() {
        assertEquals(1.0f, resolve("not json", Map.of()), 1e-6f);
    }

    @Test
    void clampsAboveOne() {
        String hint = "{ \"internal_resolution_scale\": { \"option\": \"OverScale\" } }";
        assertEquals(1.0f, resolve(hint, Map.of("OverScale", "2.5")), 1e-6f);
    }

    @Test
    void clampsBelowMin() {
        String hint = "{ \"internal_resolution_scale\": { \"option\": \"TinyScale\" } }";
        assertEquals(0.1f, resolve(hint, Map.of("TinyScale", "0.001")), 1e-6f);
    }

    @Test
    void clampHelper_roundTrips() {
        assertEquals(0.5f, ShaderPackHint.clamp(0.5f), 1e-6f);
        assertEquals(0.1f, ShaderPackHint.clamp(0.0f), 1e-6f);
        assertEquals(1.0f, ShaderPackHint.clamp(1.5f), 1e-6f);
        assertEquals(1.0f, ShaderPackHint.clamp(Float.NaN), 1e-6f);
    }

    @Test
    void iterationR2SequenceUsesDeclaredPeriodAndIndexOffset() {
        ShaderPackHint.ProjectionTransform frame0 = resolveProjection(
                iterationHint(), iterationOptions("1", "false"),
                0, 1920, 1080);
        ShaderPackHint.ProjectionTransform frame18 = resolveProjection(
                iterationHint(), iterationOptions("1", "false"),
                18, 1920, 1080);

        float rawX = ((0.5f + 0.754877666f) % 1.0f) * 2.0f - 1.0f;
        float rawY = ((0.5f + 0.569840291f) % 1.0f) * 2.0f - 1.0f;
        assertTrue(frame0.exactTemporalJitter());
        assertEquals(rawX / 1920.0f, frame0.jitterX(), 1e-7f);
        assertEquals(rawY / 1080.0f, frame0.jitterY(), 1e-7f);
        assertEquals(frame0.jitterX(), frame18.jitterX(), 1e-7f);
        assertEquals(frame0.jitterY(), frame18.jitterY(), 1e-7f);
    }

    @Test
    void pixelRoundingResolvesScalePerAxis() {
        ShaderPackHint.ProjectionTransform transform = resolveProjection(
                iterationHint(), iterationOptions("1", "false"),
                0, 1919, 1079);
        assertEquals((float) Math.floor(1919.0 * 0.6667) / 1919.0f,
                transform.scaleX(), 1e-7f);
        assertEquals((float) Math.floor(1079.0 * 0.6667) / 1079.0f,
                transform.scaleY(), 1e-7f);
    }

    @Test
    void unavailableFrameCounterKeepsScaleButDisablesExactJitter() {
        ShaderPackHint.ProjectionTransform transform = resolveProjection(
                iterationHint(), iterationOptions("3", "false"),
                -1, 1920, 1080);
        assertEquals(0.5f, transform.scaleX(), 1e-7f);
        assertEquals(0.5f, transform.scaleY(), 1e-7f);
        assertEquals(0.0f, transform.jitterX(), 0.0f);
        assertEquals(0.0f, transform.jitterY(), 0.0f);
        assertFalse(transform.exactTemporalJitter());
    }

    @Test
    void exactTemporalReplayDisablesWhenPackModeRequirementsDoNotMatch() {
        ShaderPackHint.ProjectionTransform renderingMode = resolveProjection(
                iterationHint(), iterationOptions("1", "true"), 0, 1920, 1080);
        assertEquals((float) Math.floor(1920.0 * 0.6667) / 1920.0f,
                renderingMode.scaleX(), 1e-7f);
        assertFalse(renderingMode.exactTemporalJitter());

    }

    @Test
    void exactTemporalReplayDisablesForNativeResolutionAndProjectionOverrides() {
        ShaderPackHint.ProjectionTransform nativeResolution = resolveProjection(
                iterationHint(), iterationOptions("-1", "false"), 0, 1920, 1080);
        assertFalse(nativeResolution.exactTemporalJitter());

        ShaderPackHint.ProjectionTransform customResolution = resolveProjection(
                iterationHint(), Map.of(
                        "FSR2_SCALE", "1",
                        "RENDERING_MODE", "false",
                        "CUSTOM_RENDER_RESOLUTION", "true",
                        "DISABLE_PLAYER_TAA_MOTION_BLUR", "false"), 0, 1920, 1080);
        assertFalse(customResolution.exactTemporalJitter());

        ShaderPackHint.ProjectionTransform playerTaaOverride = resolveProjection(
                iterationHint(), Map.of(
                        "FSR2_SCALE", "1",
                        "RENDERING_MODE", "false",
                        "CUSTOM_RENDER_RESOLUTION", "false",
                        "DISABLE_PLAYER_TAA_MOTION_BLUR", "true"), 0, 1920, 1080);
        assertFalse(playerTaaOverride.exactTemporalJitter());
    }

    @Test
    void exactTemporalReplayRequiresResolvedInternalScale() {
        ShaderPackHint.ProjectionTransform missingScaleMapping = resolveProjection(
                iterationHint(), iterationOptions("99", "false"),
                0, 1920, 1080);

        assertEquals(1.0f, missingScaleMapping.scaleX(), 0.0f);
        assertFalse(missingScaleMapping.exactTemporalJitter());
    }

    @Test
    void incompleteTemporalDeclarationFallsBackWithoutPartialActivation() {
        String hint = "{\"internal_resolution_scale\":{"
                + "\"option\":\"ResolutionScale\"},"
                + "\"temporal_jitter\":{"
                + "\"sequence\":\"r2\",\"phase\":0.5}}";
        ShaderPackHint.ProjectionTransform transform = resolveProjection(
                hint, Map.of("ResolutionScale", "0.75"), 4, 1920, 1080);
        assertEquals(0.75f, transform.scaleX(), 1e-7f);
        assertFalse(transform.exactTemporalJitter());
    }
}
