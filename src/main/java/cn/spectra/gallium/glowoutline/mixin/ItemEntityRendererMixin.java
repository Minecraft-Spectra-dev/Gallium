package cn.spectra.gallium.glowoutline.mixin;

import cn.spectra.gallium.glowoutline.GlowOutlineConfig;
import cn.spectra.gallium.glowoutline.capture.CaptureSites;
import cn.spectra.gallium.glowoutline.capture.ItemEntityRenderStateAccessor;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.ItemEntityRenderer;
import net.minecraft.client.renderer.entity.state.ItemClusterRenderState;
import net.minecraft.client.renderer.entity.state.ItemEntityRenderState;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ItemEntityRenderer.class)
public class ItemEntityRendererMixin {

    @Inject(method = "extractRenderState(Lnet/minecraft/world/entity/item/ItemEntity;Lnet/minecraft/client/renderer/entity/state/ItemEntityRenderState;F)V",
            at = @At("HEAD"))
    private void galliumCaptureItemStack(net.minecraft.world.entity.item.ItemEntity entity, ItemEntityRenderState state, float partialTicks, CallbackInfo ci) {
        ((ItemEntityRenderStateAccessor) state).gallium$setItemStack(entity.getItem());
    }

    @WrapOperation(method = "submit", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/entity/ItemEntityRenderer;submitMultipleFromCount(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;ILnet/minecraft/client/renderer/entity/state/ItemClusterRenderState;Lnet/minecraft/util/RandomSource;Lnet/minecraft/world/phys/AABB;)V"))
    private void galliumWrapSubmitMultiple(PoseStack poseStack, SubmitNodeCollector collector,
                                           int lightCoords, ItemClusterRenderState clusterState,
                                           RandomSource random, AABB boundingBox,
                                           Operation<Void> original,
                                           ItemEntityRenderState state, PoseStack ps, SubmitNodeCollector col,
                                           net.minecraft.client.renderer.state.level.CameraRenderState cam) {
        ItemStack itemStack = ((ItemEntityRenderStateAccessor) state).gallium$getItemStack();
        SubmitNodeCollector wrapped = CaptureSites.beginIfCapturable(
                itemStack, collector, GlowOutlineConfig.Toggle.DROPPED_ITEMS);
        try {
            original.call(poseStack, wrapped, lightCoords, clusterState, random, boundingBox);
        } finally {
            CaptureSites.end();
        }
    }
}
