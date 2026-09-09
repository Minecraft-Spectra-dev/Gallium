package cn.spectra.gallium.glowoutline.sr.runtime;

import cn.spectra.gallium.glowoutline.sr.definition.SrDefinition.InputTexture;
import cn.spectra.gallium.glowoutline.sr.definition.SrDefinition.Format;
import cn.spectra.gallium.glowoutline.sr.definition.SrDefinition.Jitter;
import cn.spectra.gallium.glowoutline.sr.definition.SrDefinition.JitterOwner;
import cn.spectra.gallium.glowoutline.sr.definition.SrDefinition.OutputTexture;
import cn.spectra.gallium.glowoutline.sr.definition.SrDefinition.Profile;
import cn.spectra.gallium.glowoutline.sr.definition.SrDefinition.Region;
import cn.spectra.gallium.glowoutline.sr.definition.SrDefinition.RegionValue;
import cn.spectra.gallium.glowoutline.sr.definition.SrDefinition.SourceKind;
import cn.spectra.gallium.glowoutline.sr.definition.SrDefinition.Trigger;
import cn.spectra.gallium.glowoutline.sr.definition.SrDefinition.Upscale;
import cn.spectra.gallium.glowoutline.sr.definition.SrDefinition.ValueSource;
import cn.spectra.gallium.glowoutline.sr.definition.SrDefinition.ValueType;
import cn.spectra.gallium.glowoutline.sr.runtime.SrProjectionResolver.ProjectionResolution;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SrProjectionResolverTest {

    @Test
    void resolvesPerAxisScaleFromIntegerExtents() {
        FakeRuntime runtime = new FakeRuntime();
        runtime.extents = Optional.of(new SrRuntimeAccess.Extents(1279, 719, 1919, 1079));

        ProjectionResolution result = new SrProjectionResolver(runtime)
                .resolve(activeProfile(Jitter.DISABLED));

        assertEquals(1279, result.renderWidth());
        assertEquals(719, result.renderHeight());
        assertEquals(1919, result.screenWidth());
        assertEquals(1079, result.screenHeight());
        assertEquals(1279.0f / 1919.0f, result.scaleX(), 1e-7f);
        assertEquals(719.0f / 1079.0f, result.scaleY(), 1e-7f);
        assertTrue(result.scaleResolved());
        assertTrue(result.exactScale());
        assertEquals(0.0f, result.originX(), 0.0f);
        assertEquals(0.0f, result.originY(), 0.0f);
    }

    @Test
    void resolvesNonZeroExplicitColorRegionIntoScreenUv() {
        FakeRuntime runtime = validRuntime();
        Region colorRegion = new Region(
                RegionValue.literal(100), RegionValue.literal(50),
                RegionValue.literal(640), RegionValue.literal(360));
        Profile profile = activeProfile(Jitter.DISABLED, Map.of(
                "color", new InputTexture(true, "colortex0", colorRegion),
                "depth", new InputTexture(true, "depthtex", Region.FULL_RENDER)));

        ProjectionResolution result = new SrProjectionResolver(runtime).resolve(profile);

        assertEquals(640.0f / 1920.0f, result.scaleX(), 1e-7f);
        assertEquals(360.0f / 1080.0f, result.scaleY(), 1e-7f);
        assertEquals(100.0f / 1920.0f, result.originX(), 1e-7f);
        assertEquals(50.0f / 1080.0f, result.originY(), 1e-7f);
        assertTrue(result.exactScale());
    }

    @Test
    void resolvesRenderAndScreenSentinelsAndFallsBackToDepth() {
        FakeRuntime runtime = validRuntime();
        Region mixed = new Region(
                RegionValue.literal(0), RegionValue.literal(0),
                RegionValue.renderSize(), RegionValue.screenSize());
        Profile profile = activeProfile(Jitter.DISABLED, Map.of(
                "color", new InputTexture(false, "colortex0", Region.FULL_SCREEN),
                "depth", new InputTexture(true, "depthtex", mixed)));

        ProjectionResolution result = new SrProjectionResolver(runtime).resolve(profile);

        assertEquals(1280.0f / 1920.0f, result.scaleX(), 1e-7f);
        assertEquals(1.0f, result.scaleY(), 0.0f);
        assertTrue(result.exactScale());
    }

    @Test
    void invalidExtentsFailClosedToIdentity() {
        FakeRuntime runtime = new FakeRuntime();
        runtime.extents = Optional.of(new SrRuntimeAccess.Extents(1920, 1080, 1280, 720));

        ProjectionResolution result = new SrProjectionResolver(runtime)
                .resolve(activeProfile(Jitter.DISABLED));

        assertEquals(1.0f, result.scaleX(), 0.0f);
        assertEquals(1.0f, result.scaleY(), 0.0f);
        assertFalse(result.scaleResolved());
    }

    @Test
    void inactiveProfileIsConservativeIdentityWithoutJitterLookup() {
        FakeRuntime runtime = new FakeRuntime();
        Profile disabled = new Profile(false, Upscale.DISABLED, Jitter.DISABLED);

        ProjectionResolution result = new SrProjectionResolver(runtime).resolve(disabled);

        assertEquals(1.0f, result.scaleX(), 0.0f);
        assertEquals(1.0f, result.scaleY(), 0.0f);
        assertFalse(result.active());
        assertTrue(result.activationResolved());
        assertFalse(result.scaleResolved());
        assertFalse(result.exactJitterTransform());
        assertEquals(0, runtime.modJitterCalls);
        assertEquals(0, runtime.shaderValueCalls);
    }

    @Test
    void runtimeInactiveOverridesEnabledProfileAndSkipsJitterLookup() {
        FakeRuntime runtime = validRuntime();
        runtime.active = Optional.of(false);
        runtime.modJitter = Optional.of(SrRuntimeAccess.NumericValue.vector(0.5, -0.5));
        runtime.modSequence = OptionalInt.of(8);
        Jitter jitter = new Jitter(
                true, JitterOwner.MOD, ValueSource.unresolved(), ValueSource.unresolved());

        ProjectionResolution result = new SrProjectionResolver(runtime)
                .resolve(activeProfile(jitter));

        assertEquals(1.0f, result.scaleX(), 0.0f);
        assertEquals(1.0f, result.scaleY(), 0.0f);
        assertEquals(0.0f, result.jitterPixelsX(), 0.0f);
        assertEquals(0.0f, result.jitterPixelsY(), 0.0f);
        assertFalse(result.active());
        assertTrue(result.activationResolved());
        assertFalse(result.scaleResolved());
        assertFalse(result.exactJitterTransform());
        assertEquals(0, runtime.modJitterCalls);
    }

    @Test
    void resolvesShaderpackConstants() {
        FakeRuntime runtime = validRuntime();
        Jitter jitter = new Jitter(
                true,
                JitterOwner.SHADERPACK,
                ValueSource.constant(ValueType.VECTOR2F, java.util.List.of(0.25, -0.5)),
                ValueSource.constant(ValueType.INT, java.util.List.of(16.0)));

        ProjectionResolution result = new SrProjectionResolver(runtime)
                .resolve(activeProfile(jitter));

        assertEquals(0.25f, result.jitterPixelsX(), 0.0f);
        assertEquals(-0.5f, result.jitterPixelsY(), 0.0f);
        assertEquals(16, result.jitterSequenceLength());
        assertTrue(result.jitterResolved());
        assertTrue(result.jitterSequenceLengthResolved());
        assertEquals(JitterOwner.SHADERPACK, result.jitterOwner());
        assertTrue(result.jitterConventionResolved());
        assertTrue(result.exactJitterTransform());
    }

    @Test
    void resolvesShaderpackUniformAndVariableReferences() {
        FakeRuntime runtime = validRuntime();
        runtime.values.put(key(SourceKind.UNIFORM, "taaOffset"),
                SrRuntimeAccess.NumericValue.vector(-0.125, 0.375));
        runtime.values.put(key(SourceKind.VARIABLE, "taaPeriod"),
                SrRuntimeAccess.NumericValue.scalar(23));
        Jitter jitter = new Jitter(
                true,
                JitterOwner.SHADERPACK,
                ValueSource.reference(SourceKind.UNIFORM, ValueType.VECTOR2F, "taaOffset"),
                ValueSource.reference(SourceKind.VARIABLE, ValueType.INT, "taaPeriod"));

        ProjectionResolution result = new SrProjectionResolver(runtime)
                .resolve(activeProfile(jitter));

        assertEquals(-0.125f, result.jitterPixelsX(), 0.0f);
        assertEquals(0.375f, result.jitterPixelsY(), 0.0f);
        assertEquals(23, result.jitterSequenceLength());
        assertTrue(result.hasExactJitterDeclaration());
        assertTrue(result.exactJitterTransform());
        assertEquals(2, runtime.shaderValueCalls);
    }

    @Test
    void missingShaderReferencePreservesScaleButDropsJitter() {
        FakeRuntime runtime = validRuntime();
        Jitter jitter = new Jitter(
                true,
                JitterOwner.SHADERPACK,
                ValueSource.reference(SourceKind.UNIFORM, ValueType.VECTOR2F, "missing"),
                ValueSource.constant(ValueType.INT, java.util.List.of(8.0)));

        ProjectionResolution result = new SrProjectionResolver(runtime)
                .resolve(activeProfile(jitter));

        assertTrue(result.scaleResolved());
        assertEquals(0.0f, result.jitterPixelsX(), 0.0f);
        assertEquals(0.0f, result.jitterPixelsY(), 0.0f);
        assertFalse(result.jitterResolved());
        assertTrue(result.jitterSequenceLengthResolved());
        assertFalse(result.hasExactJitterDeclaration());
    }

    @Test
    void resolvesModOwnedJitterThroughRuntimeAdapter() {
        FakeRuntime runtime = validRuntime();
        runtime.modJitter = Optional.of(SrRuntimeAccess.NumericValue.vector(0.5, -0.25));
        runtime.modSequence = OptionalInt.of(32);
        Jitter jitter = new Jitter(
                true, JitterOwner.MOD, ValueSource.unresolved(), ValueSource.unresolved());

        ProjectionResolution result = new SrProjectionResolver(runtime)
                .resolve(activeProfile(jitter));

        assertEquals(0.5f, result.jitterPixelsX(), 0.0f);
        assertEquals(-0.25f, result.jitterPixelsY(), 0.0f);
        assertEquals(32, result.jitterSequenceLength());
        assertTrue(result.hasExactJitterDeclaration());
        assertFalse(result.exactJitterTransform());
        assertEquals(JitterOwner.MOD, result.jitterOwner());
    }

    @Test
    void v3UsesShaderFacingModUniformAsExactConvention() {
        FakeRuntime runtime = validRuntime();
        runtime.shaderFacingModJitter = Optional.of(
                SrRuntimeAccess.NumericValue.vector(0.5, 0.25));
        runtime.modJitter = Optional.of(SrRuntimeAccess.NumericValue.vector(0.5, -0.25));
        runtime.modSequence = OptionalInt.of(16);
        Jitter jitter = new Jitter(
                true, JitterOwner.MOD, ValueSource.unresolved(), ValueSource.unresolved());

        ProjectionResolution result = new SrProjectionResolver(runtime)
                .resolve(activeProfile(jitter), Format.V3);

        assertEquals(0.25f, result.jitterPixelsY(), 0.0f);
        assertTrue(result.jitterConventionResolved());
        assertTrue(result.exactJitterTransform());
        assertEquals(0, runtime.modJitterCalls);
    }

    @Test
    void sameNamedPackCustomUniformIsNotTrustedAsSrStandardUniform() {
        FakeRuntime runtime = validRuntime();
        runtime.values.put(key(SourceKind.UNIFORM, "SRJitterOffset"),
                SrRuntimeAccess.NumericValue.vector(0.5, 0.25));
        runtime.modJitter = Optional.of(SrRuntimeAccess.NumericValue.vector(0.5, -0.25));
        Jitter jitter = new Jitter(
                true, JitterOwner.MOD, ValueSource.unresolved(), ValueSource.unresolved());

        ProjectionResolution result = new SrProjectionResolver(runtime)
                .resolve(activeProfile(jitter), Format.V3);

        assertEquals(-0.25f, result.jitterPixelsY(), 0.0f);
        assertFalse(result.jitterConventionResolved());
        assertFalse(result.exactJitterTransform());
        assertEquals(0, runtime.shaderValueCalls);
    }

    @Test
    void versionedFallbackAdaptsOnlyKnownAlgorithms() {
        SrRuntimeAccess.NumericValue raw = SrRuntimeAccess.NumericValue.vector(0.25, -0.5);
        assertEquals(java.util.List.of(0.25, 0.5),
                SrProjectionResolver.adaptSchemaJitter(raw, Format.V2, "dlss").orElseThrow()
                        .components());
        assertEquals(java.util.List.of(0.25, -0.5),
                SrProjectionResolver.adaptSchemaJitter(raw, Format.V3, "fsr2").orElseThrow()
                        .components());
        assertTrue(SrProjectionResolver.adaptSchemaJitter(
                raw, Format.V3, "custom").isEmpty());
        assertTrue(SrProjectionResolver.adaptSchemaJitter(
                raw, Format.V1, "dlss").isEmpty());
    }

    @Test
    void resolvesUnsignedSequenceAndRejectsOutOfRangeValue() {
        FakeRuntime runtime = validRuntime();
        Jitter valid = new Jitter(
                true, JitterOwner.SHADERPACK,
                ValueSource.constant(ValueType.VECTOR2F, java.util.List.of(0.0, 0.0)),
                ValueSource.constant(ValueType.UINT, java.util.List.of(16.0)));
        ProjectionResolution accepted = new SrProjectionResolver(runtime)
                .resolve(activeProfile(valid));
        assertEquals(16, accepted.jitterSequenceLength());
        assertTrue(accepted.jitterSequenceLengthResolved());

        Jitter overflow = new Jitter(
                true, JitterOwner.SHADERPACK,
                ValueSource.constant(ValueType.VECTOR2F, java.util.List.of(0.0, 0.0)),
                ValueSource.constant(ValueType.UINT,
                        java.util.List.of((double) Integer.MAX_VALUE + 1.0)));
        ProjectionResolution rejected = new SrProjectionResolver(runtime)
                .resolve(activeProfile(overflow));
        assertFalse(rejected.jitterSequenceLengthResolved());
    }

    @Test
    void unknownRuntimeActivationDoesNotApplyDefinition() {
        FakeRuntime runtime = validRuntime();
        runtime.active = Optional.empty();

        ProjectionResolution result = new SrProjectionResolver(runtime)
                .resolve(activeProfile(Jitter.DISABLED));

        assertFalse(result.active());
        assertFalse(result.activationResolved());
        assertFalse(result.scaleResolved());
    }

    @Test
    void legacyModJitterCanResolveWithoutSequenceLength() {
        FakeRuntime runtime = validRuntime();
        runtime.modJitter = Optional.of(SrRuntimeAccess.NumericValue.vector(0.1, 0.2));
        Jitter jitter = new Jitter(
                true, JitterOwner.MOD, ValueSource.unresolved(), ValueSource.unresolved());

        ProjectionResolution result = new SrProjectionResolver(runtime)
                .resolve(activeProfile(jitter));

        assertTrue(result.jitterResolved());
        assertFalse(result.jitterSequenceLengthResolved());
        assertFalse(result.hasExactJitterDeclaration());
    }

    @Test
    void nonFiniteJitterFailsClosed() {
        FakeRuntime runtime = validRuntime();
        runtime.modJitter = Optional.of(SrRuntimeAccess.NumericValue.vector(Double.NaN, 0.0));
        Jitter jitter = new Jitter(
                true, JitterOwner.MOD, ValueSource.unresolved(), ValueSource.unresolved());

        ProjectionResolution result = new SrProjectionResolver(runtime)
                .resolve(activeProfile(jitter));

        assertEquals(0.0f, result.jitterPixelsX(), 0.0f);
        assertFalse(result.jitterResolved());
    }

    private static Profile activeProfile(Jitter jitter) {
        return activeProfile(jitter, Map.of(
                "color", new InputTexture(true, "colortex0", Region.FULL_RENDER)));
    }

    private static Profile activeProfile(Jitter jitter, Map<String, InputTexture> inputs) {
        Upscale upscale = new Upscale(
                true,
                Trigger.NONE,
                "",
                inputs,
                Map.<String, OutputTexture>of());
        return new Profile(true, upscale, jitter);
    }

    private static FakeRuntime validRuntime() {
        FakeRuntime runtime = new FakeRuntime();
        runtime.extents = Optional.of(new SrRuntimeAccess.Extents(1280, 720, 1920, 1080));
        return runtime;
    }

    private static String key(SourceKind kind, String reference) {
        return kind + ":" + reference;
    }

    private static final class FakeRuntime implements SrRuntimeAccess {
        private Optional<Boolean> active = Optional.of(true);
        private Optional<Extents> extents = Optional.empty();
        private Optional<NumericValue> modJitter = Optional.empty();
        private Optional<NumericValue> shaderFacingModJitter = Optional.empty();
        private OptionalInt modSequence = OptionalInt.empty();
        private final Map<String, NumericValue> values = new HashMap<>();
        private int modJitterCalls;
        private int shaderValueCalls;

        @Override
        public Optional<Boolean> upscaleActive() {
            return active;
        }

        @Override
        public Optional<Extents> extents() {
            return extents;
        }

        @Override
        public Optional<NumericValue> modJitter() {
            modJitterCalls++;
            return modJitter;
        }

        @Override
        public Optional<NumericValue> shaderFacingModJitter() {
            return shaderFacingModJitter;
        }

        @Override
        public OptionalInt modJitterSequenceLength() {
            return modSequence;
        }

        @Override
        public Optional<NumericValue> shaderValue(SourceKind kind, String reference) {
            shaderValueCalls++;
            return Optional.ofNullable(values.get(key(kind, reference)));
        }
    }
}
