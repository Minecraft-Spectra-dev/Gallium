package cn.spectra.gallium.glowoutline.mixin;

//#if MC>=1_21_09
import cn.spectra.gallium.glowoutline.GlowOutlineConfig;
import cn.spectra.gallium.glowoutline.capture.CaptureSites;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
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

    // WrapMethod (HEAD+TAIL) keeps suppressDepth balanced even if the original throws.
    @WrapMethod(method = "renderPlayerArm")
    private void galliumWrapRenderPlayerArm(PoseStack poseStack, SubmitNodeCollector collector, int light,
                                            float equipped, float swing, net.minecraft.world.entity.HumanoidArm arm,
                                            Operation<Void> original) {
        GlowCaptureManager.beginSuppress();
        try {
            original.call(poseStack, collector, light, equipped, swing, arm);
        } finally {
            GlowCaptureManager.endSuppress();
        }
    }

    @WrapMethod(method = "renderMapHand")
    private void galliumWrapRenderMapHand(PoseStack poseStack, SubmitNodeCollector collector, int light,
                                          net.minecraft.world.entity.HumanoidArm arm,
                                          Operation<Void> original) {
        GlowCaptureManager.beginSuppress();
        try {
            original.call(poseStack, collector, light, arm);
        } finally {
            GlowCaptureManager.endSuppress();
        }
    }
}
//#elseif MC>=1_21_05
//$$ import cn.spectra.gallium.glowoutline.GlowOutlineConfig;
//$$ import cn.spectra.gallium.glowoutline.capture.CaptureSites;
//$$ import cn.spectra.gallium.glowoutline.capture.GlowCaptureManager;
//$$ import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
//$$ import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
//$$ import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
//$$ import com.mojang.blaze3d.vertex.PoseStack;
//$$ import net.minecraft.client.renderer.ItemInHandRenderer;
//$$ import net.minecraft.client.renderer.MultiBufferSource;
//$$ import net.minecraft.client.renderer.entity.ItemRenderer;
//$$ import net.minecraft.world.entity.LivingEntity;
//$$ import net.minecraft.world.item.ItemDisplayContext;
//$$ import net.minecraft.world.item.ItemStack;
//$$ import org.spongepowered.asm.mixin.Mixin;
//$$ import org.spongepowered.asm.mixin.injection.At;
//$$
//$$ @Mixin(ItemInHandRenderer.class)
//$$ public class ItemInHandRendererMixin {
//$$
//$$     @WrapOperation(method = "renderItem", at = @At(value = "INVOKE",
//$$             target = "Lnet/minecraft/client/renderer/entity/ItemRenderer;renderStatic(Lnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/item/ItemDisplayContext;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;Lnet/minecraft/world/level/Level;III)V"))
//$$     private void galliumWrapItemSubmit(ItemRenderer renderer, LivingEntity livingEntity, ItemStack itemStack, ItemDisplayContext itemDisplayContext, PoseStack poseStack, MultiBufferSource multiBufferSource, net.minecraft.world.level.Level level, int light, int overlay, int seed, Operation<Void> original, LivingEntity le, ItemStack stack, ItemDisplayContext ctx, PoseStack ps, MultiBufferSource mbs, int i) {
//$$         MultiBufferSource wrapped = CaptureSites.beginIfCapturable(
//$$                 itemStack, multiBufferSource, GlowOutlineConfig.Toggle.FIRST_PERSON, true);
//$$         try {
//$$             original.call(renderer, livingEntity, itemStack, itemDisplayContext, poseStack, wrapped, level, light, overlay, seed);
//$$         } finally {
//$$             CaptureSites.end();
//$$         }
//$$     }
//$$
//$$     @WrapMethod(method = "renderPlayerArm")
//$$     private void galliumWrapRenderPlayerArm(PoseStack poseStack, MultiBufferSource multiBufferSource, int light,
//$$                                             float equipped, float swing, net.minecraft.world.entity.HumanoidArm arm,
//$$                                             Operation<Void> original) {
//$$         GlowCaptureManager.beginSuppress();
//$$         try {
//$$             original.call(poseStack, multiBufferSource, light, equipped, swing, arm);
//$$         } finally {
//$$             GlowCaptureManager.endSuppress();
//$$         }
//$$     }
//$$
//$$     @WrapMethod(method = "renderMapHand")
//$$     private void galliumWrapRenderMapHand(PoseStack poseStack, MultiBufferSource multiBufferSource, int light,
//$$                                           net.minecraft.world.entity.HumanoidArm arm,
//$$                                           Operation<Void> original) {
//$$         GlowCaptureManager.beginSuppress();
//$$         try {
//$$             original.call(poseStack, multiBufferSource, light, arm);
//$$         } finally {
//$$             GlowCaptureManager.endSuppress();
//$$         }
//$$     }
//$$ }
//#else
//$$ // 1.21.3 / 1.21.4: ItemRenderer.renderStatic carries an extra `boolean` parameter that
//$$ // 1.21.5 dropped, and ItemInHandRenderer.renderItem itself also takes that boolean. Both
//$$ // the injection target descriptor and the wrapper signature must match the 10-arg form,
//$$ // otherwise mixin reports "Scanned 0 target(s)" and crashes at startup. Signature is
//$$ // identical between 1.21.3 and 1.21.4, so a single branch covers both.
//$$ import cn.spectra.gallium.glowoutline.GlowOutlineConfig;
//$$ import cn.spectra.gallium.glowoutline.capture.CaptureSites;
//$$ import cn.spectra.gallium.glowoutline.capture.GlowCaptureManager;
//$$ import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
//$$ import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
//$$ import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
//$$ import com.mojang.blaze3d.vertex.PoseStack;
//$$ import net.minecraft.client.renderer.ItemInHandRenderer;
//$$ import net.minecraft.client.renderer.MultiBufferSource;
//$$ import net.minecraft.client.renderer.entity.ItemRenderer;
//$$ import net.minecraft.world.entity.LivingEntity;
//$$ import net.minecraft.world.item.ItemDisplayContext;
//$$ import net.minecraft.world.item.ItemStack;
//$$ import org.spongepowered.asm.mixin.Mixin;
//$$ import org.spongepowered.asm.mixin.injection.At;
//$$
//$$ @Mixin(ItemInHandRenderer.class)
//$$ public class ItemInHandRendererMixin {
//$$
//$$     @WrapOperation(method = "renderItem", at = @At(value = "INVOKE",
//$$             target = "Lnet/minecraft/client/renderer/entity/ItemRenderer;renderStatic(Lnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/item/ItemDisplayContext;ZLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;Lnet/minecraft/world/level/Level;III)V"))
//$$     private void galliumWrapItemSubmit(ItemRenderer renderer, LivingEntity livingEntity, ItemStack itemStack, ItemDisplayContext itemDisplayContext, boolean leftHand, PoseStack poseStack, MultiBufferSource multiBufferSource, net.minecraft.world.level.Level level, int light, int overlay, int seed, Operation<Void> original, LivingEntity le, ItemStack stack, ItemDisplayContext ctx, boolean lh, PoseStack ps, MultiBufferSource mbs, int i) {
//#if MC>=1_21_02
//$$         MultiBufferSource wrapped = CaptureSites.beginIfCapturable(
//$$                 itemStack, multiBufferSource, GlowOutlineConfig.Toggle.FIRST_PERSON, true);
//$$         try {
//$$             original.call(renderer, livingEntity, itemStack, itemDisplayContext, leftHand, poseStack, wrapped, level, light, overlay, seed);
//$$         } finally {
//$$             CaptureSites.end();
//$$         }
//#else
//$$         // 1.21.1: renderItem is the single funnel for BOTH first- and third-person held items
//$$         // (ItemInHandLayer.renderArmWithItem calls it too on this version — ItemInHandLayerMixin
//$$         // is stubbed here), so pick the toggle from the display context / entity type. Other
//$$         // contexts (GUI/GROUND/...) don't reach this renderer; pass them through uncaptured.
//$$         boolean firstPersonCtx = itemDisplayContext == ItemDisplayContext.FIRST_PERSON_LEFT_HAND
//$$                 || itemDisplayContext == ItemDisplayContext.FIRST_PERSON_RIGHT_HAND;
//$$         boolean thirdPersonCtx = itemDisplayContext == ItemDisplayContext.THIRD_PERSON_LEFT_HAND
//$$                 || itemDisplayContext == ItemDisplayContext.THIRD_PERSON_RIGHT_HAND;
//$$         GlowOutlineConfig.Toggle flag = null;
//$$         if (firstPersonCtx) {
//$$             flag = GlowOutlineConfig.Toggle.FIRST_PERSON;
//$$         } else if (thirdPersonCtx) {
//$$             flag = livingEntity instanceof net.minecraft.world.entity.player.Player
//$$                     ? GlowOutlineConfig.Toggle.THIRD_PERSON
//$$                     : GlowOutlineConfig.Toggle.OTHER_ENTITIES;
//$$         }
//$$         if (flag == null) {
//$$             original.call(renderer, livingEntity, itemStack, itemDisplayContext, leftHand, poseStack, multiBufferSource, level, light, overlay, seed);
//$$             return;
//$$         }
//$$         MultiBufferSource wrapped = CaptureSites.beginIfCapturable(itemStack, multiBufferSource, flag, firstPersonCtx);
//$$         try {
//$$             original.call(renderer, livingEntity, itemStack, itemDisplayContext, leftHand, poseStack, wrapped, level, light, overlay, seed);
//$$         } finally {
//$$             CaptureSites.end();
//$$         }
//#endif
//$$     }
//$$
//$$     @WrapMethod(method = "renderPlayerArm")
//$$     private void galliumWrapRenderPlayerArm(PoseStack poseStack, MultiBufferSource multiBufferSource, int light,
//$$                                             float equipped, float swing, net.minecraft.world.entity.HumanoidArm arm,
//$$                                             Operation<Void> original) {
//$$         GlowCaptureManager.beginSuppress();
//$$         try {
//$$             original.call(poseStack, multiBufferSource, light, equipped, swing, arm);
//$$         } finally {
//$$             GlowCaptureManager.endSuppress();
//$$         }
//$$     }
//$$
//$$     @WrapMethod(method = "renderMapHand")
//$$     private void galliumWrapRenderMapHand(PoseStack poseStack, MultiBufferSource multiBufferSource, int light,
//$$                                           net.minecraft.world.entity.HumanoidArm arm,
//$$                                           Operation<Void> original) {
//$$         GlowCaptureManager.beginSuppress();
//$$         try {
//$$             original.call(poseStack, multiBufferSource, light, arm);
//$$         } finally {
//$$             GlowCaptureManager.endSuppress();
//$$         }
//$$     }
//$$ }
//#endif
