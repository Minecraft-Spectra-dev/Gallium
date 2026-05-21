package cn.spectra.gallium.glowoutline.mixin;

import cn.spectra.gallium.glowoutline.ItemEffectsManager;
import cn.spectra.gallium.glowoutline.capture.DuplicatingSubmitNodeStorage;
import cn.spectra.gallium.glowoutline.capture.GlowCaptureManager;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.ItemInHandRenderer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.SubmitNodeStorage;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ItemInHandRenderer.class)
public class ItemInHandRendererMixin {

    @ModifyVariable(method = "renderHandsWithItems", at = @At("HEAD"), argsOnly = true, ordinal = 0)
    private SubmitNodeCollector galliumWrapCollector(SubmitNodeCollector collector) {
        if (!ItemEffectsManager.isActive()) return collector;
        if (!GlowCaptureManager.isActive(GlowCaptureManager.MAIN) && !GlowCaptureManager.isActive(GlowCaptureManager.OFF)) return collector;
        if (collector instanceof DuplicatingSubmitNodeStorage) return collector;
        if (collector instanceof SubmitNodeStorage storage) {
            return new DuplicatingSubmitNodeStorage(storage);
        }
        return collector;
    }

    @Inject(method = "renderArmWithItem", at = @At("HEAD"))
    private void galliumBeginHand(AbstractClientPlayer player, float frameInterp, float xRot, InteractionHand hand,
                                   float attack, ItemStack stack, float inverseArmHeight,
                                   PoseStack poseStack, SubmitNodeCollector collector, int light, CallbackInfo ci) {
        GlowCaptureManager.beginHandSubmit(hand, stack);
    }

    @Inject(method = "renderArmWithItem", at = @At("TAIL"))
    private void galliumEndHand(AbstractClientPlayer player, float frameInterp, float xRot, InteractionHand hand,
                                 float attack, ItemStack stack, float inverseArmHeight,
                                 PoseStack poseStack, SubmitNodeCollector collector, int light, CallbackInfo ci) {
        GlowCaptureManager.endHandSubmit();
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
