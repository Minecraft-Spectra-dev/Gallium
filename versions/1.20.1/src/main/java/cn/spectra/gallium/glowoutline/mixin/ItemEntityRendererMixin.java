package cn.spectra.gallium.glowoutline.mixin;
import cn.spectra.gallium.glowoutline.GlowOutlineConfig;
import cn.spectra.gallium.glowoutline.capture.CaptureSites;
import cn.spectra.gallium.glowoutline.capture.GlowCaptureManager;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.ItemEntityRenderer;
import net.minecraft.client.renderer.entity.ItemRenderer;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
@Mixin(ItemEntityRenderer.class)
public class ItemEntityRendererMixin {
    @Unique private MultiBufferSource gallium$itemCapture;
    // Keep every copy of a dropped stack in one capture, while nameplates retain the original buffers.
    @WrapMethod(method = "render(Lnet/minecraft/world/entity/item/ItemEntity;FFLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V")
    private void galliumCaptureStack(ItemEntity entity, float yaw, float partial, PoseStack pose,
                                    MultiBufferSource buffers, int light, Operation<Void> original) {
        MultiBufferSource previous = gallium$itemCapture;
        gallium$itemCapture = CaptureSites.beginIfCapturable(entity.getItem(), buffers, GlowOutlineConfig.Toggle.DROPPED_ITEMS);
        try { original.call(entity, yaw, partial, pose, buffers, light); }
        finally { gallium$itemCapture = previous; CaptureSites.end(); }
    }
    @WrapOperation(method = "render(Lnet/minecraft/world/entity/item/ItemEntity;FFLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/client/renderer/entity/ItemRenderer;render(Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/item/ItemDisplayContext;ZLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;IILnet/minecraft/client/resources/model/BakedModel;)V"))
    private void galliumCaptureCopy(ItemRenderer renderer, ItemStack item, ItemDisplayContext context,
                                   boolean leftHand, PoseStack pose, MultiBufferSource buffers, int light,
                                   int overlay, BakedModel model, Operation<Void> original) {
        GlowCaptureManager.captureItemView(pose);
        original.call(renderer, item, context, leftHand, pose,
                      gallium$itemCapture == null ? buffers : gallium$itemCapture, light, overlay, model);
    }
}
