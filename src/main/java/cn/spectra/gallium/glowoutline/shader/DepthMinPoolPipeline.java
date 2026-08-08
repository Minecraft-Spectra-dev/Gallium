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
 * TAA-jitter compensation for the world-glow mask-depth pre-fill on Iris.
 *
 * <p>Problem: the composite-time mask replay runs under {@code IrisCompat.setBypass(true)}
 * (vanilla shaders) so it reliably writes to the mask, while the active shader pack may apply a
 * temporal projection offset to the <em>scene</em> depth we pre-fill the mask with. At silhouette
 * boundary pixels that mismatch makes the replay's un-jittered {@code itemDepth} PASS/FAIL
 * LEQUAL inconsistently, so the mask <em>color</em> shimmers -> outline "waves like water".
 *
 * <p>Fix: instead of {@code copyTextureToTexture(sceneDepth -> mask.depth)} (which copies the
 * jittered depth verbatim), run this full-screen pass that samples {@code sceneDepth} over a 3x3
 * neighbourhood and writes the <em>farthest</em> neighbour (MAX on forward-Z, the most permissive
 * value for LEQUAL) as {@code gl_FragDepth} into {@code mask.depth}. The replay's un-jittered
 * {@code itemDepth} is then {@code <=} the farthest-neighbour depth at every boundary/interior
 * pixel, so LEQUAL passes stably every frame and the mask color silhouette is solid. Occlusion is
 * preserved because items significantly behind a wall still fail LEQUAL against the wall's pooled
 * depth. No z-bias is needed (z-bias breaks wall-occlusion at the exact NDC-z border).
 *
 * <p>On 26.x the pipeline declares {@code depth-test=ALWAYS_PASS + writeDepth=true}; on
 * 1.21.6-1.21.11, whose pipeline enum has no ALWAYS value, the render pass clears the destination
 * depth to forward-Z far ({@code 1.0}) and uses {@code LEQUAL + writeDepth}. Every pooled value is
 * in {@code [0,1]}, so every full-screen fragment still passes. The color attachment is a dummy
 * ({@code mask.colorView}) only to satisfy {@code createRenderPass}'s color-view contract - nothing
 * is written to it (color writes disabled, no clear). Reading from
 * {@code sceneDepthTarget} (already a copy of {@code srcDepth}) avoids a read-from-and-write-to
 * {@code mask.depth} hazard on the same texture. Capture runs this pass once, then copies the
 * resulting depth texture to the remaining masks so the cost does not scale with item count.
 *
 * <h2>Version scope</h2>
 * Active on 1.21.6-1.21.11, 26.1, and 26.2's Iris/OpenGL forward-Z compatibility path. All use
 * MAX-pooled forward-Z depth for the replay's LEQUAL test; native 26.2 reverse-Z and Vulkan skip
 * the pool.
 *
 * <p>Gallium deliberately does not infer an exact offset from shader-pack custom-uniform names.
 * Their names, enable conditions, and placement relative to internal-resolution scaling are not
 * standardized; variable presence alone is therefore not enough to reproduce the pack transform.
 */
//#if MC>=1_26_02
//$$ public final class DepthMinPoolPipeline {
//$$     private static final Identifier SHADER_ID =
//$$             Identifier.fromNamespaceAndPath("gallium", "internal/depth_minpool");
//$$
//$$     private static final String VERTEX_SHADER = """
//$$             #version 450
//$$
//$$             void main() {
//$$                 vec2 p = vec2((gl_VertexID & 1) << 2, (gl_VertexID & 2) << 1);
//$$                 gl_Position = vec4(p - 1.0, 0.0, 1.0);
//$$             }
//$$             """;
//$$
//$$     /** Iris 1.11.x restores forward-Z on OpenGL while a pack is active. MAX is therefore
//$$      *  the farthest neighbour and the most permissive value for the replay's LEQUAL. */
//$$     private static final String FRAGMENT_SHADER = """
//$$             #version 450
//$$
//$$             uniform sampler2D Source;
//$$
//$$             out vec4 fragColor;
//$$
//$$             void main() {
//$$                 ivec2 size = textureSize(Source, 0);
//$$                 ivec2 pixel = clamp(ivec2(gl_FragCoord.xy), ivec2(0), size - 1);
//$$                 float m = 0.0;
//$$                 for (int y = -1; y <= 1; y++) {
//$$                     for (int x = -1; x <= 1; x++) {
//$$                         ivec2 samplePixel = clamp(pixel + ivec2(x, y), ivec2(0), size - 1);
//$$                         m = max(m, texelFetch(Source, samplePixel, 0).r);
//$$                     }
//$$                 }
//$$                 gl_FragDepth = m;
//$$                 fragColor = vec4(0.0);
//$$             }
//$$             """;
//$$
//$$     private static @Nullable RenderPipeline pipeline;
//$$     private static boolean ready;
//$$
//$$     static {
//$$         GlowResources.registerPipeline(DepthMinPoolPipeline::dispose);
//$$     }
//$$
//$$     private DepthMinPoolPipeline() {}
//$$
//$$     public static void precompile() {
//$$         try {
//$$             if (pipeline == null) {
//$$                 pipeline = RenderPipeline.builder()
//$$                     .withLocation("pipeline/gallium_depth_minpool")
//$$                     .withVertexShader(SHADER_ID)
//$$                     .withFragmentShader(SHADER_ID)
//$$                     .withBindGroupLayout(BindGroupLayout.builder().withSampler("Source").build())
//$$                     .withColorTargetState(new ColorTargetState(
//$$                             Optional.empty(), GpuFormat.RGBA8_UNORM, ColorTargetState.WRITE_NONE))
//$$                     .withCull(false)
//$$                     .withDepthStencilState(new DepthStencilState(CompareOp.ALWAYS_PASS, true))
//$$                     .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
//$$                     .build();
//$$             }
//$$
//$$             // ShaderManager clears the device cache on every resource reload. Always seed
//$$             // it again with the in-memory source even when our pipeline object is unchanged.
//$$             var compiled = RenderSystem.getDevice().precompilePipeline(pipeline, (id, type) -> {
//$$                 if (!SHADER_ID.equals(id)) return null;
//$$                 return type == ShaderType.VERTEX ? VERTEX_SHADER
//$$                      : type == ShaderType.FRAGMENT ? FRAGMENT_SHADER
//$$                      : null;
//$$             });
//$$             if (!compiled.isValid()) {
//$$                 throw new IllegalStateException("Depth-min-pool pipeline compilation failed");
//$$             }
//$$             ready = true;
//$$             Gallium.LOGGER.info(
//$$                     "Compiled gallium depth-min-pool pipeline (26.2 Iris forward-Z MAX).");
//$$         } catch (Throwable t) {
//$$             Gallium.LOGGER.error(
//$$                     "Failed to compile depth-min-pool pipeline; TAA jitter compensation disabled", t);
//$$             pipeline = null;
//$$             ready = false;
//$$         }
//$$     }
//$$
//$$     public static boolean isReady() {
//$$         return ready && pipeline != null;
//$$     }
//$$
//$$     public static boolean pool(
//$$             CommandEncoder encoder,
//$$             GpuTextureView srcDepthView,
//$$             GpuTextureView destColorView,
//$$             GpuTextureView destDepthView) {
//$$         if (!isReady() || encoder == null || srcDepthView == null || destColorView == null || destDepthView == null) {
//$$             return false;
//$$         }
//$$
//$$         try {
//$$             try (RenderPass pass = encoder.createRenderPass(
//$$                     () -> "Gallium DepthMinPool", destColorView, Optional.empty(),
//$$                     destDepthView, OptionalDouble.empty())) {
//$$                 pass.setPipeline(pipeline);
//$$                 SamplerHelper.bindClampToEdge(pass, "Source", srcDepthView, FilterMode.NEAREST);
//$$                 pass.draw(3, 1, 0, 0);
//$$             }
//$$             return true;
//$$         } catch (RuntimeException e) {
//$$             Gallium.LOGGER.error(
//$$                     "Depth-min-pool dispatch failed; falling back to raw scene depth", e);
//$$             pipeline = null;
//$$             ready = false;
//$$             return false;
//$$         }
//$$     }
//$$
//$$     private static void dispose() {
//$$         pipeline = null;
//$$         ready = false;
//$$     }
//$$ }
//#elseif MC>=1_26_00
public final class DepthMinPoolPipeline {

    /** Arbitrary identifier; its only role is to key the GlDevice shader cache. */
    private static final Identifier SHADER_ID =
            Identifier.fromNamespaceAndPath("gallium", "internal/depth_minpool");

    /** Attribute-less full-screen triangle, identical to {@link DepthFlipPipeline}'s vsh. */
    private static final String VERTEX_SHADER = """
            #version 450

            void main() {
                // gl_VertexID -> clip-space (-1,-1), (3,-1), (-1,3).
                vec2 p = vec2((gl_VertexID & 1) << 2, (gl_VertexID & 2) << 1);
                gl_Position = vec4(p - 1.0, 0.0, 1.0);
            }
            """;

    /** 3x3 MAX-pool of the source depth (forward-Z: farther = larger; MAX is most permissive
     *  for LEQUAL). Writes {@code gl_FragDepth}; the dummy color output is discarded by WRITE_NONE. */
    private static final String FRAGMENT_SHADER = """
            #version 450

            uniform sampler2D Source;

            out vec4 fragColor;

            void main() {
                ivec2 size = textureSize(Source, 0);
                ivec2 pixel = clamp(ivec2(gl_FragCoord.xy), ivec2(0), size - 1);
                float m = 0.0;
                for (int y = -1; y <= 1; y++) {
                    for (int x = -1; x <= 1; x++) {
                        ivec2 samplePixel = clamp(pixel + ivec2(x, y), ivec2(0), size - 1);
                        m = max(m, texelFetch(Source, samplePixel, 0).r);
                    }
                }
                gl_FragDepth = m;
                fragColor = vec4(0.0); // discarded by ColorTargetState.WRITE_NONE
            }
            """;

    private static @Nullable RenderPipeline pipeline;
    private static boolean ready;

    static {
        // No runtime disposer: the only resource owned here is the RenderPipeline (driver state);
        // GlowResources drops it via the pipeline channel on full teardown.
        GlowResources.registerPipeline(DepthMinPoolPipeline::dispose);
    }

    private DepthMinPoolPipeline() {}

    /**
     * Eagerly compile the pipeline. Called from {@link cn.spectra.gallium.glowoutline.ItemEffectsManager}'s
     * resource-reload listener after the GpuDevice exists. Idempotent.
     */
    public static void precompile() {
        try {
            if (pipeline == null) {
                pipeline = RenderPipeline.builder()
                    .withLocation("pipeline/gallium_depth_minpool")
                    .withVertexShader(SHADER_ID)
                    .withFragmentShader(SHADER_ID)
                    .withSampler("Source")
                    // Dummy color target: attached to satisfy createRenderPass's color-view contract,
                    // but WRITE_NONE means no color is written (we only care about gl_FragDepth).
                    .withColorTargetState(new ColorTargetState(Optional.empty(), ColorTargetState.WRITE_NONE))
                    .withCull(false)
                    // ALWAYS_PASS so every fullscreen-triangle fragment passes the depth test and
                    // writes its gl_FragDepth; writeDepth=true to actually update mask.depth.
                    .withDepthStencilState(new DepthStencilState(CompareOp.ALWAYS_PASS, true))
                    .withVertexFormat(DefaultVertexFormat.EMPTY, VertexFormat.Mode.TRIANGLES)
                    .build();
            }

            // ShaderManager clears the device cache on every resource reload. Always seed it
            // again with the in-memory source even when our pipeline object is unchanged.
            var compiled = RenderSystem.getDevice().precompilePipeline(pipeline, (id, type) -> {
                if (!SHADER_ID.equals(id)) return null;
                return type == ShaderType.VERTEX ? VERTEX_SHADER
                     : type == ShaderType.FRAGMENT ? FRAGMENT_SHADER
                     : null;
            });
            if (!compiled.isValid()) {
                throw new IllegalStateException("Depth-min-pool pipeline compilation failed");
            }
            ready = true;
            Gallium.LOGGER.info("Compiled gallium depth-min-pool pipeline (TAA jitter compensation).");
        } catch (Throwable t) {
            // Don't block the mod: glow will just shimmer under Iris until next reload retries.
            Gallium.LOGGER.error("Failed to compile depth-min-pool pipeline; TAA jitter compensation disabled", t);
            pipeline = null;
            ready = false;
        }
    }

    public static boolean isReady() {
        return ready && pipeline != null;
    }

    /**
     * Sample {@code srcDepthView} (scene depth, forward-Z) and write the 3x3 farthest-neighbour
     * (MAX) pool into {@code destDepthView} as {@code gl_FragDepth}. {@code destColorView} is the
     * dummy color attachment (WRITE_NONE). Records a RenderPass onto the caller's {@code encoder}
     * so the pool is ordered after any earlier {@code copyTextureToTexture} of the source and before
     * any later copy of the result - one command buffer, no cross-encoder hazard. Caller should
     * ensure {@code srcDepthView} is fully written before this call (it reads a separate texture
     * from {@code destDepthView}, so there is no same-texture hazard).
     */
    public static boolean pool(CommandEncoder encoder, GpuTextureView srcDepthView, GpuTextureView destColorView, GpuTextureView destDepthView) {
        if (!isReady() || encoder == null || srcDepthView == null || destColorView == null || destDepthView == null) return false;

        // No clear on either attachment: we overwrite every depth texel via the fullscreen triangle
        // (ALWAYS_PASS + writeDepth), and the color attachment is WRITE_NONE (preserved as-is).
        try {
            try (RenderPass pass = encoder.createRenderPass(
                    () -> "Gallium DepthMinPool", destColorView, OptionalInt.empty(),
                    destDepthView, OptionalDouble.empty())) {
                pass.setPipeline(pipeline);
                SamplerHelper.bindClampToEdge(pass, "Source", srcDepthView, FilterMode.NEAREST);
                pass.draw(0, 3);
            }
            return true;
        } catch (RuntimeException e) {
            Gallium.LOGGER.error(
                    "Depth-min-pool dispatch failed; falling back to raw scene depth", e);
            pipeline = null;
            ready = false;
            return false;
        }
    }

    private static void dispose() {
        // RenderPipeline has no close(); the IdentityHashMap cache entry lives for the device
        // lifetime. Drop our reference so a future precompile() rebuilds against new shader source.
        pipeline = null;
        ready = false;
    }
}
//#elseif MC>=1_21_06
//$$ public final class DepthMinPoolPipeline {
//#if MC>=1_21_11
//$$     private static final Identifier SHADER_ID =
//$$             Identifier.fromNamespaceAndPath("gallium", "internal/depth_minpool");
//#else
//$$     private static final ResourceLocation SHADER_ID =
//$$             ResourceLocation.fromNamespaceAndPath("gallium", "internal/depth_minpool");
//#endif
//$$
//$$     private static final String VERTEX_SHADER = """
//$$             #version 150
//$$
//$$             void main() {
//$$                 vec2 p = vec2((gl_VertexID & 1) << 2, (gl_VertexID & 2) << 1);
//$$                 gl_Position = vec4(p - 1.0, 0.0, 1.0);
//$$             }
//$$             """;
//$$
//$$     private static final String FRAGMENT_SHADER = """
//$$             #version 150
//$$
//$$             uniform sampler2D Source;
//$$
//$$             out vec4 fragColor;
//$$
//$$             void main() {
//$$                 ivec2 size = textureSize(Source, 0);
//$$                 ivec2 pixel = clamp(ivec2(gl_FragCoord.xy), ivec2(0), size - 1);
//$$                 float m = 0.0;
//$$                 for (int y = -1; y <= 1; y++) {
//$$                     for (int x = -1; x <= 1; x++) {
//$$                         ivec2 samplePixel = clamp(pixel + ivec2(x, y), ivec2(0), size - 1);
//$$                         m = max(m, texelFetch(Source, samplePixel, 0).r);
//$$                     }
//$$                 }
//$$                 gl_FragDepth = m;
//$$                 fragColor = vec4(0.0);
//$$             }
//$$             """;
//$$
//$$     private static @Nullable RenderPipeline pipeline;
//$$     private static boolean ready;
//$$
//$$     static {
//$$         GlowResources.registerPipeline(DepthMinPoolPipeline::dispose);
//$$     }
//$$
//$$     private DepthMinPoolPipeline() {}
//$$
//$$     public static void precompile() {
//$$         try {
//$$             if (pipeline == null) {
//$$                 pipeline = RenderPipeline.builder()
//$$                         .withLocation("pipeline/gallium_depth_minpool")
//$$                         .withVertexShader(SHADER_ID)
//$$                         .withFragmentShader(SHADER_ID)
//$$                         .withSampler("Source")
//$$                         .withCull(false)
//$$                         .withColorWrite(false, false)
//$$                         // DepthTestFunction has no ALWAYS value on these versions. Clearing
//$$                         // the attachment to 1.0 in pool() makes every [0,1] output pass LEQUAL.
//$$                         .withDepthTestFunction(DepthTestFunction.LEQUAL_DEPTH_TEST)
//$$                         .withDepthWrite(true)
//$$                         .withVertexFormat(DefaultVertexFormat.EMPTY, VertexFormat.Mode.TRIANGLES)
//$$                         .build();
//$$             }
//$$
//$$             var compiled = RenderSystem.getDevice().precompilePipeline(pipeline, (id, type) -> {
//$$                 if (!SHADER_ID.equals(id)) return null;
//$$                 return type == ShaderType.VERTEX ? VERTEX_SHADER
//$$                      : type == ShaderType.FRAGMENT ? FRAGMENT_SHADER
//$$                      : null;
//$$             });
//$$             if (!compiled.isValid()) {
//$$                 throw new IllegalStateException("Depth-min-pool pipeline compilation failed");
//$$             }
//$$             ready = true;
//$$             Gallium.LOGGER.info(
//$$                     "Compiled gallium depth-min-pool pipeline (1.21.x TAA jitter compensation).");
//$$         } catch (Throwable t) {
//$$             Gallium.LOGGER.error(
//$$                     "Failed to compile depth-min-pool pipeline; TAA jitter compensation disabled", t);
//$$             pipeline = null;
//$$             ready = false;
//$$         }
//$$     }
//$$
//$$     public static boolean isReady() {
//$$         return ready && pipeline != null;
//$$     }
//$$
//$$     public static boolean pool(
//$$             CommandEncoder encoder,
//$$             GpuTextureView srcDepthView,
//$$             GpuTextureView destColorView,
//$$             GpuTextureView destDepthView) {
//$$         if (!isReady() || encoder == null || srcDepthView == null
//$$                 || destColorView == null || destDepthView == null) {
//$$             return false;
//$$         }
//$$
//$$         // LEQUAL is equivalent to ALWAYS for this pass because the load op first clears the
//$$         // forward-Z destination to 1.0 and the shader only emits normalized depth values.
//$$         try {
//$$             try (RenderPass pass = encoder.createRenderPass(
//$$                     () -> "Gallium DepthMinPool", destColorView, OptionalInt.empty(),
//$$                     destDepthView, OptionalDouble.of(1.0))) {
//$$                 pass.setPipeline(pipeline);
//$$                 SamplerHelper.bindClampToEdge(pass, "Source", srcDepthView, FilterMode.NEAREST);
//$$                 pass.draw(0, 3);
//$$             }
//$$             return true;
//$$         } catch (RuntimeException e) {
//$$             Gallium.LOGGER.error(
//$$                     "Depth-min-pool dispatch failed; falling back to raw scene depth", e);
//$$             pipeline = null;
//$$             ready = false;
//$$             return false;
//$$         }
//$$     }
//$$
//$$     private static void dispose() {
//$$         pipeline = null;
//$$         ready = false;
//$$     }
//$$ }
//#else
//$$ public final class DepthMinPoolPipeline {
//$$     private DepthMinPoolPipeline() {}
//$$     public static void precompile() {}
//$$     public static boolean isReady() { return false; }
//$$ }
//#endif
