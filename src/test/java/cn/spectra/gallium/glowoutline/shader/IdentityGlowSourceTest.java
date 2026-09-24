package cn.spectra.gallium.glowoutline.shader;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class IdentityGlowSourceTest {
    private static final String ALIASES = """
            struct GalliumGlowValues { vec4 ShaderAlign; vec4 ShaderOffset; };
            #define ShaderAlign gallium_InternalValues[gallium_InternalInstance].ShaderAlign
            #define ShaderOffset gallium_InternalValues[gallium_InternalInstance].ShaderOffset
            """;

    @Test void specializesOnlyAliasesAndPreservesTheUniformLayoutAndComments() {
        String source = "// #define ShaderAlign unsupported\n" + ALIASES + "/* #undef ShaderOffset */\n";
        String result = IdentityGlowSource.specialize(source);
        assertNotNull(result);
        assertTrue(result.contains("struct GalliumGlowValues { vec4 ShaderAlign; vec4 ShaderOffset; };"));
        assertTrue(result.contains("#define ShaderAlign vec4(1.0)"));
        assertTrue(result.contains("#define ShaderOffset vec4(0.0)"));
        assertTrue(result.startsWith("// #define ShaderAlign unsupported\n"));
        assertTrue(result.endsWith("/* #undef ShaderOffset */\n"));
    }

    @Test void rejectsMissingRedefinedAndComputedAliases() {
        assertNull(IdentityGlowSource.specialize(null));
        assertNull(IdentityGlowSource.specialize(ALIASES.replace(".ShaderAlign", ".ShaderOffset")));
        assertNull(IdentityGlowSource.specialize(ALIASES + "#undef ShaderAlign\n"));
        assertNull(IdentityGlowSource.specialize(ALIASES + "#define ShaderOffset vec4(1.0)\n"));
        assertNull(IdentityGlowSource.specialize(ALIASES.replace(".ShaderAlign", ".ShaderAlign * 2.0")));
        assertNull(IdentityGlowSource.specialize(ALIASES.replace("#define ShaderOffset", "//#define ShaderOffset")));
    }

    @Test void checksActualNativeBytesAtReportedOffsetsWithoutMutatingTheView() {
        var storage = ByteBuffer.allocate(112).order(ByteOrder.nativeOrder());
        for (int i = 0; i < 4; i++) storage.putFloat(24 + i * 4, 1.0f);
        storage.position(8).limit(104);
        var data = storage.asReadOnlyBuffer();
        assertTrue(IdentityGlowSource.matchesUniforms(data, 16, 64));
        assertEquals(8, data.position()); assertEquals(104, data.limit());
        storage.putFloat(28, Float.NaN);
        assertFalse(IdentityGlowSource.matchesUniforms(data, 16, 64));
        storage.putFloat(28, 1.0f).putFloat(80, .0001f);
        assertFalse(IdentityGlowSource.matchesUniforms(data, 16, 64));
        storage.putFloat(80, -0.0f);
        assertFalse(IdentityGlowSource.matchesUniforms(data, 16, 64));
    }

    @Test void rejectsMissingAndOutOfRangeUniformMembers() {
        var data = ByteBuffer.allocate(64);
        assertFalse(IdentityGlowSource.matchesUniforms(null, 0, 16));
        assertFalse(IdentityGlowSource.matchesUniforms(data, -1, 16));
        assertFalse(IdentityGlowSource.matchesUniforms(data, 0, Integer.MAX_VALUE));
        assertFalse(IdentityGlowSource.matchesUniforms(data, 52, 16));
    }
}
