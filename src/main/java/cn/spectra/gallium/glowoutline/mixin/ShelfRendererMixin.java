package cn.spectra.gallium.glowoutline.mixin;

//#if MC>=1_21_09
import cn.spectra.gallium.glowoutline.GlowOutlineConfig;
import cn.spectra.gallium.glowoutline.capture.CaptureSites;
import cn.spectra.gallium.glowoutline.capture.ShelfRenderStateAccessor;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.blockentity.ShelfRenderer;
import net.minecraft.client.renderer.blockentity.state.ShelfRenderState;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.core.NonNullList;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.ShelfBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ShelfRenderer.class)
public class ShelfRendererMixin {

    /** Mirrors {@code ShelfRenderStateMixin.gallium$items.length}. Vanilla shelves always hold
     *  three items; making the assumption explicit at the call site means a future shelf
     *  expansion fails review here, instead of silently no-opping inside
     *  {@link ShelfRenderStateAccessor#gallium$setItemStack(int, ItemStack)}'s out-of-range guard.
     *  If vanilla ever expands shelves, both this constant and the state mixin's array length
     *  must grow together. */
    private static final int GALLIUM_SHELF_SLOT_CAP = 3;

    @Inject(method = "extractRenderState(Lnet/minecraft/world/level/block/entity/ShelfBlockEntity;Lnet/minecraft/client/renderer/blockentity/state/ShelfRenderState;FLnet/minecraft/world/phys/Vec3;Lnet/minecraft/client/renderer/feature/ModelFeatureRenderer$CrumblingOverlay;)V",
            at = @At("TAIL"))
    private void galliumCaptureItems(ShelfBlockEntity blockEntity, ShelfRenderState state, float partialTicks,
                                      net.minecraft.world.phys.Vec3 cameraPosition,
                                      net.minecraft.client.renderer.feature.ModelFeatureRenderer.CrumblingOverlay breakProgress,
                                      CallbackInfo ci) {
        NonNullList<ItemStack> items = blockEntity.getItems();
        ShelfRenderStateAccessor accessor = (ShelfRenderStateAccessor) state;
        int bound = Math.min(items.size(), GALLIUM_SHELF_SLOT_CAP);
        for (int i = 0; i < bound; i++) {
            accessor.gallium$setItemStack(i, items.get(i));
        }
    }

    @WrapOperation(method = "submitItem", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/item/ItemStackRenderState;submit(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;III)V"))
    private void galliumWrapItemSubmit(ItemStackRenderState renderState, PoseStack poseStack,
                                       SubmitNodeCollector collector, int light, int overlay, int outlineColor,
                                       Operation<Void> original,
                                       ShelfRenderState state, ItemStackRenderState item, PoseStack ps,
                                       SubmitNodeCollector col, int slot, float yRot) {
        ItemStack itemStack = ((ShelfRenderStateAccessor) state).gallium$getItemStack(slot);
        SubmitNodeCollector wrapped = CaptureSites.beginIfCapturable(
                itemStack, collector, GlowOutlineConfig.Toggle.OTHER_ENTITIES);
        try {
            original.call(renderState, poseStack, wrapped, light, overlay, outlineColor);
        } finally {
            CaptureSites.end();
        }
    }
}
//#else
//$$ // Shelves (ShelfRenderer) are a 1.21.9 feature absent on 1.21.6–1.21.8; the world
//$$ // submit path it hooks also doesn't exist here. Empty stub, stripped from mixins.json
//$$ // via STUB_MIXIN_CLASSES_PRE_1_21_09 in common.gradle.
//$$ public final class ShelfRendererMixin {
//$$     private ShelfRendererMixin() {}
//$$ }
//#endif
