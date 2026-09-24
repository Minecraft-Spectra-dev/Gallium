package cn.spectra.gallium.glowoutline.shader;

//#if MC>=1_21_05 && MC<1_26_02
import cn.spectra.gallium.Gallium;
import cn.spectra.gallium.glowoutline.IrisCompat;
import cn.spectra.gallium.glowoutline.capture.GlowCaptureState;
import cn.spectra.gallium.glowoutline.capture.OpenGlMaskOrdering;
//#if MC>=1_21_06
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
//#endif
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.RenderTarget;
//#if MC>=1_26_00
import com.mojang.blaze3d.pipeline.ColorTargetState;
//#else
//$$ import com.mojang.blaze3d.platform.DepthTestFunction;
//#endif
import com.mojang.blaze3d.shaders.ShaderType;
import com.mojang.blaze3d.shaders.UniformType;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
//#if MC>=1_21_06
import com.mojang.blaze3d.textures.GpuTextureView;
//#else
//$$ import com.mojang.blaze3d.textures.GpuTexture;
//$$ import com.mojang.blaze3d.textures.AddressMode;
//#endif
import com.mojang.blaze3d.textures.TextureFormat;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import java.util.Optional;
import java.util.OptionalInt;
//#if MC>=1_21_09
import net.minecraft.resources.Identifier;
//#else
//$$ import net.minecraft.resources.ResourceLocation;
//#endif
import org.lwjgl.system.MemoryStack;

/** Constant alpha is independent of every borrowed mask and can be written once per group. */
final class ModernSparseGlow implements AutoCloseable {
    //#if MC>=1_21_09
    private static final Identifier SHADER = Identifier.fromNamespaceAndPath("gallium", "internal/bounded_glow_alpha");
    //#else
    //$$ private static final ResourceLocation SHADER = cn.spectra.gallium.glowoutline.LegacyResourceIds.create("gallium", "internal/bounded_glow_alpha");
    //#endif
    private static RenderPipeline alphaPipeline;
    //#if MC>=1_21_06
    private static GpuBuffer alphaBuffer;
    private static GpuBufferSlice alphaSlice;
    //#endif
    private static boolean unavailable;
    private static ModernSparseGlow current;
    private final ModernSparseGlow parent;
    private final RenderTarget output;
    private final CommandEncoder encoder;
    private final OpenGlMaskOrdering.Stamp ordering;
    //#if MC>=1_21_06
    private GpuTextureView foreground;
    //#else
    //$$ private GpuTexture foreground;
    //#endif
    private float alpha;
    private boolean pending;
    private int alphaDraws;
    private BoundedGlowContract candidate;

    static {
        GlowResources.registerPipeline(() -> { alphaPipeline = null; unavailable = false; });
        GlowResources.register(() -> {
            //#if MC>=1_21_06
            if (alphaBuffer != null) alphaBuffer.close();
            alphaBuffer = null; alphaSlice = null;
            //#endif
            current = null; unavailable = false;
        });
    }

    ModernSparseGlow(RenderTarget output, CommandEncoder encoder) {
        this.output = output; this.encoder = encoder;
        ordering = OpenGlMaskOrdering.observe();
        parent = current; current = this;
    }

    //#if MC==1_21_11 || MC==1_26_01
    static boolean visibilityReady(RenderTarget output) {
        var frame=current;
        return frame!=null && frame.output==output && frame.ordering!=null && frame.ordering.current() && ensurePipeline();
    }
    //#endif

    static BoundedGlowContract.Rectangle begin(GlowCaptureState state, RenderTarget output,
            //#if MC>=1_21_06
            GpuTextureView foreground
            //#else
            //$$ GpuTexture foreground
            //#endif
    ) {
        var frame = current;
        if (frame == null || frame.output != output) return null;
        frame.candidate = null;
        boolean iris = IrisCompat.isShaderActive();
        var bounds = state.maskBounds;
        boolean srMapping = false;
        //#if MC==1_21_11
        //$$ var nativeBounds = OutlineSrCapture.bounds(state);
        //$$ srMapping = OutlineTemporalStabilizer.capturingSr() && OutlineSrCapture.valid()
        //$$         && nativeBounds != null && nativeBounds.valid()
        //$$         && OriginalGlowParameters.supportsDeferredSceneOcclusion(state.config);
        //$$ if (srMapping) bounds = nativeBounds;
        //#endif
        // Enable mapped Iris bounds only for backends with validated native replay.
        //#if MC==1_21_05 || MC==1_21_08 || MC==1_21_10 || MC==1_21_11 || MC==1_26_01
        boolean irisMapping = true;
        //#else
        //$$ boolean irisMapping = srMapping;
        //#endif
        var contract = state.guiEntity == null && !state.superResolutionPrepared
                && output.getColorTexture().getFormat() == TextureFormat.RGBA8
                && (!iris || irisMapping) && (!IrisCompat.isActiveSrRuntime() || srMapping)
                && frame.ordering != null && frame.ordering.current()
                ? BoundedGlowContracts.get(state.config.shader()) : null;
        boolean mapped = iris && contract != null && BoundedGlowContracts.automatic(state.config.shader())
                && OriginalGlowParameters.supported(state.config);
        if (!mapped && (iris || state.lastMaskScaleX != 1 || state.lastMaskScaleY != 1
                || state.lastMaskOffsetX != 0 || state.lastMaskOffsetY != 0)) contract = null;
        var rectangle = contract == null ? null : mapped
                ? contract.mappedRectangle(bounds, output.width, output.height,
                    state.itemWorldToUv.x, state.itemWorldToUv.y, state.lastMaskScaleX,
                    state.lastMaskScaleY, state.lastMaskOffsetX, state.lastMaskOffsetY)
                : contract.rectangle(bounds, output.width, output.height,
                    state.itemWorldToUv.x, state.itemWorldToUv.y);
        if (rectangle == null || !ensurePipeline()) { frame.flush(); return null; }
        if (frame.pending && (frame.foreground != foreground || frame.alpha != contract.constantAlpha())) frame.flush();
        frame.foreground = foreground; frame.candidate = contract;
        return rectangle;
    }

    static void completed(RenderTarget output) {
        completed(output, 1);
    }

    static void completed(RenderTarget output, int draws) {
        var frame = current;
        if (frame == null || frame.output != output || frame.candidate == null) return;
        frame.alpha = frame.candidate.constantAlpha(); frame.alphaDraws += draws;
        frame.pending = true; frame.candidate = null;
    }

    private void flush() {
        if (!pending) return;
        pending = false;
        int draws = alphaDraws;
        alphaDraws = 0;
        if (alpha == 0) return;
        // Native ADDITIVE uses ONE/ONE for alpha too. Preserve its per-draw rounding
        // unless an RGBA8 lower bound proves that every possible starting alpha has
        // already saturated. Each conversion can lose at most one UNORM8 step.
        boolean saturated = AlphaAccumulation.saturatesUnorm8(alpha, draws);
        //#if MC>=1_21_06
        try (MemoryStack stack = MemoryStack.stackPush()) {
            var bytes = stack.malloc(16);
            bytes.putFloat(saturated ? 1 : alpha).putFloat(output.width).putFloat(output.height).putFloat(0).flip();
            encoder.writeToBuffer(alphaSlice, bytes);
        }
        try (RenderPass pass = encoder.createRenderPass(() -> "Gallium bounded glow alpha",
                output.getColorTextureView(), OptionalInt.empty())) {
        //#else
        //$$ try (RenderPass pass = encoder.createRenderPass(output.getColorTexture(), OptionalInt.empty())) {
        //#endif
            pass.setPipeline(alphaPipeline);
            //#if MC>=1_21_06
            pass.setUniform("AlphaUniforms", alphaSlice);
            SamplerHelper.bindClampToEdge(pass, "Foreground", foreground, FilterMode.NEAREST);
            //#else
            //$$ pass.setUniform("AlphaAndSize", saturated ? 1 : alpha, output.width, output.height, 0);
            //$$ foreground.setTextureFilter(FilterMode.NEAREST, false);
            //$$ foreground.setAddressMode(AddressMode.CLAMP_TO_EDGE, AddressMode.CLAMP_TO_EDGE);
            //$$ pass.bindSampler("Foreground", foreground);
            //#endif
            //#if MC<1_21_09
            //$$ pass.setVertexBuffer(0, RenderSystem.getQuadVertexBuffer());
            //#endif
            for (int i = 0; i < (saturated ? 1 : draws); i++) pass.draw(0, 3);
        }
    }

    @Override public void close() {
        try { flush(); } finally { if (current == this) current = parent; }
    }

    private static boolean ensurePipeline() {
        if (unavailable) return false;
        try {
            var pipeline = alphaPipeline;
            if (pipeline == null) pipeline = RenderPipeline.builder().withLocation("pipeline/gallium_bounded_glow_alpha")
                    .withVertexShader(SHADER).withFragmentShader(SHADER)
                    .withSampler("Foreground")
                    //#if MC>=1_21_06
                    .withUniform("AlphaUniforms", UniformType.UNIFORM_BUFFER)
                    //#else
                    //$$ .withUniform("AlphaAndSize", UniformType.VEC4)
                    //#endif
                    //#if MC>=1_26_00
                    .withColorTargetState(new ColorTargetState(Optional.of(BlendFunction.ADDITIVE), ColorTargetState.WRITE_ALPHA))
                    .withDepthStencilState(Optional.empty())
                    //#else
                    //$$ .withBlend(BlendFunction.ADDITIVE).withColorWrite(false, true).withDepthTestFunction(DepthTestFunction.NO_DEPTH_TEST).withDepthWrite(false)
                    //#endif
                    .withCull(false)
                    //#if MC>=1_21_06
                    .withVertexFormat(DefaultVertexFormat.EMPTY, VertexFormat.Mode.TRIANGLES)
                    //#else
                    //$$ .withVertexFormat(DefaultVertexFormat.BLIT_SCREEN, VertexFormat.Mode.TRIANGLES)
                    //#endif
                    .build();
            // Resource reloads clear the native program cache independently of our
            // Java pipeline and uniform buffer. Supply the inline sources on a miss.
            var compiled = RenderSystem.getDevice().precompilePipeline(pipeline,
                    (id, type) -> !SHADER.equals(id) ? null : type == ShaderType.VERTEX ? VERTEX : type == ShaderType.FRAGMENT ? FRAGMENT : null);
            if (!compiled.isValid()) throw new IllegalStateException("Bounded glow alpha pipeline compilation failed");
            //#if MC>=1_21_06
            if (alphaBuffer == null) {
                alphaBuffer = RenderSystem.getDevice().createBuffer(() -> "Gallium bounded glow alpha",
                        GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_COPY_DST, 16);
                alphaSlice = alphaBuffer.slice();
            }
            //#endif
            alphaPipeline = pipeline;
            return true;
        } catch (RuntimeException | LinkageError failure) {
            unavailable = true;
            Gallium.LOGGER.warn("Bounded glow alpha unavailable: {}", failure.toString());
            return false;
        }
    }

    private static final String VERTEX = """
            #version 150
            noperspective out vec2 ScreenUv;
            void main() {
                vec2 p = vec2(float((gl_VertexID << 1) & 2), float(gl_VertexID & 2));
                gl_Position = vec4(p * 2.0 - 1.0, 0.0, 1.0);
                ScreenUv = gl_Position.xy / gl_Position.w * 0.5 + 0.5;
            }
            """;
    private static final String FRAGMENT = """
            #version 150
            uniform sampler2D Foreground;
            layout(std140) uniform AlphaUniforms { vec4 AlphaAndSize; };
            noperspective in vec2 ScreenUv;
            out vec4 fragColor;
            void main() {
                ivec2 size = textureSize(Foreground, 0);
                ivec2 pixel = clamp(ivec2(floor(ScreenUv * vec2(size))), ivec2(0), size - ivec2(1));
                if (texelFetch(Foreground, pixel, 0).r < 1.0) discard;
                fragColor = vec4(0.0, 0.0, 0.0, AlphaAndSize.x);
            }
            """
            //#if MC<1_21_06
            //$$ .replace("layout(std140) uniform AlphaUniforms { vec4 AlphaAndSize; };", "uniform vec4 AlphaAndSize;")
            //#endif
            ;
}
//#else
//$$ final class ModernSparseGlow { private ModernSparseGlow() {} }
//#endif
