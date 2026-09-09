package cn.spectra.gallium.glowoutline.sr.runtime;

import java.util.List;
import org.junit.jupiter.api.Test;

import static cn.spectra.gallium.glowoutline.sr.runtime.FsrTemporalJitterAnalyzer.Kind.AMBIGUOUS;
import static cn.spectra.gallium.glowoutline.sr.runtime.FsrTemporalJitterAnalyzer.Kind.NONE;
import static cn.spectra.gallium.glowoutline.sr.runtime.FsrTemporalJitterAnalyzer.Kind.UNIFORM;
import static cn.spectra.gallium.glowoutline.sr.runtime.FsrTemporalJitterAnalyzer.Kind.ZERO;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FsrTemporalJitterAnalyzerTest {

    @Test
    void classifiesOneUniformAndIgnoresTheFunctionDeclaration() {
        var result = FsrTemporalJitterAnalyzer.analyzeProgram(sourceWithMain(
                "FsrScaleVS(gl_Position, taaJitter);"));

        assertEquals(UNIFORM, result.kind());
        assertEquals("taaJitter", result.uniformName());
        assertEquals(1, result.callCount());
        assertTrue(result.exactCandidate());
    }

    @Test
    void commentsDoNotCreateAnEntitiesZeroBranch() {
        String source = sourceWithMain("""
                FsrScaleVS(gl_Position, taaJitter);
                /*
                  if (hasColorWheel) FsrScaleVS(gl_Position, vec2(0.0));
                */
                // FsrScaleVS(gl_Position, otherJitter);
                """);

        var result = FsrTemporalJitterAnalyzer.analyzeProgram(source);

        assertEquals(UNIFORM, result.kind());
        assertEquals("taaJitter", result.uniformName());
        assertEquals(1, result.callCount());
    }

    @Test
    void recognizesCommonLiteralZeroVectorForms() {
        for (String zero : List.of(
                "vec2(0)", "vec2(0.0)", "vec2(0.0f)",
                "vec2(0, 0.0)", "vec2((+0.0), (-0))")) {
            var result = FsrTemporalJitterAnalyzer.analyzeProgram(
                    sourceWithMain("FsrScaleVS(gl_Position, " + zero + ");"));
            assertEquals(ZERO, result.kind(), zero);
            assertEquals(1, result.callCount(), zero);
            assertTrue(result.exactCandidate(), zero);
        }
    }

    @Test
    void repeatedCallsUsingTheSameUniformRemainExact() {
        String source = sourceWithMain("""
                if (leftEye) FsrScaleVS(gl_Position, taaJitter);
                else FsrScaleVS((gl_Position), (taaJitter));
                """);

        var result = FsrTemporalJitterAnalyzer.analyzeProgram(source);

        assertEquals(UNIFORM, result.kind());
        assertEquals("taaJitter", result.uniformName());
        assertEquals(2, result.callCount());
    }

    @Test
    void runtimeBranchMixingUniformAndZeroIsAmbiguous() {
        String source = sourceWithMain("""
                if (disableJitter) FsrScaleVS(gl_Position, vec2(0.0));
                else FsrScaleVS(gl_Position, taaJitter);
                """);

        var result = FsrTemporalJitterAnalyzer.analyzeProgram(source);

        assertEquals(AMBIGUOUS, result.kind());
        assertEquals(2, result.callCount());
        assertFalse(result.exactCandidate());
    }

    @Test
    void differentUniformsAndExpressionsAreAmbiguous() {
        var different = FsrTemporalJitterAnalyzer.analyzeProgram(sourceWithMain("""
                FsrScaleVS(gl_Position, taaJitter);
                FsrScaleVS(gl_Position, handJitter);
                """));
        var expression = FsrTemporalJitterAnalyzer.analyzeProgram(sourceWithMain(
                "FsrScaleVS(gl_Position, taaJitter * fsrRenderScale);"));

        assertEquals(AMBIGUOUS, different.kind());
        assertEquals(AMBIGUOUS, expression.kind());
    }

    @Test
    void callsForAnotherPositionDoNotAffectTheResult() {
        String source = sourceWithMain("""
                FsrScaleVS(shadowPosition, vec2(0.0));
                FsrScaleVS(gl_Position, taaJitter);
                """);

        var result = FsrTemporalJitterAnalyzer.analyzeProgram(source);

        assertEquals(UNIFORM, result.kind());
        assertEquals("taaJitter", result.uniformName());
        assertEquals(1, result.callCount());
    }

    @Test
    void declarationOnlyAndEmptySourcesHaveNoCall() {
        String declarationOnly = """
                void FsrScaleVS(inout vec4 position, vec2 jitter) {
                    position.xy += jitter;
                }
                """;

        assertEquals(NONE,
                FsrTemporalJitterAnalyzer.analyzeProgram(declarationOnly).kind());
        assertEquals(NONE,
                FsrTemporalJitterAnalyzer.analyzeProgram("").kind());
        assertEquals(NONE,
                FsrTemporalJitterAnalyzer.analyzeProgram(null).kind());
    }

    @Test
    void malformedTargetCallFailsClosed() {
        var result = FsrTemporalJitterAnalyzer.analyzeProgram(
                "void main() { FsrScaleVS(gl_Position, taaJitter;");

        assertEquals(AMBIGUOUS, result.kind());
        assertEquals(1, result.callCount());
    }

    @Test
    void worldItemConsensusAcceptsOnlyOneSharedUniformOrAllZero() {
        String entities = sourceWithMain("FsrScaleVS(gl_Position, taaJitter);");
        String item = sourceWithMain("FsrScaleVS(gl_Position, taaJitter);");
        String glint = sourceWithMain("FsrScaleVS(gl_Position, taaJitter);");
        String zero = sourceWithMain("FsrScaleVS(gl_Position, vec2(0.0));");

        var uniformConsensus = FsrTemporalJitterAnalyzer.consensusSources(
                List.of(entities, item, glint));
        var zeroConsensus = FsrTemporalJitterAnalyzer.consensusSources(
                List.of(zero, zero));
        var mixedConsensus = FsrTemporalJitterAnalyzer.consensusSources(
                List.of(entities, zero));

        assertEquals(UNIFORM, uniformConsensus.kind());
        assertEquals("taaJitter", uniformConsensus.uniformName());
        assertEquals(3, uniformConsensus.callCount());
        assertEquals(ZERO, zeroConsensus.kind());
        assertEquals(AMBIGUOUS, mixedConsensus.kind());
    }

    @Test
    void relevantProgramWithoutACallMakesNonEmptyConsensusAmbiguous() {
        var result = FsrTemporalJitterAnalyzer.consensusSources(List.of(
                sourceWithMain("FsrScaleVS(gl_Position, taaJitter);"),
                "void main() { gl_Position = vec4(0.0); }"));

        assertEquals(AMBIGUOUS, result.kind());
        assertEquals(1, result.callCount());
    }

    @Test
    void allMissingOrNoProgramsProduceNone() {
        assertEquals(NONE, FsrTemporalJitterAnalyzer.consensusSources(
                List.of("void main() {}", "void main() {}" )).kind());
        assertEquals(NONE, FsrTemporalJitterAnalyzer.consensusSources(List.of()).kind());
    }

    @Test
    void quotedPreprocessorTextCannotForgeACall() {
        String source = """
                #line 1 "FsrScaleVS(gl_Position, fakeJitter);"
                void main() { FsrScaleVS(gl_Position, taaJitter); }
                """;

        var result = FsrTemporalJitterAnalyzer.analyzeProgram(source);

        assertEquals(UNIFORM, result.kind());
        assertEquals("taaJitter", result.uniformName());
        assertEquals(1, result.callCount());
    }

    private static String sourceWithMain(String mainBody) {
        return """
                void FsrScaleVS(inout vec4 position, vec2 jitter) {
                    position.xy /= position.w;
                    position.xy = position.xy * fsrRenderScale + fsrRenderScale - 1.0;
                    position.xy += jitter;
                    position.xy *= position.w;
                }
                void main() {
                %s
                }
                """.formatted(mainBody);
    }
}
