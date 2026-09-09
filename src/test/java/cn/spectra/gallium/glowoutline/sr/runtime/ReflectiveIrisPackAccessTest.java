package cn.spectra.gallium.glowoutline.sr.runtime;

import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReflectiveIrisPackAccessTest {

    private static final String AFFINE_SOURCE = """
            void FsrScaleVS(inout vec4 position, vec2 jitter) {
                position.xy /= position.w;
                position.xy = position.xy * fsrRenderScale + fsrRenderScale - 1.0;
                position.xy += jitter;
                position.xy *= position.w;
            }
            void main() { FsrScaleVS(gl_Position, taaJitter); }
            """;

    @Test
    void parsesEverySrColorTargetAliasWithoutGuessingDepthNames() {
        assertEquals(0, ReflectiveIrisPackAccess.colorTargetIndex("colortex0"));
        assertEquals(10, ReflectiveIrisPackAccess.colorTargetIndex("colortex10"));
        assertEquals(6, ReflectiveIrisPackAccess.colorTargetIndex("autotex6"));
        assertEquals(31, ReflectiveIrisPackAccess.colorTargetIndex("alttex31"));
        assertEquals(-1, ReflectiveIrisPackAccess.colorTargetIndex("depthtex"));
        assertEquals(-1, ReflectiveIrisPackAccess.colorTargetIndex("colortex32"));
    }

    @Test
    void acceptsOnlyTheSimpleAfterScaleFsrProjectionFormula() {
        String customResolution = AFFINE_SOURCE.replace(
                "position.xy += jitter;", "position.xy += jitter * fsrRenderScale;");
        String definitionOnly = AFFINE_SOURCE.replace(
                "void main() { FsrScaleVS(gl_Position, taaJitter); }", "");
        String extraVectorWrite = AFFINE_SOURCE.replace(
                "position.xy += jitter;",
                "position.xy += jitter;\n    position.xy += vec2(0.01);");
        String extraComponentWrite = AFFINE_SOURCE.replace(
                "position.xy += jitter;",
                "position.xy += jitter;\n    position.x += 0.01;");

        assertTrue(ReflectiveIrisPackAccess.isFsrAffineProjectionSource(AFFINE_SOURCE));
        assertFalse(ReflectiveIrisPackAccess.isFsrAffineProjectionSource(customResolution));
        assertFalse(ReflectiveIrisPackAccess.isFsrAffineProjectionSource(definitionOnly));
        assertFalse(ReflectiveIrisPackAccess.isFsrAffineProjectionSource(extraVectorWrite));
        assertFalse(ReflectiveIrisPackAccess.isFsrAffineProjectionSource(extraComponentWrite));
    }

    @Test
    void affineProofRequiresTheExactParameterAndFourStatementBody() {
        String byValuePosition = AFFINE_SOURCE.replace(
                "inout vec4 position", "vec4 position");
        String helper = AFFINE_SOURCE.replace(
                "position.xy /= position.w;",
                "adjustProjection(position);\n    position.xy /= position.w;");
        String conditional = AFFINE_SOURCE.replace(
                "position.xy += jitter;", "if (applyJitter) position.xy += jitter;");
        String shadowingLocal = AFFINE_SOURCE.replace(
                "position.xy /= position.w;",
                "vec4 position = gl_Position;\n    position.xy /= position.w;");

        assertFalse(ReflectiveIrisPackAccess.isFsrAffineProjectionSource(byValuePosition));
        assertFalse(ReflectiveIrisPackAccess.isFsrAffineProjectionSource(helper));
        assertFalse(ReflectiveIrisPackAccess.isFsrAffineProjectionSource(conditional));
        assertFalse(ReflectiveIrisPackAccess.isFsrAffineProjectionSource(shadowingLocal));
    }

    @Test
    void resolvesEveryCaptureProgramThroughItsEffectiveFallback() {
        FakeProgramSet programs = new FakeProgramSet(Map.of(
                FakeProgramId.Textured, new FakeProgramSource(AFFINE_SOURCE)));

        ReflectiveIrisPackAccess.ProjectionAnalysis analysis =
                ReflectiveIrisPackAccess.analyzeCapturePrograms(
                        programs, FakeProgramId.class);

        assertTrue(analysis.affine());
        assertEquals(FsrTemporalJitterAnalyzer.Kind.UNIFORM,
                analysis.temporalJitter().kind());
        assertEquals("taaJitter", analysis.temporalJitter().uniformName());
    }

    @Test
    void mixedEffectiveJitterCallsKeepScaleProofButDropExactJitter() {
        String zeroJitter = AFFINE_SOURCE.replace(
                "FsrScaleVS(gl_Position, taaJitter)",
                "FsrScaleVS(gl_Position, vec2(0.0))");
        FakeProgramSet programs = new FakeProgramSet(Map.of(
                FakeProgramId.Textured, new FakeProgramSource(AFFINE_SOURCE),
                FakeProgramId.Hand, new FakeProgramSource(zeroJitter)));

        ReflectiveIrisPackAccess.ProjectionAnalysis analysis =
                ReflectiveIrisPackAccess.analyzeCapturePrograms(
                        programs, FakeProgramId.class);

        assertTrue(analysis.affine());
        assertEquals(FsrTemporalJitterAnalyzer.Kind.AMBIGUOUS,
                analysis.temporalJitter().kind());
    }

    @Test
    void sceneDepthJitterMustAgreeBeforeProjectionIsGloballyExact() {
        String zeroJitter = AFFINE_SOURCE.replace(
                "FsrScaleVS(gl_Position, taaJitter)",
                "FsrScaleVS(gl_Position, vec2(0.0))");
        FakeProgramSet programs = new FakeProgramSet(Map.of(
                FakeProgramId.Textured, new FakeProgramSource(AFFINE_SOURCE),
                FakeProgramId.Terrain, new FakeProgramSource(zeroJitter)));

        ReflectiveIrisPackAccess.ProjectionAnalysis capture =
                ReflectiveIrisPackAccess.analyzeCapturePrograms(
                        programs, FakeProgramId.class);
        ReflectiveIrisPackAccess.ProjectionAnalysis global =
                ReflectiveIrisPackAccess.analyzeProjectionPrograms(
                        programs, FakeProgramId.class);

        assertEquals(FsrTemporalJitterAnalyzer.Kind.UNIFORM,
                capture.temporalJitter().kind());
        assertTrue(global.affine());
        assertEquals(FsrTemporalJitterAnalyzer.Kind.AMBIGUOUS,
                global.temporalJitter().kind());
    }

    @Test
    void weatherParticipatesInGlobalConsensusOnlyWhenRainWritesDepth() {
        String zeroJitter = AFFINE_SOURCE.replace(
                "FsrScaleVS(gl_Position, taaJitter)",
                "FsrScaleVS(gl_Position, vec2(0.0))");
        FakeProgramSet programs = new FakeProgramSet(Map.of(
                FakeProgramId.Textured, new FakeProgramSource(AFFINE_SOURCE),
                FakeProgramId.Weather, new FakeProgramSource(zeroJitter)));

        ReflectiveIrisPackAccess.ProjectionAnalysis noRainDepth =
                ReflectiveIrisPackAccess.analyzeProjectionPrograms(
                        programs, FakeProgramId.class, false);
        ReflectiveIrisPackAccess.ProjectionAnalysis rainDepth =
                ReflectiveIrisPackAccess.analyzeProjectionPrograms(
                        programs, FakeProgramId.class, true);

        assertEquals(FsrTemporalJitterAnalyzer.Kind.UNIFORM,
                noRainDepth.temporalJitter().kind());
        assertEquals(FsrTemporalJitterAnalyzer.Kind.AMBIGUOUS,
                rainDepth.temporalJitter().kind());
    }

    @Test
    void rejectsWhenDirectProgramPassesButAnotherEffectiveFallbackDoesNot() {
        FakeProgramSet programs = new FakeProgramSet(Map.of(
                FakeProgramId.ArmorGlint, new FakeProgramSource(AFFINE_SOURCE),
                FakeProgramId.Textured, new FakeProgramSource("void main() {}")));

        assertFalse(ReflectiveIrisPackAccess.allCaptureProgramsHaveFsrAffineProjection(
                programs, FakeProgramId.class));
    }

    @Test
    void missingEntireFallbackChainFailsClosed() {
        assertFalse(ReflectiveIrisPackAccess.allCaptureProgramsHaveFsrAffineProjection(
                new FakeProgramSet(Map.of()), FakeProgramId.class));
    }

    private enum FakeProgramId {
        Textured(null),
        TexturedLit(Textured),
        Entities(TexturedLit),
        EntitiesTrans(Entities),
        EntitiesGlowing(Entities),
        Item(TexturedLit),
        Block(TexturedLit),
        BlockTrans(Block),
        ArmorGlint(Textured),
        Hand(TexturedLit),
        HandWater(Hand),
        Terrain(TexturedLit),
        TerrainSolid(Terrain),
        TerrainCutout(Terrain),
        Water(TexturedLit),
        DhTerrain(Terrain),
        DhWater(Water),
        DhGeneric(Terrain),
        Weather(TexturedLit);

        private final FakeProgramId fallback;

        FakeProgramId(FakeProgramId fallback) {
            this.fallback = fallback;
        }

        public Optional<FakeProgramId> getFallback() {
            return Optional.ofNullable(fallback);
        }
    }

    private static final class FakeProgramSource {
        private final String vertexSource;

        private FakeProgramSource(String vertexSource) {
            this.vertexSource = vertexSource;
        }

        public Optional<String> getVertexSource() {
            return Optional.of(vertexSource);
        }
    }

    private static final class FakeProgramSet {
        private final Map<FakeProgramId, FakeProgramSource> sources;

        private FakeProgramSet(Map<FakeProgramId, FakeProgramSource> sources) {
            this.sources = new EnumMap<>(FakeProgramId.class);
            this.sources.putAll(sources);
        }

        public Optional<FakeProgramSource> get(FakeProgramId id) {
            return Optional.ofNullable(sources.get(id));
        }
    }
}
