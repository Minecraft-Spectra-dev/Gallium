package cn.spectra.gallium.glowoutline.shader;

import cn.spectra.gallium.Gallium;
import cn.spectra.gallium.glowoutline.ItemEffectConfig;
import com.mojang.blaze3d.pipeline.BlendFunction;
//#if MC>=1_26_00
import com.mojang.blaze3d.pipeline.ColorTargetState;
//#endif
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.shaders.UniformType;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;

/**
 * Per-(shader,params) GUI glow pipeline cache. Two rules sharing a shader name but
 * different params must NOT share a pipeline/UBO: GUI elements are submitted into
 * a layered batch and drawn later with whatever uniforms were last written, so
 * collapsing them by shader name causes color/intensity bleed across items.
 * <p>
 * Keying by {@link ItemEffectConfig} (a record whose equality covers shader+params)
 * preserves the originally-intended one-pipeline-per-config invariant.
 */
public final class GuiGlowElementPipeline {

    private static final Map<ItemEffectConfig, RenderPipeline> pipelinesByConfig = new HashMap<>();
    private static final Map<RenderPipeline, GlowUniformBuffer> uboByPipeline = new HashMap<>();
    private static final Map<RenderPipeline, ItemEffectConfig> configByPipeline = new HashMap<>();
    private static int locationCounter;

    static {
        GlowResources.registerPipeline(GuiGlowElementPipeline::clear);
    }

    private GuiGlowElementPipeline() {}

    public static RenderPipeline getOrCreate(ItemEffectConfig cfg) {
        RenderPipeline cached = pipelinesByConfig.get(cfg);
        if (cached != null) return cached;

        String shader = cfg.shader();
        String location = "pipeline/gallium_gui_glow/" + shader + "_" + (locationCounter++);
        String shaderPath = "core/" + shader + "_gui";

        RenderPipeline p = RenderPipeline.builder(RenderPipelines.GUI_TEXTURED_SNIPPET)
                .withLocation(location)
                .withFragmentShader(Identifier.fromNamespaceAndPath("gallium", shaderPath))
                //#if MC>=1_26_00
                .withColorTargetState(new ColorTargetState(BlendFunction.ADDITIVE))
                //#else
                //$$ .withBlend(BlendFunction.ADDITIVE)
                //#endif
                .withUniform("GalliumGuiGlow", UniformType.UNIFORM_BUFFER)
                .build();
        pipelinesByConfig.put(cfg, p);
        uboByPipeline.put(p, new GlowUniformBuffer("GUI Glow Uniform Buffer"));
        configByPipeline.put(p, cfg);
        Gallium.LOGGER.info("Created GUI glow pipeline for shader '{}' (variant #{}, {} params)",
                shader, locationCounter - 1, cfg.params().size());
        return p;
    }

    public static GlowUniformBuffer getUbo(RenderPipeline p) {
        return uboByPipeline.get(p);
    }

    public static ItemEffectConfig getConfig(RenderPipeline p) {
        return configByPipeline.get(p);
    }

    public static void updateAllForFrame(int screenW, int screenH, float frameTime) {
        for (var entry : uboByPipeline.entrySet()) {
            ItemEffectConfig cfg = configByPipeline.get(entry.getKey());
            entry.getValue().update(frameTime, screenW, screenH, cfg);
        }
    }

    /** Drop pipelines (and their UBOs) keyed by configs not in {@code liveConfigs}. */
    public static void retainOnly(Set<ItemEffectConfig> liveConfigs) {
        Iterator<Map.Entry<ItemEffectConfig, RenderPipeline>> it = pipelinesByConfig.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<ItemEffectConfig, RenderPipeline> e = it.next();
            if (liveConfigs.contains(e.getKey())) continue;
            RenderPipeline p = e.getValue();
            GlowUniformBuffer ubo = uboByPipeline.remove(p);
            if (ubo != null) ubo.close();
            configByPipeline.remove(p);
            it.remove();
        }
        // Counter exists only to disambiguate location strings between live pipelines, so
        // when nothing is live we can safely rewind. Without this, locationCounter grows
        // unboundedly across reloads and bloats the pipeline name in logs / RenderDoc.
        if (pipelinesByConfig.isEmpty()) {
            locationCounter = 0;
        }
    }

    public static void clear() {
        for (var ubo : uboByPipeline.values()) ubo.close();
        pipelinesByConfig.clear();
        uboByPipeline.clear();
        configByPipeline.clear();
        locationCounter = 0;
    }
}
