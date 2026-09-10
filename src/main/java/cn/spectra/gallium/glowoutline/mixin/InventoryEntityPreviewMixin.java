package cn.spectra.gallium.glowoutline.mixin;

//#if MC<1_21_06
//$$ import cn.spectra.gallium.glowoutline.capture.GuiEntityGlowCapture;
//$$ import cn.spectra.gallium.glowoutline.capture.LegacyEntityPreview;
//$$ import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
//$$ import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
//$$ import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
//$$ import com.mojang.blaze3d.vertex.PoseStack;
//$$ import net.minecraft.client.gui.GuiGraphics;
//$$ import net.minecraft.client.gui.screens.inventory.InventoryScreen;
//$$ import net.minecraft.client.renderer.MultiBufferSource;
//$$ import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
//$$ import net.minecraft.world.entity.Entity;
//$$ import net.minecraft.world.entity.LivingEntity;
//$$ import org.joml.Quaternionf;
//$$ import org.joml.Vector3f;
//$$ import org.spongepowered.asm.mixin.Mixin;
//$$ import org.spongepowered.asm.mixin.injection.At;
//$$
//$$ @Mixin(InventoryScreen.class)
//$$ public class InventoryEntityPreviewMixin {
//$$     @WrapMethod(method = "renderEntityInInventory")
//$$     private static void galliumPreview(GuiGraphics gui, float x, float y, float scale,
//$$                                        Vector3f translation, Quaternionf rotation, Quaternionf camera,
//$$                                        LivingEntity entity, Operation<Void> original) {
//$$         LegacyEntityPreview.render(gui, scale,
//$$                 () -> original.call(gui, x, y, scale, translation, rotation, camera, entity));
//$$     }
//$$
//$$     // The invocation lives in a synthetic Consumer/Runnable whose name differs between mappings.
//$$     // Match the unique EntityRenderDispatcher invocation, not that unstable synthetic name.
//$$     @WrapOperation(method = "*", at = @At(value = "INVOKE",
//#if MC>=1_21_02
//$$             target = "Lnet/minecraft/client/renderer/entity/EntityRenderDispatcher;render(Lnet/minecraft/world/entity/Entity;DDDFLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V"
//#else
//$$             target = "Lnet/minecraft/client/renderer/entity/EntityRenderDispatcher;render(Lnet/minecraft/world/entity/Entity;DDDFFLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V"
//#endif
//$$     ))
//$$     private static void galliumRedirectEntity(EntityRenderDispatcher dispatcher, Entity entity,
//$$                                               double x, double y, double z,
//#if MC<1_21_02
//$$                                               float yaw,
//#endif
//$$                                               float partialTick, PoseStack pose, MultiBufferSource source,
//$$                                               int light, Operation<Void> original) {
//$$         var preview = LegacyEntityPreview.active();
//$$         MultiBufferSource output = preview != null ? preview.legacyBody() : source;
//$$         original.call(dispatcher, entity, x, y, z,
//#if MC<1_21_02
//$$                 yaw,
//#endif
//$$                 partialTick, pose, output, light);
//$$         if (preview != null) preview.legacyBody().flushToTarget(preview.target());
//$$     }
//$$ }
//#else
public final class InventoryEntityPreviewMixin {}
//#endif
