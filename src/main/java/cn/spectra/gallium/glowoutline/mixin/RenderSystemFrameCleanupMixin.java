package cn.spectra.gallium.glowoutline.mixin;

import cn.spectra.gallium.glowoutline.SuperResolutionCompat;
import com.mojang.blaze3d.systems.RenderSystem;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Observes vanilla's real flipFrame tail without depending on its versioned arguments.
 * SuperResolutionCompat uses this only on the precisely gated pre-26.2 cleanup workaround;
 * Minecraft 26.2 never infers cleanup ownership from a missing flipFrame tail.
 */
@Mixin(RenderSystem.class)
public abstract class RenderSystemFrameCleanupMixin {
    @Inject(method = "flipFrame", at = @At("TAIL"), require = 0, remap = false)
    private static void galliumObserveFrameCleanup(CallbackInfo ci) {
        SuperResolutionCompat.onRenderSystemFrameCleanup();
    }
}
