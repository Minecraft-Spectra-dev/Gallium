package cn.spectra.gallium.glowoutline.mixin.accessor;
//#if MC==1_21_11
//$$ import org.spongepowered.asm.mixin.Mixin;
//$$ import org.spongepowered.asm.mixin.gen.Accessor;
//$$ @Mixin(com.mojang.blaze3d.vertex.BufferBuilder.class)
//$$ public interface NativePrimaryBuilderAccessor {
//$$  @Accessor("vertices") int gallium$vertexCount();
//$$ }
//#else
public interface NativePrimaryBuilderAccessor {}
//#endif
