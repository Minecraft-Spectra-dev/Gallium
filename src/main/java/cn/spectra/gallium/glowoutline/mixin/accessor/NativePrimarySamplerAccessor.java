package cn.spectra.gallium.glowoutline.mixin.accessor;
//#if MC==1_21_11
//$$ import com.mojang.blaze3d.opengl.*;
//$$ import org.spongepowered.asm.mixin.Mixin;
//$$ import org.spongepowered.asm.mixin.gen.Accessor;
//$$ // The nested type is protected in Java; the Mixin processor reports its class-file public flag.
//$$ @SuppressWarnings("public-target")
//$$ @Mixin(targets="com.mojang.blaze3d.opengl.GlRenderPass$TextureViewAndSampler")
//$$ public interface NativePrimarySamplerAccessor {
//$$  @Accessor("view") GlTextureView gallium$view();
//$$  @Accessor("sampler") GlSampler gallium$sampler();
//$$ }
//#else
public interface NativePrimarySamplerAccessor {}
//#endif
