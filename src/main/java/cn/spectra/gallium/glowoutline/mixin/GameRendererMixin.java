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

    //#if MC>=1_21_06
    // 1.21.6+: Iris's MixinGameRenderer.iris$runColorSpace also injects at renderLevel TAIL,
    // but on these versions the composite ordering has been observed to work correctly without
    // intervention. Keep the simple TAIL hook here.
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
    //#else
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
    //$$                 // CommandEncoder is unobfuscated (com.mojang.blaze3d.* lives outside
    //$$                 // Mojang mappings), so suppress the Mixin AP "Unable to locate method
    //$$                 // mapping" warning the same way galliumCaptureSceneDepth does.
    //$$                 remap = false),
    //$$         expect = 1)
    //$$ private void galliumGlowComposite(DeltaTracker deltaTracker, boolean bl, CallbackInfo ci) {
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
    //#endif
}
