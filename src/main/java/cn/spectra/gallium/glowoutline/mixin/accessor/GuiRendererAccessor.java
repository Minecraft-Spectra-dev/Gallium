package cn.spectra.gallium.glowoutline.mixin.accessor;

//#if MC<1_26_00
//$$ import com.mojang.blaze3d.textures.GpuTextureView;
//$$ import net.minecraft.client.gui.render.GuiRenderer;
//$$ import org.spongepowered.asm.mixin.Mixin;
//$$ import org.spongepowered.asm.mixin.gen.Accessor;
//$$
//$$ /**
//$$  * 1.21.11 inlines the items atlas as a private GpuTextureView field on
//$$  * GuiRenderer rather than exposing it through a SlotView like 26.1's
//$$  * GuiItemAtlas. We need that view to sample the rendered item bitmap
//$$  * for the glow mask pipeline.
//$$  */
//$$ @Mixin(GuiRenderer.class)
//$$ public interface GuiRendererAccessor {
//$$     @Accessor("itemsAtlasView")
//$$     GpuTextureView gallium$getItemsAtlasView();
//$$ }
//#else
public interface GuiRendererAccessor {}
//#endif
