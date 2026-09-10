package cn.spectra.gallium.glowoutline.sr.runtime;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

class InlineViewportProjectionAnalyzerTest {
    static final String VERTEX = """
            #version 460 compatibility
            uniform vec2 viewportScale;
            uniform vec2 sampleOffset;
            void main() {
                vec3 viewPos = vec3(0);
                gl_Position = ftransform();
                gl_Position.xy += sampleOffset * gl_Position.w;
                gl_Position.xy = gl_Position.xy * viewportScale + (viewportScale - 1.0) * gl_Position.w;
            }
            """;
    static final String FRAGMENT = """
            #version 460 compatibility
            uniform vec2 viewportSize;
            void main() {
                if (any(greaterThanEqual(gl_FragCoord.xy, viewportSize))) { discard; }
                color = texture(tex, texCoord);
            }
            """;
    private static final String JITTER = "gl_Position.xy += sampleOffset * gl_Position.w;";
    private static final String SCALE = "gl_Position.xy = gl_Position.xy * viewportScale + (viewportScale - 1.0) * gl_Position.w;";

    @Test
    void discoversShaderFacingNamesAndBeforeScaleJitterWithoutAPackName() {
        var proof = InlineViewportProjectionAnalyzer.analyze(VERTEX, FRAGMENT).orElseThrow();
        assertEquals("viewportScale", proof.scaleUniform());
        assertEquals("viewportSize", proof.extentUniform());
        assertEquals("sampleOffset", proof.jitterUniform());
        assertTrue(proof.jitterBeforeScale());
        assertTrue(proof.exactJitter());
    }

    @Test
    void distinguishesAfterScaleJitterAndTheNoJitterBranch() {
        var after = InlineViewportProjectionAnalyzer.analyze(
                VERTEX.replace(JITTER, "").replace(SCALE, SCALE + JITTER), FRAGMENT).orElseThrow();
        assertFalse(after.jitterBeforeScale());
        assertEquals("sampleOffset", after.jitterUniform());
        var zero = InlineViewportProjectionAnalyzer.analyze(VERTEX.replace(JITTER, ""), FRAGMENT).orElseThrow();
        assertTrue(zero.exactJitter());
        assertEquals("", zero.jitterUniform());
    }

    @Test
    void afterScaleJitterCannotBeSkippedByAnEarlyReturn() {
        String conditionalJitter = VERTEX.replace(JITTER, "")
                .replace(SCALE, SCALE + " if (skipJitter) return; " + JITTER);
        assertTrue(InlineViewportProjectionAnalyzer.analyze(conditionalJitter, FRAGMENT).isEmpty());
    }

    @Test
    void doesNotGuessAnUnprocessedMacroBranch() {
        assertTrue(InlineViewportProjectionAnalyzer.analyze(
                VERTEX.replace(SCALE, "#if NATIVE_SR\n" + SCALE + "\n#endif\n"), FRAGMENT).isEmpty());
        assertTrue(InlineViewportProjectionAnalyzer.analyze(
                VERTEX, FRAGMENT.replace("color =", "#define FOO 1\ncolor =")).isEmpty());
    }

    @Test
    void rejectsConditionalTransformsReturnsAndEscapingPosition() {
        for (String change : new String[]{
                "if (enabled) " + SCALE,
                "if (enabled) { " + SCALE + " }",
                "for (int i = 0; i < 1; i++) " + SCALE,
                "if (enabled) return; " + SCALE,
                SCALE + " gl_Position.x += 0.1;",
                SCALE + " adjust(gl_Position);",
                SCALE + " gl_Position = vec4(0);",
                SCALE + SCALE
        }) {
            assertTrue(InlineViewportProjectionAnalyzer.analyze(VERTEX.replace(SCALE, change), FRAGMENT).isEmpty(), change);
        }
    }

    @Test
    void uniformNamesAloneAndInactiveCommentedProofAreInsufficient() {
        assertTrue(InlineViewportProjectionAnalyzer.analyze(VERTEX.replace(SCALE, "/*" + SCALE + "*/"), FRAGMENT).isEmpty());
        assertTrue(InlineViewportProjectionAnalyzer.analyze(VERTEX,
                FRAGMENT.replace("discard;", "color = vec4(0);")).isEmpty());
        assertTrue(InlineViewportProjectionAnalyzer.analyze(VERTEX,
                FRAGMENT.replace("greaterThanEqual", "lessThan")).isEmpty());
        assertTrue(InlineViewportProjectionAnalyzer.analyze(VERTEX,
                FRAGMENT.replace("if (any", "if (enabled) if (any")).isEmpty());
    }

    @Test
    void rejectsShadowedUniformsDifferentFormulaAndHelperWrites() {
        assertTrue(InlineViewportProjectionAnalyzer.analyze(
                VERTEX.replace(SCALE, "vec2 viewportScale = vec2(0.5); " + SCALE), FRAGMENT).isEmpty());
        assertTrue(InlineViewportProjectionAnalyzer.analyze(
                VERTEX.replace("uniform vec2 viewportScale;", "vec2 viewportScale;"), FRAGMENT).isEmpty());
        assertTrue(InlineViewportProjectionAnalyzer.analyze(
                VERTEX.replace("(viewportScale - 1.0)", "(viewportScale - 0.5)"), FRAGMENT).isEmpty());
        assertTrue(InlineViewportProjectionAnalyzer.analyze(
                VERTEX.replace(JITTER, JITTER + JITTER), FRAGMENT).isEmpty());
        assertTrue(InlineViewportProjectionAnalyzer.analyze(
                VERTEX + "void change() { gl_Position.x += 1; }", FRAGMENT).isEmpty());
    }

    @Test
    void resolvesArbitrarilyNamedPureHelpersAndTheDiscardReturnForm() {
        String vertex = VERTEX.replace(SCALE, "applyViewport(gl_Position);") + """
                void applyViewport(inout vec4 clip) {
                    clip.xy = clip.xy * viewportScale + (viewportScale - 1.0) * clip.w;
                }
                """;
        String fragment = FRAGMENT.replace(
                "any(greaterThanEqual(gl_FragCoord.xy, viewportSize))", "outside(gl_FragCoord.xy)")
                .replace("discard;", "discard; return;") + """
                bool outside(vec2 pixel) { return any(greaterThanEqual(pixel, viewportSize)); }
                """;
        var analysis = InlineViewportProjectionAnalyzer.analyze(vertex, fragment).orElseThrow();
        assertEquals("viewportScale", analysis.scaleUniform());
        assertEquals("viewportSize", analysis.extentUniform());
        assertTrue(analysis.exactJitter());
        // Names do not select an adapter: rename every helper and formal parameter.
        assertEquals(analysis, InlineViewportProjectionAnalyzer.analyze(
                vertex.replace("applyViewport", "anotherName").replace("clip", "q"),
                fragment.replace("outside", "checkLimit").replace("pixel", "sampleCoord")).orElseThrow());
    }

    @Test
    void followsNestedHelpersAndSubstitutesUniformArgumentsWithoutCapture() {
        String vertex = VERTEX.replace(SCALE, "wrapper(gl_Position);") + """
                void inner(inout vec4 p, vec2 factor) {
                    p.xy = p.xy * factor + (factor - 1.0) * p.w;
                }
                void wrapper(inout vec4 p) { inner(p, viewportScale); }
                """;
        String fragment = FRAGMENT.replace(
                "any(greaterThanEqual(gl_FragCoord.xy, viewportSize))", "wrapper(gl_FragCoord.xy)") + """
                bool inner(vec2 p) { return any(greaterThanEqual(p, viewportSize)); }
                bool wrapper(vec2 p) { return inner(p); }
                """;
        var analysis = InlineViewportProjectionAnalyzer.analyze(vertex, fragment).orElseThrow();
        assertEquals("viewportScale", analysis.scaleUniform());
        assertTrue(analysis.exactJitter());
    }

    @Test
    void opaqueInitialMatrixRetainsViewportWithoutInventingZeroJitter() {
        String vertex = VERTEX.replace(JITTER, "").replace(
                "gl_Position = ftransform();", "gl_Position = gpuProjection * ftransform();");
        var analysis = InlineViewportProjectionAnalyzer.analyze(vertex, FRAGMENT).orElseThrow();
        assertEquals("viewportScale", analysis.scaleUniform());
        assertFalse(analysis.exactJitter());
        assertFalse(InlineViewportProjectionAnalyzer.analyze(VERTEX
                + "vec4 ftransform() { return gpuProjection * gl_Vertex; }", FRAGMENT)
                .orElseThrow().exactJitter());
    }

    @Test
    void callerUniformsCannotBeCapturedByAnotherFormalParameter() {
        String vertex = VERTEX.replace(JITTER, "").replace(SCALE,
                "apply(gl_Position, viewportScale, sampleOffset);") + """
                void apply(inout vec4 p, vec2 sampleOffset, vec2 viewportScale) {
                    p.xy = p.xy * sampleOffset + (sampleOffset - 1.0) * p.w;
                    p.xy += viewportScale * p.w;
                }
                """;
        var analysis = InlineViewportProjectionAnalyzer.analyze(vertex, FRAGMENT).orElseThrow();
        assertEquals("viewportScale", analysis.scaleUniform());
        assertEquals("sampleOffset", analysis.jitterUniform());
        assertFalse(analysis.jitterBeforeScale());
    }

    @Test
    void longDisabledPreprocessorBranchesDoNotCauseQuadraticRenderThreadWork() {
        String emptyBranches = " \t \n".repeat(100_000);
        assertTimeoutPreemptively(java.time.Duration.ofSeconds(3), () -> {
            var analysis = InlineViewportProjectionAnalyzer.analyze(
                    emptyBranches + "#line 10 1\n" + VERTEX,
                    emptyBranches + "#line 20 2\n" + FRAGMENT).orElseThrow();
            assertEquals("viewportScale", analysis.scaleUniform());
        });
    }

    @Test
    void commentRemovalPreservesTokenBoundariesAndDoesNotHideUnresolvedDirectives() {
        assertTrue(InlineViewportProjectionAnalyzer.analyze(
                VERTEX.replace("uniform vec2", "uniform/**/vec2"), FRAGMENT).isPresent());
        assertTrue(InlineViewportProjectionAnalyzer.analyze(VERTEX + "/* unterminated", FRAGMENT).isEmpty());
        assertTrue(InlineViewportProjectionAnalyzer.analyze(
                "/*comment*/ #if UNKNOWN\n" + VERTEX + "\n#endif", FRAGMENT).isEmpty());
    }

    private static String ndcHelper(String jitterStatement) {
        return """
                void transformViewport(inout vec4 p, vec2 delta) {
                    p.xy /= p.w;
                    p.xy = p.xy * viewportScale + viewportScale - 1.0;
                """ + jitterStatement + "p.xy *= p.w; }";
    }

    @Test
    void normalizedCoordinateMathUsesTheSameNameIndependentAnalysis() {
        String source = VERTEX.replace(JITTER, "").replace(SCALE,
                "transformViewport(gl_Position, sampleOffset);") + ndcHelper("p.xy += delta;");
        var analysis = InlineViewportProjectionAnalyzer.analyze(
                source, FRAGMENT.replace("greaterThanEqual", "greaterThan")).orElseThrow();
        assertEquals("viewportScale", analysis.scaleUniform());
        assertEquals("sampleOffset", analysis.jitterUniform());
        assertFalse(analysis.jitterBeforeScale());
        assertTrue(analysis.exactJitter());
        assertEquals(analysis, InlineViewportProjectionAnalyzer.analyze(
                source.replace("transformViewport", "anyOtherName").replace("delta", "sample"),
                FRAGMENT).orElseThrow());
    }

    @Test
    void normalizedCoordinateJitterRetainsItsMathematicalOrder() {
        String source = VERTEX.replace(JITTER, "").replace(SCALE,
                "transformViewport(gl_Position, sampleOffset);") + ndcHelper("p.xy += delta * viewportScale;");
        var analysis = InlineViewportProjectionAnalyzer.analyze(source, FRAGMENT).orElseThrow();
        assertTrue(analysis.jitterBeforeScale());
        assertEquals("sampleOffset", analysis.jitterUniform());
        for (String zero : new String[]{"vec2(0.0)", "vec2(0.0, 0.0)"}) {
            var noJitter = InlineViewportProjectionAnalyzer.analyze(source.replace(
                    "transformViewport(gl_Position, sampleOffset)", "transformViewport(gl_Position, " + zero + ")"),
                    FRAGMENT).orElseThrow();
            assertEquals("", noJitter.jitterUniform());
            assertTrue(noJitter.exactJitter());
        }
    }

    @Test
    void multipleInitialMatrixOperationsKeepScaleButNotAnExactTemporalClaim() {
        String source = VERTEX.replace("gl_Position = ftransform();", """
                gl_Position = gl_ModelViewMatrix * gl_Vertex;
                gl_Position.z += 1e-5;
                gl_Position = gl_ProjectionMatrix * gl_Position;
                """);
        var analysis = InlineViewportProjectionAnalyzer.analyze(source, FRAGMENT).orElseThrow();
        assertEquals("viewportScale", analysis.scaleUniform());
        assertFalse(analysis.exactJitter());
    }

    @Test
    void purePositionCopiesDoNotChangeTheProvenViewport() {
        String source = VERTEX.replace(SCALE, SCALE + """
                vec4 portalCoord = gl_Position * 0.5;
                portalCoord.zw = gl_Position.zw;
                """);
        assertTrue(InlineViewportProjectionAnalyzer.analyze(source, FRAGMENT).isPresent());
        assertTrue(InlineViewportProjectionAnalyzer.analyze(source.replace(
                "portalCoord = gl_Position * 0.5", "portalCoord = mutate(gl_Position)"), FRAGMENT).isEmpty());
        assertTrue(InlineViewportProjectionAnalyzer.analyze(source.replace(
                "portalCoord = gl_Position * 0.5", "portalCoord = gl_Position++"), FRAGMENT).isEmpty());
    }

    @Test
    void rejectsAmbiguousRecursiveConditionalAndStatefulHelpers() {
        String called = VERTEX.replace(SCALE, "applyViewport(gl_Position);");
        String scale = SCALE.replace("gl_Position", "p");
        for (String body : new String[]{"applyViewport(p);", "if (enabled) { " + scale + " }",
                scale + " imageStore(outputImage, ivec2(0), vec4(1));", scale + " p.z *= 0.5;",
                "vec2 viewportScale = vec2(0.5); " + scale}) {
            assertTrue(InlineViewportProjectionAnalyzer.analyze(called
                    + "void applyViewport(inout vec4 p) { " + body + " }", FRAGMENT).isEmpty(), body);
        }
        assertTrue(InlineViewportProjectionAnalyzer.analyze(called
                + "void applyViewport(inout vec4 p) { " + scale + " }"
                + "void applyViewport(inout vec4 p, float s) { " + scale + " }", FRAGMENT).isEmpty());
        String fragment = FRAGMENT.replace(
                "any(greaterThanEqual(gl_FragCoord.xy, viewportSize))", "outside(gl_FragCoord.xy)")
                + "bool outside(vec2 p) { sideEffect(); return any(greaterThanEqual(p, viewportSize)); }";
        assertTrue(InlineViewportProjectionAnalyzer.analyze(VERTEX, fragment).isEmpty());
    }
}
