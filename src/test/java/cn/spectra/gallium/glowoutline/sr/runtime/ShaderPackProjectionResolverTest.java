package cn.spectra.gallium.glowoutline.sr.runtime;

import cn.spectra.gallium.glowoutline.sr.definition.SrDefinition.SourceKind;
import cn.spectra.gallium.glowoutline.sr.definition.SrDefinition.InputTexture;
import cn.spectra.gallium.glowoutline.sr.definition.SrDefinition.Jitter;
import cn.spectra.gallium.glowoutline.sr.definition.SrDefinition.OutputTexture;
import cn.spectra.gallium.glowoutline.sr.definition.SrDefinition.Profile;
import cn.spectra.gallium.glowoutline.sr.definition.SrDefinition.Region;
import cn.spectra.gallium.glowoutline.sr.definition.SrDefinition.Trigger;
import cn.spectra.gallium.glowoutline.sr.definition.SrDefinition.Upscale;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ShaderPackProjectionResolverTest {

    @Test
    void resolvesCoherentFsrRuntimeViewportUsingIntegerExtents() {
        FakeRuntime runtime = new FakeRuntime();
        runtime.put("fsrRenderScale", 1128.0 / 1919.0, 634.0 / 1079.0);
        runtime.put("fsrScreenSize", 1128.0, 634.0);

        var result = new ShaderPackProjectionResolver(runtime)
                .resolve(1919, 1079).orElseThrow();

        assertEquals(1128.0f / 1919.0f, result.scaleX(), 0.0f);
        assertEquals(634.0f / 1079.0f, result.scaleY(), 0.0f);
        assertEquals("iris:fsrRenderScale+fsrScreenSize", result.source());
    }

    @Test
    void rejectsAnUnrelatedOrIncoherentPairOfUniforms() {
        FakeRuntime runtime = new FakeRuntime();
        runtime.put("fsrRenderScale", 0.5, 0.5);
        runtime.put("fsrScreenSize", 1600.0, 900.0);

        assertTrue(new ShaderPackProjectionResolver(runtime)
                .resolve(1920, 1080).isEmpty());
    }

    @Test
    void rejectsEvenAOnePixelRuntimeExtentDisagreement() {
        FakeRuntime runtime = new FakeRuntime();
        runtime.put("fsrRenderScale", 959.0 / 1920.0, 540.0 / 1080.0);
        runtime.put("fsrScreenSize", 960.0, 540.0);

        assertTrue(new ShaderPackProjectionResolver(runtime)
                .resolve(1920, 1080).isEmpty());
    }

    @Test
    void missingRuntimeDeclarationFailsClosed() {
        assertTrue(new ShaderPackProjectionResolver(new FakeRuntime())
                .resolve(1920, 1080).isEmpty());
    }

    @Test
    void universalSrMotionInputSelectsIrisPreprocessedTargetExtent() {
        FakeRuntime runtime = new FakeRuntime();
        runtime.put("fsrRenderScale", 0.5, 0.5);
        runtime.put("fsrScreenSize", 960.0, 540.0);
        Profile profile = fullRenderProfile();
        ShaderPackProjectionResolver resolver = new ShaderPackProjectionResolver(
                runtime, java.util.List.of(ShaderPackProjectionResolverTest::resolveFsrRuntime),
                (pack, source, width, height) -> source.equals("colortex10")
                        ? Optional.of(new int[]{960, 540})
                        : Optional.of(new int[]{width, height}));

        var result = resolver.resolve(profile, new Object(), 1920, 1080).orElseThrow();

        assertEquals(0.5f, result.scaleX(), 0.0f);
        assertEquals(0.5f, result.scaleY(), 0.0f);
        assertEquals("sr-input:motion_vectors:colortex10", result.source());
    }

    @Test
    void callSiteProvenUniformSuppliesExactNativeFsrJitter() {
        FakeRuntime runtime = new FakeRuntime();
        runtime.put("fsrRenderScale", 0.5, 0.5);
        runtime.put("fsrScreenSize", 960.0, 540.0);
        runtime.put("taaJitter", 0.00025, -0.0005);
        ShaderPackProjectionResolver resolver = new ShaderPackProjectionResolver(
                runtime, java.util.List.of(ShaderPackProjectionResolverTest::resolveFsrRuntime),
                (pack, source, width, height) -> Optional.of(new int[]{960, 540}),
                ignored -> true,
                ignored -> new FsrTemporalJitterAnalyzer.Analysis(
                        FsrTemporalJitterAnalyzer.Kind.UNIFORM, "taaJitter", 9));

        var result = resolver.resolve(
                fullRenderProfile(), new Object(), 1920, 1080).orElseThrow();

        assertEquals(0.00025f, result.jitterX(), 0.0f);
        assertEquals(-0.0005f, result.jitterY(), 0.0f);
        assertTrue(result.exactTemporalJitter());
    }

    @Test
    void unanimousLiteralZeroIsAnExactNativeFsrJitter() {
        FakeRuntime runtime = new FakeRuntime();
        runtime.put("fsrRenderScale", 0.5, 0.5);
        runtime.put("fsrScreenSize", 960.0, 540.0);
        ShaderPackProjectionResolver resolver = new ShaderPackProjectionResolver(
                runtime, java.util.List.of(ShaderPackProjectionResolverTest::resolveFsrRuntime),
                (pack, source, width, height) -> Optional.of(new int[]{960, 540}),
                ignored -> true,
                ignored -> new FsrTemporalJitterAnalyzer.Analysis(
                        FsrTemporalJitterAnalyzer.Kind.ZERO, "", 9));

        var result = resolver.resolve(
                fullRenderProfile(), new Object(), 1920, 1080).orElseThrow();

        assertEquals(0.0f, result.jitterX(), 0.0f);
        assertEquals(0.0f, result.jitterY(), 0.0f);
        assertTrue(result.exactTemporalJitter());
    }

    private static Profile fullRenderProfile() {
        return new Profile(true, new Upscale(
                true, Trigger.NONE, "",
                Map.of(
                        "color", new InputTexture(true, "autotex6", Region.FULL_RENDER),
                        "motion_vectors", new InputTexture(
                                true, "colortex10", Region.FULL_RENDER)),
                Map.<String, OutputTexture>of()), Jitter.DISABLED);
    }

    private static Optional<ShaderPackProjectionResolver.ProjectionResolution> resolveFsrRuntime(
            SrRuntimeAccess runtime, int width, int height) {
        Optional<SrRuntimeAccess.NumericValue> scale = runtime.shaderValue(
                SourceKind.UNIFORM, "fsrRenderScale");
        Optional<SrRuntimeAccess.NumericValue> extent = runtime.shaderValue(
                SourceKind.UNIFORM, "fsrScreenSize");
        if (scale.isEmpty() || extent.isEmpty()) return Optional.empty();
        return Optional.of(new ShaderPackProjectionResolver.ProjectionResolution(
                (float) scale.get().component(0), (float) scale.get().component(1),
                0.0f, 0.0f, 0.0f, 0.0f, false, "test"));
    }

    private static final class FakeRuntime implements SrRuntimeAccess {
        private final Map<String, NumericValue> values = new HashMap<>();

        private void put(String name, double x, double y) {
            values.put(name, NumericValue.vector(x, y));
        }

        @Override public Optional<Boolean> upscaleActive() { return Optional.empty(); }
        @Override public Optional<Extents> extents() { return Optional.empty(); }
        @Override public Optional<NumericValue> modJitter() { return Optional.empty(); }
        @Override public OptionalInt modJitterSequenceLength() { return OptionalInt.empty(); }

        @Override
        public Optional<NumericValue> shaderValue(SourceKind kind, String reference) {
            return kind == SourceKind.UNIFORM
                    ? Optional.ofNullable(values.get(reference)) : Optional.empty();
        }
    }
}
