package cn.spectra.gallium.glowoutline.mixin.accessor;
//#if MC<1_21_05
//$$ import net.minecraft.client.renderer.RenderStateShard;
//$$ import net.minecraft.client.renderer.RenderType;
//$$ import org.spongepowered.asm.mixin.Mixin;
//$$ import org.spongepowered.asm.mixin.gen.Accessor;
//$$ @Mixin(RenderType.CompositeState.class)
//$$ public interface LegacyCompositeStateAccessor {
//$$     @Accessor("textureState") RenderStateShard.EmptyTextureStateShard gallium$texture();
//$$ }
//#else
public interface LegacyCompositeStateAccessor {}
//#endif
