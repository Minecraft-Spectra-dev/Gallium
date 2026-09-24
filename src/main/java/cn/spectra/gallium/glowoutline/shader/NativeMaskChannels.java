package cn.spectra.gallium.glowoutline.shader;

//#if MC==1_21_11 || MC==1_26_01
import cn.spectra.gallium.glowoutline.capture.ModernMaskBounds;
import cn.spectra.gallium.glowoutline.capture.GlowCaptureState;
import com.mojang.blaze3d.opengl.GlRenderPipeline;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.SourceFactor;
import com.mojang.blaze3d.platform.DestFactor;
import com.mojang.blaze3d.systems.RenderSystem;
import org.lwjgl.opengl.*;
import org.lwjgl.system.MemoryStack;
import java.util.HashMap;

/** Omits only native draws that cannot change any mask channel the effect consumes. */
public final class NativeMaskChannels {
    private static final HashMap<Integer, Boolean> programs = new HashMap<>();
    static { GlowResources.register(programs::clear); }
    private NativeMaskChannels() {}

    public static boolean canOmit(RenderPipeline pipeline) {
        return canOmit(ModernMaskBounds.currentState(), pipeline);
    }

    /** The same channel contract can be checked before entering a native mask draw scope. */
    public static boolean canOmit(GlowCaptureState state, RenderPipeline pipeline) {
        if (state == null || state.config == null || pipeline == null) return false;
        var contract = BoundedGlowContracts.get(state.config.shader());
        if (contract == null || !contract.alphaDepthOnly()) return false;
        //#if MC>=1_26_00
        var depth = pipeline.getDepthStencilState();
        if (depth != null && depth.writeDepth()) return false;
        var color = pipeline.getColorTargetState();
        if (color.writeAlpha()) {
            var blend = color.blendFunction().orElse(null);
        //#else
        //$$ if (pipeline.isWriteDepth() || pipeline.getColorLogic()!=com.mojang.blaze3d.platform.LogicOp.NONE) return false;
        //$$ if (pipeline.isWriteAlpha()) {
        //$$     var blend = pipeline.getBlendFunction().orElse(null);
        //#endif
            if (blend == null || blend.sourceAlpha() != SourceFactor.ZERO || blend.destAlpha() != DestFactor.ONE) return false;
            if (GL11.glGetInteger(GL20.GL_BLEND_EQUATION_ALPHA) != GL14.GL_FUNC_ADD) return false;
        }
        if (!(RenderSystem.getDevice().precompilePipeline(pipeline) instanceof GlRenderPipeline gl) || !gl.isValid()) return false;
        int program = gl.program().getProgramId();
        if (programs.size() >= 128 && !programs.containsKey(program)) programs.clear();
        return programs.computeIfAbsent(program, NativeMaskChannels::hasOnlyRasterOutputs);
    }

    private static boolean hasOnlyRasterOutputs(int program) {
        if (program <= 0 || GL20.glGetProgrami(program, GL20.GL_ATTACHED_SHADERS) != 2) return false;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            var count = stack.mallocInt(1); var shaders = stack.mallocInt(2);
            GL20.glGetAttachedShaders(program, count, shaders);
            if (count.get(0) != 2) return false;
            boolean vertex = false, fragment = false;
            for (int i = 0; i < 2; i++) {
                int shader = shaders.get(i), type = GL20.glGetShaderi(shader, GL20.GL_SHADER_TYPE);
                if (type == GL20.GL_VERTEX_SHADER) vertex = true;
                else if (type == GL20.GL_FRAGMENT_SHADER) fragment = true;
                else return false;
                if (!NativeShaderSideEffects.hasOnlyRasterOutputs(GL20.glGetShaderSource(shader))) return false;
            }
            return vertex && fragment;
        }
    }
}
//#else
//$$ public final class NativeMaskChannels { private NativeMaskChannels() {} }
//#endif
