package cn.spectra.gallium.glowoutline.shader;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class WorldGlowShaderTest {
    @Test
    void onlyPrivateAliasesResolveToWorldResourceSources() {
        assertEquals("core/nested/glow", WorldGlowShader.originalPath("gallium", WorldGlowShader.path("nested/glow")));
        assertNull(WorldGlowShader.originalPath("gallium", "core/glow"));
        assertNull(WorldGlowShader.originalPath("gallium", "core/glow_gui"));
        assertNull(WorldGlowShader.originalPath("gallium", "internal/depth_resample"));
        assertNull(WorldGlowShader.originalPath("minecraft", WorldGlowShader.path("glow")));
        assertNull(WorldGlowShader.originalPath("gallium", WorldGlowShader.path("")));
    }

    @Test
    void wrapperPreservesVersionExtensionsAndEarlyReturns() {
        String source = "#version 150\n#extension GL_ARB_explicit_attrib_location : enable\n"
                + "out vec4 fragColor;\nvoid main(void) { fragColor = vec4(1); return; }\n";
        String wrapped = WorldGlowShader.wrap(source, false);
        assertTrue(wrapped.startsWith("#version 150\n"));
        assertTrue(wrapped.contains("#define main gallium_InternalPackMain"));
        assertTrue(wrapped.contains("#extension GL_ARB_explicit_attrib_location : enable"));
        assertTrue(wrapped.contains("void main(void) { fragColor = vec4(1); return; }"));
        assertTrue(wrapped.lastIndexOf("discard;") < wrapped.lastIndexOf("gallium_InternalPackMain();"));
        assertEquals(wrapped, WorldGlowShader.wrap(wrapped, false));
    }

    @Test
    void commentDirectivesAndBomDoNotMoveCodeAheadOfTheVersion() {
        String source = "\uFEFF/*\n#version 120\n*/\n#version 150\nvoid main() {}";
        String wrapped = WorldGlowShader.wrap(source, true);
        assertFalse(wrapped.startsWith("\uFEFF"));
        assertTrue(wrapped.indexOf("#define main") > wrapped.indexOf("#version 150"));
        assertTrue(wrapped.contains("noperspective out vec2 gallium_InternalScreenUv;"));
        assertTrue(wrapped.contains("gl_Position.xy / gl_Position.w"));
    }

    @Test
    void conditionalEntryPointsRemainUnderTheirOriginalPreprocessorGuards() {
        String source = "#version 150\n#if MODE\nvoid main() {}\n#else\nvoid main() {}\n#endif\n";
        String wrapped = WorldGlowShader.wrap(source, true);
        assertTrue(wrapped.contains(source.substring(source.indexOf("#if"))));
        assertTrue(wrapped.indexOf("#define main") < wrapped.indexOf("#if MODE"));
        assertTrue(wrapped.indexOf("#undef main") > wrapped.indexOf("#endif"));
    }
}
