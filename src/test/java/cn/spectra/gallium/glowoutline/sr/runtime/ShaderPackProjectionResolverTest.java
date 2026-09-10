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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ShaderPackProjectionResolverTest {

    @Test
    void historicalUniformNamesNeverEstablishAViewportWithoutSourceProof() {
        FakeRuntime runtime = new FakeRuntime();
        runtime.put("fsrRenderScale", 0.5, 0.5);
        runtime.put("fsrScreenSize", 960, 540);
        var resolver = new ShaderPackProjectionResolver(runtime,
                (pack, source, w, h) -> Optional.of(new int[]{960, 540}), ignored -> Optional.empty());
        assertTrue(resolver.resolve(fullRenderProfile(), new Object(), 1920, 1080).isEmpty());
        assertEquals(0, runtime.uniformReads);
    }

    @Test
    void inlineViewportInsideFullSizeSrInputsUsesActualIntegerExtentAndJitterOrder() {
        FakeRuntime runtime = new FakeRuntime();
        runtime.put("viewportScale", 639.0 / 853, 359.0 / 479);
        runtime.put("viewportSize", 639, 359);
        runtime.put("sampleOffset", 0.4 / 639, -0.25 / 359);
        var result = inlineResolver(runtime, (pack, source, w, h) -> Optional.of(new int[]{w, h}))
                .resolve(fullRenderProfile(), new Object(), 853, 479).orElseThrow();
        assertEquals(639.0f / 853, result.scaleX());
        assertEquals(359.0f / 479, result.scaleY());
        assertEquals(0.4f / 853, result.jitterX(), 1e-9f);
        assertEquals(-0.25f / 479, result.jitterY(), 1e-9f);
        assertTrue(result.exactTemporalJitter());
    }

    @Test
    void readsEveryNativePresetFromLiveUniformsWithoutAnOptionTable() {
        int[][] viewports = {
                {426, 239}, {501, 281}, {511, 287}, {568, 319},
                {639, 359}, {725, 407}, {767, 431}, {853, 479}
        };
        for (int[] size : viewports) {
            FakeRuntime runtime = new FakeRuntime();
            runtime.put("viewportScale", size[0] / 853.0, size[1] / 479.0);
            runtime.put("viewportSize", size[0], size[1]);
            // Missing jitter must lose only exact temporal alignment, not the proven viewport.
            var result = inlineResolver(runtime, (pack, source, w, h) -> Optional.of(new int[]{w, h}))
                    .resolve(fullRenderProfile(), new Object(), 853, 479).orElseThrow();
            assertEquals(size[0] / 853.0f, result.scaleX());
            assertEquals(size[1] / 479.0f, result.scaleY());
            assertFalse(result.exactTemporalJitter());
            assertEquals(0, result.jitterX());
            assertEquals(0, result.jitterY());
        }
    }

    @Test
    void nativeAdapterCannotOverrideAnActiveExternalSrDispatch() {
        FakeRuntime runtime = inlineRuntime();
        runtime.active = Optional.of(true);
        assertTrue(inlineResolver(runtime, (pack, source, w, h) -> Optional.of(new int[]{w, h}))
                .resolve(fullRenderProfile(), new Object(), 853, 479).isEmpty());
    }

    @Test
    void rejectsIncoherentExtentAndUnrelatedSrAttachments() {
        FakeRuntime runtime = inlineRuntime();
        var resolver = inlineResolver(runtime, (pack, source, w, h) -> Optional.of(new int[]{w, h}));
        runtime.put("viewportSize", 640, 359);
        assertTrue(resolver.resolve(fullRenderProfile(), new Object(), 853, 479).isEmpty());
        runtime.put("viewportSize", 639, 359);
        assertTrue(inlineResolver(runtime, (pack, source, w, h) -> Optional.of(new int[]{100, 100}))
                .resolve(fullRenderProfile(), new Object(), 853, 479).isEmpty());
        Profile noInputs = new Profile(true, new Upscale(true, Trigger.NONE, "",
                Map.of(), Map.of()), Jitter.DISABLED);
        assertTrue(resolver.resolve(noInputs, new Object(), 853, 479).isEmpty());
    }

    @Test
    void missingNonFiniteOrNonIntegerUniformsCannotEstablishAViewport() {
        for (double[] extent : new double[][]{{Double.NaN, 359}, {639.5, 359}, {0, 359}, {854, 479}}) {
            FakeRuntime runtime = inlineRuntime();
            runtime.put("viewportSize", extent[0], extent[1]);
            assertTrue(inlineResolver(runtime, (pack, source, w, h) -> Optional.of(new int[]{w, h}))
                    .resolve(fullRenderProfile(), new Object(), 853, 479).isEmpty());
        }
        assertTrue(inlineResolver(new FakeRuntime(), (pack, source, w, h) -> Optional.of(new int[]{w, h}))
                .resolve(fullRenderProfile(), new Object(), 853, 479).isEmpty());
    }

    private static FakeRuntime inlineRuntime() {
        FakeRuntime runtime = new FakeRuntime();
        runtime.put("viewportScale", 639.0 / 853, 359.0 / 479);
        runtime.put("viewportSize", 639, 359);
        return runtime;
    }

    private static ShaderPackProjectionResolver inlineResolver(
            FakeRuntime runtime, ShaderPackProjectionResolver.TextureExtentAccess extents) {
        var proof = InlineViewportProjectionAnalyzer.analyze(
                InlineViewportProjectionAnalyzerTest.VERTEX, InlineViewportProjectionAnalyzerTest.FRAGMENT);
        return new ShaderPackProjectionResolver(runtime, extents, ignored -> proof);
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

    private static final class FakeRuntime implements SrRuntimeAccess {
        private final Map<String, NumericValue> values = new HashMap<>();
        private Optional<Boolean> active = Optional.empty();
        private int uniformReads;

        private void put(String name, double x, double y) {
            values.put(name, NumericValue.vector(x, y));
        }

        @Override public Optional<Boolean> upscaleActive() { return active; }
        @Override public Optional<Extents> extents() { return Optional.empty(); }
        @Override public Optional<NumericValue> modJitter() { return Optional.empty(); }
        @Override public OptionalInt modJitterSequenceLength() { return OptionalInt.empty(); }

        @Override
        public Optional<NumericValue> shaderValue(SourceKind kind, String reference) {
            uniformReads++;
            return kind == SourceKind.UNIFORM
                    ? Optional.ofNullable(values.get(reference)) : Optional.empty();
        }
    }
}
