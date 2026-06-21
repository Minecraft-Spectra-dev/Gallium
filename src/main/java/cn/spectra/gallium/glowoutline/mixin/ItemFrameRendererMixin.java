package cn.spectra.gallium.glowoutline.mixin;

//#if MC>=1_21_09
import cn.spectra.gallium.glowoutline.GlowOutlineConfig;
import cn.spectra.gallium.glowoutline.capture.CaptureSites;
import cn.spectra.gallium.glowoutline.capture.ItemFrameRenderStateAccessor;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.ItemFrameRenderer;
import net.minecraft.client.renderer.entity.state.ItemFrameRenderState;
import net.minecraft.client.renderer.item.ItemStackRenderState;
//#if MC>=1_26_00
import net.minecraft.client.renderer.state.level.CameraRenderState;
//#else
//$$ import net.minecraft.client.renderer.state.CameraRenderState;
//#endif
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
        SubmitNodeCollector wrapped = CaptureSites.beginIfCapturable(
                itemStack, collector, GlowOutlineConfig.Toggle.OTHER_ENTITIES);
        try {
            original.call(renderState, poseStack, wrapped, light, overlay, outlineColor);
        } finally {
            CaptureSites.end();
        }
    }
}
//#elseif MC>=1_21_04
//$$ import cn.spectra.gallium.glowoutline.GlowOutlineConfig;
//$$ import cn.spectra.gallium.glowoutline.capture.CaptureSites;
//$$ import cn.spectra.gallium.glowoutline.capture.ItemFrameRenderStateAccessor;
//$$ import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
//$$ import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
//$$ import com.mojang.blaze3d.vertex.PoseStack;
//$$ import net.minecraft.client.renderer.MultiBufferSource;
//$$ import net.minecraft.client.renderer.entity.ItemFrameRenderer;
//$$ import net.minecraft.client.renderer.entity.state.ItemFrameRenderState;
//$$ import net.minecraft.client.renderer.item.ItemStackRenderState;
//$$ import net.minecraft.world.item.ItemStack;
//$$ import org.spongepowered.asm.mixin.Mixin;
//$$ import org.spongepowered.asm.mixin.injection.At;
//$$ import org.spongepowered.asm.mixin.injection.Inject;
//$$ import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
//$$
//$$ @Mixin(ItemFrameRenderer.class)
//$$ public class ItemFrameRendererMixin {
//$$
//$$     @Inject(method = "extractRenderState(Lnet/minecraft/world/entity/decoration/ItemFrame;Lnet/minecraft/client/renderer/entity/state/ItemFrameRenderState;F)V",
//$$             at = @At("HEAD"))
//$$     private void galliumCaptureItemStack(net.minecraft.world.entity.decoration.ItemFrame entity, ItemFrameRenderState state, float partialTicks, CallbackInfo ci) {
//$$         ((ItemFrameRenderStateAccessor) state).gallium$setItemStack(entity.getItem());
//$$     }
//$$
//$$     @WrapOperation(method = "render(Lnet/minecraft/client/renderer/entity/state/ItemFrameRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V", at = @At(value = "INVOKE",
//$$             target = "Lnet/minecraft/client/renderer/item/ItemStackRenderState;render(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;II)V"))
//$$     private void galliumWrapItemRender(ItemStackRenderState renderState, PoseStack poseStack,
//$$                                        MultiBufferSource bufferSource, int light, int overlay,
//$$                                        Operation<Void> original,
//$$                                        ItemFrameRenderState state, PoseStack ps, MultiBufferSource bs, int i) {
//$$         ItemStack itemStack = ((ItemFrameRenderStateAccessor) state).gallium$getItemStack();
//$$         MultiBufferSource wrapped = CaptureSites.beginIfCapturable(
//$$                 itemStack, bufferSource, GlowOutlineConfig.Toggle.OTHER_ENTITIES);
//$$         try {
//$$             original.call(renderState, poseStack, wrapped, light, overlay);
//$$         } finally {
//$$             CaptureSites.end();
//$$         }
//$$     }
//$$ }
//#elseif MC>=1_21_02
//$$ // 1.21.3: ItemFrameRenderState.itemStack is a raw ItemStack; the render path calls
//$$ // ItemRenderer.render(itemStack, FIXED, false, poseStack, multiBufferSource, l,
//$$ // NO_OVERLAY, itemFrameRenderState.itemModel) directly. We wrap that call and read
//$$ // the ItemStack from its first argument — no side-channel accessor needed.
//$$ import cn.spectra.gallium.glowoutline.GlowOutlineConfig;
//$$ import cn.spectra.gallium.glowoutline.capture.CaptureSites;
//$$ import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
//$$ import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
//$$ import com.mojang.blaze3d.vertex.PoseStack;
//$$ import net.minecraft.client.renderer.MultiBufferSource;
//$$ import net.minecraft.client.renderer.entity.ItemFrameRenderer;
//$$ import net.minecraft.client.renderer.entity.ItemRenderer;
//$$ import net.minecraft.client.renderer.entity.state.ItemFrameRenderState;
//$$ import net.minecraft.client.resources.model.BakedModel;
//$$ import net.minecraft.world.item.ItemDisplayContext;
//$$ import net.minecraft.world.item.ItemStack;
//$$ import org.spongepowered.asm.mixin.Mixin;
//$$ import org.spongepowered.asm.mixin.injection.At;
//$$
//$$ @Mixin(ItemFrameRenderer.class)
//$$ public class ItemFrameRendererMixin {
//$$
//$$     @WrapOperation(method = "render(Lnet/minecraft/client/renderer/entity/state/ItemFrameRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V", at = @At(value = "INVOKE",
//$$             target = "Lnet/minecraft/client/renderer/entity/ItemRenderer;render(Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/item/ItemDisplayContext;ZLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;IILnet/minecraft/client/resources/model/BakedModel;)V"))
//$$     private void galliumWrapItemRender(ItemRenderer renderer, ItemStack itemStack, ItemDisplayContext ctx, boolean leftHand,
//$$                                        PoseStack poseStack, MultiBufferSource bufferSource, int light, int overlay, BakedModel bakedModel,
//$$                                        Operation<Void> original,
//$$                                        ItemFrameRenderState state, PoseStack ps, MultiBufferSource bs, int i) {
//$$         MultiBufferSource wrapped = CaptureSites.beginIfCapturable(
//$$                 itemStack, bufferSource, GlowOutlineConfig.Toggle.OTHER_ENTITIES);
//$$         try {
//$$             original.call(renderer, itemStack, ctx, leftHand, poseStack, wrapped, light, overlay, bakedModel);
//$$         } finally {
//$$             CaptureSites.end();
//$$         }
//$$     }
//$$ }
//#else
//$$ // 1.21.1 (pre-1.21.2): ItemFrameRenderer.render takes the ItemFrame entity directly; non-map
//$$ // framed items draw via ItemRenderer.renderStatic(...). We wrap that call, read the ItemStack
//$$ // from its first arg, and tee the MultiBufferSource. (Map items go through MapRenderer — not
//$$ // an item draw — so they're correctly excluded.)
//$$ import cn.spectra.gallium.glowoutline.GlowOutlineConfig;
//$$ import cn.spectra.gallium.glowoutline.capture.CaptureSites;
//$$ import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
//$$ import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
//$$ import com.mojang.blaze3d.vertex.PoseStack;
//$$ import net.minecraft.client.renderer.MultiBufferSource;
//$$ import net.minecraft.client.renderer.entity.ItemFrameRenderer;
//$$ import net.minecraft.client.renderer.entity.ItemRenderer;
//$$ import net.minecraft.world.entity.decoration.ItemFrame;
//$$ import net.minecraft.world.item.ItemDisplayContext;
//$$ import net.minecraft.world.item.ItemStack;
//$$ import net.minecraft.world.level.Level;
//$$ import org.spongepowered.asm.mixin.Mixin;
//$$ import org.spongepowered.asm.mixin.injection.At;
//$$
//$$ @Mixin(ItemFrameRenderer.class)
//$$ public class ItemFrameRendererMixin {
//$$
//$$     @WrapOperation(method = "render(Lnet/minecraft/world/entity/decoration/ItemFrame;FFLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V", at = @At(value = "INVOKE",
//$$             target = "Lnet/minecraft/client/renderer/entity/ItemRenderer;renderStatic(Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/item/ItemDisplayContext;IILcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;Lnet/minecraft/world/level/Level;I)V"))
//$$     private void galliumWrapItemRender(ItemRenderer renderer, ItemStack itemStack, ItemDisplayContext ctx, int light, int overlay,
//$$                                        PoseStack poseStack, MultiBufferSource bufferSource, Level level, int id,
//$$                                        Operation<Void> original,
//$$                                        ItemFrame entity, float f, float g, PoseStack ps, MultiBufferSource bs, int i) {
//$$         MultiBufferSource wrapped = CaptureSites.beginIfCapturable(
//$$                 itemStack, bufferSource, GlowOutlineConfig.Toggle.OTHER_ENTITIES);
//$$         try {
//$$             original.call(renderer, itemStack, ctx, light, overlay, poseStack, wrapped, level, id);
//$$         } finally {
//$$             CaptureSites.end();
//$$         }
//$$     }
//$$ }
//#endif
