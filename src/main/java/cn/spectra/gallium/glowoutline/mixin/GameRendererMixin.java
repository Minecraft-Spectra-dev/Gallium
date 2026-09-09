package cn.spectra.gallium.glowoutline.mixin;

import cn.spectra.gallium.glowoutline.GlowOutlineConfig;
import cn.spectra.gallium.glowoutline.IrisCompat;
import cn.spectra.gallium.glowoutline.ItemEffectsManager;
import cn.spectra.gallium.glowoutline.SuperResolutionCompat;
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

// SR's hudless/presentation injector uses priority 900 at the same final call point.  Keep the
// vanilla/default 1000 priority explicit: Gallium must paint the final target before SR captures
// it, while avoiding a global priority change relative to Iris's ordinary renderLevel hooks.
@Mixin(value = GameRenderer.class, priority = 1000)
public class GameRendererMixin {

    @Shadow @Final private Minecraft minecraft;

    @Inject(method = "render(Lnet/minecraft/client/DeltaTracker;Z)V", at = @At("HEAD"))
    private void galliumClientRenderFrameStart(
            DeltaTracker deltaTracker, boolean advanceGameTime, CallbackInfo ci) {
        SuperResolutionCompat.beginClientRenderFrame();
    }

    @Inject(method = "renderLevel", at = @At("HEAD"))
    private void galliumGlowFrameStart(DeltaTracker deltaTracker, CallbackInfo ci) {
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
        GlowTime.advanceWorld(deltaTracker.getGameTimeDeltaTicks());
    }

    @Inject(method = "renderItemInHand", at = @At("TAIL"), require = 0)
    private void galliumPrepareInlineSrHand(CallbackInfo ci) {
        SuperResolutionCompat.afterInlineHandRender();
    }

    //#if MC>=1_21_11
    /**
     * One final call point shared by 1.21.11, 26.1 and 26.2. It is after renderLevel, Iris color
     * conversion, vanilla entity outlines and post effects, but immediately before SR's priority
     * 900 hudless/presentation capture at FogRenderer.endFrame. Since this mixin has priority
     * 1000, Gallium's callback is injected ahead of SR's callback at the same instruction.
     */
    @Inject(method = "render(Lnet/minecraft/client/DeltaTracker;Z)V",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/fog/FogRenderer;endFrame()V",
                    shift = At.Shift.BEFORE),
            require = 0)
    private void galliumCompositeSrDisplayFrame(DeltaTracker deltaTracker, boolean advanceGameTime,
                                                CallbackInfo ci) {
        if (SuperResolutionCompat.compositeDisplayFrame()) return;
        galliumCompositeLegacyFinalTarget();
    }
    //#endif

    /** Ordinary Gallium replay owned by the final hook when no SR frame consumed the capture. */
    private void galliumCompositeLegacyFinalTarget() {
        if (this.minecraft.level == null) return;
        if (!ItemEffectsManager.isActive()) return;
        if (!GlowOutlineConfig.isEnabled()) return;
        if (IrisCompat.isShadowPass()) return;

        //#if MC>=1_26_02
        //$$ RenderTarget mainTarget = minecraft.gameRenderer.mainRenderTarget();
        //#else
        RenderTarget mainTarget = minecraft.getMainRenderTarget();
        //#endif
        //#if MC>=1_21_05
        if (mainTarget == null || mainTarget.getColorTexture() == null) return;
        //#else
        //$$ if (mainTarget == null || mainTarget.getColorTextureId() == -1) return;
        //#endif
        if (!GlowComposite.hasAnyValidCapture()) return;

        GlowComposite.composite(minecraft, mainTarget);
    }

    //#if MC>=1_21_05
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
        if (!GlowCaptureManager.needsSceneDepthCapture()) {
            SuperResolutionCompat.captureVanillaSceneDepth(null);
            return;
        }

        //#if MC>=1_26_02
        //$$ RenderTarget mainTarget = minecraft.gameRenderer.mainRenderTarget();
        //#else
        RenderTarget mainTarget = minecraft.getMainRenderTarget();
        //#endif
        mainTarget = SuperResolutionCompat.worldDepthSource(mainTarget);
        SuperResolutionCompat.captureVanillaSceneDepth(
                mainTarget != null && mainTarget.getDepthTexture() != null ? mainTarget : null);
    }
    //#else
    //$$ @Inject(method = "renderLevel", at = @At(value = "INVOKE",
    //#if MC>=1_21_02
    //$$         target = "Lcom/mojang/blaze3d/systems/RenderSystem;clear(I)V",
    //#else
    //$$         target = "Lcom/mojang/blaze3d/systems/RenderSystem;clear(IZ)V",
    //#endif
    //$$         ordinal = 0,
    //$$         shift = At.Shift.BEFORE,
    //$$         remap = false))
    //$$ private void galliumCaptureSceneDepth(DeltaTracker deltaTracker, CallbackInfo ci) {
    //$$     if (IrisCompat.isShadowPass()) return;
    //$$     if (!GlowCaptureManager.needsSceneDepthCapture()) {
    //$$         SuperResolutionCompat.captureVanillaSceneDepth(null);
    //$$         return;
    //$$     }
    //$$     RenderTarget mainTarget = SuperResolutionCompat.worldDepthSource(
    //$$             minecraft.getMainRenderTarget());
    //$$     SuperResolutionCompat.captureVanillaSceneDepth(
    //$$             mainTarget != null && mainTarget.getDepthTextureId() != -1 ? mainTarget : null);
    //$$ }
    //#endif

    //#if MC>=1_21_06
    // 1.21.6+: Iris's MixinGameRenderer.iris$runColorSpace also injects at renderLevel TAIL,
    // but on these versions the composite ordering has been observed to work correctly without
    // intervention. Keep the simple TAIL hook here.
    @Inject(method = "renderLevel", at = @At("TAIL"))
    private void galliumGlowComposite(DeltaTracker deltaTracker, CallbackInfo ci) {
        // Mainline builds defer from frame one so the final hook owns both SR and no-SR replay.
        // If that optional injection point ever stops matching, the next frame's HEAD watchdog
        // logs once and re-enables this legacy path permanently.
        if (SuperResolutionCompat.shouldDeferLegacyCompositeAtTail()) return;
        if (!ItemEffectsManager.isActive()) return;
        if (!GlowOutlineConfig.isEnabled()) return;
        if (IrisCompat.isShadowPass()) return;

        //#if MC>=1_26_02
        //$$ RenderTarget mainTarget = minecraft.gameRenderer.mainRenderTarget();
        //#else
        RenderTarget mainTarget = minecraft.getMainRenderTarget();
        //#endif
        if (mainTarget == null || mainTarget.getColorTexture() == null) return;
        if (!GlowComposite.hasAnyValidCapture()) return;

        GlowComposite.composite(minecraft, mainTarget);
    }
    //#elseif MC>=1_21_05
    //$$ // 1.21.5: three different things ALL fight for the same renderLevel TAIL slot or run
    //$$ // shortly after, any of which can clobber a composite painted onto mainTarget too early:
    //$$ //   1. Iris's MixinGameRenderer.iris$runColorSpace injects at renderLevel TAIL too;
    //$$ //      relative ordering with our hook is decided by mixin apply order, not mixin
    //$$ //      priority — and Iris's finalize writes the shader pack's final image into
    //$$ //      mainTarget. If Iris runs after us, our outline is gone.
    //$$ //   2. After renderLevel returns, GameRenderer.render calls levelRenderer.doEntityOutline
    //$$ //      which blits the (separate) entity-outline target onto mainTarget. Mostly empty
    //$$ //      blit when no entity is glowing, but still touches the surface.
    //$$ //   3. If postEffectId is active (spectator-camera entity invert/sobel/etc.),
    //$$ //      postChain.process(mainTarget, ...) is called AFTER doEntityOutline; it reads
    //$$ //      mainTarget, applies the post-shader, and writes it back — destroying our outline.
    //$$ //
    //$$ // Fix: target the FIRST clearDepthTexture call inside GameRenderer.render itself
    //$$ // (1.21.5: line 483, immediately AFTER the `if (level != null)` block closes — i.e.
    //$$ // past renderLevel-TAIL, past doEntityOutline, past PostChain.process, BEFORE GUI/HUD
    //$$ // setup). ordinal=0 picks that exact site (line 500 is the second clearDepthTexture
    //$$ // and runs after GUI, which is too late). expect=1 fails the build loudly if vanilla
    //$$ // refactors render() and the first clearDepthTexture moves elsewhere; without expect
    //$$ // a refactor would silently disable our composite.
    //$$ //
    //$$ // Note: line 483 sits inside the outer `if (!noRender)` block but OUTSIDE the
    //$$ // `if (level != null)` block — i.e. it always runs when render produces output. We
    //$$ // explicitly guard on level == null inside the inject method because composite has
    //$$ // nothing to do without a world.
    //$$ @Inject(method = "render(Lnet/minecraft/client/DeltaTracker;Z)V",
    //$$         at = @At(value = "INVOKE",
    //$$                 target = "Lcom/mojang/blaze3d/systems/CommandEncoder;clearDepthTexture(Lcom/mojang/blaze3d/textures/GpuTexture;D)V",
    //$$                 ordinal = 0,
    //$$                 // remap=false: same blaze3d-unobfuscated rationale as galliumCaptureSceneDepth above.
    //$$                 remap = false),
    //$$         expect = 1)
    //$$ private void galliumGlowComposite(DeltaTracker deltaTracker, boolean bl, CallbackInfo ci) {
    //$$     if (SuperResolutionCompat.compositeDisplayFrame()) return;
    //$$     if (this.minecraft.level == null) return;
    //$$     if (!ItemEffectsManager.isActive()) return;
    //$$     if (!GlowOutlineConfig.isEnabled()) return;
    //$$     if (IrisCompat.isShadowPass()) return;
    //$$
    //$$     RenderTarget mainTarget = minecraft.getMainRenderTarget();
    //$$     if (mainTarget == null || mainTarget.getColorTexture() == null) return;
    //$$     if (!GlowComposite.hasAnyValidCapture()) return;
    //$$
    //$$     GlowComposite.composite(minecraft, mainTarget);
    //$$ }
    //$$
    //$$ // GUI glow on 1.21.5 is composited per-item in GuiGraphicsItemMixin (atlas-tile
    //$$ // approach matching 1.21.6+'s alpha-ring sampling). No frame-end hook needed —
    //$$ // each item draws its outline immediately after its vanilla render, so subsequent
    //$$ // GUI elements (tooltips, scoreboard, overlays) layer on top naturally.
    //#else
    //$$ @Inject(method = "render(Lnet/minecraft/client/DeltaTracker;Z)V",
    //$$         at = @At(value = "INVOKE",
    //#if MC>=1_21_02
    //$$                 target = "Lcom/mojang/blaze3d/systems/RenderSystem;clear(I)V",
    //#else
    //$$                 target = "Lcom/mojang/blaze3d/systems/RenderSystem;clear(IZ)V",
    //#endif
    //$$                 ordinal = 0,
    //$$                 shift = At.Shift.BEFORE,
    //$$                 remap = false),
    //$$         expect = 1)
    //$$ private void galliumGlowComposite(DeltaTracker deltaTracker, boolean bl, CallbackInfo ci) {
    //$$     if (SuperResolutionCompat.compositeDisplayFrame()) return;
    //$$     if (this.minecraft.level == null) return;
    //$$     if (!ItemEffectsManager.isActive()) return;
    //$$     if (!GlowOutlineConfig.isEnabled()) return;
    //$$     if (IrisCompat.isShadowPass()) return;
    //$$
    //$$     RenderTarget mainTarget = minecraft.getMainRenderTarget();
    //$$     if (mainTarget == null || mainTarget.getColorTextureId() == -1) return;
    //$$     if (!GlowComposite.hasAnyValidCapture()) return;
    //$$
    //$$     GlowComposite.composite(minecraft, mainTarget);
    //$$ }
    //#endif
}
