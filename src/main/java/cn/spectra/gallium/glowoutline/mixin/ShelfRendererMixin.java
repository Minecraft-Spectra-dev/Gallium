package cn.spectra.gallium.glowoutline.mixin;

import cn.spectra.gallium.glowoutline.GlowOutlineConfig;
import cn.spectra.gallium.glowoutline.capture.CaptureSites;
import cn.spectra.gallium.glowoutline.capture.ShelfRenderStateAccessor;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.blockentity.ShelfRenderer;
import net.minecraft.client.renderer.blockentity.state.ShelfRenderState;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.core.NonNullList;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.ShelfBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ShelfRenderer.class)
public class ShelfRendererMixin {

    @Inject(method = "extractRenderState(Lnet/minecraft/world/level/block/entity/ShelfBlockEntity;Lnet/minecraft/client/renderer/blockentity/state/ShelfRenderState;FLnet/minecraft/world/phys/Vec3;Lnet/minecraft/client/renderer/feature/ModelFeatureRenderer$CrumblingOverlay;)V",
            at = @At("TAIL"))
    private void galliumCaptureItems(ShelfBlockEntity blockEntity, ShelfRenderState state, float partialTicks,
                                      net.minecraft.world.phys.Vec3 cameraPosition,
                                      net.minecraft.client.renderer.feature.ModelFeatureRenderer.CrumblingOverlay breakProgress,
                                      CallbackInfo ci) {
        NonNullList<ItemStack> items = blockEntity.getItems();
        ShelfRenderStateAccessor accessor = (ShelfRenderStateAccessor) state;
        for (int i = 0; i < items.size(); i++) {
            accessor.gallium$setItemStack(i, items.get(i));
        }
    }

    @WrapOperation(method = "submitItem", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/item/ItemStackRenderState;submit(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;III)V"))
    private void galliumWrapItemSubmit(ItemStackRenderState renderState, PoseStack poseStack,
                                       SubmitNodeCollector collector, int light, int overlay, int outlineColor,
                                       Operation<Void> original,
                                       ShelfRenderState state, ItemStackRenderState item, PoseStack ps,
                                       SubmitNodeCollector col, int slot, float yRot) {
        ItemStack itemStack = ((ShelfRenderStateAccessor) state).gallium$getItemStack(slot);
        SubmitNodeCollector wrapped = CaptureSites.beginIfCapturable(
                itemStack, collector, GlowOutlineConfig.Toggle.OTHER_ENTITIES);
        try {
            original.call(renderState, poseStack, wrapped, light, overlay, outlineColor);
        } finally {
            CaptureSites.end();
        }
    }
}
