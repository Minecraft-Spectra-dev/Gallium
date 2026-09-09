package cn.spectra.gallium.glowoutline.shader;

//#if MC>=1_21_06
import cn.spectra.gallium.Gallium;
//#if MC>=1_26_00
import com.mojang.blaze3d.platform.CompareOp;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.DepthStencilState;
//#else
//$$ import com.mojang.blaze3d.platform.DepthTestFunction;
//#endif
import com.mojang.blaze3d.pipeline.RenderPipeline;
//#if MC>=1_26_02
//$$ import com.mojang.blaze3d.GpuFormat;
//$$ import com.mojang.blaze3d.PrimitiveTopology;
//$$ import com.mojang.blaze3d.pipeline.BindGroupLayout;
//#endif
import com.mojang.blaze3d.shaders.ShaderType;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuTextureView;
//#if MC<1_26_02
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
//#endif
//#if MC>=1_26_00
import java.util.Optional;
//#endif
import java.util.OptionalDouble;
//#if MC<1_26_02
import java.util.OptionalInt;
//#endif
//#if MC>=1_21_11
import net.minecraft.resources.Identifier;
//#else
//$$ import net.minecraft.resources.ResourceLocation;
//#endif
import org.jspecify.annotations.Nullable;
//#endif

/**
 * Nearest-neighbour depth resampling used by the Super Resolution output-space mask path.
 * Source and destination may have different physical extents.  Sampling through normalized UVs
 * preserves raw forward/reverse-Z values; the destination depth attachment remains in the native
 * convention used by the captured render types.
 */
//#if MC>=1_26_02
//$$ public final class DepthResamplePipeline {
//$$     private static final Identifier SHADER_ID =
//$$             Identifier.fromNamespaceAndPath("gallium", "internal/depth_resample");
//$$     private static final String VERTEX_SHADER = """
//$$             #version 450
//$$             out vec2 v_uv;
//$$             void main() {
//$$                 vec2 p = vec2((gl_VertexID & 1) << 2, (gl_VertexID & 2) << 1);
//$$                 v_uv = p * 0.5;
//$$                 gl_Position = vec4(p - 1.0, 0.0, 1.0);
//$$             }
//$$             """;
//$$     private static final String FRAGMENT_SHADER = """
//$$             #version 450
//$$             uniform sampler2D Source;
//$$             in vec2 v_uv;
//$$             out vec4 fragColor;
//$$             void main() {
//$$                 gl_FragDepth = texture(Source, v_uv).r;
//$$                 fragColor = vec4(0.0);
//$$             }
//$$             """;
//$$     private static @Nullable RenderPipeline pipeline;
//$$     private static boolean ready;
//$$     static { GlowResources.registerPipeline(DepthResamplePipeline::dispose); }
//$$     private DepthResamplePipeline() {}
//$$     public static void precompile() {
//$$         try {
//$$             if (pipeline == null) {
//$$                 pipeline = RenderPipeline.builder()
//$$                         .withLocation("pipeline/gallium_depth_resample")
//$$                         .withVertexShader(SHADER_ID)
//$$                         .withFragmentShader(SHADER_ID)
//$$                         .withBindGroupLayout(BindGroupLayout.builder().withSampler("Source").build())
//$$                         .withColorTargetState(new ColorTargetState(
//$$                                 Optional.empty(), GpuFormat.RGBA8_UNORM, ColorTargetState.WRITE_NONE))
//$$                         .withCull(false)
//$$                         .withDepthStencilState(new DepthStencilState(CompareOp.ALWAYS_PASS, true))
//$$                         .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
//$$                         .build();
//$$             }
//$$             var compiled = RenderSystem.getDevice().precompilePipeline(pipeline, (id, type) -> {
//$$                 if (!SHADER_ID.equals(id)) return null;
//$$                 return type == ShaderType.VERTEX ? VERTEX_SHADER
//$$                         : type == ShaderType.FRAGMENT ? FRAGMENT_SHADER : null;
//$$             });
//$$             if (!compiled.isValid()) throw new IllegalStateException("depth-resample compile failed");
//$$             ready = true;
//$$         } catch (Throwable t) {
//$$             Gallium.LOGGER.error("Failed to compile Gallium depth-resample pipeline", t);
//$$             pipeline = null;
//$$             ready = false;
//$$         }
//$$     }
//$$     public static boolean resample(CommandEncoder encoder, GpuTextureView source,
//$$                                    GpuTextureView destColor, GpuTextureView destDepth) {
//$$         if (!ready || pipeline == null || encoder == null || source == null
//$$                 || destColor == null || destDepth == null) return false;
//$$         try (RenderPass pass = encoder.createRenderPass(
//$$                 () -> "Gallium Depth Resample", destColor, Optional.empty(),
//$$                 destDepth, OptionalDouble.empty())) {
//$$             pass.setPipeline(pipeline);
//$$             SamplerHelper.bindClampToEdge(pass, "Source", source, FilterMode.NEAREST);
//$$             pass.draw(3, 1, 0, 0);
//$$             return true;
//$$         } catch (RuntimeException e) {
//$$             Gallium.LOGGER.error("Gallium depth-resample dispatch failed", e);
//$$             ready = false;
//$$             return false;
//$$         }
//$$     }
//$$     public static boolean isReady() { return ready && pipeline != null; }
//$$     private static void dispose() { pipeline = null; ready = false; }
//$$ }
//#elseif MC>=1_26_00
public final class DepthResamplePipeline {
    private static final Identifier SHADER_ID =
            Identifier.fromNamespaceAndPath("gallium", "internal/depth_resample");
    private static final String VERTEX_SHADER = """
            #version 450
            out vec2 v_uv;
            void main() {
                vec2 p = vec2((gl_VertexID & 1) << 2, (gl_VertexID & 2) << 1);
                v_uv = p * 0.5;
                gl_Position = vec4(p - 1.0, 0.0, 1.0);
            }
            """;
    private static final String FRAGMENT_SHADER = """
            #version 450
            uniform sampler2D Source;
            in vec2 v_uv;
            out vec4 fragColor;
            void main() {
                gl_FragDepth = texture(Source, v_uv).r;
                fragColor = vec4(0.0);
            }
            """;
    private static @Nullable RenderPipeline pipeline;
    private static boolean ready;
    static { GlowResources.registerPipeline(DepthResamplePipeline::dispose); }
    private DepthResamplePipeline() {}
    public static void precompile() {
        try {
            if (pipeline == null) {
                pipeline = RenderPipeline.builder()
                        .withLocation("pipeline/gallium_depth_resample")
                        .withVertexShader(SHADER_ID)
                        .withFragmentShader(SHADER_ID)
                        .withSampler("Source")
                        .withColorTargetState(new ColorTargetState(Optional.empty(), ColorTargetState.WRITE_NONE))
                        .withCull(false)
                        .withDepthStencilState(new DepthStencilState(CompareOp.ALWAYS_PASS, true))
                        .withVertexFormat(DefaultVertexFormat.EMPTY, VertexFormat.Mode.TRIANGLES)
                        .build();
            }
            var compiled = RenderSystem.getDevice().precompilePipeline(pipeline, (id, type) -> {
                if (!SHADER_ID.equals(id)) return null;
                return type == ShaderType.VERTEX ? VERTEX_SHADER
                        : type == ShaderType.FRAGMENT ? FRAGMENT_SHADER : null;
            });
            if (!compiled.isValid()) throw new IllegalStateException("depth-resample compile failed");
            ready = true;
        } catch (Throwable t) {
            Gallium.LOGGER.error("Failed to compile Gallium depth-resample pipeline", t);
            pipeline = null;
            ready = false;
        }
    }
    public static boolean resample(CommandEncoder encoder, GpuTextureView source,
                                   GpuTextureView destColor, GpuTextureView destDepth) {
        if (!ready || pipeline == null || encoder == null || source == null
                || destColor == null || destDepth == null) return false;
        try (RenderPass pass = encoder.createRenderPass(
                () -> "Gallium Depth Resample", destColor, OptionalInt.empty(),
                destDepth, OptionalDouble.empty())) {
            pass.setPipeline(pipeline);
            SamplerHelper.bindClampToEdge(pass, "Source", source, FilterMode.NEAREST);
            pass.draw(0, 3);
            return true;
        } catch (RuntimeException e) {
            Gallium.LOGGER.error("Gallium depth-resample dispatch failed", e);
            ready = false;
            return false;
        }
    }
    public static boolean isReady() { return ready && pipeline != null; }
    private static void dispose() { pipeline = null; ready = false; }
}
//#elseif MC>=1_21_06
//$$ public final class DepthResamplePipeline {
//#if MC>=1_21_11
//$$     private static final Identifier SHADER_ID =
//$$             Identifier.fromNamespaceAndPath("gallium", "internal/depth_resample");
//#else
//$$     private static final ResourceLocation SHADER_ID =
//$$             ResourceLocation.fromNamespaceAndPath("gallium", "internal/depth_resample");
//#endif
//$$     private static final String VERTEX_SHADER = """
//$$             #version 150
//$$             out vec2 v_uv;
//$$             void main() {
//$$                 vec2 p = vec2((gl_VertexID & 1) << 2, (gl_VertexID & 2) << 1);
//$$                 v_uv = p * 0.5;
//$$                 gl_Position = vec4(p - 1.0, 0.0, 1.0);
//$$             }
//$$             """;
//$$     private static final String FRAGMENT_SHADER = """
//$$             #version 150
//$$             uniform sampler2D Source;
//$$             in vec2 v_uv;
//$$             out vec4 fragColor;
//$$             void main() {
//$$                 gl_FragDepth = texture(Source, v_uv).r;
//$$                 fragColor = vec4(0.0);
//$$             }
//$$             """;
//$$     private static @Nullable RenderPipeline pipeline;
//$$     private static boolean ready;
//$$     static { GlowResources.registerPipeline(DepthResamplePipeline::dispose); }
//$$     private DepthResamplePipeline() {}
//$$     public static void precompile() {
//$$         try {
//$$             if (pipeline == null) {
//$$                 pipeline = RenderPipeline.builder()
//$$                         .withLocation("pipeline/gallium_depth_resample")
//$$                         .withVertexShader(SHADER_ID)
//$$                         .withFragmentShader(SHADER_ID)
//$$                         .withSampler("Source")
//$$                         .withCull(false)
//$$                         .withColorWrite(false, false)
//$$                         .withDepthTestFunction(DepthTestFunction.LEQUAL_DEPTH_TEST)
//$$                         .withDepthWrite(true)
//$$                         .withVertexFormat(DefaultVertexFormat.EMPTY, VertexFormat.Mode.TRIANGLES)
//$$                         .build();
//$$             }
//$$             var compiled = RenderSystem.getDevice().precompilePipeline(pipeline, (id, type) -> {
//$$                 if (!SHADER_ID.equals(id)) return null;
//$$                 return type == ShaderType.VERTEX ? VERTEX_SHADER
//$$                         : type == ShaderType.FRAGMENT ? FRAGMENT_SHADER : null;
//$$             });
//$$             if (!compiled.isValid()) throw new IllegalStateException("depth-resample compile failed");
//$$             ready = true;
//$$         } catch (Throwable t) {
//$$             Gallium.LOGGER.error("Failed to compile Gallium depth-resample pipeline", t);
//$$             pipeline = null;
//$$             ready = false;
//$$         }
//$$     }
//$$     public static boolean resample(CommandEncoder encoder, GpuTextureView source,
//$$                                    GpuTextureView destColor, GpuTextureView destDepth) {
//$$         if (!ready || pipeline == null || encoder == null || source == null
//$$                 || destColor == null || destDepth == null) return false;
//$$         try (RenderPass pass = encoder.createRenderPass(
//$$                 () -> "Gallium Depth Resample", destColor, OptionalInt.empty(),
//$$                 destDepth, OptionalDouble.of(1.0))) {
//$$             pass.setPipeline(pipeline);
//$$             SamplerHelper.bindClampToEdge(pass, "Source", source, FilterMode.NEAREST);
//$$             pass.draw(0, 3);
//$$             return true;
//$$         } catch (RuntimeException e) {
//$$             Gallium.LOGGER.error("Gallium depth-resample dispatch failed", e);
//$$             ready = false;
//$$             return false;
//$$         }
//$$     }
//$$     public static boolean isReady() { return ready && pipeline != null; }
//$$     private static void dispose() { pipeline = null; ready = false; }
//$$ }
//#else
//$$ public final class DepthResamplePipeline {
//$$     private DepthResamplePipeline() {}
//$$     public static void precompile() {}
//$$     public static boolean isReady() { return false; }
//$$ }
//#endif
