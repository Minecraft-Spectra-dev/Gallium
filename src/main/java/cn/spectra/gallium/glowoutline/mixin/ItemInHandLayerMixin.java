package cn.spectra.gallium.glowoutline.mixin;

//#if MC>=1_21_09
import cn.spectra.gallium.glowoutline.GlowOutlineConfig;
import cn.spectra.gallium.glowoutline.capture.CaptureSites;
//#if MC<1_21_11
//$$ import cn.spectra.gallium.glowoutline.capture.ArmedEntityRenderStateAccessor;
//#endif
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.layers.ItemInHandLayer;
import net.minecraft.client.renderer.entity.state.ArmedEntityRenderState;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * The {@code submitArmWithItem} signature gained an {@code ItemStack} parameter in
 * 1.21.11. Pre-1.21.11 it only carries an {@link ItemStackRenderState}, so we recover
 * the matching stack from the per-arm cache populated by
 * {@link ArmedEntityRenderStateMixin}. This drives the two wrapper signatures below.
 */
@Mixin(ItemInHandLayer.class)
public class ItemInHandLayerMixin {

    //#if MC>=1_21_11
    @WrapOperation(method = "submitArmWithItem", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/item/ItemStackRenderState;submit(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;III)V"))
    private void galliumWrapItemSubmit(ItemStackRenderState renderState, PoseStack poseStack,
                                       SubmitNodeCollector collector, int light, int overlay, int outlineColor,
                                       Operation<Void> original,
                                       ArmedEntityRenderState state, ItemStackRenderState item,
                                       ItemStack itemStack, HumanoidArm arm, PoseStack ps,
                                       SubmitNodeCollector col, int l) {
        boolean isPlayer = state.entityType == EntityType.PLAYER;
        GlowOutlineConfig.Toggle flag = isPlayer
                ? GlowOutlineConfig.Toggle.THIRD_PERSON
                : GlowOutlineConfig.Toggle.OTHER_ENTITIES;
        SubmitNodeCollector wrapped = CaptureSites.beginIfCapturable(itemStack, collector, flag);
        try {
            original.call(renderState, poseStack, wrapped, light, overlay, outlineColor);
        } finally {
            CaptureSites.end();
        }
    }
    //#else
    //$$ @WrapOperation(method = "submitArmWithItem", at = @At(value = "INVOKE",
    //$$         target = "Lnet/minecraft/client/renderer/item/ItemStackRenderState;submit(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;III)V"))
    //$$ private void galliumWrapItemSubmit(ItemStackRenderState renderState, PoseStack poseStack,
    //$$                                    SubmitNodeCollector collector, int light, int overlay, int outlineColor,
    //$$                                    Operation<Void> original,
    //$$                                    ArmedEntityRenderState state, ItemStackRenderState item,
    //$$                                    HumanoidArm arm, PoseStack ps,
    //$$                                    SubmitNodeCollector col, int l) {
    //$$     boolean isPlayer = state.entityType == EntityType.PLAYER;
    //$$     GlowOutlineConfig.Toggle flag = isPlayer
    //$$             ? GlowOutlineConfig.Toggle.THIRD_PERSON
    //$$             : GlowOutlineConfig.Toggle.OTHER_ENTITIES;
    //$$     ItemStack itemStack = ((ArmedEntityRenderStateAccessor) (Object) state).gallium$getHandStack(arm);
    //$$     SubmitNodeCollector wrapped = CaptureSites.beginIfCapturable(itemStack, collector, flag);
    //$$     try {
    //$$         original.call(renderState, poseStack, wrapped, light, overlay, outlineColor);
    //$$     } finally {
    //$$         CaptureSites.end();
    //$$     }
    //$$ }
    //#endif
}
//#elseif MC>=1_21_05
//$$ import cn.spectra.gallium.glowoutline.GlowOutlineConfig;
//$$ import cn.spectra.gallium.glowoutline.capture.ArmedEntityRenderStateAccessor;
//$$ import cn.spectra.gallium.glowoutline.capture.CaptureSites;
//$$ import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
//$$ import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
//$$ import com.mojang.blaze3d.vertex.PoseStack;
//$$ import net.minecraft.client.renderer.MultiBufferSource;
//$$ import net.minecraft.client.renderer.entity.layers.ItemInHandLayer;
//$$ import net.minecraft.client.renderer.entity.state.ArmedEntityRenderState;
//$$ import net.minecraft.client.renderer.item.ItemStackRenderState;
//$$ import net.minecraft.world.entity.EntityType;
//$$ import net.minecraft.world.entity.HumanoidArm;
//$$ import net.minecraft.world.item.ItemStack;
//$$ import org.spongepowered.asm.mixin.Mixin;
//$$ import org.spongepowered.asm.mixin.injection.At;
//$$
//$$ @Mixin(ItemInHandLayer.class)
//$$ public class ItemInHandLayerMixin {
//$$
//$$     @WrapOperation(method = "renderArmWithItem", at = @At(value = "INVOKE",
//$$             target = "Lnet/minecraft/client/renderer/item/ItemStackRenderState;render(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;II)V"))
//$$     private void galliumWrapItemRender(ItemStackRenderState renderState, PoseStack poseStack,
//$$                                        MultiBufferSource bufferSource, int light, int overlay,
//$$                                        Operation<Void> original,
//$$                                        ArmedEntityRenderState state, ItemStackRenderState item,
//$$                                        HumanoidArm arm, PoseStack ps, MultiBufferSource bs, int i) {
//$$         boolean isPlayer = state.entityType == EntityType.PLAYER;
//$$         GlowOutlineConfig.Toggle flag = isPlayer
//$$                 ? GlowOutlineConfig.Toggle.THIRD_PERSON
//$$                 : GlowOutlineConfig.Toggle.OTHER_ENTITIES;
//$$         ItemStack itemStack = ((ArmedEntityRenderStateAccessor) (Object) state).gallium$getHandStack(arm);
//$$         MultiBufferSource wrapped = CaptureSites.beginIfCapturable(itemStack, bufferSource, flag);
//$$         try {
//$$             original.call(renderState, poseStack, wrapped, light, overlay);
//$$         } finally {
//$$             CaptureSites.end();
//$$         }
//$$     }
//$$ }
//#elseif MC>=1_21_04
//$$ // 1.21.4: same renderArmWithItem flow as 1.21.5/1.21.8, but EntityRenderState lacks the
//$$ // `entityType` field (added in 1.21.5). Use `instanceof PlayerRenderState` to distinguish
//$$ // player-vs-other-mob third-person held item rendering.
//$$ import cn.spectra.gallium.glowoutline.GlowOutlineConfig;
//$$ import cn.spectra.gallium.glowoutline.capture.CaptureSites;
//$$ import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
//$$ import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
//$$ import com.mojang.blaze3d.vertex.PoseStack;
//$$ import net.minecraft.client.renderer.MultiBufferSource;
//$$ import net.minecraft.client.renderer.entity.layers.ItemInHandLayer;
//$$ import net.minecraft.client.renderer.entity.state.ArmedEntityRenderState;
//$$ import net.minecraft.client.renderer.entity.state.PlayerRenderState;
//$$ import net.minecraft.client.renderer.item.ItemStackRenderState;
//$$ import net.minecraft.world.entity.HumanoidArm;
//$$ import net.minecraft.world.item.ItemStack;
//$$ import org.spongepowered.asm.mixin.Mixin;
//$$ import org.spongepowered.asm.mixin.injection.At;
//$$
//$$ @Mixin(ItemInHandLayer.class)
//$$ public class ItemInHandLayerMixin {
//$$
//$$     @WrapOperation(method = "renderArmWithItem", at = @At(value = "INVOKE",
//$$             target = "Lnet/minecraft/client/renderer/item/ItemStackRenderState;render(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;II)V"))
//$$     private void galliumWrapItemRender(ItemStackRenderState renderState, PoseStack poseStack,
//$$                                        MultiBufferSource bufferSource, int light, int overlay,
//$$                                        Operation<Void> original,
//$$                                        ArmedEntityRenderState state, ItemStackRenderState item,
//$$                                        HumanoidArm arm, PoseStack ps, MultiBufferSource bs, int i) {
//$$         boolean isPlayer = state instanceof PlayerRenderState;
//$$         GlowOutlineConfig.Toggle flag = isPlayer
//$$                 ? GlowOutlineConfig.Toggle.THIRD_PERSON
//$$                 : GlowOutlineConfig.Toggle.OTHER_ENTITIES;
//$$         // Recover the matching ItemStack: ArmedEntityRenderState carries rightHandItem and
//$$         // leftHandItem ItemStackRenderStates but no raw ItemStacks. Compare identity to
//$$         // figure out which arm we're rendering, then read the live entity's stack via the
//$$         // accessor populated by ArmedEntityRenderStateMixin (which is gated <1_21_11 — and
//$$         // therefore active on 1.21.4 too, since the stub-strip list keeps it real).
//$$         ItemStack itemStack;
//$$         if (state instanceof cn.spectra.gallium.glowoutline.capture.ArmedEntityRenderStateAccessor accessor) {
//$$             itemStack = accessor.gallium$getHandStack(arm);
//$$         } else {
//$$             itemStack = ItemStack.EMPTY;
//$$         }
//$$         MultiBufferSource wrapped = CaptureSites.beginIfCapturable(itemStack, bufferSource, flag);
//$$         try {
//$$             original.call(renderState, poseStack, wrapped, light, overlay);
//$$         } finally {
//$$             CaptureSites.end();
//$$         }
//$$     }
//$$ }
//#else
//$$ // 1.21.3: hand items live on LivingEntityRenderState directly as raw ItemStack +
//$$ // @Nullable BakedModel — there's no ArmedEntityRenderState class, no ItemStackRenderState,
//$$ // and renderArmWithItem already takes the ItemStack as a parameter. We hook the inner
//$$ // ItemRenderer.render(...,BakedModel) call and read the ItemStack from the enclosing
//$$ // method's parameters; no side-channel accessor needed.
//$$ import cn.spectra.gallium.glowoutline.GlowOutlineConfig;
//$$ import cn.spectra.gallium.glowoutline.capture.CaptureSites;
//$$ import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
//$$ import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
//$$ import com.mojang.blaze3d.vertex.PoseStack;
//$$ import net.minecraft.client.renderer.MultiBufferSource;
//$$ import net.minecraft.client.renderer.entity.ItemRenderer;
//$$ import net.minecraft.client.renderer.entity.layers.ItemInHandLayer;
//$$ import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
//$$ import net.minecraft.client.renderer.entity.state.PlayerRenderState;
//$$ import net.minecraft.client.resources.model.BakedModel;
//$$ import net.minecraft.world.entity.HumanoidArm;
//$$ import net.minecraft.world.item.ItemDisplayContext;
//$$ import net.minecraft.world.item.ItemStack;
//$$ import org.spongepowered.asm.mixin.Mixin;
//$$ import org.spongepowered.asm.mixin.injection.At;
//$$
//$$ @Mixin(ItemInHandLayer.class)
//$$ public class ItemInHandLayerMixin {
//$$
//$$     @WrapOperation(method = "renderArmWithItem", at = @At(value = "INVOKE",
//$$             target = "Lnet/minecraft/client/renderer/entity/ItemRenderer;render(Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/item/ItemDisplayContext;ZLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;IILnet/minecraft/client/resources/model/BakedModel;)V"))
//$$     private void galliumWrapItemRender(ItemRenderer renderer, ItemStack itemStack, ItemDisplayContext ctx, boolean leftHand,
//$$                                        PoseStack poseStack, MultiBufferSource bufferSource, int light, int overlay, BakedModel bakedModel,
//$$                                        Operation<Void> original,
//$$                                        LivingEntityRenderState state, BakedModel bm, ItemStack stack, ItemDisplayContext dc,
//$$                                        HumanoidArm arm, PoseStack ps, MultiBufferSource bs, int i) {
//$$         boolean isPlayer = state instanceof PlayerRenderState;
//$$         GlowOutlineConfig.Toggle flag = isPlayer
//$$                 ? GlowOutlineConfig.Toggle.THIRD_PERSON
//$$                 : GlowOutlineConfig.Toggle.OTHER_ENTITIES;
//$$         MultiBufferSource wrapped = CaptureSites.beginIfCapturable(itemStack, bufferSource, flag);
//$$         try {
//$$             original.call(renderer, itemStack, ctx, leftHand, poseStack, wrapped, light, overlay, bakedModel);
//$$         } finally {
//$$             CaptureSites.end();
//$$         }
//$$     }
//$$ }
//#endif
