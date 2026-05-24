package cn.spectra.gallium.glowoutline.shader;

import cn.spectra.gallium.Gallium;
import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.shaders.UniformType;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import net.minecraft.resources.Identifier;

public class GlowPipeline {

    private static final Map<String, RenderPipeline> REGISTRY = new HashMap<>();

    static {
        GlowResources.register(GlowPipeline::clearAll);
    }

    public static void clearAll() {
        // RenderPipeline currently has no close() in vanilla; clearing the cache lets
        // the next reload rebuild fresh pipelines without leaking driver state long-term.
        REGISTRY.clear();
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
                    .withColorTargetState(new ColorTargetState(BlendFunction.ADDITIVE))
                    .withCull(false)
                    .withDepthStencilState(Optional.empty())
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
        // Static initializer registers the disposer; this method exists so callers can
        // force class load deterministically during mod init.
    }
}
