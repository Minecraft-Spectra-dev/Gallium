package cn.spectra.gallium.glowoutline.mixin;

import cn.spectra.gallium.glowoutline.GlowOutlineConfig;
import cn.spectra.gallium.glowoutline.IrisCompat;
import cn.spectra.gallium.glowoutline.capture.DuplicatingSubmitNodeStorage;
import cn.spectra.gallium.glowoutline.capture.GlowCaptureManager;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.SubmitNodeStorage;
import net.minecraft.client.renderer.entity.layers.ItemInHandLayer;
import net.minecraft.client.renderer.entity.state.ArmedEntityRenderState;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(ItemInHandLayer.class)
public class ItemInHandLayerMixin {

    @WrapOperation(method = "submitArmWithItem", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/item/ItemStackRenderState;submit(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;III)V"))
    private void galliumWrapItemSubmit(ItemStackRenderState renderState, PoseStack poseStack,
                                       SubmitNodeCollector collector, int light, int overlay, int outlineColor,
                                       Operation<Void> original,
                                       ArmedEntityRenderState state, ItemStackRenderState item,
                                       ItemStack itemStack, HumanoidArm arm, PoseStack ps,
                                       SubmitNodeCollector col, int l) {
        if (IrisCompat.isShadowPass() || itemStack.isEmpty()) {
            original.call(renderState, poseStack, collector, light, overlay, outlineColor);
            return;
        }

        boolean isPlayer = state.entityType == EntityType.PLAYER;
        if (isPlayer && !GlowOutlineConfig.isThirdPerson()) {
            original.call(renderState, poseStack, collector, light, overlay, outlineColor);
            return;
        }
        if (!isPlayer && !GlowOutlineConfig.isOtherEntities()) {
            original.call(renderState, poseStack, collector, light, overlay, outlineColor);
            return;
        }

        boolean capturing = GlowCaptureManager.beginItemCapture(itemStack);
        if (capturing && collector instanceof SubmitNodeStorage storage) {
            DuplicatingSubmitNodeStorage wrapped = new DuplicatingSubmitNodeStorage(storage);
            original.call(renderState, poseStack, wrapped, light, overlay, outlineColor);
        } else {
            original.call(renderState, poseStack, collector, light, overlay, outlineColor);
        }
        GlowCaptureManager.endItemCapture();
    }
}
