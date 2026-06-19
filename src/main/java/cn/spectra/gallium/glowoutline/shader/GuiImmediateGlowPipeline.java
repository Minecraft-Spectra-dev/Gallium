package cn.spectra.gallium.glowoutline.shader;

//#if MC>=1_21_05
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
//$$         // ADDITIVE blend: overlapping outlines compound rather than alpha-blending.
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
//$$         // Per-config user params — pipeline declares each individually, shader's matching
//$$         // UBO members are flattened by ShaderUboCompatMixin to match.
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
//$$         // See GuiGlowElementPipeline.retainOnly: rewind counter when empty.
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
//#else
//$$ import cn.spectra.gallium.Gallium;
//$$ import cn.spectra.gallium.glowoutline.ItemEffectConfig;
//$$ import cn.spectra.gallium.glowoutline.ShaderParam;
//$$ import com.google.common.io.CharStreams;
//$$ import com.mojang.blaze3d.preprocessor.GlslPreprocessor;
//$$ import com.mojang.blaze3d.shaders.CompiledShader;
//$$ import com.mojang.blaze3d.vertex.DefaultVertexFormat;
//$$ import java.io.Reader;
//$$ import java.util.ArrayList;
//$$ import java.util.HashMap;
//$$ import java.util.List;
//$$ import java.util.Map;
//$$ import java.util.Set;
//$$ import net.minecraft.FileUtil;
//$$ import net.minecraft.ResourceLocationException;
//$$ import net.minecraft.client.Minecraft;
//$$ import net.minecraft.client.renderer.CompiledShaderProgram;
//$$ import net.minecraft.client.renderer.ShaderProgramConfig;
//$$ import net.minecraft.resources.ResourceLocation;
//$$ import net.minecraft.server.packs.resources.Resource;
//$$
//$$ public final class GuiImmediateGlowPipeline {
//$$     private static final Map<ItemEffectConfig, CompiledShaderProgram> pipelinesByConfig = new HashMap<>();
//$$
//$$     private GuiImmediateGlowPipeline() {}
//$$     static { GlowResources.registerPipeline(GuiImmediateGlowPipeline::clear); }
//$$
//$$     public static CompiledShaderProgram getOrCreate(ItemEffectConfig cfg) {
//$$         if (cfg == null || cfg.shader().isEmpty()) return null;
//$$         return pipelinesByConfig.computeIfAbsent(cfg, GuiImmediateGlowPipeline::compileProgram);
//$$     }
//$$     public static void retainOnly(Set<ItemEffectConfig> liveConfigs) {
//$$         pipelinesByConfig.entrySet().removeIf(e -> {
//$$             boolean remove = !liveConfigs.contains(e.getKey());
//$$             if (remove) e.getValue().close();
//$$             return remove;
//$$         });
//$$     }
//$$     public static void clear() {
//$$         for (CompiledShaderProgram p : pipelinesByConfig.values()) p.close();
//$$         pipelinesByConfig.clear();
//$$     }
//$$
//$$     // try-with-resources closes BOTH compiled shaders deterministically — both on success
//$$     // (after link, glDeleteShader marks them; the program holds the GL refs alive until
//$$     // program.close()) and on failure (vertex compiled but fragment compile/link threw —
//$$     // vertex still gets closed on unwind). See GlowPipeline.compileProgram for full notes.
//$$     private static CompiledShaderProgram compileProgram(ItemEffectConfig cfg) {
//$$         ResourceLocation vertexId = ResourceLocation.withDefaultNamespace("core/position_tex_color");
//$$         ResourceLocation fragmentId = ResourceLocation.fromNamespaceAndPath("gallium", "core/" + cfg.shader() + "_gui");
//$$         try (CompiledShader vertex = compileShader(vertexId, CompiledShader.Type.VERTEX);
//$$              CompiledShader fragment = compileShader(fragmentId, CompiledShader.Type.FRAGMENT)) {
//$$             CompiledShaderProgram program = CompiledShaderProgram.link(vertex, fragment, DefaultVertexFormat.POSITION_TEX_COLOR);
//$$             program.setupUniforms(uniformsFor(cfg), List.of(new ShaderProgramConfig.Sampler("Sampler0")));
//$$             Gallium.LOGGER.info("Created 1.21.4 immediate GUI glow shader program '{}' ({} params)", cfg.shader(), cfg.params().size());
//$$             return program;
//$$         } catch (Exception e) {
//$$             Gallium.LOGGER.error("Failed to create 1.21.4 GUI glow shader program '{}'", cfg.shader(), e);
//$$             return null;
//$$         }
//$$     }
//$$
//$$     private static CompiledShader compileShader(ResourceLocation shaderId, CompiledShader.Type type) throws Exception {
//$$         ResourceLocation fileId = ResourceLocation.fromNamespaceAndPath(shaderId.getNamespace(),
//$$                 "shaders/" + shaderId.getPath() + (type == CompiledShader.Type.VERTEX ? ".vsh" : ".fsh"));
//$$         Resource resource = Minecraft.getInstance().getResourceManager().getResourceOrThrow(fileId);
//$$         try (Reader reader = resource.openAsReader()) {
//$$             GlslPreprocessor preprocessor = preprocessorFor(fileId);
//$$             // Same UBO → individual-uniform rewrite as GlowPipeline. The shader pack ships
//$$             // `layout(std140) uniform GalliumGuiGlow { ... };` for the 1.21.6+ deferred path;
//$$             // on 1.21.4 we can't bind UBOs, so we transform the source via the shared
//$$             // UboRewriter before glCompile.
//$$             String processed = String.join("", preprocessor.process(CharStreams.toString(reader)));
//$$             processed = UboRewriter.rewrite(processed);
//$$             return CompiledShader.compile(shaderId, type, processed);
//$$         }
//$$     }
//$$
//$$     private static GlslPreprocessor preprocessorFor(ResourceLocation sourceFile) {
//$$         final ResourceLocation base = sourceFile.withPath(FileUtil::getFullResourcePath);
//$$         return new GlslPreprocessor() {
//$$             @Override
//$$             public String applyImport(boolean quoted, String path) {
//$$                 try {
//$$                     ResourceLocation importId = quoted
//$$                             ? base.withPath(p -> FileUtil.normalizeResourcePath(p + path))
//$$                             : ResourceLocation.parse(path).withPrefix("shaders/include/");
//$$                     try (Reader reader = Minecraft.getInstance().getResourceManager().getResourceOrThrow(importId).openAsReader()) {
//$$                         return CharStreams.toString(reader);
//$$                     }
//$$                 } catch (ResourceLocationException e) {
//$$                     return "#error " + e.getMessage();
//$$                 } catch (Exception e) {
//$$                     return "#error " + e.getMessage();
//$$                 }
//$$             }
//$$         };
//$$     }
//$$
//$$     private static List<ShaderProgramConfig.Uniform> uniformsFor(ItemEffectConfig cfg) {
//$$         List<ShaderProgramConfig.Uniform> uniforms = new ArrayList<>();
//$$         // Vanilla position_tex_color.vsh declares `uniform mat4 ModelViewMat; uniform mat4 ProjMat;`
//$$         // and uses them to transform Position. CompiledShaderProgram.setDefaultUniforms only
//$$         // writes MODEL_VIEW_MATRIX/PROJECTION_MATRIX if the corresponding Uniform was registered
//$$         // here — declaring them sets up `getUniform("ModelViewMat")` so vanilla can drive them.
//$$         // Without these, both matrices stay at their GL default zero, gl_Position = 0 for every
//$$         // vertex, the quad collapses to the origin, and nothing rasterizes.
//$$         uniforms.add(matrixUniform("ModelViewMat"));
//$$         uniforms.add(matrixUniform("ProjMat"));
//$$         uniforms.add(uniform("ColorModulator", 4));
//$$         uniforms.add(uniform("FrameTimeCounter", 1));
//$$         uniforms.add(uniform("ScreenSize", 2));
//$$         uniforms.add(uniform("ShaderAlign", 4));
//$$         for (ShaderParam p : cfg.params()) {
//$$             switch (p) {
//$$                 case ShaderParam.Float f -> uniforms.add(uniform(f.name(), 1));
//$$                 case ShaderParam.Vec2 v -> uniforms.add(uniform(v.name(), 2));
//$$                 case ShaderParam.Vec3 v -> uniforms.add(uniform(v.name(), 3));
//$$                 case ShaderParam.Vec4 v -> uniforms.add(uniform(v.name(), 4));
//$$             }
//$$         }
//$$         return uniforms;
//$$     }
//$$     private static ShaderProgramConfig.Uniform uniform(String name, int count) {
//$$         return new ShaderProgramConfig.Uniform(name, "float", count, List.of(0.0F));
//$$     }
//$$     // Matrix uniforms must be declared with type "matrix4x4" and count=16 (4x4 floats);
//$$     // count=1 with type "float" would register them as scalar floats and silently fail.
//$$     private static ShaderProgramConfig.Uniform matrixUniform(String name) {
//$$         return new ShaderProgramConfig.Uniform(name, "matrix4x4", 16, List.of(0.0F));
//$$     }
//$$ }
//#endif
