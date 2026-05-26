package cn.spectra.gallium.glowoutline.mixin.accessor;

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
