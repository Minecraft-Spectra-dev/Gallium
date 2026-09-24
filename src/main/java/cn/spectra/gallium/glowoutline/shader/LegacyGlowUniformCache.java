package cn.spectra.gallium.glowoutline.shader;

//#if MC<1_21_05
//$$ import cn.spectra.gallium.glowoutline.ItemEffectConfig;
//$$ import java.util.Collections;
//$$ import java.util.IdentityHashMap;
//$$ import java.util.Set;
//#if MC>=1_21_02
//$$ import net.minecraft.client.renderer.CompiledShaderProgram;
//#else
//$$ import net.minecraft.client.renderer.ShaderInstance;
//#endif
//$$
//$$ final class LegacyGlowUniformCache {
//$$     private static final Set<Object> owned = Collections.newSetFromMap(new IdentityHashMap<>());
//$$     private static final String[] FRAME_UNIFORMS = { "FrameTimeCounter", "ScreenSize",
//$$             "ShaderAlign", "ShaderOffset", "GalliumItemDistance", "GalliumWorldToUv",
//$$             "GalliumMaskBounds",
//$$             "ProjMat", "ModelViewMat", "ColorModulator", "TextureMat", "FogStart", "FogEnd",
//$$             "FogColor", "FogShape", "GameTime", "LineWidth", "GlintAlpha",
//$$             "Light0_Direction", "Light1_Direction" };
//$$     static { GlowResources.register(owned::clear); }
//$$     private LegacyGlowUniformCache() {}
//$$
//#if MC>=1_21_02
//$$     static void enable(CompiledShaderProgram program, ItemEffectConfig config) {
//#else
//$$     static void enable(ShaderInstance program, ItemEffectConfig config) {
//#endif
//$$         if (!owned.add(program)) return;
//$$         for (String name : FRAME_UNIFORMS) enable(program.getUniform(name));
//$$         for (var parameter : config.params()) enable(program.getUniform(parameter.name()));
//$$     }
//$$
//$$     private static void enable(Object uniform) {
//$$         if (uniform instanceof CachedGlowUniform cached) cached.gallium$enableUploadCache();
//$$     }
//$$ }
//#else
final class LegacyGlowUniformCache { private LegacyGlowUniformCache() {} }
//#endif
