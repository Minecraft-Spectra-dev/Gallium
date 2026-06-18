package cn.spectra.gallium.glowoutline.shader;

import cn.spectra.gallium.Gallium;
import cn.spectra.gallium.glowoutline.ItemEffectConfig;
import cn.spectra.gallium.glowoutline.ShaderParam;
import com.mojang.blaze3d.pipeline.BlendFunction;
//#if MC>=1_26_00
import com.mojang.blaze3d.pipeline.ColorTargetState;
//#else
//$$ import com.mojang.blaze3d.platform.DepthTestFunction;
//#endif
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.shaders.UniformType;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import java.util.HashMap;
import java.util.Map;
//#if MC>=1_26_00
import java.util.Optional;
//#endif
import java.util.Set;
//#if MC>=1_21_09
import net.minecraft.resources.Identifier;
//#else
//$$ import net.minecraft.resources.ResourceLocation;
//#endif

public final class GlowPipeline {

    private static final Map<String, RenderPipeline> REGISTRY = new HashMap<>();
    //#if MC<1_21_06
    //$$ // 1.21.5 keys by ItemEffectConfig — the per-config user-param uniforms (declared
    //$$ // statically in the pipeline because 1.21.5 has no UBO API) make two configs
    //$$ // sharing a shader name with different params NON-interchangeable. Mirrors
    //$$ // GuiGlowElementPipeline's config-keyed map, with the same one-pipeline-per-config
    //$$ // invariant. The String-keyed REGISTRY above stays unused on 1.21.5.
    //$$ private static final Map<ItemEffectConfig, RenderPipeline> BY_CONFIG = new HashMap<>();
    //$$ private static int worldLocationCounter;
    //#endif

    static {
        GlowResources.registerPipeline(GlowPipeline::clearAll);
    }

    private GlowPipeline() {}

    /** Drops every cached pipeline on full teardown. */
    public static void clearAll() {
        REGISTRY.clear();
        //#if MC<1_21_06
        //$$ BY_CONFIG.clear();
        //$$ worldLocationCounter = 0;
        //#endif
    }

    /** Drop pipelines whose shader name is no longer referenced (1.21.6+ key). */
    public static void retainOnly(Set<String> liveShaders) {
        REGISTRY.keySet().removeIf(name -> !liveShaders.contains(name));
    }

    //#if MC<1_21_06
    //$$ /** 1.21.5: drop pipelines whose ItemEffectConfig is no longer referenced.
    //$$  *  Must be called alongside {@link #retainOnly(Set)} on 1.21.5 — that
    //$$  *  shader-name pruning is a no-op against the config-keyed BY_CONFIG cache,
    //$$  *  so a config whose params changed across reload but shader name stayed the
    //$$  *  same would otherwise leak the stale pre-reload pipeline forever. */
    //$$ public static void retainOnlyConfigs(Set<ItemEffectConfig> liveConfigs) {
    //$$     BY_CONFIG.keySet().removeIf(cfg -> !liveConfigs.contains(cfg));
    //$$     if (BY_CONFIG.isEmpty()) {
    //$$         worldLocationCounter = 0;
    //$$     }
    //$$ }
    //#endif

    //#if MC>=1_21_06
    /** 1.21.6+: config-ignoring — UBO handles all params generically. */
    public static RenderPipeline getOrCreate(ItemEffectConfig cfg) {
        return getOrCreate(cfg.shader());
    }
    //#else
    //$$ /**
    //$$  * 1.21.5: declare each user param as an individual uniform.
    //$$  * <p>
    //$$  * When the shader uses a named uniform block without an instance name
    //$$  * ({@code layout(std140) uniform GlowUniforms { ... };}),
    //$$  * {@code glGetUniformLocation} returns valid locations for the block
    //$$  * members.  As long as no buffer is bound to the block binding point
    //$$  * (UBOs are unsupported by the 1.21.5 pipeline API), the driver
    //$$  * honours the individual {@code glUniform*} calls.
    //$$  * <p>
    //$$  * Keyed by {@link ItemEffectConfig} (not just shader name) — two configs
    //$$  * sharing a shader name with different params build separate pipelines so
    //$$  * each pipeline's static uniform declarations match its config's params,
    //$$  * mirroring GuiGlowElementPipeline.
    //$$  */
    //$$ public static RenderPipeline getOrCreate(ItemEffectConfig cfg) {
    //$$     if (cfg.shader().isEmpty()) return null;
    //$$     return BY_CONFIG.computeIfAbsent(cfg, c -> {
    //$$         String shaderName = c.shader();
    //$$         int variant = worldLocationCounter++;
    //$$         var builder = RenderPipeline.builder()
    //$$                 .withLocation("pipeline/gallium_glow/" + shaderName + "_" + variant)
    //$$                 .withVertexShader(ResourceLocation.fromNamespaceAndPath("gallium", "core/" + shaderName))
    //$$                 .withFragmentShader(ResourceLocation.fromNamespaceAndPath("gallium", "core/" + shaderName))
    //$$                 .withSampler("DiffuseSampler")
    //$$                 .withSampler("MaskSampler")
    //$$                 .withSampler("MaskDepthSampler")
    //$$                 .withSampler("SceneDepthSampler")
    //$$                 .withUniform("FrameTimeCounter", UniformType.FLOAT)
    //$$                 .withUniform("ScreenSize", UniformType.VEC2)
    //$$                 .withUniform("ShaderAlign", UniformType.VEC4);
    //$$         for (ShaderParam p : c.params()) {
    //$$             switch (p) {
    //$$                 case ShaderParam.Float f2 -> builder.withUniform(f2.name(), UniformType.FLOAT);
    //$$                 case ShaderParam.Vec2 v2 -> builder.withUniform(v2.name(), UniformType.VEC2);
    //$$                 case ShaderParam.Vec3 v3 -> builder.withUniform(v3.name(), UniformType.VEC3);
    //$$                 case ShaderParam.Vec4 v4 -> builder.withUniform(v4.name(), UniformType.VEC4);
    //$$             }
    //$$         }
    //$$         Gallium.LOGGER.info("Created glow pipeline: {} (1.21.5, variant #{}, {} params)",
    //$$                 shaderName, variant, c.params().size());
    //$$         return builder
    //$$                 .withBlend(BlendFunction.ADDITIVE)
    //$$                 .withCull(false)
    //$$                 .withDepthTestFunction(DepthTestFunction.NO_DEPTH_TEST)
    //$$                 .withDepthWrite(false)
    //$$                 .withVertexFormat(DefaultVertexFormat.BLIT_SCREEN, VertexFormat.Mode.TRIANGLES)
    //$$                 .build();
    //$$     });
    //$$ }
    //#endif

    public static RenderPipeline getOrCreate(String shaderName) {
        if (shaderName.isEmpty()) return null;
        return REGISTRY.computeIfAbsent(shaderName, name -> {
            RenderPipeline pipeline = RenderPipeline.builder()
                    .withLocation("pipeline/gallium_glow/" + name)
                    //#if MC>=1_21_09
                    .withVertexShader(Identifier.fromNamespaceAndPath("gallium", "core/" + name))
                    .withFragmentShader(Identifier.fromNamespaceAndPath("gallium", "core/" + name))
                    //#else
                    //$$ .withVertexShader(ResourceLocation.fromNamespaceAndPath("gallium", "core/" + name))
                    //$$ .withFragmentShader(ResourceLocation.fromNamespaceAndPath("gallium", "core/" + name))
                    //#endif
                    .withSampler("DiffuseSampler")
                    .withSampler("MaskSampler")
                    .withSampler("MaskDepthSampler")
                    .withSampler("SceneDepthSampler")
                    //#if MC>=1_21_06
                    .withUniform("GlowUniforms", UniformType.UNIFORM_BUFFER)
                    //#endif
                    //#if MC>=1_26_00
                    .withColorTargetState(new ColorTargetState(BlendFunction.ADDITIVE))
                    //#else
                    //$$ .withBlend(BlendFunction.ADDITIVE)
                    //#endif
                    .withCull(false)
                    //#if MC>=1_26_00
                    .withDepthStencilState(Optional.empty())
                    //#else
                    //$$ .withDepthTestFunction(DepthTestFunction.NO_DEPTH_TEST)
                    //$$ .withDepthWrite(false)
                    //#endif
                    //#if MC>=1_21_06
                    .withVertexFormat(DefaultVertexFormat.EMPTY, VertexFormat.Mode.TRIANGLES)
                    //#else
                    //$$ .withVertexFormat(DefaultVertexFormat.BLIT_SCREEN, VertexFormat.Mode.TRIANGLES)
                    //#endif
                    .build();
            Gallium.LOGGER.info("Created glow pipeline: {}", name);
            return pipeline;
        });
    }

    public static RenderPipeline get(String shaderName) {
        return REGISTRY.get(shaderName);
    }

    //#if MC<1_21_06
    //$$ /** 1.21.5 lookup: returns the pre-built pipeline for this exact config, or
    //$$  *  null if {@link ItemEffectsManager} hasn't built it yet. Composite callers
    //$$  *  must use this overload (NOT the bare-name {@link #get(String)}, which only
    //$$  *  serves the unused 1.21.6+ String-keyed REGISTRY on this version). */
    //$$ public static RenderPipeline get(ItemEffectConfig cfg) {
    //$$     return BY_CONFIG.get(cfg);
    //$$ }
    //#endif

    public static void init() {
        // Force static init.
    }
}
