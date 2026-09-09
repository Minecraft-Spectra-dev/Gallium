package cn.spectra.gallium.glowoutline.shader;

//#if MC==1_21_11 || MC==1_26_01
import cn.spectra.gallium.Gallium;
import com.mojang.blaze3d.pipeline.RenderPipeline;
//#if MC>=1_26_00
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.DepthStencilState;
import com.mojang.blaze3d.platform.CompareOp;
import java.util.Optional;
//#else
//$$ import com.mojang.blaze3d.platform.DepthTestFunction;
//#endif
import com.mojang.blaze3d.shaders.ShaderType;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;

/**
 * Private source-grid visibility / native-coverage bridge for upscaled WORLD replay.
 * Only cells where this item's source-grid geometry passed the original scene depth test
 * may remove that item's self-depth. All other cells keep the unmodified scene depth.
 * Resolve preserves native geometry color/alpha exactly, and exports source-grid depth
 * for the proven-visible cells so the public composite does not repeat the false comparison.
 */
public final class WorldMaskOcclusionPipeline {
    private static final Identifier PREPARE_ID =
            Identifier.fromNamespaceAndPath("gallium", "internal/world_mask_prepare");
    private static final Identifier RESOLVE_ID =
            Identifier.fromNamespaceAndPath("gallium", "internal/world_mask_resolve");
    private static final String VERTEX_SHADER = """
            #version 150
            out vec2 v_uv;
            void main() {
                vec2 p = vec2((gl_VertexID & 1) << 2, (gl_VertexID & 2) << 1);
                v_uv = p * 0.5;
                gl_Position = vec4(p - 1.0, 0.0, 1.0);
            }
            """;
    private static final String PREPARE_SHADER = """
            #version 150
            uniform sampler2D SourceColor;
            uniform sampler2D SceneDepth;
            in vec2 v_uv;
            out vec4 fragColor;
            void main() {
                bool ownVisible = texture(SourceColor, v_uv).a > 0.0;
                gl_FragDepth = ownVisible ? 1.0 : texture(SceneDepth, v_uv).r;
                fragColor = vec4(0.0);
            }
            """;
    private static final String RESOLVE_SHADER = """
            #version 150
            uniform sampler2D NativeColor;
            uniform sampler2D NativeDepth;
            uniform sampler2D SourceColor;
            uniform sampler2D SourceDepth;
            in vec2 v_uv;
            out vec4 fragColor;
            void main() {
                fragColor = texture(NativeColor, v_uv);
                gl_FragDepth = texture(SourceColor, v_uv).a > 0.0
                    ? texture(SourceDepth, v_uv).r : texture(NativeDepth, v_uv).r;
            }
            """;
    private static @Nullable RenderPipeline preparePipeline;
    private static @Nullable RenderPipeline resolvePipeline;
    private static boolean ready;

    static { GlowResources.registerPipeline(WorldMaskOcclusionPipeline::dispose); }
    private WorldMaskOcclusionPipeline() {}

    public static @Nullable String shaderSource(Identifier id, ShaderType type) {
        if (!PREPARE_ID.equals(id) && !RESOLVE_ID.equals(id)) return null;
        return type == ShaderType.VERTEX ? VERTEX_SHADER
                : type == ShaderType.FRAGMENT ? (PREPARE_ID.equals(id) ? PREPARE_SHADER : RESOLVE_SHADER) : null;
    }

    public static void precompile() {
        try {
            if (preparePipeline == null) preparePipeline = create(PREPARE_ID, "SourceColor", "SceneDepth");
            if (resolvePipeline == null) resolvePipeline = create(RESOLVE_ID,
                    "NativeColor", "NativeDepth", "SourceColor", "SourceDepth");
            if (!RenderSystem.getDevice().precompilePipeline(preparePipeline, WorldMaskOcclusionPipeline::shaderSource).isValid()
                    || !RenderSystem.getDevice().precompilePipeline(resolvePipeline, WorldMaskOcclusionPipeline::shaderSource).isValid()) {
                throw new IllegalStateException("world-mask occlusion compile failed");
            }
            ready = true;
        } catch (Throwable failure) {
            ready = false;
            Gallium.LOGGER.error("Failed to compile Gallium world-mask occlusion pipelines", failure);
        }
    }

    private static RenderPipeline create(Identifier id, String... samplers) {
        var builder = RenderPipeline.builder()
                        .withLocation(id)
                        .withVertexShader(id)
                        .withFragmentShader(id)
                        .withCull(false)
                        //#if MC>=1_26_00
                        .withColorTargetState(new ColorTargetState(Optional.empty(), ColorTargetState.WRITE_ALL))
                        .withDepthStencilState(new DepthStencilState(CompareOp.ALWAYS_PASS, true))
                        //#else
                        //$$ .withDepthTestFunction(DepthTestFunction.LEQUAL_DEPTH_TEST)
                        //$$ .withDepthWrite(true)
                        //#endif
                        .withVertexFormat(DefaultVertexFormat.EMPTY, VertexFormat.Mode.TRIANGLES);
        for (String sampler : samplers) builder.withSampler(sampler);
        return builder.build();
    }

    public static boolean prepareNativeDepth(CommandEncoder encoder, GpuTextureView sourceColor,
                                            GpuTextureView sceneDepth, GpuTextureView outputColor,
                                            GpuTextureView outputDepth) {
        if (!isReady() || encoder == null || sourceColor == null || sceneDepth == null
                || outputColor == null || outputDepth == null) return false;
        try (RenderPass pass = encoder.createRenderPass(() -> "Gallium Native Mask Depth",
                outputColor, OptionalInt.empty(), outputDepth, OptionalDouble.of(1.0))) {
            pass.setPipeline(preparePipeline);
            SamplerHelper.bindClampToEdge(pass, "SourceColor", sourceColor, FilterMode.NEAREST);
            SamplerHelper.bindClampToEdge(pass, "SceneDepth", sceneDepth, FilterMode.NEAREST);
            pass.draw(0, 3);
            return true;
        } catch (RuntimeException failure) {
            ready = false;
            Gallium.LOGGER.error("Gallium native-mask depth preparation failed", failure);
            return false;
        }
    }

    public static boolean resolve(CommandEncoder encoder, GpuTextureView sourceColor,
                                  GpuTextureView sourceDepth, GpuTextureView nativeColor,
                                  GpuTextureView nativeDepth, GpuTextureView outputColor,
                                  GpuTextureView outputDepth) {
        if (!isReady() || encoder == null || sourceColor == null || sourceDepth == null
                || nativeColor == null || nativeDepth == null || outputColor == null || outputDepth == null) return false;
        // These private transfers are not occlusion tests. 1.21.11 LEQUAL writes against
        // far depth to avoid a second fixed-point-to-float comparison rejecting a copy.
        try (RenderPass pass = encoder.createRenderPass(() -> "Gallium Native Mask Resolve",
                outputColor, OptionalInt.empty(), outputDepth, OptionalDouble.of(1.0))) {
            pass.setPipeline(resolvePipeline);
            SamplerHelper.bindClampToEdge(pass, "NativeColor", nativeColor, FilterMode.NEAREST);
            SamplerHelper.bindClampToEdge(pass, "NativeDepth", nativeDepth, FilterMode.NEAREST);
            SamplerHelper.bindClampToEdge(pass, "SourceColor", sourceColor, FilterMode.NEAREST);
            SamplerHelper.bindClampToEdge(pass, "SourceDepth", sourceDepth, FilterMode.NEAREST);
            pass.draw(0, 3);
            return true;
        } catch (RuntimeException failure) {
            ready = false;
            Gallium.LOGGER.error("Gallium native-mask resolve failed", failure);
            return false;
        }
    }

    public static boolean isReady() { return ready && preparePipeline != null && resolvePipeline != null; }
    private static void dispose() { ready = false; preparePipeline = null; resolvePipeline = null; }
}
//#else
//$$ public final class WorldMaskOcclusionPipeline {
//$$     private WorldMaskOcclusionPipeline() {}
//$$     public static void precompile() {}
//$$     public static boolean isReady() { return false; }
//$$ }
//#endif
