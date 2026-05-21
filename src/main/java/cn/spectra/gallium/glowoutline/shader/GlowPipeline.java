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
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;

public class GlowPipeline {

    private static final Map<String, RenderPipeline> REGISTRY = new HashMap<>();

    public static RenderPipeline getOrRegister(String shaderName) {
        return REGISTRY.computeIfAbsent(shaderName, name -> {
            RenderPipeline pipeline = RenderPipelines.register(
                    RenderPipeline.builder()
                            .withLocation("pipeline/gallium_glow/" + name)
                            .withVertexShader(Identifier.fromNamespaceAndPath("gallium", "core/" + name))
                            .withFragmentShader(Identifier.fromNamespaceAndPath("gallium", "core/" + name))
                            .withSampler("DiffuseSampler")
                            .withSampler("MaskSampler")
                            .withUniform("GlowUniforms", UniformType.UNIFORM_BUFFER)
                            .withColorTargetState(new ColorTargetState(BlendFunction.ADDITIVE))
                            .withCull(false)
                            .withDepthStencilState(Optional.empty())
                            .withVertexFormat(DefaultVertexFormat.EMPTY, VertexFormat.Mode.TRIANGLES)
                            .build()
            );
            Gallium.LOGGER.info("Registered glow pipeline: {}", name);
            return pipeline;
        });
    }

    public static RenderPipeline get(String shaderName) {
        return REGISTRY.get(shaderName);
    }

    public static void init() {}
}
