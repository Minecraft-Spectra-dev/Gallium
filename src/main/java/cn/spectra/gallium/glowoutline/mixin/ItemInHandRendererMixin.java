package cn.spectra.gallium.glowoutline.mixin;

import cn.spectra.gallium.glowoutline.ItemEffectsManager;
import cn.spectra.gallium.glowoutline.render.GlowState;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.ItemInHandRenderer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ItemInHandRenderer.class)
public class ItemInHandRendererMixin {

    @Inject(method = "renderHandsWithItems", at = @At("HEAD"))
    private void glowBeforeHands(float frameInterp, PoseStack poseStack, SubmitNodeCollector collector, LocalPlayer player, int light, CallbackInfo ci) {
        if (!ItemEffectsManager.isActive()) return;
        if (player == null) return;
        var main = player.getMainHandItem();
        var off = player.getOffhandItem();
        if (main.isEmpty() && off.isEmpty()) return;

        GlowState.setMainHandItem(main.copy());
        GlowState.setOffHandItem(off.copy());
        GlowState.setActive(true);
    }
}
