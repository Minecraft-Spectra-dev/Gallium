package cn.spectra.gallium.glowoutline.mixin.accessor;
//#if MC==1_21_11
//$$ import org.spongepowered.asm.mixin.Mixin;
//$$ import org.spongepowered.asm.mixin.gen.Accessor;
//$$ @Mixin(com.mojang.blaze3d.opengl.GlBuffer.class)
//$$ public interface NativePrimaryBufferAccessor {
//$$  @Accessor("handle") int gallium$handle();
//$$ }
//#else
public interface NativePrimaryBufferAccessor {}
//#endif
