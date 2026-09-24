package cn.spectra.gallium.glowoutline.mixin;

import cn.spectra.gallium.glowoutline.GlowOutlineConfig;
import cn.spectra.gallium.glowoutline.IrisCompat;
import cn.spectra.gallium.glowoutline.ItemEffectsManager;
import cn.spectra.gallium.glowoutline.SuperResolutionCompat;
import cn.spectra.gallium.glowoutline.capture.GlowCaptureManager;
import cn.spectra.gallium.glowoutline.shader.GlowComposite;
import cn.spectra.gallium.glowoutline.shader.GlowTime;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// SR's hudless/presentation injector uses priority 900 at the same final call point.  Keep the
// vanilla/default 1000 priority explicit: Gallium must paint the final target before SR captures
// it, while avoiding a global priority change relative to Iris's ordinary renderLevel hooks.
@Mixin(value = GameRenderer.class, priority = 1000)
public class GameRendererMixin {

    @Shadow @Final private Minecraft minecraft;

    @Inject(method = "render(FJZ)V", at = @At("HEAD"))
    private void galliumClientRenderFrameStart(
            float partialTick, long finishTimeNano, boolean advanceGameTime, CallbackInfo ci) {
        SuperResolutionCompat.beginClientRenderFrame();
    }

    @Inject(method = "renderLevel", at = @At("HEAD"))
    private void galliumGlowFrameStart(float partialTick, long finishTimeNano, PoseStack poseStack, CallbackInfo ci) {
        if (IrisCompat.isShadowPass()) return;
        SuperResolutionCompat.beginFrame();
        // No player==null guard here: beginFrame is pure cleanup (drains stale activeStates,
        // resets currentCapture, prunes pool above the high-water mark). Skipping it leaves
        // last frame's capturedThisFrame=true states alive, which the TAIL hook below would
        // happily re-composite against the new mainTarget — producing ghost glows.
        //
        // endFrame() recycles the capture-only RenderBuffers' StagedVertexBuffer GPU buffer
        // pools. This RenderBuffers is Gallium-owned (not vanilla's), so GameRenderer's own
        // renderBuffers.endFrame() never runs on it; without this per-frame call every
        // offscreen capture re-render allocates fresh vertex/index/staging buffers that are
        // never freed — OOMing the GPU (Vulkan) or the host commit charge (OpenGL) after a
        // few minutes. See GlowCaptureManager.endFrame().
        GlowCaptureManager.endFrame();
        GlowCaptureManager.beginFrame();
        GlowTime.advanceWorld(minecraft.getDeltaFrameTime());
    }

    @Inject(method = "renderItemInHand", at = @At("TAIL"), require = 0)
    private void galliumPrepareInlineSrHand(CallbackInfo ci) {
        SuperResolutionCompat.afterInlineHandRender();
    }


    /** Ordinary Gallium replay owned by the final hook when no SR frame consumed the capture. */
    private void galliumCompositeLegacyFinalTarget() {
        if (this.minecraft.level == null) return;
        if (!ItemEffectsManager.isActive()) return;
        if (!GlowOutlineConfig.isEnabled()) return;
        if (IrisCompat.isShadowPass()) return;

        RenderTarget mainTarget = minecraft.getMainRenderTarget();
        if (mainTarget == null || mainTarget.getColorTextureId() == -1) return;
        if (!GlowComposite.hasAnyValidCapture()) return;

        GlowComposite.composite(minecraft, mainTarget);
    }

    @Inject(method = "renderLevel", at = @At(value = "INVOKE",
            target = "Lcom/mojang/blaze3d/systems/RenderSystem;clear(IZ)V",
            ordinal = 0,
            shift = At.Shift.BEFORE,
            remap = false))
    private void galliumCaptureSceneDepth(float partialTick, long finishTimeNano, PoseStack poseStack, CallbackInfo ci) {
        if (IrisCompat.isShadowPass()) return;
        if (!GlowCaptureManager.needsSceneDepthCapture()) {
            SuperResolutionCompat.captureVanillaSceneDepth(null);
            return;
        }
        RenderTarget mainTarget = SuperResolutionCompat.worldDepthSource(
                minecraft.getMainRenderTarget());
        SuperResolutionCompat.captureVanillaSceneDepth(
                mainTarget != null && mainTarget.getDepthTextureId() != -1 ? mainTarget : null);
    }

    @Inject(method = "render(FJZ)V",
            at = @At(value = "INVOKE",
                    target = "Lcom/mojang/blaze3d/systems/RenderSystem;clear(IZ)V",
                    ordinal = 0,
                    shift = At.Shift.BEFORE,
                    remap = false),
            expect = 1)
    private void galliumGlowComposite(float partialTick, long finishTimeNano, boolean bl, CallbackInfo ci) {
        if (SuperResolutionCompat.compositeDisplayFrame()) return;
        if (this.minecraft.level == null) return;
        if (!ItemEffectsManager.isActive()) return;
        if (!GlowOutlineConfig.isEnabled()) return;
        if (IrisCompat.isShadowPass()) return;

        RenderTarget mainTarget = minecraft.getMainRenderTarget();
        if (mainTarget == null || mainTarget.getColorTextureId() == -1) return;
        if (!GlowComposite.hasAnyValidCapture()) return;

        GlowComposite.composite(minecraft, mainTarget);
    }
}
