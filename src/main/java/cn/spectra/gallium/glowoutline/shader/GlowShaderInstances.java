package cn.spectra.gallium.glowoutline.shader;

//#if MC<1_21_02
//$$ import cn.spectra.gallium.Gallium;
//$$ import cn.spectra.gallium.glowoutline.ItemEffectConfig;
//$$ import cn.spectra.gallium.glowoutline.ShaderParam;
//$$ import com.google.common.io.CharStreams;
//$$ import com.mojang.blaze3d.shaders.Program;
//$$ import com.mojang.blaze3d.vertex.DefaultVertexFormat;
//$$ import com.mojang.blaze3d.vertex.VertexFormat;
//$$ import java.io.ByteArrayInputStream;
//$$ import java.io.InputStream;
//$$ import java.io.Reader;
//$$ import java.nio.charset.StandardCharsets;
//$$ import java.util.HashMap;
//$$ import java.util.IdentityHashMap;
//$$ import java.util.List;
//$$ import java.util.Map;
//$$ import java.util.Optional;
//$$ import java.util.regex.Matcher;
//$$ import java.util.regex.Pattern;
//$$ import net.minecraft.client.Minecraft;
//$$ import net.minecraft.client.renderer.ShaderInstance;
//$$ import net.minecraft.resources.ResourceLocation;
//$$ import net.minecraft.server.packs.PackResources;
//$$ import net.minecraft.server.packs.resources.Resource;
//$$ import net.minecraft.server.packs.resources.ResourceManager;
//$$ import net.minecraft.server.packs.resources.ResourceProvider;
//$$
//$$ /**
//$$  * 1.21.1 (pre-1.21.2 rendering rework) glow-shader factory.
//$$  *
//$$  * <p>1.21.1 has no {@code CompiledShaderProgram}/{@code ShaderProgramConfig}; the only
//$$  * runtime shader path is {@link ShaderInstance}, whose constructor loads a JSON definition
//$$  * from {@code minecraft:shaders/core/<name>.json} (hardcoded namespace) plus {@code .vsh}/
//$$  * {@code .fsh} program files. Gallium's shaders are pack-supplied {@code gallium:} GLSL with
//$$  * dynamic names + per-config uniforms and ship no JSON. So we wrap the {@link ResourceProvider}
//$$  * to synthesize the JSON on the fly and to redirect the {@code minecraft:.../<synth>.vsh/.fsh}
//$$  * reads to the real gallium sources, applying {@link UboRewriter} (the pack ships
//$$  * {@code layout(std140) uniform {...}} for the 1.21.6+ UBO path; 1.21.1 has no UBO).
//$$  *
//$$  * <p>Each created instance uses a UNIQUE synthetic program name so the global
//$$  * {@link Program.Type} cache never collapses two configs that share a shader name but differ
//$$  * in params. {@link ShaderInstance#close()} only frees the linked program, not the attached
//$$  * vertex/fragment {@link Program}s, so we track the synthetic names we own and evict them in
//$$  * {@link #dispose(ShaderInstance)} (the vanilla {@code position_tex_color} vertex program used
//$$  * by the GUI path is NOT owned and never evicted).
//$$  */
//$$ public final class GlowShaderInstances {
//$$     private GlowShaderInstances() {}
//$$
//$$     private static int counter = 0;
//$$
//$$     /** Per-instance ownership of synthetic Program names, for eviction on dispose. */
//$$     private static final Map<ShaderInstance, Owned> OWNED = new IdentityHashMap<>();
//$$
//$$     private record Owned(List<String> vertex, List<String> fragment) {}
//$$
//$$     /** World composite shader: gallium {@code core/<shader>.vsh+.fsh}, screen-quad format. */
//$$     public static ShaderInstance createWorld(ItemEffectConfig cfg) {
//$$         String synth = "gallium_glow_" + sanitize(cfg.shader()) + "_" + (counter++);
//$$         StringBuilder json = new StringBuilder();
//$$         json.append("{\"vertex\":\"").append(synth).append("\",\"fragment\":\"").append(synth).append("\",");
//$$         // Declare the same four samplers the world composite contract has on 1.21.2+. If the
//$$         // pack's .fsh doesn't read one (e.g. DiffuseSampler), vanilla's updateLocations removes
//$$         // it from BOTH samplerNames AND samplerLocations in lock-step, so apply()'s loop stays
//$$         // aligned. drawGlow then setSamplers all four; unused ones become a no-op map entry.
//$$         appendSamplers(json, "DiffuseSampler", "MaskSampler", "MaskDepthSampler", "SceneDepthSampler");
//$$         json.append(",\"uniforms\":[");
//$$         appendFloatUniform(json, "FrameTimeCounter", 1, true);
//$$         appendFloatUniform(json, "ScreenSize", 2, false);
//$$         appendFloatUniform(json, "ShaderAlign", 4, false);
//$$         appendParamUniforms(json, cfg);
//$$         json.append("]}");
//$$
//$$         Map<ResourceLocation, ResourceLocation> rewrite = new HashMap<>();
//$$         rewrite.put(coreLoc(synth + ".vsh"), galliumLoc("core/" + cfg.shader() + ".vsh"));
//$$         rewrite.put(coreLoc(synth + ".fsh"), galliumLoc("core/" + cfg.shader() + ".fsh"));
//$$
//$$         ShaderInstance si = build(synth, json.toString(), rewrite,
//$$                 galliumLoc("core/" + cfg.shader() + ".fsh"), DefaultVertexFormat.BLIT_SCREEN);
//$$         if (si != null) {
//$$             OWNED.put(si, new Owned(List.of(synth), List.of(synth)));
//$$             Gallium.LOGGER.info("Created 1.21.1 world glow ShaderInstance: {} ({} params)",
//$$                     cfg.shader(), cfg.params().size());
//$$         }
//$$         return si;
//$$     }
//$$
//$$     /** GUI composite shader: vanilla {@code position_tex_color} vertex + gallium
//$$      *  {@code core/<shader>_gui.fsh} fragment, POSITION_TEX_COLOR format. */
//$$     public static ShaderInstance createGui(ItemEffectConfig cfg) {
//$$         String synth = "gallium_glow_gui_" + sanitize(cfg.shader()) + "_" + (counter++);
//$$         StringBuilder json = new StringBuilder();
//$$         // vertex reuses the vanilla position_tex_color program (already compiled by the game,
//$$         // so its .vsh is never re-read through us); fragment is the synthetic gallium one.
//$$         json.append("{\"vertex\":\"position_tex_color\",\"fragment\":\"").append(synth).append("\",");
//$$         appendSamplers(json, "Sampler0");
//$$         json.append(",\"uniforms\":[");
//$$         appendMatrixUniform(json, "ModelViewMat", true);
//$$         appendMatrixUniform(json, "ProjMat", false);
//$$         appendFloatUniform(json, "ColorModulator", 4, false);
//$$         appendFloatUniform(json, "FrameTimeCounter", 1, false);
//$$         appendFloatUniform(json, "ScreenSize", 2, false);
//$$         appendFloatUniform(json, "ShaderAlign", 4, false);
//$$         appendParamUniforms(json, cfg);
//$$         json.append("]}");
//$$
//$$         Map<ResourceLocation, ResourceLocation> rewrite = new HashMap<>();
//$$         rewrite.put(coreLoc(synth + ".fsh"), galliumLoc("core/" + cfg.shader() + "_gui.fsh"));
//$$
//$$         ShaderInstance si = build(synth, json.toString(), rewrite,
//$$                 galliumLoc("core/" + cfg.shader() + "_gui.fsh"), DefaultVertexFormat.POSITION_TEX_COLOR);
//$$         if (si != null) {
//$$             OWNED.put(si, new Owned(List.of(), List.of(synth)));
//$$             Gallium.LOGGER.info("Created 1.21.1 GUI glow ShaderInstance: {} ({} params)",
//$$                     cfg.shader(), cfg.params().size());
//$$         }
//$$         return si;
//$$     }
//$$
//$$     /** Closes the instance AND evicts the synthetic Programs it owns from the global cache. */
//$$     public static void dispose(ShaderInstance si) {
//$$         if (si == null) return;
//$$         try {
//$$             si.close();
//$$         } catch (Exception e) {
//$$             Gallium.LOGGER.warn("Error closing glow ShaderInstance", e);
//$$         }
//$$         Owned owned = OWNED.remove(si);
//$$         if (owned != null) {
//$$             for (String name : owned.vertex()) evictProgram(Program.Type.VERTEX, name);
//$$             for (String name : owned.fragment()) evictProgram(Program.Type.FRAGMENT, name);
//$$         }
//$$     }
//$$
//$$     private static void evictProgram(Program.Type type, String name) {
//$$         Program program = type.getPrograms().get(name);
//$$         if (program != null) program.close(); // glDeleteShader + removes from the type cache
//$$     }
//$$
//$$     private static ShaderInstance build(String jsonName, String jsonText,
//$$                                         Map<ResourceLocation, ResourceLocation> rewrite,
//$$                                         ResourceLocation packAnchor, VertexFormat format) {
//$$         ResourceManager rm = Minecraft.getInstance().getResourceManager();
//$$         PackResources anchorPack = rm.getResource(packAnchor).map(Resource::source).orElse(null);
//$$         if (anchorPack == null) {
//$$             Gallium.LOGGER.error("Missing glow shader source {}", packAnchor);
//$$             return null;
//$$         }
//$$         ResourceProvider provider = new SynthProvider(rm, coreLoc(jsonName + ".json"), jsonText,
//$$                 anchorPack, rewrite);
//$$         try {
//$$             return new ShaderInstance(provider, jsonName, format);
//$$         } catch (Exception e) {
//$$             Gallium.LOGGER.error("Failed to build 1.21.1 glow ShaderInstance '{}'", jsonName, e);
//$$             return null;
//$$         }
//$$     }
//$$
//$$     // shaders/core/<x> in the minecraft namespace — ShaderInstance hardcodes that namespace.
//$$     private static ResourceLocation coreLoc(String tail) {
//$$         return ResourceLocation.withDefaultNamespace("shaders/core/" + tail);
//$$     }
//$$
//$$     private static ResourceLocation galliumLoc(String tail) {
//$$         return ResourceLocation.fromNamespaceAndPath("gallium", "shaders/" + tail);
//$$     }
//$$
//$$     private static String sanitize(String shader) {
//$$         return shader.replaceAll("[^a-z0-9_]", "_");
//$$     }
//$$
//$$     private static void appendSamplers(StringBuilder json, String... names) {
//$$         json.append("\"samplers\":[");
//$$         for (int i = 0; i < names.length; i++) {
//$$             if (i > 0) json.append(',');
//$$             json.append("{\"name\":\"").append(names[i]).append("\"}");
//$$         }
//$$         json.append(']');
//$$     }
//$$
//$$     private static void appendFloatUniform(StringBuilder json, String name, int count, boolean first) {
//$$         if (!first) json.append(',');
//$$         json.append("{\"name\":\"").append(name).append("\",\"type\":\"float\",\"count\":")
//$$             .append(count).append(",\"values\":[0.0]}");
//$$     }
//$$
//$$     private static void appendMatrixUniform(StringBuilder json, String name, boolean first) {
//$$         if (!first) json.append(',');
//$$         json.append("{\"name\":\"").append(name)
//$$             .append("\",\"type\":\"matrix4x4\",\"count\":16,\"values\":[0.0]}");
//$$     }
//$$
//$$     private static void appendParamUniforms(StringBuilder json, ItemEffectConfig cfg) {
//$$         for (ShaderParam p : cfg.params()) {
//$$             switch (p) {
//$$                 case ShaderParam.Float f -> appendFloatUniform(json, f.name(), 1, false);
//$$                 case ShaderParam.Vec2 v -> appendFloatUniform(json, v.name(), 2, false);
//$$                 case ShaderParam.Vec3 v -> appendFloatUniform(json, v.name(), 3, false);
//$$                 case ShaderParam.Vec4 v -> appendFloatUniform(json, v.name(), 4, false);
//$$             }
//$$         }
//$$     }
//$$
//$$     // Matches `#moj_import <ns:path>` with the same whitespace tolerance as vanilla
//$$     // GlslPreprocessor.REGEX_MOJ_IMPORT, capturing namespace + path separately.
//$$     private static final Pattern NAMESPACED_IMPORT = Pattern.compile(
//$$             "#(?:\\h)*moj_import(?:\\h)*<([a-z0-9_.-]+):([a-z0-9_./-]+)>");
//$$
//$$     /** Replaces every `#moj_import <gallium:foo.glsl>` (or any namespaced angled import)
//$$      *  inline with the actual include file contents from `<ns>:shaders/include/<path>`.
//$$      *  Unquoted simple-name imports like `#moj_import <foo.glsl>` are NOT matched and pass
//$$      *  through to vanilla's preprocessor (which handles them via the default include path).
//$$      *  No recursive expansion — includes that themselves namespace-import would need another
//$$      *  pass; keep this simple until a real shader hits the case. */
//$$     private static String inlineGalliumImports(String source, ResourceManager rm) {
//$$         Matcher m = NAMESPACED_IMPORT.matcher(source);
//$$         if (!m.find()) return source;
//$$         StringBuilder out = new StringBuilder(source.length() + 256);
//$$         m.reset();
//$$         int last = 0;
//$$         while (m.find()) {
//$$             out.append(source, last, m.start());
//$$             String ns = m.group(1);
//$$             String path = m.group(2);
//$$             ResourceLocation includeLoc = ResourceLocation.fromNamespaceAndPath(ns, "shaders/include/" + path);
//$$             Optional<Resource> res = rm.getResource(includeLoc);
//$$             if (res.isPresent()) {
//$$                 try (Reader r = res.get().openAsReader()) {
//$$                     out.append(CharStreams.toString(r));
//$$                     if (out.charAt(out.length() - 1) != '\n') out.append('\n');
//$$                 } catch (Exception e) {
//$$                     Gallium.LOGGER.error("Failed to inline glow shader include {}", includeLoc, e);
//$$                     out.append("#error failed to inline ").append(includeLoc).append('\n');
//$$                 }
//$$             } else {
//$$                 Gallium.LOGGER.error("Missing glow shader include {}", includeLoc);
//$$                 out.append("#error missing include ").append(includeLoc).append('\n');
//$$             }
//$$             last = m.end();
//$$         }
//$$         out.append(source, last, source.length());
//$$         return out.toString();
//$$     }
//$$
//$$     /** ResourceProvider that serves the synthesized JSON, rewrites the synthetic GLSL reads to
//$$      *  gallium sources (UboRewriter-applied), and delegates everything else to the real manager. */
//$$     private static final class SynthProvider implements ResourceProvider {
//$$         private final ResourceManager delegate;
//$$         private final ResourceLocation jsonLoc;
//$$         private final String jsonText;
//$$         private final PackResources anchorPack;
//$$         private final Map<ResourceLocation, ResourceLocation> rewrite;
//$$
//$$         SynthProvider(ResourceManager delegate, ResourceLocation jsonLoc, String jsonText,
//$$                       PackResources anchorPack, Map<ResourceLocation, ResourceLocation> rewrite) {
//$$             this.delegate = delegate;
//$$             this.jsonLoc = jsonLoc;
//$$             this.jsonText = jsonText;
//$$             this.anchorPack = anchorPack;
//$$             this.rewrite = rewrite;
//$$         }
//$$
//$$         @Override
//$$         public Optional<Resource> getResource(ResourceLocation loc) {
//$$             if (loc.equals(jsonLoc)) {
//$$                 return Optional.of(stringResource(jsonText));
//$$             }
//$$             ResourceLocation src = rewrite.get(loc);
//$$             if (src != null) {
//$$                 Optional<Resource> real = delegate.getResource(src);
//$$                 if (real.isEmpty()) return Optional.empty();
//$$                 try (Reader reader = real.get().openAsReader()) {
//$$                     String text = CharStreams.toString(reader);
//$$                     // Inline namespaced `#moj_import <gallium:foo>` ourselves BEFORE handing the
//$$                     // source to vanilla ShaderInstance. Vanilla's pre-1.21.2 GlslPreprocessor
//$$                     // prefixes angled imports with "shaders/include/" and then ResourceLocation
//$$                     // .parse(...)s the result — so `<gallium:foo>` becomes namespace
//$$                     // "shaders/include/gallium" (illegal: '/' rejected) and throws. Inlining
//$$                     // here keeps the gallium namespace contract working without patching vanilla.
//$$                     text = inlineGalliumImports(text, delegate);
//$$                     text = UboRewriter.rewrite(text);
//$$                     return Optional.of(stringResource(text));
//$$                 } catch (Exception e) {
//$$                     Gallium.LOGGER.error("Failed to read glow shader source {}", src, e);
//$$                     return Optional.empty();
//$$                 }
//$$             }
//$$             return delegate.getResource(loc);
//$$         }
//$$
//$$         private Resource stringResource(String text) {
//$$             byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
//$$             return new Resource(anchorPack, () -> (InputStream) new ByteArrayInputStream(bytes));
//$$         }
//$$     }
//$$ }
//#else
public final class GlowShaderInstances {
    private GlowShaderInstances() {}
}
//#endif
