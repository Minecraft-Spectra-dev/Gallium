package cn.spectra.gallium.glowoutline.mixin;
import cn.spectra.gallium.glowoutline.GlowOutlineConfig;
import cn.spectra.gallium.glowoutline.capture.CaptureSites;
import cn.spectra.gallium.glowoutline.capture.GlowCaptureManager;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.layers.HumanoidArmorLayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ArmorItem;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
@Mixin(HumanoidArmorLayer.class)
public class HumanoidArmorLayerMixin {
    @WrapOperation(method = "renderArmorPiece", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/client/renderer/entity/layers/HumanoidArmorLayer;renderModel(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;ILnet/minecraft/world/item/ArmorItem;Lnet/minecraft/client/model/HumanoidModel;ZFFFLjava/lang/String;)V"))
    private void galliumWrapArmorModel(HumanoidArmorLayer self, PoseStack pose, MultiBufferSource buffers,
        int light, ArmorItem armor, HumanoidModel model, boolean inner, float r, float g, float b, String suffix,
        Operation<Void> original, PoseStack outerPose, MultiBufferSource outerBuffers, LivingEntity entity,
        EquipmentSlot slot, int outerLight, HumanoidModel outerModel) {
        MultiBufferSource wrapped = CaptureSites.beginIfCapturable(entity.getItemBySlot(slot), buffers, GlowOutlineConfig.Toggle.ARMOR);
        try {
            GlowCaptureManager.captureItemView(pose);
            original.call(self, pose, wrapped, light, armor, model, inner, r, g, b, suffix);
        } finally { CaptureSites.end(); }
    }
}
