package cn.spectra.gallium.glowoutline.shader;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class VertexPositionProofTest {
    private static final String DEFAULT = """
            #version 150
            in vec3 Position;
            uniform mat4 ProjMat;
            uniform mat4 ModelViewMat;
            void main() { gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0); }
            """;

    private static final String BLOCKS = DEFAULT.replace("uniform mat4 ProjMat;", """
            layout(std140) uniform Projection { mat4 ProjMat; };
            """).replace("uniform mat4 ModelViewMat;", """
            layout(std140) uniform DynamicTransforms {
                mat4 ModelViewMat; vec4 ColorModulator; vec3 ModelOffset; mat4 TextureMat;
            };
            """);

    @Test void recognizesNativeMatrixBlocksWithoutTrustingArbitraryLayouts() {
        assertTrue(VertexPositionProof.matchesUniformBlocks(BLOCKS));
        assertTrue(VertexPositionProof.matchesUniformBlocks(BLOCKS.replace("mat4 TextureMat;", "mat4 TextureMat; float LineWidth;")));
        assertFalse(VertexPositionProof.matchesUniformBlocks(DEFAULT));
        assertFalse(VertexPositionProof.matchesUniformBlocks(BLOCKS.replace("std140", "shared")));
        assertFalse(VertexPositionProof.matchesUniformBlocks(BLOCKS.replace("mat4 ProjMat;", "mat4 Other; mat4 ProjMat;")));
        assertFalse(VertexPositionProof.matchesUniformBlocks(BLOCKS.replace("};", "} instance;")));
        assertFalse(VertexPositionProof.matchesUniformBlocks("#define Projection Other\n" + BLOCKS));
        assertFalse(VertexPositionProof.matchesUniformBlocks(BLOCKS.replace("vec4(Position, 1.0)", "vec4(Position + ModelOffset, 1.0)")));
        assertFalse(VertexPositionProof.matchesUniformBlocks(BLOCKS.replace("void main() {", "void main() { mat4 ModelViewMat = mat4(1.0);")));
    }

    @Test void recognizesTheActualTransformAndIgnoresComments() {
        assertTrue(VertexPositionProof.matches(DEFAULT));
        assertTrue(VertexPositionProof.matches("// gl_Position = displaced;\n#define FOG_GLSL\n" + DEFAULT));
        assertTrue(VertexPositionProof.matches("#define MINECRAFT_LIGHT_POWER (0.6)\n" + DEFAULT));
    }

    @Test void baseVertexRequiresAttributeOnlyAddressingEvenOutsideThePositionExpression() {
        assertTrue(VertexPositionProof.supportsBaseVertex(BLOCKS));
        assertTrue(VertexPositionProof.supportsBaseVertex("// gl_VertexID\n" + BLOCKS));
        assertFalse(VertexPositionProof.supportsBaseVertex(BLOCKS.replace("; }", "; color.r = float(gl_VertexID); }")));
        assertFalse(VertexPositionProof.supportsBaseVertex(BLOCKS.replace("; }", "; color.r = float(gl_BaseVertexARB); }")));
    }

    @Test void rejectsDisplacementShadowedInputsAndAlternateViewports() {
        assertFalse(VertexPositionProof.matches(DEFAULT.replace("; }", "; gl_Position.x += 1.0; }")));
        assertFalse(VertexPositionProof.matches(DEFAULT.replace("void main() {", "void main() { vec3 Position = vec3(100.0);")));
        assertFalse(VertexPositionProof.matches(DEFAULT.replace("void main() {", "void main() { mat4 ProjMat = mat4(1.0);")));
        assertFalse(VertexPositionProof.matches(DEFAULT.replace("; }", "; gl_ViewportIndex = 1; }")));
    }

    @Test void doesNotTrustMacroExpansionOrUnrecognizedPositionExpressions() {
        assertFalse(VertexPositionProof.matches("#define Position displaced\n" + DEFAULT));
        assertFalse(VertexPositionProof.matches("#define LOCAL_TYPE vec3\n" + DEFAULT));
        assertFalse(VertexPositionProof.matches(DEFAULT.replace("vec4(Position, 1.0)", "vec4(Position + offset, 1.0)")));
        assertFalse(VertexPositionProof.matches("#if 0\n" + DEFAULT + "\n#endif"));
        assertFalse(VertexPositionProof.matches(DEFAULT.replace("void main() {", "void main() { if (flag)")));
        assertFalse(VertexPositionProof.matches(null));
    }
}
