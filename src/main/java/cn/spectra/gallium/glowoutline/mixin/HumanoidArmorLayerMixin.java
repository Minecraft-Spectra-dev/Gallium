package cn.spectra.gallium.glowoutline.mixin;

import cn.spectra.gallium.glowoutline.GlowOutlineConfig;
import cn.spectra.gallium.glowoutline.capture.CaptureSites;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.model.Model;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.layers.EquipmentLayerRenderer;
import net.minecraft.client.renderer.entity.layers.HumanoidArmorLayer;
import net.minecraft.client.resources.model.EquipmentClientInfo;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(HumanoidArmorLayer.class)
public class HumanoidArmorLayerMixin {

    @WrapOperation(method = "renderArmorPiece", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/entity/layers/EquipmentLayerRenderer;renderLayers(Lnet/minecraft/client/resources/model/EquipmentClientInfo$LayerType;Lnet/minecraft/resources/ResourceKey;Lnet/minecraft/client/model/Model;Ljava/lang/Object;Lnet/minecraft/world/item/ItemStack;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;II)V"))
    private void galliumWrapRenderLayers(EquipmentLayerRenderer renderer,
                                          EquipmentClientInfo.LayerType layerType,
                                          ResourceKey<?> equipmentAssetId,
                                          Model<?> model,
                                          Object state,
                                          ItemStack itemStack,
                                          PoseStack poseStack,
                                          SubmitNodeCollector collector,
                                          int lightCoords,
                                          int outlineColor,
                                          Operation<Void> original) {
        SubmitNodeCollector wrapped = CaptureSites.beginIfCapturable(
                itemStack, collector, GlowOutlineConfig.Toggle.ARMOR);
        try {
            original.call(renderer, layerType, equipmentAssetId, model, state, itemStack, poseStack, wrapped, lightCoords, outlineColor);
        } finally {
            CaptureSites.end();
        }
    }
}
