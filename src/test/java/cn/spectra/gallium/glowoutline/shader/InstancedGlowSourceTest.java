package cn.spectra.gallium.glowoutline.shader;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class InstancedGlowSourceTest {
    private static final String VERTEX = "#version 450\nvoid main() { gl_Position = vec4(float(gl_VertexID), 0, 0, 1); }\n";

    @Test void preservesPackGeometryAndRenamesOnlyTheFinalWrapperEntry() {
        String wrapped = WorldGlowShader.wrap(VERTEX, true);
        String adapted = InstancedGlowSource.adapt(wrapped, true);
        assertNotNull(adapted);
        assertTrue(adapted.contains("void main() { gl_Position = vec4(float(gl_VertexID), 0, 0, 1); }"));
        assertTrue(adapted.contains("void gallium_InternalInstanceMain()"));
        assertTrue(adapted.endsWith("    gl_ViewportIndex = gl_InstanceID;\n}\n"));
        assertTrue(adapted.indexOf("#extension") < adapted.indexOf("#define main"));
    }

    @Test void rejectsShadersWhoseExistingInstanceOrViewportSemanticsWouldChange() {
        assertNull(InstancedGlowSource.adapt(VERTEX, true));
        for (String builtin : new String[]{"gl_InstanceID", "gl_ViewportIndex", "gl_Layer", "gl_PrimitiveID", "gl_FragCoord"}) {
            assertNull(InstancedGlowSource.adapt(WorldGlowShader.wrap(VERTEX.replace("gl_VertexID", builtin), true), true));
        }
    }

    @Test void fragmentAdaptationOnlyEnablesTheExplicitPackUniformContract() {
        String wrapped = WorldGlowShader.wrap("#version 450\nvoid main() {}\n", false);
        String adapted = InstancedGlowSource.adapt(wrapped, false);
        assertEquals(wrapped, adapted.replace("\n#define GALLIUM_HAS_MASK_INSTANCES 1\n", ""));
    }
}
