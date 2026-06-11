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

    // WrapMethod gives a single hook for HEAD+TAIL begin/end, so an exception inside the
    // original still runs endSuppress() and keeps suppressDepth balanced. Previously a
    // throw from renderPlayerArm would leak the increment; beginFrame's per-frame reset
    // hid the symptom but the invariant was unsafe.
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
//#else
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
//#endif
