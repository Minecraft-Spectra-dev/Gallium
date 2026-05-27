package cn.spectra.gallium.glowoutline;

import java.io.StringReader;
import java.util.Map;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ShaderPackHintTest {

    private static float resolve(String json, Map<String, String> options) {
        Function<String, String> lookup = ShaderPackHint.mapLookup(options);
        return ShaderPackHint.resolveFromHintReader(new StringReader(json), lookup);
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
}
