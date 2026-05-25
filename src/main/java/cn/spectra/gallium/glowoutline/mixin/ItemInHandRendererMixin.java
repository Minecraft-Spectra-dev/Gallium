package cn.spectra.gallium.glowoutline.mixin;

import cn.spectra.gallium.glowoutline.GlowOutlineConfig;
import cn.spectra.gallium.glowoutline.capture.CaptureSites;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.ItemInHandRenderer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import cn.spectra.gallium.glowoutline.capture.GlowCaptureManager;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ItemDisplayContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ItemInHandRenderer.class)
public class ItemInHandRendererMixin {

    @WrapOperation(method = "renderItem", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/item/ItemStackRenderState;submit(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;III)V"))
    private void galliumWrapItemSubmit(ItemStackRenderState renderState, PoseStack poseStack,
                                       SubmitNodeCollector collector, int light, int overlay, int outlineColor,
                                       Operation<Void> original,
                                       LivingEntity mob, ItemStack itemStack, ItemDisplayContext type,
                                       PoseStack ps, SubmitNodeCollector col, int l) {
        SubmitNodeCollector wrapped = CaptureSites.beginIfCapturable(
                itemStack, collector, GlowOutlineConfig.Toggle.FIRST_PERSON, true);
        try {
            original.call(renderState, poseStack, wrapped, light, overlay, outlineColor);
        } finally {
            CaptureSites.end();
        }
    }

    @Inject(method = "renderPlayerArm", at = @At("HEAD"))
    private void galliumSuppressArmStart(PoseStack p, SubmitNodeCollector c, int l, float eq, float sw,
                                          net.minecraft.world.entity.HumanoidArm arm, CallbackInfo ci) {
        GlowCaptureManager.beginSuppress();
    }

    @Inject(method = "renderPlayerArm", at = @At("TAIL"))
    private void galliumSuppressArmEnd(PoseStack p, SubmitNodeCollector c, int l, float eq, float sw,
                                        net.minecraft.world.entity.HumanoidArm arm, CallbackInfo ci) {
        GlowCaptureManager.endSuppress();
    }

    @Inject(method = "renderMapHand", at = @At("HEAD"))
    private void galliumSuppressMapStart(PoseStack p, SubmitNodeCollector c, int l,
                                          net.minecraft.world.entity.HumanoidArm arm, CallbackInfo ci) {
        GlowCaptureManager.beginSuppress();
    }

    @Inject(method = "renderMapHand", at = @At("TAIL"))
    private void galliumSuppressMapEnd(PoseStack p, SubmitNodeCollector c, int l,
                                        net.minecraft.world.entity.HumanoidArm arm, CallbackInfo ci) {
        GlowCaptureManager.endSuppress();
    }
}
