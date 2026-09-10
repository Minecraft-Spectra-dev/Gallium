package cn.spectra.gallium.glowoutline.sr.runtime;

import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReflectiveIrisPackAccessTest {

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
    void inlineViewportUsesEffectiveFallbacksAndChecksAllDepthProducers() {
        FakeProgramSet programs = new FakeProgramSet(Map.of(
                FakeProgramId.Textured, inlineSource(InlineViewportProjectionAnalyzerTest.VERTEX)));
        var exact = ReflectiveIrisPackAccess.analyzeProjectionPrograms(
                programs, FakeProgramId.class, true).orElseThrow();
        assertEquals("viewportScale", exact.scaleUniform());
        assertEquals("viewportSize", exact.extentUniform());
        assertTrue(exact.exactJitter());
        assertTrue(exact.jitterBeforeScale());
        var noTerrainJitter = inlineSource(InlineViewportProjectionAnalyzerTest.VERTEX.replace(
                "gl_Position.xy += sampleOffset * gl_Position.w;", ""));
        FakeProgramSet mixed = new FakeProgramSet(Map.of(
                FakeProgramId.Textured, inlineSource(InlineViewportProjectionAnalyzerTest.VERTEX),
                FakeProgramId.Terrain, noTerrainJitter));
        var conservative = ReflectiveIrisPackAccess.analyzeProjectionPrograms(
                mixed, FakeProgramId.class, false).orElseThrow();
        assertEquals("viewportScale", conservative.scaleUniform());
        assertFalse(conservative.exactJitter());
    }

    @Test
    void inlineViewportRejectsAMissingCaptureOrALaterProgrammableStage() {
        assertTrue(ReflectiveIrisPackAccess.analyzeProjectionPrograms(
                new FakeProgramSet(Map.of()), FakeProgramId.class, false).isEmpty());
        FakeProgramSource geometry = inlineSource(InlineViewportProjectionAnalyzerTest.VERTEX);
        geometry.geometrySource = "void main() { gl_Position = vec4(0); EmitVertex(); }";
        assertTrue(ReflectiveIrisPackAccess.analyzeProjectionPrograms(new FakeProgramSet(Map.of(
                FakeProgramId.Textured, inlineSource(InlineViewportProjectionAnalyzerTest.VERTEX),
                FakeProgramId.Hand, geometry)), FakeProgramId.class, false).isEmpty());
    }

    @Test
    void inlineWeatherJitterMattersOnlyWhenItWritesSceneDepth() {
        FakeProgramSet programs = new FakeProgramSet(Map.of(
                FakeProgramId.Textured, inlineSource(InlineViewportProjectionAnalyzerTest.VERTEX),
                FakeProgramId.Weather, inlineSource(InlineViewportProjectionAnalyzerTest.VERTEX.replace(
                        "gl_Position.xy += sampleOffset * gl_Position.w;", ""))));
        assertTrue(ReflectiveIrisPackAccess.analyzeProjectionPrograms(
                programs, FakeProgramId.class, false).orElseThrow().exactJitter());
        assertFalse(ReflectiveIrisPackAccess.analyzeProjectionPrograms(
                programs, FakeProgramId.class, true).orElseThrow().exactJitter());
    }

    private static FakeProgramSource inlineSource(String vertex) {
        FakeProgramSource source = new FakeProgramSource(vertex);
        source.fragmentSource = InlineViewportProjectionAnalyzerTest.FRAGMENT;
        return source;
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
        private String fragmentSource;
        private String geometrySource;

        private FakeProgramSource(String vertexSource) {
            this.vertexSource = vertexSource;
        }

        public Optional<String> getVertexSource() {
            return Optional.of(vertexSource);
        }
        public Optional<String> getFragmentSource() { return Optional.ofNullable(fragmentSource); }
        public Optional<String> getGeometrySource() { return Optional.ofNullable(geometrySource); }
        public Optional<String> getTessControlSource() { return Optional.empty(); }
        public Optional<String> getTessEvalSource() { return Optional.empty(); }
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
