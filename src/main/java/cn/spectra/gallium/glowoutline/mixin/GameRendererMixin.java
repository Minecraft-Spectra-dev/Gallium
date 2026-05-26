package cn.spectra.gallium.glowoutline.mixin;

import cn.spectra.gallium.glowoutline.GlowOutlineConfig;
import cn.spectra.gallium.glowoutline.IrisCompat;
import cn.spectra.gallium.glowoutline.ItemEffectsManager;
import cn.spectra.gallium.glowoutline.capture.GlowCaptureManager;
import cn.spectra.gallium.glowoutline.shader.GlowComposite;
import cn.spectra.gallium.glowoutline.shader.GlowTime;
import com.mojang.blaze3d.pipeline.RenderTarget;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GameRenderer.class)
public class GameRendererMixin {

    @Shadow @Final private Minecraft minecraft;

    @Inject(method = "renderLevel", at = @At("HEAD"))
    private void galliumGlowFrameStart(DeltaTracker deltaTracker, CallbackInfo ci) {
        if (IrisCompat.isShadowPass()) return;
        // No player==null guard here: beginFrame is pure cleanup (drains stale activeStates,
        // resets currentCapture, prunes pool above the high-water mark). Skipping it leaves
        // last frame's capturedThisFrame=true states alive, which the TAIL hook below would
        // happily re-composite against the new mainTarget — producing ghost glows.
        GlowCaptureManager.beginFrame();
        GlowTime.advanceWorld(deltaTracker.getGameTimeDeltaTicks());
    }

    @Inject(method = "renderLevel", at = @At(value = "INVOKE",
            target = "Lcom/mojang/blaze3d/systems/CommandEncoder;clearDepthTexture(Lcom/mojang/blaze3d/textures/GpuTexture;D)V",
            ordinal = 0,
            // CommandEncoder is unobfuscated (com.mojang.blaze3d.* is bytecode-named through the
            // mapping pipeline), so there's nothing to remap and Mixin AP's "Unable to locate
            // method mapping" warning would otherwise fire on loom-remap versions.
            remap = false),
            // Fail loudly if vanilla rearranges renderLevel and the first clearDepthTexture is
            // no longer the main-target depth clear we want to copy from. Without expect=1 a
            // future refactor could silently capture the wrong moment and produce stale glows.
            expect = 1)
    private void galliumCaptureSceneDepth(DeltaTracker deltaTracker, CallbackInfo ci) {
        if (IrisCompat.isShadowPass()) return;
        if (!ItemEffectsManager.isActive()) return;

        RenderTarget mainTarget = minecraft.getMainRenderTarget();
        if (mainTarget == null || mainTarget.getDepthTexture() == null) return;

        GlowCaptureManager.captureSceneDepth(mainTarget);
    }

    @Inject(method = "renderLevel", at = @At("TAIL"))
    private void galliumGlowComposite(DeltaTracker deltaTracker, CallbackInfo ci) {
        if (!ItemEffectsManager.isActive()) return;
        if (!GlowOutlineConfig.isEnabled()) return;
        if (IrisCompat.isShadowPass()) return;

        RenderTarget mainTarget = minecraft.getMainRenderTarget();
        if (mainTarget == null || mainTarget.getColorTexture() == null) return;
        if (!GlowComposite.hasAnyValidCapture()) return;

        GlowComposite.composite(minecraft, mainTarget);
    }
}
