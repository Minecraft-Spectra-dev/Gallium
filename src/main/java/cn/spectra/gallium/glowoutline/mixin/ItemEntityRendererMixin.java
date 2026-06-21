package cn.spectra.gallium.glowoutline.mixin;

//#if MC>=1_21_09
import cn.spectra.gallium.glowoutline.GlowOutlineConfig;
import cn.spectra.gallium.glowoutline.capture.CaptureSites;
import cn.spectra.gallium.glowoutline.capture.ItemEntityRenderStateAccessor;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.ItemEntityRenderer;
import net.minecraft.client.renderer.entity.state.ItemClusterRenderState;
import net.minecraft.client.renderer.entity.state.ItemEntityRenderState;
//#if MC>=1_26_00
import net.minecraft.client.renderer.state.level.CameraRenderState;
//#else
//$$ import net.minecraft.client.renderer.state.CameraRenderState;
//#endif
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ItemEntityRenderer.class)
public class ItemEntityRendererMixin {

    @Inject(method = "extractRenderState(Lnet/minecraft/world/entity/item/ItemEntity;Lnet/minecraft/client/renderer/entity/state/ItemEntityRenderState;F)V",
            at = @At("HEAD"))
    private void galliumCaptureItemStack(net.minecraft.world.entity.item.ItemEntity entity, ItemEntityRenderState state, float partialTicks, CallbackInfo ci) {
        ((ItemEntityRenderStateAccessor) state).gallium$setItemStack(entity.getItem());
    }

    @WrapOperation(method = "submit", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/entity/ItemEntityRenderer;submitMultipleFromCount(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;ILnet/minecraft/client/renderer/entity/state/ItemClusterRenderState;Lnet/minecraft/util/RandomSource;Lnet/minecraft/world/phys/AABB;)V"))
    private void galliumWrapSubmitMultiple(PoseStack poseStack, SubmitNodeCollector collector,
                                           int lightCoords, ItemClusterRenderState clusterState,
                                           RandomSource random, AABB boundingBox,
                                           Operation<Void> original,
                                           ItemEntityRenderState state, PoseStack ps, SubmitNodeCollector col,
                                           CameraRenderState cam) {
        ItemStack itemStack = ((ItemEntityRenderStateAccessor) state).gallium$getItemStack();
        SubmitNodeCollector wrapped = CaptureSites.beginIfCapturable(
                itemStack, collector, GlowOutlineConfig.Toggle.DROPPED_ITEMS);
        try {
            original.call(poseStack, wrapped, lightCoords, clusterState, random, boundingBox);
        } finally {
            CaptureSites.end();
        }
    }
}
//#elseif MC>=1_21_05
//$$ import cn.spectra.gallium.glowoutline.GlowOutlineConfig;
//$$ import cn.spectra.gallium.glowoutline.capture.CaptureSites;
//$$ import cn.spectra.gallium.glowoutline.capture.ItemEntityRenderStateAccessor;
//$$ import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
//$$ import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
//$$ import com.mojang.blaze3d.vertex.PoseStack;
//$$ import net.minecraft.client.renderer.MultiBufferSource;
//$$ import net.minecraft.client.renderer.entity.ItemEntityRenderer;
//$$ import net.minecraft.client.renderer.entity.state.ItemClusterRenderState;
//$$ import net.minecraft.client.renderer.entity.state.ItemEntityRenderState;
//$$ import net.minecraft.util.RandomSource;
//$$ import net.minecraft.world.item.ItemStack;
//$$ import net.minecraft.world.phys.AABB;
//$$ import org.spongepowered.asm.mixin.Mixin;
//$$ import org.spongepowered.asm.mixin.injection.At;
//$$ import org.spongepowered.asm.mixin.injection.Inject;
//$$ import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
//$$
//$$ @Mixin(ItemEntityRenderer.class)
//$$ public class ItemEntityRendererMixin {
//$$
//$$     @Inject(method = "extractRenderState(Lnet/minecraft/world/entity/item/ItemEntity;Lnet/minecraft/client/renderer/entity/state/ItemEntityRenderState;F)V",
//$$             at = @At("HEAD"))
//$$     private void galliumCaptureItemStack(net.minecraft.world.entity.item.ItemEntity entity, ItemEntityRenderState state, float partialTicks, CallbackInfo ci) {
//$$         ((ItemEntityRenderStateAccessor) state).gallium$setItemStack(entity.getItem());
//$$     }
//$$
//$$     @WrapOperation(method = "render(Lnet/minecraft/client/renderer/entity/state/ItemEntityRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V", at = @At(value = "INVOKE",
//$$             target = "Lnet/minecraft/client/renderer/entity/ItemEntityRenderer;renderMultipleFromCount(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;ILnet/minecraft/client/renderer/entity/state/ItemClusterRenderState;Lnet/minecraft/util/RandomSource;Lnet/minecraft/world/phys/AABB;)V"))
//$$     private void galliumWrapRenderMultiple(PoseStack poseStack, MultiBufferSource bufferSource,
//$$                                            int lightCoords, ItemClusterRenderState clusterState,
//$$                                            RandomSource random, AABB boundingBox,
//$$                                            Operation<Void> original,
//$$                                            ItemEntityRenderState state, PoseStack ps, MultiBufferSource bs, int i) {
//$$         ItemStack itemStack = ((ItemEntityRenderStateAccessor) state).gallium$getItemStack();
//$$         MultiBufferSource wrapped = CaptureSites.beginIfCapturable(
//$$                 itemStack, bufferSource, GlowOutlineConfig.Toggle.DROPPED_ITEMS);
//$$         try {
//$$             original.call(poseStack, wrapped, lightCoords, clusterState, random, boundingBox);
//$$         } finally {
//$$             CaptureSites.end();
//$$         }
//$$     }
//$$ }
//#elseif MC>=1_21_04
//$$ // 1.21.4: ItemEntityRenderer.renderMultipleFromCount has no AABB parameter — that
//$$ // was added in 1.21.5 alongside the model bounding-box pre-pass. Both the injection
//$$ // descriptor and the wrapper signature must drop AABB to match this version.
//$$ import cn.spectra.gallium.glowoutline.GlowOutlineConfig;
//$$ import cn.spectra.gallium.glowoutline.capture.CaptureSites;
//$$ import cn.spectra.gallium.glowoutline.capture.ItemEntityRenderStateAccessor;
//$$ import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
//$$ import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
//$$ import com.mojang.blaze3d.vertex.PoseStack;
//$$ import net.minecraft.client.renderer.MultiBufferSource;
//$$ import net.minecraft.client.renderer.entity.ItemEntityRenderer;
//$$ import net.minecraft.client.renderer.entity.state.ItemClusterRenderState;
//$$ import net.minecraft.client.renderer.entity.state.ItemEntityRenderState;
//$$ import net.minecraft.util.RandomSource;
//$$ import net.minecraft.world.item.ItemStack;
//$$ import org.spongepowered.asm.mixin.Mixin;
//$$ import org.spongepowered.asm.mixin.injection.At;
//$$ import org.spongepowered.asm.mixin.injection.Inject;
//$$ import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
//$$
//$$ @Mixin(ItemEntityRenderer.class)
//$$ public class ItemEntityRendererMixin {
//$$
//$$     @Inject(method = "extractRenderState(Lnet/minecraft/world/entity/item/ItemEntity;Lnet/minecraft/client/renderer/entity/state/ItemEntityRenderState;F)V",
//$$             at = @At("HEAD"))
//$$     private void galliumCaptureItemStack(net.minecraft.world.entity.item.ItemEntity entity, ItemEntityRenderState state, float partialTicks, CallbackInfo ci) {
//$$         ((ItemEntityRenderStateAccessor) state).gallium$setItemStack(entity.getItem());
//$$     }
//$$
//$$     @WrapOperation(method = "render(Lnet/minecraft/client/renderer/entity/state/ItemEntityRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V", at = @At(value = "INVOKE",
//$$             target = "Lnet/minecraft/client/renderer/entity/ItemEntityRenderer;renderMultipleFromCount(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;ILnet/minecraft/client/renderer/entity/state/ItemClusterRenderState;Lnet/minecraft/util/RandomSource;)V"))
//$$     private void galliumWrapRenderMultiple(PoseStack poseStack, MultiBufferSource bufferSource,
//$$                                            int lightCoords, ItemClusterRenderState clusterState,
//$$                                            RandomSource random,
//$$                                            Operation<Void> original,
//$$                                            ItemEntityRenderState state, PoseStack ps, MultiBufferSource bs, int i) {
//$$         ItemStack itemStack = ((ItemEntityRenderStateAccessor) state).gallium$getItemStack();
//$$         MultiBufferSource wrapped = CaptureSites.beginIfCapturable(
//$$                 itemStack, bufferSource, GlowOutlineConfig.Toggle.DROPPED_ITEMS);
//$$         try {
//$$             original.call(poseStack, wrapped, lightCoords, clusterState, random);
//$$         } finally {
//$$             CaptureSites.end();
//$$         }
//$$     }
//$$ }
//#else
//$$ // 1.21.3 / 1.21.1: renderMultipleFromCount is an 8-arg STATIC method
//$$ // (ItemRenderer, PoseStack, MultiBufferSource, int, ItemStack, BakedModel, boolean, RandomSource);
//$$ // ItemClusterRenderState doesn't exist yet. We wrap that static call directly and
//$$ // read the ItemStack from its 5th arg — no side-channel accessor needed. The only
//$$ // difference on 1.21.1 (pre-1.21.2) is the enclosing render method: it takes the
//$$ // ItemEntity directly instead of an ItemEntityRenderState.
//$$ import cn.spectra.gallium.glowoutline.GlowOutlineConfig;
//$$ import cn.spectra.gallium.glowoutline.capture.CaptureSites;
//$$ import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
//$$ import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
//$$ import com.mojang.blaze3d.vertex.PoseStack;
//$$ import net.minecraft.client.renderer.MultiBufferSource;
//$$ import net.minecraft.client.renderer.entity.ItemEntityRenderer;
//$$ import net.minecraft.client.renderer.entity.ItemRenderer;
//#if MC>=1_21_02
//$$ import net.minecraft.client.renderer.entity.state.ItemEntityRenderState;
//#else
//$$ import net.minecraft.world.entity.item.ItemEntity;
//#endif
//$$ import net.minecraft.client.resources.model.BakedModel;
//$$ import net.minecraft.util.RandomSource;
//$$ import net.minecraft.world.item.ItemStack;
//$$ import org.spongepowered.asm.mixin.Mixin;
//$$ import org.spongepowered.asm.mixin.injection.At;
//$$
//$$ @Mixin(ItemEntityRenderer.class)
//$$ public class ItemEntityRendererMixin {
//$$
//#if MC>=1_21_02
//$$     @WrapOperation(method = "render(Lnet/minecraft/client/renderer/entity/state/ItemEntityRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V", at = @At(value = "INVOKE",
//#else
//$$     @WrapOperation(method = "render(Lnet/minecraft/world/entity/item/ItemEntity;FFLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V", at = @At(value = "INVOKE",
//#endif
//$$             target = "Lnet/minecraft/client/renderer/entity/ItemEntityRenderer;renderMultipleFromCount(Lnet/minecraft/client/renderer/entity/ItemRenderer;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;ILnet/minecraft/world/item/ItemStack;Lnet/minecraft/client/resources/model/BakedModel;ZLnet/minecraft/util/RandomSource;)V"))
//$$     private void galliumWrapRenderMultiple(ItemRenderer renderer, PoseStack poseStack, MultiBufferSource bufferSource,
//$$                                            int lightCoords, ItemStack itemStack, BakedModel bakedModel,
//$$                                            boolean gui3d, RandomSource random,
//$$                                            Operation<Void> original,
//#if MC>=1_21_02
//$$                                            ItemEntityRenderState state, PoseStack ps, MultiBufferSource bs, int i) {
//#else
//$$                                            ItemEntity entity, float f, float g, PoseStack ps, MultiBufferSource bs, int i) {
//#endif
//$$         MultiBufferSource wrapped = CaptureSites.beginIfCapturable(
//$$                 itemStack, bufferSource, GlowOutlineConfig.Toggle.DROPPED_ITEMS);
//$$         try {
//$$             original.call(renderer, poseStack, wrapped, lightCoords, itemStack, bakedModel, gui3d, random);
//$$         } finally {
//$$             CaptureSites.end();
//$$         }
//$$     }
//$$ }
//#endif
