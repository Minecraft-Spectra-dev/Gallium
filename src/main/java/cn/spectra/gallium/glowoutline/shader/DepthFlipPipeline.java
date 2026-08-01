package cn.spectra.gallium.glowoutline.shader;

//#if MC>=1_26_02
//$$ import cn.spectra.gallium.Gallium;
//$$ import com.mojang.blaze3d.GpuFormat;
//$$ import com.mojang.blaze3d.PrimitiveTopology;
//$$ import com.mojang.blaze3d.pipeline.BindGroupLayout;
//$$ import com.mojang.blaze3d.pipeline.ColorTargetState;
//$$ import com.mojang.blaze3d.pipeline.RenderPipeline;
//$$ import com.mojang.blaze3d.pipeline.TextureTarget;
//$$ import com.mojang.blaze3d.shaders.ShaderType;
//$$ import com.mojang.blaze3d.systems.RenderPass;
//$$ import com.mojang.blaze3d.systems.RenderSystem;
//$$ import com.mojang.blaze3d.textures.FilterMode;
//$$ import com.mojang.blaze3d.textures.GpuTextureView;
//$$ import java.util.Optional;
//$$ import net.minecraft.resources.Identifier;
//$$ import org.jspecify.annotations.Nullable;
//$$
//$$ /**
//$$  * Reverse-Z compensation for MC 26.2. MC 26.2 switched the entire render pipeline to
//$$  * reverse-Z ({@code glClipControl(GL_LOWER_LEFT, GL_ZERO_TO_ONE)}, near/far swapped in
//$$  * {@link net.minecraft.client.renderer.Projection}, depth clear value 0.0, depth test
//$$  * {@code GREATER_THAN_OR_EQUAL}), so every depth texture vanilla writes carries values
//$$  * where 1.0 = near, 0.0 = far. Gallium-owned mask depth textures, which inherit the
//$$  * captured projection matrix, are in the same reverse-Z space outside Iris's OpenGL
//$$  * forward-Z compatibility path. Iris/Vulkan retains reverse-Z; Iris/OpenGL already has
//$$  * forward depth and therefore bypasses this pipeline.
//$$  *
//$$  * <p>Pack-author glow shaders predate this and assume forward-Z (0=near, 1=far): the
//$$  * reference {@code step(itemDepth, maxSceneDepth)} test treats "larger = farther". With
//$$  * raw reverse-Z values, that inverts: world glows appear only when the item is behind
//$$  * a wall, and first-person glows never appear at all.
//$$  *
//$$  * <p>To avoid forcing pack authors to branch their shaders, Gallium runs a tiny full-screen
//$$  * pass before each composite that samples the reverse-Z depth texture and writes
//$$  * {@code 1.0 - depth} into an R32F color attachment Gallium owns. The pack shader then
//$$  * receives this color view as {@code MaskDepthSampler} / {@code SceneDepthSampler} and
//$$  * reads forward-Z values from {@code texture(sampler, uv).r}, exactly as on 26.1 and
//$$  * earlier versions. No pack-side change required.
//$$  *
//$$  * <h2>Shader source delivery</h2>
//$$  *
//$$  * The vertex + fragment GLSL live as {@link String} constants in this class. We register
//$$  * a custom {@link com.mojang.blaze3d.shaders.ShaderSource} that returns them by
//$$  * {@link Identifier}, then call {@link com.mojang.blaze3d.systems.GpuDevice#precompilePipeline}
//$$  * to seed the IdentityHashMap pipeline cache before any draw. Later
//$$  * {@code RenderPass.setPipeline(this.pipeline)} hits the cache by reference identity and
//$$  * never queries vanilla's resource-pack-backed {@code defaultShaderSource}. The shader
//$$  * source thus stays entirely inside the mod's compiled jar (no entry under
//$$  * {@code assets/}; no chance of pack override; not visible to {@code ResourceManager}).
//$$  */
//$$ public final class DepthFlipPipeline {
//$$
//$$     /** Identifiers are arbitrary; their only role is to key the GlDevice shader cache.
//$$      *  Using a {@code gallium:internal/...} path keeps them out of the pack-author namespace
//$$      *  even though no resource file with this id will ever be looked up. */
//$$     private static final Identifier SHADER_ID =
//$$             Identifier.fromNamespaceAndPath("gallium", "internal/depth_flip");
//$$
//$$     /** Full-screen triangle, attribute-less. Two vertices live outside the clip volume so
//$$      *  the rasterizer covers the whole viewport with a single triangle. Same trick vanilla
//$$      *  POST_PROCESSING_SNIPPET expects from its callers — no vertex buffer needed. */
//$$     private static final String VERTEX_SHADER = """
//$$             #version 450
//$$
//$$             out vec2 v_uv;
//$$
//$$             void main() {
//$$                 // gl_VertexID -> (0,0), (2,0), (0,2) corners; clip-space (-1,-1)..(3,-1)..(-1,3).
//$$                 vec2 p = vec2((gl_VertexID & 1) << 2, (gl_VertexID & 2) << 1);
//$$                 v_uv = p * 0.5;
//$$                 gl_Position = vec4(p - 1.0, 0.0, 1.0);
//$$             }
//$$             """;
//$$
//$$     /** Samples the reverse-Z depth texture and writes (1.0 - d) into R. Vanilla binds
//$$      *  depth textures to a sampler2D just like color textures (see PostPass.TargetInput
//$$      *  with depthBuffer=true), so a plain {@code texture(...).r} read gives the raw
//$$      *  reverse-Z value in [0, 1]. */
//$$     private static final String FRAGMENT_SHADER = """
//$$             #version 450
//$$
//$$             uniform sampler2D Source;
//$$
//$$             in vec2 v_uv;
//$$             out vec4 fragColor;
//$$
//$$             void main() {
//$$                 float d = texture(Source, v_uv).r;
//$$                 fragColor = vec4(1.0 - d, 0.0, 0.0, 1.0);
//$$             }
//$$             """;
//$$
//$$     private static @Nullable RenderPipeline pipeline;
//$$     /** Tracks whether {@link #precompile} succeeded so we can fall back gracefully. */
//$$     private static boolean ready;
//$$
//$$     static {
//$$         // No runtime disposer: the only resource owned here is the RenderPipeline
//$$         // (driver state); GlowResources.disposeAll() drops it via the pipeline channel.
//$$         GlowResources.registerPipeline(DepthFlipPipeline::dispose);
//$$     }
//$$
//$$     private DepthFlipPipeline() {}
//$$
//$$     /**
//$$      * Compile or re-register the pipeline from the item-effects resource-reload listener.
//$$      * Calling more than once is required and safe: Minecraft clears the device cache during
//$$      * reload, while precompilePipeline is idempotent when that cache is still populated.
//$$      */
//$$     public static void precompile() {
//$$         try {
//$$             if (pipeline == null) {
//$$                 pipeline = RenderPipeline.builder()
//$$                     .withLocation("pipeline/gallium_depth_flip")
//$$                     .withVertexShader(SHADER_ID)
//$$                     .withFragmentShader(SHADER_ID)
//$$                     .withBindGroupLayout(BindGroupLayout.builder()
//$$                             .withSampler("Source")
//$$                             .build())
//$$                     // No blend — we overwrite the destination fully. The color target
//$$                     // is R32F (set on the TextureTarget), so we must echo that format
//$$                     // into the ColorTargetState too: vanilla's no-arg ColorTargetState
//$$                     // defaults to RGBA8_UNORM, which would fail format-compatibility
//$$                     // checks against our R32F attachment.
//$$                     .withColorTargetState(new ColorTargetState(
//$$                             Optional.empty(), GpuFormat.R32_FLOAT, ColorTargetState.WRITE_RED))
//$$                     .withCull(false)
//$$                     .withDepthStencilState(Optional.empty())
//$$                     .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
//$$                     .build();
//$$             }
//$$
//$$             // Re-seed the cache on every resource reload. ShaderManager.apply() clears the
//$$             // device pipeline cache, while this internal shader has no resource-pack source
//$$             // for the default ShaderSource to find later during setPipeline().
//$$             var compiled = RenderSystem.getDevice().precompilePipeline(pipeline, (id, type) -> {
//$$                 if (!SHADER_ID.equals(id)) return null;
//$$                 return type == ShaderType.VERTEX ? VERTEX_SHADER
//$$                      : type == ShaderType.FRAGMENT ? FRAGMENT_SHADER
//$$                      : null;
//$$             });
//$$             if (!compiled.isValid()) {
//$$                 throw new IllegalStateException("Depth-flip pipeline compilation failed");
//$$             }
//$$             ready = true;
//$$             Gallium.LOGGER.info("Compiled gallium depth-flip pipeline (reverse-Z compensation).");
//$$         } catch (Throwable t) {
//$$             // Don't block the whole mod from loading if pipeline compilation throws — the
//$$             // glow will just look wrong on 26.2 until the next resource reload (which retries).
//$$             Gallium.LOGGER.error("Failed to compile depth-flip pipeline; reverse-Z compensation disabled", t);
//$$             pipeline = null;
//$$             ready = false;
//$$         }
//$$     }
//$$
//$$     /** Returns true if the pipeline is compiled and ready to dispatch. */
//$$     public static boolean isReady() {
//$$         return ready && pipeline != null;
//$$     }
//$$
//$$     /**
//$$      * Sample {@code srcDepthView} (reverse-Z) and write {@code 1 - depth} (forward-Z) into
//$$      * {@code dest}'s color attachment. {@code dest} must be created with an R-channel color
//$$      * format that can carry depth precision; we use {@code R32_FLOAT} via
//$$      * {@link #ensureForwardZTarget}.
//$$      *
//$$      * <p>Call from within a CommandEncoder scope — opens its own RenderPass internally.
//$$      */
//$$     public static void flip(GpuTextureView srcDepthView, TextureTarget dest) {
//$$         if (!isReady() || srcDepthView == null || dest == null) return;
//$$         GpuTextureView destView = dest.getColorTextureView();
//$$         if (destView == null) return;
//$$
//$$         var encoder = RenderSystem.getDevice().createCommandEncoder();
//$$         // Optional.empty() = no clear; we overwrite every fragment anyway via the full-screen tri.
//$$         try (RenderPass pass = encoder.createRenderPass(
//$$                 () -> "Gallium DepthFlip", destView, Optional.empty())) {
//$$             pass.setPipeline(pipeline);
//$$             SamplerHelper.bindClampToEdge(pass, "Source", srcDepthView, FilterMode.NEAREST);
//$$             pass.draw(3, 1, 0, 0);
//$$         }
//$$     }
//$$
//$$     /**
//$$      * Allocates or resizes a single-channel float color target sized to match a depth source.
//$$      * The output stores {@code 1 - reverseZ} per texel; downstream shaders read it from a
//$$      * regular {@code sampler2D} via {@code .r}.
//$$      *
//$$      * @param existing  prior target (will be destroyed and replaced if size mismatches)
//$$      * @param label     debug label
//$$      * @param w         width in pixels
//$$      * @param h         height in pixels
//$$      * @return          a TextureTarget whose color attachment is R32F sized {@code w x h}
//$$      */
//$$     public static TextureTarget ensureForwardZTarget(@Nullable TextureTarget existing,
//$$                                                       String label, int w, int h) {
//$$         if (existing != null && existing.width == w && existing.height == h) {
//$$             return existing;
//$$         }
//$$         if (existing != null) {
//$$             existing.destroyBuffers();
//$$         }
//$$         // useDepth=false: this is a color-only target. Depth attachment would be wasted memory.
//$$         // GpuFormat.R32_FLOAT keeps full precision so deep-z items (depth near 0 in reverse-Z,
//$$         // near 1 in forward-Z) don't bleed into the same texel as nearby items.
//$$         return new TextureTarget(label, w, h, false, GpuFormat.R32_FLOAT);
//$$     }
//$$
//$$     private static void dispose() {
//$$         // RenderPipeline has no close() in vanilla; the IdentityHashMap cache entry sticks
//$$         // around in GlDevice.pipelineCache for the lifetime of the device. On resource reload
//$$         // we drop our reference so a future precompile() rebuilds the pipeline against any
//$$         // updated shader source; on full teardown the device itself is going away.
//$$         pipeline = null;
//$$         ready = false;
//$$     }
//$$ }
//#else
public final class DepthFlipPipeline {
    private DepthFlipPipeline() {}
    public static void precompile() {}
    public static boolean isReady() { return false; }
}
//#endif
