package cn.spectra.gallium.glowoutline.shader;

import cn.spectra.gallium.Gallium;
import cn.spectra.gallium.glowoutline.ItemEffectConfig;
import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.shaders.UniformType;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;

public class GuiGlowElementPipeline {

    private static final Map<ItemEffectConfig, RenderPipeline> pipelines = new HashMap<>();
    private static final Map<RenderPipeline, GuiGlowUniformBuffer> uboByPipeline = new HashMap<>();
    private static final Map<RenderPipeline, ItemEffectConfig> configByPipeline = new HashMap<>();

    public static RenderPipeline getOrCreate(ItemEffectConfig cfg) {
        return pipelines.computeIfAbsent(cfg, c -> {
            int hash = System.identityHashCode(c);
            String location = "pipeline/gallium_gui_glow_" + Integer.toHexString(hash);
            String shaderPath = "core/" + c.shader() + "_gui";

            RenderPipeline p = RenderPipeline.builder(RenderPipelines.GUI_TEXTURED_SNIPPET)
                    .withLocation(location)
                    .withFragmentShader(Identifier.fromNamespaceAndPath("gallium", shaderPath))
                    .withColorTargetState(new ColorTargetState(BlendFunction.ADDITIVE))
                    .withUniform("GalliumGuiGlow", UniformType.UNIFORM_BUFFER)
                    .build();
            uboByPipeline.put(p, new GuiGlowUniformBuffer());
            configByPipeline.put(p, c);
            Gallium.LOGGER.info("Created GUI glow pipeline for shader '{}'", c.shader());
            return p;
        });
    }

    public static GuiGlowUniformBuffer getUbo(RenderPipeline p) {
        return uboByPipeline.get(p);
    }

    public static ItemEffectConfig getConfig(RenderPipeline p) {
        return configByPipeline.get(p);
    }

    public static void updateAllForFrame(int screenW, int screenH, float frameTime, float globalIntensity) {
        for (var entry : uboByPipeline.entrySet()) {
            ItemEffectConfig cfg = configByPipeline.get(entry.getKey());
            entry.getValue().update(frameTime, screenW, screenH, globalIntensity, cfg);
        }
    }

    public static void clear() {
        for (var ubo : uboByPipeline.values()) ubo.close();
        pipelines.clear();
        uboByPipeline.clear();
        configByPipeline.clear();
    }
}
