package cn.spectra.gallium.glowoutline.mixin.accessor;
//#if MC<1_21_05
//$$ import net.minecraft.client.renderer.RenderType;
//$$ import org.spongepowered.asm.mixin.Mixin;
//$$ import org.spongepowered.asm.mixin.gen.Accessor;
//#if MC>=1_21_02
//$$ @Mixin(RenderType.CompositeRenderType.class)
//#else
//$$ @Mixin(targets = "net.minecraft.client.renderer.RenderType$CompositeRenderType")
//#endif
//$$ public interface LegacyCompositeRenderTypeAccessor {
//$$     @Accessor("state") RenderType.CompositeState gallium$state();
//$$ }
//#else
public interface LegacyCompositeRenderTypeAccessor {}
//#endif
