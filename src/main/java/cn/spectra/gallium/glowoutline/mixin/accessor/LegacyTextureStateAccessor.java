package cn.spectra.gallium.glowoutline.mixin.accessor;
//#if MC<1_21_05
//$$ import java.util.Optional;
//$$ import net.minecraft.client.renderer.RenderStateShard;
//$$ import net.minecraft.resources.ResourceLocation;
//$$ import org.spongepowered.asm.mixin.Mixin;
//$$ import org.spongepowered.asm.mixin.gen.Invoker;
//$$ @Mixin(RenderStateShard.EmptyTextureStateShard.class)
//$$ public interface LegacyTextureStateAccessor {
//$$     @Invoker("cutoutTexture") Optional<ResourceLocation> gallium$cutoutTexture();
//$$ }
//#else
public interface LegacyTextureStateAccessor {}
//#endif
