package cn.spectra.gallium.glowoutline.mixin;

//#if MC>=1_21_09
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
//#elseif MC>=1_21_04
//$$ import cn.spectra.gallium.glowoutline.GlowOutlineConfig;
//$$ import cn.spectra.gallium.glowoutline.capture.CaptureSites;
//$$ import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
//$$ import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
//$$ import com.mojang.blaze3d.vertex.PoseStack;
//$$ import net.minecraft.client.model.Model;
//$$ import net.minecraft.client.renderer.MultiBufferSource;
//$$ import net.minecraft.client.renderer.entity.layers.EquipmentLayerRenderer;
//$$ import net.minecraft.client.renderer.entity.layers.HumanoidArmorLayer;
//$$ import net.minecraft.client.resources.model.EquipmentClientInfo;
//$$ import net.minecraft.resources.ResourceKey;
//$$ import net.minecraft.world.item.ItemStack;
//$$ import org.spongepowered.asm.mixin.Mixin;
//$$ import org.spongepowered.asm.mixin.injection.At;
//$$
//$$ @Mixin(HumanoidArmorLayer.class)
//$$ public class HumanoidArmorLayerMixin {
//$$
//$$     @WrapOperation(method = "renderArmorPiece", at = @At(value = "INVOKE",
//$$             target = "Lnet/minecraft/client/renderer/entity/layers/EquipmentLayerRenderer;renderLayers(Lnet/minecraft/client/resources/model/EquipmentClientInfo$LayerType;Lnet/minecraft/resources/ResourceKey;Lnet/minecraft/client/model/Model;Lnet/minecraft/world/item/ItemStack;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V"))
//$$     private void galliumWrapArmorRender(EquipmentLayerRenderer renderer, EquipmentClientInfo.LayerType layerType, ResourceKey<?> assetId, Model model, ItemStack itemStack, PoseStack poseStack, MultiBufferSource bufferSource, int light, Operation<Void> original) {
//$$         MultiBufferSource wrapped = CaptureSites.beginIfCapturable(
//$$                 itemStack, bufferSource, GlowOutlineConfig.Toggle.ARMOR);
//$$         try {
//$$             original.call(renderer, layerType, assetId, model, itemStack, poseStack, wrapped, light);
//$$         } finally {
//$$             CaptureSites.end();
//$$         }
//$$     }
//$$ }
//#elseif MC>=1_21_02
//$$ // 1.21.3: EquipmentLayerRenderer.renderLayers takes EquipmentModel.LayerType +
//$$ // ResourceLocation (1.21.4 renamed to EquipmentClientInfo.LayerType + ResourceKey<EquipmentAsset>).
//$$ // Same wrap shape, just with the older type names in the descriptor and signature.
//$$ import cn.spectra.gallium.glowoutline.GlowOutlineConfig;
//$$ import cn.spectra.gallium.glowoutline.capture.CaptureSites;
//$$ import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
//$$ import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
//$$ import com.mojang.blaze3d.vertex.PoseStack;
//$$ import net.minecraft.client.model.Model;
//$$ import net.minecraft.client.renderer.MultiBufferSource;
//$$ import net.minecraft.client.renderer.entity.layers.EquipmentLayerRenderer;
//$$ import net.minecraft.client.renderer.entity.layers.HumanoidArmorLayer;
//$$ import net.minecraft.resources.ResourceLocation;
//$$ import net.minecraft.world.item.ItemStack;
//$$ import net.minecraft.world.item.equipment.EquipmentModel;
//$$ import org.spongepowered.asm.mixin.Mixin;
//$$ import org.spongepowered.asm.mixin.injection.At;
//$$
//$$ @Mixin(HumanoidArmorLayer.class)
//$$ public class HumanoidArmorLayerMixin {
//$$
//$$     @WrapOperation(method = "renderArmorPiece", at = @At(value = "INVOKE",
//$$             target = "Lnet/minecraft/client/renderer/entity/layers/EquipmentLayerRenderer;renderLayers(Lnet/minecraft/world/item/equipment/EquipmentModel$LayerType;Lnet/minecraft/resources/ResourceLocation;Lnet/minecraft/client/model/Model;Lnet/minecraft/world/item/ItemStack;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V"))
//$$     private void galliumWrapArmorRender(EquipmentLayerRenderer renderer, EquipmentModel.LayerType layerType, ResourceLocation assetId, Model model, ItemStack itemStack, PoseStack poseStack, MultiBufferSource bufferSource, int light, Operation<Void> original) {
//$$         MultiBufferSource wrapped = CaptureSites.beginIfCapturable(
//$$                 itemStack, bufferSource, GlowOutlineConfig.Toggle.ARMOR);
//$$         try {
//$$             original.call(renderer, layerType, assetId, model, itemStack, poseStack, wrapped, light);
//$$         } finally {
//$$             CaptureSites.end();
//$$         }
//$$     }
//$$ }
//#else
//$$ // 1.21.1 (pre-1.21.2): no EquipmentLayerRenderer. HumanoidArmorLayer.renderArmorPiece reads
//$$ // the slot item from the entity and draws armor in three passes inside the same private method:
//$$ //   1. renderModel per ArmorMaterial.Layer  — base mesh, the silhouette source
//$$ //   2. renderTrim  if DataComponents.TRIM   — overlay on the SAME humanoidModel geometry
//$$ //   3. renderGlint if hasFoil()             — overlay on the SAME geometry
//$$ // We only wrap (1) because trim and glint draw onto identical geometry: any pixel they
//$$ // touch is already inside the base silhouette, so capturing them adds nothing visible
//$$ // to the glow outline. (Glint is also filtered out by CaptureSites' render-type rule on
//$$ // every version.) Other versions wrap the unified EquipmentLayerRenderer.renderLayers
//$$ // which structurally includes trim, but the visual result is the same — trim contributes
//$$ // no extra outline because it shares the base mesh.
//$$ import cn.spectra.gallium.glowoutline.GlowOutlineConfig;
//$$ import cn.spectra.gallium.glowoutline.capture.CaptureSites;
//$$ import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
//$$ import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
//$$ import com.mojang.blaze3d.vertex.PoseStack;
//$$ import net.minecraft.client.model.HumanoidModel;
//$$ import net.minecraft.client.renderer.MultiBufferSource;
//$$ import net.minecraft.client.renderer.entity.layers.HumanoidArmorLayer;
//$$ import net.minecraft.resources.ResourceLocation;
//$$ import net.minecraft.world.entity.EquipmentSlot;
//$$ import net.minecraft.world.entity.LivingEntity;
//$$ import net.minecraft.world.item.ItemStack;
//$$ import org.spongepowered.asm.mixin.Mixin;
//$$ import org.spongepowered.asm.mixin.injection.At;
//$$
//$$ @Mixin(HumanoidArmorLayer.class)
//$$ public class HumanoidArmorLayerMixin {
//$$
//$$     @WrapOperation(method = "renderArmorPiece", at = @At(value = "INVOKE",
//$$             target = "Lnet/minecraft/client/renderer/entity/layers/HumanoidArmorLayer;renderModel(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;ILnet/minecraft/client/model/HumanoidModel;ILnet/minecraft/resources/ResourceLocation;)V"))
//$$     private void galliumWrapArmorModel(HumanoidArmorLayer self, PoseStack poseStack, MultiBufferSource multiBufferSource,
//$$                                        int light, HumanoidModel model, int color, ResourceLocation texture,
//$$                                        Operation<Void> original,
//$$                                        PoseStack outerPose, MultiBufferSource outerBuf, LivingEntity livingEntity,
//$$                                        EquipmentSlot slot, int outerLight, HumanoidModel outerModel) {
//$$         ItemStack itemStack = livingEntity.getItemBySlot(slot);
//$$         MultiBufferSource wrapped = CaptureSites.beginIfCapturable(
//$$                 itemStack, multiBufferSource, GlowOutlineConfig.Toggle.ARMOR);
//$$         try {
//$$             original.call(self, poseStack, wrapped, light, model, color, texture);
//$$         } finally {
//$$             CaptureSites.end();
//$$         }
//$$     }
//$$ }
//#endif
