package cn.spectra.gallium.glowoutline.shader;

//#if MC<1_21_06
//$$ import cn.spectra.gallium.Gallium;
//$$ import cn.spectra.gallium.glowoutline.ItemEffectConfig;
//$$ import cn.spectra.gallium.glowoutline.ShaderParam;
//$$ import com.mojang.blaze3d.pipeline.BlendFunction;
//$$ import com.mojang.blaze3d.pipeline.RenderPipeline;
//$$ import com.mojang.blaze3d.platform.DepthTestFunction;
//$$ import com.mojang.blaze3d.shaders.UniformType;
//$$ import com.mojang.blaze3d.vertex.DefaultVertexFormat;
//$$ import com.mojang.blaze3d.vertex.VertexFormat;
//$$ import java.util.HashMap;
//$$ import java.util.Map;
//$$ import java.util.Set;
//$$ import net.minecraft.client.renderer.RenderPipelines;
//$$ import net.minecraft.resources.ResourceLocation;
//$$
//$$ /**
//$$  * Pre-1.21.6 (immediate-mode GUI rendering) mirror of {@link GuiGlowElementPipeline}
//$$  * for the per-item GUI glow path. Differences from the 1.21.6+ version:
//$$  * <ul>
//$$  *   <li>No {@code UniformType.UNIFORM_BUFFER} on this branch: each shader-side uniform
//$$  *       is declared individually here.
//$$  *       {@link cn.spectra.gallium.glowoutline.mixin.ShaderUboCompatMixin} rewrites the
//$$  *       shader's {@code layout(std140) uniform GalliumGuiGlow} block into matching
//$$  *       individual {@code uniform} declarations at shader load time.</li>
//$$  *   <li>Depth test is disabled — the glow quad is drawn after the vanilla item, on top
//$$  *       of the GUI render order. Disabling depth ensures the additive overlay paints
//$$  *       regardless of whatever depth values vanilla GUI elements left behind.</li>
//$$  *   <li>Pipeline cache keyed by {@link ItemEffectConfig} (record equality covers
//$$  *       shader+params), matching the 1.21.6+ invariant that two rules sharing a shader
//$$  *       name but different params get distinct pipelines so their static uniforms don't
//$$  *       overwrite each other within a frame.</li>
//$$  * </ul>
//$$  */
//$$ public final class GuiImmediateGlowPipeline {
//$$
//$$     private static final Map<ItemEffectConfig, RenderPipeline> pipelinesByConfig = new HashMap<>();
//$$     private static int locationCounter;
//$$
//$$     static {
//$$         GlowResources.registerPipeline(GuiImmediateGlowPipeline::clear);
//$$     }
//$$
//$$     private GuiImmediateGlowPipeline() {}
//$$
//$$     public static RenderPipeline getOrCreate(ItemEffectConfig cfg) {
//$$         if (cfg == null || cfg.shader().isEmpty()) return null;
//$$         RenderPipeline cached = pipelinesByConfig.get(cfg);
//$$         if (cached != null) return cached;
//$$
//$$         String shader = cfg.shader();
//$$         String location = "pipeline/gallium_gui_glow_immediate/" + shader + "_" + (locationCounter++);
//$$         String shaderPath = "core/" + shader + "_gui";
//$$
//$$         // Inherit base GUI snippet for ProjMat / ModelViewMat / ColorModulator / Sampler0
//$$         // / POSITION_TEX_COLOR vertex format / "core/position_tex_color" vertex shader.
//$$         // Override fragment shader to load core/<shader>_gui.fsh, override blend to ADDITIVE
//$$         // (so multiple overlapping outlines compound rather than alpha-blending), and
//$$         // declare each shader-side uniform individually.
//$$         var builder = RenderPipeline.builder(RenderPipelines.GUI_TEXTURED_SNIPPET)
//$$                 .withLocation(location)
//$$                 .withFragmentShader(ResourceLocation.fromNamespaceAndPath("gallium", shaderPath))
//$$                 .withBlend(BlendFunction.ADDITIVE)
//$$                 .withCull(false)
//$$                 // Disable depth test so the additive outline always paints, regardless of
//$$                 // whatever vanilla item / icon / tooltip depth values landed in the
//$$                 // mainTarget depth buffer at the quad's pixels.
//$$                 .withDepthTestFunction(DepthTestFunction.NO_DEPTH_TEST)
//$$                 .withDepthWrite(false)
//$$                 // Standard glow uniforms — header (FrameTimeCounter, ScreenSize) plus
//$$                 // ShaderAlign at the tail. ShaderUboCompatMixin promotes the matching UBO
//$$                 // members in the shader to top-level uniforms with these exact names.
//$$                 .withUniform("FrameTimeCounter", UniformType.FLOAT)
//$$                 .withUniform("ScreenSize", UniformType.VEC2)
//$$                 .withUniform("ShaderAlign", UniformType.VEC4);
//$$         // Per-config user params (Intensity, PulseSpeed, WaveSpeed, InnerColor, OuterColor,
//$$         // or whatever the config's shader declares). Each one becomes an individual uniform
//$$         // declaration on the pipeline AND a matching individual uniform in the shader after
//$$         // ShaderUboCompatMixin runs.
//$$         for (ShaderParam p : cfg.params()) {
//$$             switch (p) {
//$$                 case ShaderParam.Float f -> builder.withUniform(f.name(), UniformType.FLOAT);
//$$                 case ShaderParam.Vec2 v -> builder.withUniform(v.name(), UniformType.VEC2);
//$$                 case ShaderParam.Vec3 v -> builder.withUniform(v.name(), UniformType.VEC3);
//$$                 case ShaderParam.Vec4 v -> builder.withUniform(v.name(), UniformType.VEC4);
//$$             }
//$$         }
//$$         RenderPipeline p = builder
//$$                 .withVertexFormat(DefaultVertexFormat.POSITION_TEX_COLOR, VertexFormat.Mode.QUADS)
//$$                 .build();
//$$         pipelinesByConfig.put(cfg, p);
//$$         Gallium.LOGGER.info("Created immediate-mode GUI glow pipeline for shader '{}' (variant #{}, {} params)",
//$$                 shader, locationCounter - 1, cfg.params().size());
//$$         return p;
//$$     }
//$$
//$$     /** Drop pipelines whose config is no longer referenced. */
//$$     public static void retainOnly(Set<ItemEffectConfig> liveConfigs) {
//$$         pipelinesByConfig.keySet().removeIf(cfg -> !liveConfigs.contains(cfg));
//$$         // Counter exists only to disambiguate location strings between live pipelines, so
//$$         // when nothing is live we can safely rewind. Without this, locationCounter grows
//$$         // unboundedly across reloads and bloats the pipeline name in logs / RenderDoc.
//$$         if (pipelinesByConfig.isEmpty()) {
//$$             locationCounter = 0;
//$$         }
//$$     }
//$$
//$$     public static void clear() {
//$$         pipelinesByConfig.clear();
//$$         locationCounter = 0;
//$$     }
//$$ }
//#else
public final class GuiImmediateGlowPipeline {
    private GuiImmediateGlowPipeline() {}
}
//#endif
