package cn.spectra.gallium.glowoutline.mixin;

import cn.spectra.gallium.glowoutline.IrisCompat;
import cn.spectra.gallium.glowoutline.capture.DuplicatingSubmitNodeStorage;
import cn.spectra.gallium.glowoutline.capture.GlowCaptureManager;
import cn.spectra.gallium.glowoutline.capture.GlowCaptureState;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.SubmitNodeStorage;
import net.minecraft.client.renderer.entity.layers.ItemInHandLayer;
import net.minecraft.client.renderer.entity.state.ArmedEntityRenderState;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ItemInHandLayer.class)
public class ItemInHandLayerMixin {

    @Inject(method = "submitArmWithItem", at = @At("HEAD"))
    private void galliumBeginThirdPerson(ArmedEntityRenderState state, ItemStackRenderState item,
                                          ItemStack itemStack, HumanoidArm arm, PoseStack poseStack,
                                          SubmitNodeCollector collector, int light, CallbackInfo ci) {
        if (Minecraft.getInstance().options.getCameraType().isFirstPerson()) return;
        if (IrisCompat.isShadowPass()) return;
        if (itemStack.isEmpty()) return;

        boolean isMainArm = (arm == state.mainArm);
        InteractionHand hand = isMainArm ? InteractionHand.MAIN_HAND : InteractionHand.OFF_HAND;

        GlowCaptureState captureState = GlowCaptureManager.stateFor(hand);
        if (captureState.config != null
                && !captureState.capturedThisFrame
                && ItemStack.isSameItemSameComponents(itemStack, captureState.item)) {
            GlowCaptureManager.beginHandSubmit(hand, itemStack);
        }
    }

    @ModifyVariable(method = "submitArmWithItem", at = @At("HEAD"), argsOnly = true, ordinal = 0)
    private SubmitNodeCollector galliumWrapThirdPerson(SubmitNodeCollector collector) {
        if (Minecraft.getInstance().options.getCameraType().isFirstPerson()) return collector;
        if (IrisCompat.isShadowPass()) return collector;
        if (GlowCaptureManager.activeHand() == null) return collector;
        if (!GlowCaptureManager.isActive(GlowCaptureManager.MAIN) && !GlowCaptureManager.isActive(GlowCaptureManager.OFF)) return collector;
        if (collector instanceof DuplicatingSubmitNodeStorage) return collector;
        if (collector instanceof SubmitNodeStorage storage) {
            return new DuplicatingSubmitNodeStorage(storage);
        }
        return collector;
    }

    @Inject(method = "submitArmWithItem", at = @At("TAIL"))
    private void galliumEndThirdPerson(ArmedEntityRenderState state, ItemStackRenderState item,
                                        ItemStack itemStack, HumanoidArm arm, PoseStack poseStack,
                                        SubmitNodeCollector collector, int light, CallbackInfo ci) {
        if (Minecraft.getInstance().options.getCameraType().isFirstPerson()) return;
        GlowCaptureManager.endHandSubmit();
    }
}
