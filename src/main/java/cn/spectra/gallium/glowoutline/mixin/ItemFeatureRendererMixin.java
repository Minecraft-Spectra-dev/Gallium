package cn.spectra.gallium.glowoutline.mixin;

import cn.spectra.gallium.glowoutline.render.DepthSnapshot;
import cn.spectra.gallium.glowoutline.render.GlowState;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.OutlineBufferSource;
import net.minecraft.client.renderer.SubmitNodeCollection;
import net.minecraft.client.renderer.feature.ItemFeatureRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ItemFeatureRenderer.class)
public class ItemFeatureRendererMixin {

    @Inject(method = "renderSolid", at = @At("HEAD"))
    private void glowBefore(SubmitNodeCollection nodeCollection, MultiBufferSource.BufferSource bufferSource,
                             OutlineBufferSource outlineBufferSource, CallbackInfo ci) {
        if (!GlowState.isActive() || GlowState.isCaptured()) return;
        bufferSource.endBatch();
        if (outlineBufferSource != null) outlineBufferSource.endOutlineBatch();
        DepthSnapshot.capture("ItemBefore", GlowState::getItemBefore, GlowState::setItemBefore);
    }

    @Inject(method = "renderSolid", at = @At("TAIL"))
    private void glowAfter(SubmitNodeCollection nodeCollection, MultiBufferSource.BufferSource bufferSource,
                            OutlineBufferSource outlineBufferSource, CallbackInfo ci) {
        if (!GlowState.isActive() || GlowState.isCaptured()) return;
        if (GlowState.getItemBefore() == null) return;
        bufferSource.endBatch();
        if (outlineBufferSource != null) outlineBufferSource.endOutlineBatch();
        DepthSnapshot.capture("ItemAfter", GlowState::getItemAfter, GlowState::setItemAfter);
        GlowState.setCaptured(true);
    }
}
