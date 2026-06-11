package cn.spectra.gallium.glowoutline.mixin.accessor;

//#if MC>=1_21_09
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher;
//#if MC>=1_26_00
import net.minecraft.client.resources.model.sprite.AtlasManager;
//#else
//$$ import net.minecraft.client.resources.model.AtlasManager;
//#endif
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(FeatureRenderDispatcher.class)
public interface FeatureRenderDispatcherAccessor {
    @Accessor("atlasManager")
    AtlasManager gallium$getAtlasManager();
}
//#else
//$$ // FeatureRenderDispatcher is part of the 1.21.9 submit-node system; absent on
//$$ // 1.21.6–1.21.8. Empty stub interface, stripped from mixins.json via
//$$ // STUB_MIXIN_CLASSES_PRE_1_21_09 in common.gradle.
//$$ public interface FeatureRenderDispatcherAccessor {}
//#endif
