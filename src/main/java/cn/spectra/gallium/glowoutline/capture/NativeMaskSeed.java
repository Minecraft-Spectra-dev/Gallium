package cn.spectra.gallium.glowoutline.capture;

//#if MC==1_26_01
import cn.spectra.gallium.glowoutline.IrisCompat;
import cn.spectra.gallium.glowoutline.shader.GlowResources;
import cn.spectra.gallium.glowoutline.shader.SamplerHelper;
import com.mojang.blaze3d.opengl.GlRenderPipeline;
import com.mojang.blaze3d.opengl.GlStateManager;
import com.mojang.blaze3d.pipeline.*;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.shaders.ShaderType;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
//#if MC>=1_26_00
import com.mojang.blaze3d.platform.CompareOp;
//#else
//$$ import com.mojang.blaze3d.platform.DepthTestFunction;
//#endif
//#if MC>=1_21_11
import net.minecraft.resources.Identifier;
//#else
//$$ import net.minecraft.resources.ResourceLocation;
//#endif
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalDouble;
import org.lwjgl.opengl.*;

/** Replay original float depth values (before fixed-point conversion) and initialize transparent color. */
public final class NativeMaskSeed {
    //#if MC>=1_21_11
    private static final Identifier SHADER = Identifier.fromNamespaceAndPath("gallium", "internal/native_mask_seed");
    //#else
    //$$ private static final ResourceLocation SHADER = cn.spectra.gallium.glowoutline.LegacyResourceIds.create("gallium", "internal/native_mask_seed");
    //#endif
    private static final String VERTEX = """
            #version 430
            void main() {
                vec2 p = vec2((gl_VertexID & 1) << 2, (gl_VertexID & 2) << 1);
                gl_Position = vec4(p - 1.0, 0.0, 1.0);
            }
            """;
    private static final String FRAGMENT = """
            #version 430
            uniform sampler2D Source;
            out vec4 fragColor;
            void main() {
                gl_FragDepth = uintBitsToFloat(packUnorm4x8(texelFetch(Source, ivec2(gl_FragCoord.xy), 0)));
                fragColor = vec4(0.0);
            }
            """;
    private static RenderPipeline pipeline;
    private static boolean unavailable, drawing, applied, depthOverridden;
    private static int program, previousDepthFunction;
    static { GlowResources.registerPipeline(() -> { pipeline = null; unavailable = drawing = applied = depthOverridden = false; }); }
    private NativeMaskSeed() {}

    static boolean preparePipeline() {
        if (unavailable) return false;
        if (pipeline == null) {
            pipeline = RenderPipeline.builder().withLocation("pipeline/gallium_native_mask_seed")
                    .withVertexShader(SHADER).withFragmentShader(SHADER).withSampler("Source")
                    .withCull(false)
                    //#if MC>=1_26_00
                    .withColorTargetState(new ColorTargetState(Optional.empty(), ColorTargetState.WRITE_ALL))
                    .withDepthStencilState(new DepthStencilState(CompareOp.ALWAYS_PASS, true))
                    //#else
                    //$$ .withColorWrite(true, true).withDepthWrite(true)
                    //$$ .withDepthTestFunction(DepthTestFunction.LEQUAL_DEPTH_TEST)
                    //#endif
                    .withVertexFormat(DefaultVertexFormat.EMPTY, VertexFormat.Mode.TRIANGLES).build();
        }
        var compiled = RenderSystem.getDevice().precompilePipeline(pipeline, (id, type) ->
                !SHADER.equals(id) ? null : type == ShaderType.VERTEX ? VERTEX : type == ShaderType.FRAGMENT ? FRAGMENT : null);
        if (!(compiled instanceof GlRenderPipeline gl) || !compiled.isValid()) { unavailable = true; return false; }
        program = gl.program().getProgramId();
        return true;
    }

    static boolean restore(TextureTarget mask, TextureTarget source, int x, int y, int width, int height, boolean partial) {
        if (drawing || source == null || source == mask || !SharedMaskFrame.current()
                || !IrisCompat.isShaderActive() || IrisCompat.isActiveSrRuntime()
                || !IrisFramebufferRoute.nativeRoute()) return false;
        var seed = SharedPooledDepth.seedBits(source);
        if (seed == null) return false;
        try { if (!preparePipeline()) return false; }
        catch (RuntimeException | LinkageError failed) { unavailable = true; return false; }
        int draw = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
        int read = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
        if (GlStateManager.getFrameBuffer(GL30.GL_DRAW_FRAMEBUFFER) != draw
                || GlStateManager.getFrameBuffer(GL30.GL_READ_FRAMEBUFFER) != read) return false;
        try {
            if (partial) {
                // Update the native cache before a native pass, and retain the physical refresh.
                GlStateManager._glBindFramebuffer(GL30.GL_FRAMEBUFFER, 0);
                GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, 0);
            }
            var encoder = RenderSystem.getDevice().createCommandEncoder();
            drawing = true; applied = depthOverridden = false;
            try (var pass = encoder.createRenderPass(() -> "Gallium native mask seed",
                    mask.getColorTextureView(), OptionalInt.empty(), mask.getDepthTextureView(), OptionalDouble.empty())) {
                pass.setPipeline(pipeline);
                pass.enableScissor(x, y, width, height);
                SamplerHelper.bindClampToEdge(pass, "Source", seed, FilterMode.NEAREST);
                pass.draw(0, 3);
                if (!applied) throw new IllegalStateException("Native mask seed draw hook did not execute");
            }
            return true;
        } catch (RuntimeException | LinkageError failed) {
            unavailable = true;
            return false; // The caller repeats the old complete color/depth initialization.
        } finally {
            if (depthOverridden) GlStateManager._depthFunc(previousDepthFunction);
            drawing = depthOverridden = false;
            GlStateManager._glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, draw);
            GlStateManager._glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, read);
            if (partial) ModernMaskReuse.restoreBindings(draw, read);
        }
    }

    /** The old native API lacks ALWAYS; scope the override to this exact linked draw. */
    public static void beforeNativeDraw() {
        if (!drawing) return;
        if (applied || GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM) != program)
            throw new IllegalStateException("Unexpected draw in native mask seed scope");
        //#if MC<1_26_00
        //$$ previousDepthFunction = GL11.glGetInteger(GL11.GL_DEPTH_FUNC);
        //$$ depthOverridden = true;
        //$$ GlStateManager._depthFunc(GL11.GL_ALWAYS);
        //#endif
        applied = true;
    }
}
//#else
//$$ public final class NativeMaskSeed { private NativeMaskSeed() {} }
//#endif
