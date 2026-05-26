package cn.spectra.gallium.glowoutline.shader;

import cn.spectra.gallium.Gallium;
import com.mojang.blaze3d.pipeline.BlendFunction;
//#if MC>=1_26_00
import com.mojang.blaze3d.pipeline.ColorTargetState;
//#endif
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.shaders.UniformType;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
//#if MC>=1_26_00
import java.util.Optional;
//#endif
import java.util.Set;
import net.minecraft.resources.Identifier;

public final class GlowPipeline {

    private static final Map<String, RenderPipeline> REGISTRY = new HashMap<>();

    static {
        GlowResources.registerPipeline(GlowPipeline::clearAll);
    }

    private GlowPipeline() {}

    /** Drops every cached pipeline. Used on full teardown.
     *  RenderPipeline currently has no close() in vanilla; clearing the cache lets the next
     *  reload rebuild fresh pipelines without leaking driver state long-term. */
    public static void clearAll() {
        REGISTRY.clear();
    }

    /** Drop pipelines whose shader name is no longer referenced by current rules. */
    public static void retainOnly(Set<String> liveShaders) {
        Iterator<Map.Entry<String, RenderPipeline>> it = REGISTRY.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, RenderPipeline> e = it.next();
            if (!liveShaders.contains(e.getKey())) it.remove();
        }
    }

    public static RenderPipeline getOrCreate(String shaderName) {
        return REGISTRY.computeIfAbsent(shaderName, name -> {
            RenderPipeline pipeline = RenderPipeline.builder()
                    .withLocation("pipeline/gallium_glow/" + name)
                    .withVertexShader(Identifier.fromNamespaceAndPath("gallium", "core/" + name))
                    .withFragmentShader(Identifier.fromNamespaceAndPath("gallium", "core/" + name))
                    .withSampler("DiffuseSampler")
                    .withSampler("MaskSampler")
                    .withSampler("MaskDepthSampler")
                    .withSampler("SceneDepthSampler")
                    .withUniform("GlowUniforms", UniformType.UNIFORM_BUFFER)
                    //#if MC>=1_26_00
                    .withColorTargetState(new ColorTargetState(BlendFunction.ADDITIVE))
                    //#else
                    //$$ .withBlend(BlendFunction.ADDITIVE)
                    //#endif
                    .withCull(false)
                    //#if MC>=1_26_00
                    .withDepthStencilState(Optional.empty())
                    //#endif
                    .withVertexFormat(DefaultVertexFormat.EMPTY, VertexFormat.Mode.TRIANGLES)
                    .build();
            Gallium.LOGGER.info("Created glow pipeline: {}", name);
            return pipeline;
        });
    }

    public static RenderPipeline get(String shaderName) {
        return REGISTRY.get(shaderName);
    }

    public static void init() {
        // Force static init.
    }
}
