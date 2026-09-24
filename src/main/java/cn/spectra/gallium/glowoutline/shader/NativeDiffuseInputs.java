package cn.spectra.gallium.glowoutline.shader;

//#if MC>=1_21_06 && MC<1_26_02
import cn.spectra.gallium.glowoutline.IrisCompat;
import cn.spectra.gallium.glowoutline.capture.GlowCaptureState;
import com.mojang.blaze3d.opengl.GlRenderPipeline;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import org.lwjgl.opengl.*;
import org.lwjgl.system.MemoryStack;

/** Proves that a complete ordinary sequence cannot observe its scene-color snapshot. */
final class NativeDiffuseInputs {
    private static final IdentityHashMap<Object, Boolean> proofs = new IdentityHashMap<>();
    private static final Set<String> otherSamplers = Set.of("MaskSampler", "MaskDepthSampler",
            "SceneDepthSampler", WorldGlowShader.FOREGROUND_SAMPLER, "GalliumMaskBaseDepthSampler");

    static { GlowResources.register(proofs::clear); }

    private NativeDiffuseInputs() {}

    static boolean independent(List<GlowCaptureState> states) {
        if (states.isEmpty() || IrisCompat.isShaderActive() || IrisCompat.isActiveSrRuntime()) return false;
        String previous = null;
        try {
            for (var state : states) {
                if (state.config == null || state.guiEntity != null || state.superResolutionPrepared) return false;
                String name = state.config.shader();
                if (!name.equals(previous) && !NativeGlowInstances.diffuseIndependent(name)) return false;
                previous = name;
            }
            return true;
        } catch (RuntimeException | LinkageError unavailable) {
            return false;
        }
    }

    static boolean independent(GlRenderPipeline pipeline) {
        if (!pipeline.isValid()) return false;
        // Program IDs may be reused after reload; the engine's program object may not.
        return proofs.computeIfAbsent(pipeline.program(), ignored -> inspect(pipeline.program().getProgramId()));
    }

    private static boolean inspect(int program) {
        if (GL20.glGetUniformLocation(program, "DiffuseSampler") >= 0) return false;
        int count = GL20.glGetProgrami(program, GL20.GL_ACTIVE_UNIFORMS);
        try (var stack = MemoryStack.stackPush()) {
            var size = stack.mallocInt(1);
            var type = stack.mallocInt(1);
            for (int i = 0; i < count; i++) {
                String name = GL20.glGetActiveUniform(program, i, size, type);
                switch (type.get(0)) {
                    case GL11.GL_FLOAT, GL11.GL_INT, GL11.GL_UNSIGNED_INT,
                         GL20.GL_FLOAT_VEC2, GL20.GL_FLOAT_VEC3, GL20.GL_FLOAT_VEC4,
                         GL20.GL_INT_VEC2, GL20.GL_INT_VEC3, GL20.GL_INT_VEC4,
                         GL30.GL_UNSIGNED_INT_VEC2, GL30.GL_UNSIGNED_INT_VEC3, GL30.GL_UNSIGNED_INT_VEC4,
                         GL20.GL_BOOL, GL20.GL_BOOL_VEC2, GL20.GL_BOOL_VEC3, GL20.GL_BOOL_VEC4,
                         GL20.GL_FLOAT_MAT2, GL20.GL_FLOAT_MAT3, GL20.GL_FLOAT_MAT4 -> {}
                    case GL20.GL_SAMPLER_2D -> {
                        if (size.get(0) != 1 || !otherSamplers.contains(name)) return false;
                    }
                    // Unknown samplers and image bindings might alias the scene-color texture.
                    default -> { return false; }
                }
            }
        }
        return true;
    }
}
//#else
//$$ final class NativeDiffuseInputs { private NativeDiffuseInputs() {} }
//#endif
