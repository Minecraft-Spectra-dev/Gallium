package cn.spectra.gallium.glowoutline.mixin.accessor;
//#if MC==1_21_11
//$$ import java.util.*;
//$$ import net.minecraft.client.model.geom.ModelPart;
//$$ import org.spongepowered.asm.mixin.Mixin;
//$$ import org.spongepowered.asm.mixin.gen.Accessor;
//$$ @Mixin(ModelPart.class)
//$$ public interface NativePrimaryPartAccessor {
//$$  @Accessor("cubes") List<ModelPart.Cube> gallium$cubes();
//$$  @Accessor("children") Map<String,ModelPart> gallium$children();
//$$ }
//#else
public interface NativePrimaryPartAccessor {}
//#endif
