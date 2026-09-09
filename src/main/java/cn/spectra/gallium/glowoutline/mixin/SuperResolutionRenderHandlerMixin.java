package cn.spectra.gallium.glowoutline.mixin;

import cn.spectra.gallium.glowoutline.SuperResolutionCompat;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Optional hooks into Super Resolution's target-replacement render handler.
 *
 * <p>The implemented marker only proves this exact handler class was transformed. Hack
 * configuration is detected independently, so a missing marker, renamed capture-mode enum, or
 * moved callback still reaches the deterministic render-call fallback. Each callback records a
 * per-frame hit, and every injector remains {@code require=0} for the optional dependency.</p>
 */
@Pseudo
@Mixin(targets = "io.homo.superresolution.common.minecraft.handler.MinecraftRenderHandler",
        remap = false)
public abstract class SuperResolutionRenderHandlerMixin
        implements SuperResolutionCompat.HookedHandler {

    @Inject(method = "onRenderWorldEnd", at = @At(value = "INVOKE",
            target = "Lio/homo/superresolution/common/perf/PerformanceTracker;push(Ljava/lang/String;)V",
            ordinal = 0), require = 0, remap = false)
    private void galliumPrepareMasksBeforeUpscale(CallbackInfo ci) {
        SuperResolutionCompat.beforeWorldUpscale(this);
    }

    @Inject(method = "onRenderWorldEnd", at = @At(value = "INVOKE",
            target = "Lio/homo/superresolution/common/perf/PerformanceTracker;pop(Ljava/lang/String;)V",
            shift = At.Shift.AFTER), require = 0, remap = false)
    private void galliumMarkUpscaleFinished(CallbackInfo ci) {
        SuperResolutionCompat.afterWorldUpscale();
    }

    @Inject(method = "onRenderHandEnd", at = @At(value = "INVOKE",
            target = "Lio/homo/superresolution/core/graphics/opengl/GlStates;pop(Ljava/lang/Object;)Lio/homo/superresolution/core/graphics/opengl/GlState;"),
            require = 0, remap = false)
    private void galliumPrepareSeparatedHandBeforeRestore(CallbackInfo ci) {
        SuperResolutionCompat.beforeSeparatedHandRestore(this);
    }
}
