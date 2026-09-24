package cn.spectra.gallium.platform.neoforge.mixin;

//#if NEOFORGE && MC==1_21_01
//$$ import cn.spectra.gallium.glowoutline.GlowOutlineConfig;
//$$ import cn.spectra.gallium.glowoutline.capture.CaptureSites;
//$$ import cn.spectra.gallium.glowoutline.capture.GlowCaptureManager;
//$$ import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
//$$ import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
//$$ import com.mojang.blaze3d.vertex.PoseStack;
//$$ import net.minecraft.client.model.HumanoidModel;
//$$ import net.minecraft.client.model.Model;
//$$ import net.minecraft.client.renderer.MultiBufferSource;
//$$ import net.minecraft.client.renderer.entity.layers.HumanoidArmorLayer;
//$$ import net.minecraft.resources.ResourceLocation;
//$$ import net.minecraft.world.entity.EquipmentSlot;
//$$ import net.minecraft.world.entity.LivingEntity;
//$$ import org.spongepowered.asm.mixin.Mixin;
//$$ import org.spongepowered.asm.mixin.injection.At;
//$$
//$$ /** NeoForge's armor extension uses Model and adds animation arguments to the vanilla hook. */
//$$ @Mixin(value = HumanoidArmorLayer.class, remap = false)
//$$ public final class NeoForgeArmorLayerMixin {
//$$     // NeoForge-added overloads have no vanilla obfuscation mappings.
//$$     @WrapOperation(
//$$             remap = false,
//$$             method = "renderArmorPiece(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;Lnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/world/entity/EquipmentSlot;ILnet/minecraft/client/model/HumanoidModel;FFFFFF)V",
//$$             at = @At(value = "INVOKE", remap = false, target = "Lnet/minecraft/client/renderer/entity/layers/HumanoidArmorLayer;renderModel(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;ILnet/minecraft/client/model/Model;ILnet/minecraft/resources/ResourceLocation;)V"))
//$$     private void galliumCaptureArmor(HumanoidArmorLayer self, PoseStack pose, MultiBufferSource buffers,
//$$             int light, Model model, int color, ResourceLocation texture, Operation<Void> original,
//$$             PoseStack outerPose, MultiBufferSource outerBuffers, LivingEntity entity, EquipmentSlot slot,
//$$             int outerLight, HumanoidModel outerModel, float limbSwing, float limbSwingAmount,
//$$             float partialTick, float ageInTicks, float headYaw, float headPitch) {
//$$         MultiBufferSource wrapped = CaptureSites.beginIfCapturable(
//$$                 entity.getItemBySlot(slot), buffers, GlowOutlineConfig.Toggle.ARMOR);
//$$         try {
//$$             GlowCaptureManager.captureItemView(pose);
//$$             original.call(self, pose, wrapped, light, model, color, texture);
//$$         } finally {
//$$             CaptureSites.end();
//$$         }
//$$     }
//$$ }
//#else
public final class NeoForgeArmorLayerMixin {}
//#endif
