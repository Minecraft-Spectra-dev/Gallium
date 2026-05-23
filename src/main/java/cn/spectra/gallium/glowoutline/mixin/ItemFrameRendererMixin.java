package cn.spectra.gallium.glowoutline.mixin;

import cn.spectra.gallium.glowoutline.GlowOutlineConfig;
import cn.spectra.gallium.glowoutline.IrisCompat;
import cn.spectra.gallium.glowoutline.capture.DuplicatingSubmitNodeStorage;
import cn.spectra.gallium.glowoutline.capture.GlowCaptureManager;
import cn.spectra.gallium.glowoutline.capture.ItemFrameRenderStateAccessor;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.SubmitNodeStorage;
import net.minecraft.client.renderer.entity.ItemFrameRenderer;
import net.minecraft.client.renderer.entity.state.ItemFrameRenderState;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ItemFrameRenderer.class)
public class ItemFrameRendererMixin {

    @Inject(method = "extractRenderState(Lnet/minecraft/world/entity/decoration/ItemFrame;Lnet/minecraft/client/renderer/entity/state/ItemFrameRenderState;F)V",
            at = @At("HEAD"))
    private void galliumCaptureItemStack(net.minecraft.world.entity.decoration.ItemFrame entity, ItemFrameRenderState state, float partialTicks, CallbackInfo ci) {
        ((ItemFrameRenderStateAccessor) state).gallium$setItemStack(entity.getItem());
    }

    @WrapOperation(method = "submit", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/item/ItemStackRenderState;submit(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;III)V"))
    private void galliumWrapItemSubmit(ItemStackRenderState renderState, PoseStack poseStack,
                                       SubmitNodeCollector collector, int light, int overlay, int outlineColor,
                                       Operation<Void> original,
                                       ItemFrameRenderState state, PoseStack ps, SubmitNodeCollector col, CameraRenderState cam) {
        ItemStack itemStack = ((ItemFrameRenderStateAccessor) state).gallium$getItemStack();
        if (IrisCompat.isShadowPass() || itemStack.isEmpty() || !GlowOutlineConfig.isOtherEntities()) {
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
