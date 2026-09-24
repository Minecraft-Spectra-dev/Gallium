package cn.spectra.gallium.glowoutline;
//#if MC<1_21_11
//$$ import net.minecraft.resources.ResourceLocation;
//$$ public final class LegacyResourceIds {
//$$     private LegacyResourceIds() {}
//$$     public static ResourceLocation create(String namespace, String path) {
//#if MC>=1_21_00
//$$         return ResourceLocation.fromNamespaceAndPath(namespace, path);
//#else
//$$         return new ResourceLocation(namespace, path);
//#endif
//$$     }
//$$     public static ResourceLocation parse(String value) {
//#if MC>=1_21_00
//$$         return ResourceLocation.parse(value);
//#else
//$$         return new ResourceLocation(value);
//#endif
//$$     }
//$$ }
//#else
public final class LegacyResourceIds { private LegacyResourceIds() {} }
//#endif
