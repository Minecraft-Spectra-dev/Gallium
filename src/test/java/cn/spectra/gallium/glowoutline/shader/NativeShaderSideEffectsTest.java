package cn.spectra.gallium.glowoutline.shader;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class NativeShaderSideEffectsTest {
    @Test void acceptsOrdinaryRasterMathAndIgnoresComments() {
        assertTrue(NativeShaderSideEffects.hasOnlyRasterOutputs("// framebuffer\nvoid main(){ fragColor=texture(Sampler0,uv)*color; }"));
        assertTrue(NativeShaderSideEffects.hasOnlyRasterOutputs("layout(std140) uniform Transforms { mat4 ModelView; };"));
    }
    @Test void rejectsWritesAndPreprocessorWaysOfHidingThem() {
        for (String source : new String[]{"buffer Data { float a; };", "imageStore(target,pos,vec4(1));",
                "atomicCounterIncrement(counter);", "#define hidden image##Store", "#define hidden image\\\nStore",
                "#extension GL_NV_shader_buffer_store : require", "beginInvocationInterlockARB();"}) {
            assertFalse(NativeShaderSideEffects.hasOnlyRasterOutputs(source), source);
        }
        assertFalse(NativeShaderSideEffects.hasOnlyRasterOutputs(null));
    }
}
