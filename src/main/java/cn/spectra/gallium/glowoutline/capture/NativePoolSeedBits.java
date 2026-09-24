package cn.spectra.gallium.glowoutline.capture;
//#if MC==1_26_01
import cn.spectra.gallium.glowoutline.shader.*;
import com.mojang.blaze3d.opengl.GlTexture;
import com.mojang.blaze3d.pipeline.*;
import com.mojang.blaze3d.systems.*;
import com.mojang.blaze3d.shaders.ShaderType;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.vertex.*;
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
import java.util.*;
import org.lwjgl.opengl.*;

/** Preserve the original pool shader's float bits in its otherwise unused RGBA8 color texture. */
final class NativePoolSeedBits {
    //#if MC>=1_21_11
    private static final Identifier SOURCE = Identifier.fromNamespaceAndPath("gallium", "internal/depth_minpool");
    private static final Identifier SHADER = Identifier.fromNamespaceAndPath("gallium", "internal/pooled_seed_bits");
    //#else
    //$$ private static final ResourceLocation SOURCE = cn.spectra.gallium.glowoutline.LegacyResourceIds.create("gallium", "internal/depth_minpool");
    //$$ private static final ResourceLocation SHADER = cn.spectra.gallium.glowoutline.LegacyResourceIds.create("gallium", "internal/pooled_seed_bits");
    //#endif
    private static RenderPipeline pipeline;
    private static String vertex, fragment;
    private static boolean unavailable;
    static { GlowResources.registerPipeline(() -> { pipeline = null; vertex = fragment = null; unavailable = false; }); }
    private NativePoolSeedBits() {}

    private static boolean prepare() {
        if (unavailable || !NativeMaskSeed.preparePipeline()) return false;
        if (pipeline == null) {
            vertex = DepthMinPoolPipeline.shaderSource(SOURCE, ShaderType.VERTEX);
            fragment = DepthMinPoolPipeline.shaderSource(SOURCE, ShaderType.FRAGMENT);
            String assignment = "gl_FragDepth = m;";
            if (vertex == null || fragment == null || fragment.indexOf(assignment) < 0
                    || fragment.indexOf(assignment) != fragment.lastIndexOf(assignment)) return false;
            vertex = vertex.replaceFirst("#version[^\r\n]*", "#version 430");
            fragment = fragment.replaceFirst("#version[^\r\n]*", "#version 430\nlayout(rgba8ui, binding=0) writeonly uniform uimage2D PooledSeedBits;");
            fragment = fragment.replace(assignment, assignment + "\nuint bits = floatBitsToUint(m);\nimageStore(PooledSeedBits, ivec2(gl_FragCoord.xy), uvec4(bits & 255u, (bits >> 8u) & 255u, (bits >> 16u) & 255u, bits >> 24u));");
            pipeline = RenderPipeline.builder().withLocation("pipeline/gallium_pooled_seed_bits")
                    .withVertexShader(SHADER).withFragmentShader(SHADER).withSampler("Source").withCull(false)
                    //#if MC>=1_26_00
                    .withColorTargetState(new ColorTargetState(Optional.empty(), ColorTargetState.WRITE_NONE))
                    .withDepthStencilState(new DepthStencilState(CompareOp.ALWAYS_PASS, true))
                    //#else
                    //$$ .withColorWrite(false, false).withDepthWrite(true)
                    //$$ .withDepthTestFunction(DepthTestFunction.LEQUAL_DEPTH_TEST)
                    //#endif
                    .withVertexFormat(DefaultVertexFormat.EMPTY, VertexFormat.Mode.TRIANGLES).build();
        }
        return RenderSystem.getDevice().precompilePipeline(pipeline, (id, type) -> !SHADER.equals(id) ? null
                : type == ShaderType.VERTEX ? vertex : type == ShaderType.FRAGMENT ? fragment : null).isValid();
    }

    static boolean pool(CommandEncoder encoder, TextureTarget source, TextureTarget target) {
        if (source == target || !(target.getColorTexture() instanceof GlTexture bits)
                || (!GL.getCapabilities().OpenGL42 && !GL.getCapabilities().GL_ARB_shader_image_load_store)
                || !IrisFramebufferRoute.nativeRoute()) return false;
        try {
            if (!prepare()) return false;
            try (var image = new NativeVisibilityBindings.Image(0, bits.glId(), GL15.GL_WRITE_ONLY, GL30.GL_RGBA8UI);
                 // Keep the image-store destination separate from framebuffer attachments.
                 var pass = encoder.createRenderPass(() -> "Gallium pooled depth and seed bits",
                         source.getColorTextureView(), OptionalInt.empty(), target.getDepthTextureView(), OptionalDouble.of(1.0))) {
                pass.setPipeline(pipeline);
                SamplerHelper.bindClampToEdge(pass, "Source", source.getDepthTextureView(), FilterMode.NEAREST);
                pass.draw(0, 3);
            }
            GL42.glMemoryBarrier(GL42.GL_TEXTURE_FETCH_BARRIER_BIT);
            return true;
        } catch (RuntimeException | LinkageError failed) { unavailable = true; return false; }
    }
}
//#else
//$$ final class NativePoolSeedBits { private NativePoolSeedBits() {} }
//#endif
