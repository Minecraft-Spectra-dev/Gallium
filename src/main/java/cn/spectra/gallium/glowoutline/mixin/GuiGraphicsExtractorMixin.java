package cn.spectra.gallium.glowoutline.mixin;

import cn.spectra.gallium.glowoutline.GlowOutlineConfig;
import cn.spectra.gallium.glowoutline.ItemEffectConfig;
import cn.spectra.gallium.glowoutline.ItemEffectsManager;
import cn.spectra.gallium.glowoutline.capture.GuiItemRenderStateAccessor;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.renderer.item.TrackingItemStackRenderState;
import net.minecraft.client.renderer.state.gui.GuiItemRenderState;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import org.joml.Matrix3x2f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(GuiGraphicsExtractor.class)
public class GuiGraphicsExtractorMixin {

    @WrapOperation(method = "item(Lnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/world/level/Level;Lnet/minecraft/world/item/ItemStack;III)V",
            at = @At(value = "NEW",
                    target = "(Lorg/joml/Matrix3x2f;Lnet/minecraft/client/renderer/item/TrackingItemStackRenderState;IILnet/minecraft/client/gui/navigation/ScreenRectangle;)Lnet/minecraft/client/renderer/state/gui/GuiItemRenderState;"))
    private GuiItemRenderState galliumAttachConfig(Matrix3x2f pose, TrackingItemStackRenderState renderState, int x, int y, ScreenRectangle scissor,
                                                    Operation<GuiItemRenderState> original,
                                                    LivingEntity owner, Level level, ItemStack itemStack, int xArg, int yArg, int seed) {
        GuiItemRenderState state = original.call(pose, renderState, x, y, scissor);
        if (!ItemEffectsManager.isActive() || !GlowOutlineConfig.isEnabled() || !GlowOutlineConfig.isGui()) return state;
        ItemEffectConfig cfg = ItemEffectsManager.getConfig(itemStack);
        if (cfg != null && !cfg.shader().isEmpty()) {
            ((GuiItemRenderStateAccessor) (Object) state).gallium$setEffectConfig(cfg);
        }
        return state;
    }
}
