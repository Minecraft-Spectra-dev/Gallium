package cn.spectra.gallium.glowoutline;

import cn.spectra.gallium.glowoutline.sr.SrShaderPackContext;
import cn.spectra.gallium.glowoutline.sr.SrShaderPackResolver.Session;
import cn.spectra.gallium.glowoutline.sr.definition.SrDefinition.JitterOwner;
import cn.spectra.gallium.glowoutline.sr.runtime.SrProjectionResolver.ProjectionResolution;
import java.io.StringReader;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ShaderPackHintTest {

    private static final class ThrowingPackContext implements SrShaderPackContext {
        @Override
        public boolean gallium$hasCapturedShaderPackContext() {
            return true;
        }

        @Override
        public Optional<Session> gallium$getSrDefinitionSession() {
            throw new IllegalStateException("broken new pack context");
        }

        @Override
        public Optional<String> gallium$getGalliumHint() {
            return Optional.empty();
        }
    }

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

    @Test
    void failedNewPackDefinitionLoadProducesAnEmptyAtomicReloadValue() {
        assertTrue(ShaderPackHint.loadDefinitionForReload(
                new ThrowingPackContext()).isEmpty());
    }

    @Test
    void packIdentityHintAndSessionUseOneImmutableVolatilePublication() throws Exception {
        var snapshotField = ShaderPackHint.class.getDeclaredField("cachedPackSnapshot");
        assertTrue(Modifier.isVolatile(snapshotField.getModifiers()));

        Class<?> snapshotType = snapshotField.getType();
        assertTrue(snapshotType.isRecord());
        assertEquals(List.of("packRef", "hint", "srDefinition"),
                Arrays.stream(snapshotType.getRecordComponents())
                        .map(component -> component.getName()).toList());
        assertTrue(Arrays.stream(snapshotType.getDeclaredFields())
                .filter(field -> !Modifier.isStatic(field.getModifiers()))
                .allMatch(field -> Modifier.isFinal(field.getModifiers())));

        var projectionKey = ShaderPackHint.class.getDeclaredField(
                "cachedProjectionPackSnapshot");
        assertTrue(Modifier.isVolatile(projectionKey.getModifiers()));
        assertEquals(snapshotType, projectionKey.getType());
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

    @Test
    void activeSrDefinitionWinsAnOlderNativeUpscalerHintByDefault() {
        String hint = "{\"internal_resolution_scale\":{"
                + "\"option\":\"FSR2_SCALE\",\"values\":{\"-1\":1.0}}}";
        ShaderPackHint.ProjectionTransform sr =
                new ShaderPackHint.ProjectionTransform(0.5f, 0.5f, 0.0f, 0.0f, false);

        ShaderPackHint.ProjectionTransform result =
                ShaderPackHint.resolveProjectionFromHintReader(
                        new StringReader(hint), ShaderPackHint.mapLookup(Map.of("FSR2_SCALE", "-1")),
                        4, 3840, 2054, sr);

        assertEquals(0.5f, result.scaleX(), 0.0f);
        assertEquals(0.5f, result.scaleY(), 0.0f);
    }

    @Test
    void nonstandardPackCanExplicitlyOverrideAnActiveSrDefinition() {
        String hint = "{\"override_sr_definition\":true,"
                + "\"internal_resolution_scale\":{"
                + "\"option\":\"CUSTOM_SCALE\",\"values\":{\"native\":0.75}}}";
        ShaderPackHint.ProjectionTransform sr =
                new ShaderPackHint.ProjectionTransform(0.5f, 0.5f, 0.0f, 0.0f, false);

        ShaderPackHint.ProjectionTransform result =
                ShaderPackHint.resolveProjectionFromHintReader(
                        new StringReader(hint),
                        ShaderPackHint.mapLookup(Map.of("CUSTOM_SCALE", "native")),
                        4, 3840, 2054, sr);

        assertEquals(0.75f, result.scaleX(), 0.0f);
        assertEquals(0.75f, result.scaleY(), 0.0f);
    }

    @Test
    void srCompatiblePackRuntimeProjectionWinsLegacyHintWhenExternalSrIsInactive() {
        ShaderPackHint.ProjectionTransform oldHint =
                new ShaderPackHint.ProjectionTransform(1.0f, 1.0f, 0.0f, 0.0f, false);
        ShaderPackHint.ProjectionTransform runtime =
                new ShaderPackHint.ProjectionTransform(0.5f, 0.5f, 0.0f, 0.0f, false);

        ShaderPackHint.ProjectionTransform result = ShaderPackHint.selectProjection(
                oldHint, false, null, runtime);

        assertEquals(0.5f, result.scaleX(), 0.0f);
        assertEquals(0.5f, result.scaleY(), 0.0f);
    }

    @Test
    void provenBuiltInFsrJitterRemainsExactWhenExternalSrIsInactive() {
        ShaderPackHint.ProjectionTransform runtime =
                new ShaderPackHint.ProjectionTransform(
                        0.5f, 0.5f, 0.00025f, -0.0005f, true);

        ShaderPackHint.ProjectionTransform result = ShaderPackHint.selectProjection(
                null, false, null, runtime);

        assertEquals(runtime, result);
        assertTrue(result.exactTemporalJitter());
    }

    @Test
    void provenPackJitterDoesNotPromoteLegacySrAtThePostUpscaleStage() {
        ShaderPackHint.ProjectionTransform sr =
                new ShaderPackHint.ProjectionTransform(0.5f, 0.5f, 0.0f, 0.0f, false);
        ShaderPackHint.ProjectionTransform pack =
                new ShaderPackHint.ProjectionTransform(
                        0.5f, 0.5f, 0.00025f, -0.0005f, true);

        ShaderPackHint.ProjectionTransform result =
                ShaderPackHint.mergeCompatibleRuntimeJitter(sr, pack);

        assertEquals(0.0f, result.jitterX(), 0.0f);
        assertEquals(0.0f, result.jitterY(), 0.0f);
        assertFalse(result.exactTemporalJitter());
    }

    @Test
    void provenPackJitterCannotCrossAProjectionExtentMismatch() {
        ShaderPackHint.ProjectionTransform sr =
                new ShaderPackHint.ProjectionTransform(0.5f, 0.5f, 0.0f, 0.0f, false);
        ShaderPackHint.ProjectionTransform pack =
                new ShaderPackHint.ProjectionTransform(
                        0.6f, 0.6f, 0.00025f, -0.0005f, true);

        assertEquals(sr, ShaderPackHint.mergeCompatibleRuntimeJitter(sr, pack));
    }

    @Test
    void conflictingExactSchemaAndCallSiteJitterDropsExactReplay() {
        ShaderPackHint.ProjectionTransform sr =
                new ShaderPackHint.ProjectionTransform(
                        0.5f, 0.5f, 0.001f, -0.001f, true);
        ShaderPackHint.ProjectionTransform pack =
                new ShaderPackHint.ProjectionTransform(
                        0.5f, 0.5f, 0.0005f, -0.0005f, true);

        ShaderPackHint.ProjectionTransform result =
                ShaderPackHint.mergeCompatibleRuntimeJitter(sr, pack);

        assertFalse(result.exactTemporalJitter());
        assertEquals(0.0f, result.jitterX(), 0.0f);
        assertEquals(0.0f, result.jitterY(), 0.0f);
    }

    @Test
    void matchingViewportWithoutGlobalProgramConsensusDropsSchemaExactReplay() {
        ShaderPackHint.ProjectionTransform sr =
                new ShaderPackHint.ProjectionTransform(
                        0.5f, 0.5f, 0.0005f, -0.0005f, true);
        ShaderPackHint.ProjectionTransform pack =
                new ShaderPackHint.ProjectionTransform(
                        0.5f, 0.5f, 0.0f, 0.0f, false);

        ShaderPackHint.ProjectionTransform result =
                ShaderPackHint.mergeCompatibleRuntimeJitter(sr, pack);

        assertFalse(result.exactTemporalJitter());
        assertEquals(0.0f, result.jitterX(), 0.0f);
        assertEquals(0.0f, result.jitterY(), 0.0f);
    }

    @Test
    void unresolvedExplicitOverrideCannotSuppressAValidSrProjection() {
        ShaderPackHint.ProjectionTransform sr =
                new ShaderPackHint.ProjectionTransform(0.5f, 0.5f, 0.0f, 0.0f, false);
        ShaderPackHint.ProjectionTransform runtime =
                new ShaderPackHint.ProjectionTransform(0.75f, 0.75f, 0.0f, 0.0f, false);

        ShaderPackHint.ProjectionTransform result = ShaderPackHint.selectProjection(
                null, true, sr, runtime);

        assertEquals(0.5f, result.scaleX(), 0.0f);
    }

    @Test
    void activeSrSourceFlagFollowsTheSameOverridePrecedence() {
        ShaderPackHint.ProjectionTransform explicit =
                new ShaderPackHint.ProjectionTransform(0.75f, 0.75f, 0.0f, 0.0f, false);
        ShaderPackHint.ProjectionTransform sr =
                new ShaderPackHint.ProjectionTransform(0.5f, 0.5f, 0.0f, 0.0f, false);

        assertTrue(ShaderPackHint.selectsActiveSrProjection(explicit, false, sr));
        assertFalse(ShaderPackHint.selectsActiveSrProjection(explicit, true, sr));
        assertFalse(ShaderPackHint.selectsActiveSrProjection(explicit, false, null));
    }

    @Test
    void activeSrRuntimeRemainsVisibleWhenGalliumProjectionOverridesIt() {
        ShaderPackHint.ProjectionTransform sr =
                new ShaderPackHint.ProjectionTransform(0.5f, 0.5f, 0.0f, 0.0f, false);

        assertTrue(ShaderPackHint.hasActiveSrRuntime(sr));
        assertFalse(ShaderPackHint.hasActiveSrRuntime(null));
    }

    @Test
    void srPixelProtocolConvertsPerAxisScaleOriginAndJitter() {
        ProjectionResolution resolution = new ProjectionResolution(
                1279, 719, 1919, 1079,
                1279.0f / 1919.0f, 719.0f / 1079.0f,
                4.0f / 1919.0f, 2.0f / 1079.0f,
                0.5f, -0.25f, 16,
                true, true, true, JitterOwner.SHADERPACK,
                true, true, true);

        ShaderPackHint.ProjectionTransform transform =
                ShaderPackHint.projectionFromSrResolution(resolution);

        assertEquals(resolution.scaleX(), transform.scaleX(), 0.0f);
        assertEquals(resolution.scaleY(), transform.scaleY(), 0.0f);
        assertEquals(resolution.originX(), transform.viewportOriginX(), 0.0f);
        assertEquals(resolution.originY(), transform.viewportOriginY(), 0.0f);
        assertEquals(1.0f / 1919.0f, transform.jitterX(), 1e-8f);
        assertEquals(-0.5f / 1079.0f, transform.jitterY(), 1e-8f);
        assertTrue(transform.exactTemporalJitter());
        assertEquals(transform.viewportOriginX() + transform.jitterX() * 0.5f,
                transform.uvOffsetX(), 1e-8f);
    }

    @Test
    void modOwnedSrJitterUsesTheShaderFacingRuntimeValue() {
        ProjectionResolution resolution = new ProjectionResolution(
                1280, 720, 1920, 1080,
                2.0f / 3.0f, 2.0f / 3.0f,
                0.0f, 0.0f,
                0.5f, 0.5f, 16,
                true, true, true, JitterOwner.MOD,
                true, true, true);

        ShaderPackHint.ProjectionTransform transform =
                ShaderPackHint.projectionFromSrResolution(resolution);

        assertEquals(1.0f / 1920.0f, transform.jitterX(), 1e-8f);
        assertTrue(transform.exactTemporalJitter());
    }

    @Test
    void nonExactSrConventionNeverLeaksApproximateJitterToConsumers() {
        ProjectionResolution resolution = new ProjectionResolution(
                1280, 720, 1920, 1080,
                2.0f / 3.0f, 2.0f / 3.0f,
                0.0f, 0.0f,
                0.5f, -0.5f, 16,
                true, true, true, JitterOwner.MOD,
                true, true, false);

        ShaderPackHint.ProjectionTransform transform =
                ShaderPackHint.projectionFromSrResolution(resolution);

        assertEquals(0.0f, transform.jitterX(), 0.0f);
        assertEquals(0.0f, transform.jitterY(), 0.0f);
        assertFalse(transform.exactTemporalJitter());
    }
}
