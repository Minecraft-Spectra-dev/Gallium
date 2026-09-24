package cn.spectra.gallium.glowoutline.shader;

//#if MC>=1_21_06
import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.RenderPipeline;
//#if MC>=1_26_00
import com.mojang.blaze3d.pipeline.ColorTargetState;
//#endif
import net.minecraft.client.renderer.RenderPipelines;
//#if MC>=1_21_09
import net.minecraft.resources.Identifier;
//#else
//$$ import net.minecraft.resources.ResourceLocation;
//#endif

/** Unlike vanilla position_tex_color, this blit preserves emissive RGB at alpha zero. */
public final class GuiEntityBlitPipeline {
    private static RenderPipeline pipeline;
    static { GlowResources.registerPipeline(() -> pipeline = null); }
    private GuiEntityBlitPipeline() {}

    public static RenderPipeline get() {
        if (pipeline == null) {
            pipeline = RenderPipeline.builder(RenderPipelines.GUI_TEXTURED_SNIPPET)
                    .withLocation("gallium/entity_preview_blit")
                    //#if MC>=1_21_09
                    .withFragmentShader(Identifier.fromNamespaceAndPath("gallium", "core/internal/entity_preview_blit_gui"))
                    //#else
                    //$$ .withFragmentShader(net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("gallium", "core/internal/entity_preview_blit_gui"))
                    //#endif
                    //#if MC>=1_26_00
                    .withColorTargetState(new ColorTargetState(BlendFunction.TRANSLUCENT_PREMULTIPLIED_ALPHA))
                    //#else
                    //$$ .withBlend(new BlendFunction(com.mojang.blaze3d.platform.SourceFactor.ONE,
                    //$$         com.mojang.blaze3d.platform.DestFactor.ONE_MINUS_SRC_ALPHA,
                    //$$         com.mojang.blaze3d.platform.SourceFactor.ONE,
                    //$$         com.mojang.blaze3d.platform.DestFactor.ONE_MINUS_SRC_ALPHA))
                    //#endif
                    .build();
        }
        return pipeline;
    }
}
//#else
//$$ public final class GuiEntityBlitPipeline {}
//#endif
