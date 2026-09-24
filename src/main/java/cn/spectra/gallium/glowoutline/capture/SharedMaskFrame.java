package cn.spectra.gallium.glowoutline.capture;

import cn.spectra.gallium.glowoutline.IrisCompat;
import cn.spectra.gallium.glowoutline.SuperResolutionCompat;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
//#if MC>=1_21_05
import com.mojang.blaze3d.textures.GpuTexture;
//#endif
//#if MC>=1_26_02
//$$ import com.mojang.blaze3d.GpuFormat;
//#endif
import net.minecraft.client.Minecraft;

/** One native mask owner for every supported version, with mutually exclusive frame leases. */
public final class SharedMaskFrame {
    private static final SequentialMaskPolicy policy = new SequentialMaskPolicy();
    private static final MaskRenderContext<TextureTarget> masks = new MaskRenderContext<>(
            SharedMaskFrame::allocate, SharedMaskFrame::destroy);
    private static Evidence frame;

    private record Evidence(long epoch, RenderTarget output,
            //#if MC>=1_21_05
            GpuTexture color, GpuTexture depth,
            //#else
            //$$ int color, int depth,
            //#endif
            int width, int height, boolean iris, boolean sr, OpenGlMaskOrdering.Stamp backend) {
        boolean current() {
            return epoch == SuperResolutionCompat.currentFrameEpoch() && backend.current()
                    && currentMainTarget() == output && targetMatches(output, width, height)
                    //#if MC>=1_21_05
                    && output.getColorTexture() == color && output.getDepthTexture() == depth
                    //#else
                    //$$ && output.getColorTextureId() == color && output.getDepthTextureId() == depth
                    //#endif
                    && IrisCompat.isShaderActive() == iris && IrisCompat.isActiveSrRuntime() == sr
                    && !SuperResolutionCompat.isHackConfigured();
        }
    }

    private SharedMaskFrame() {}

    public static RenderTarget currentMainTarget() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null) return null;
        //#if MC>=1_26_02
        //$$ return minecraft.gameRenderer.mainRenderTarget();
        //#else
        return minecraft.getMainRenderTarget();
        //#endif
    }

    public static boolean targetMatches(RenderTarget target, int width, int height) {
        //#if MC>=1_21_05
        return GlowCaptureManager.renderTargetSizeMatches(target, width, height)
                && target.getDepthTexture() != null && !target.getColorTexture().isClosed()
                && !target.getDepthTexture().isClosed();
        //#else
        //$$ return target != null && width > 0 && height > 0 && target.width == width && target.height == height
        //$$         && target.getColorTextureId() != -1 && target.getDepthTextureId() != -1;
        //#endif
    }

    private static TextureTarget allocate(int width, int height) {
        if (!GlowCaptureManager.sharedMaskFitsBudget(width, height)) return null;
        //#if MC>=1_21_05
        TextureTarget target = new TextureTarget("GlowSharedNativeMask", width, height, true
                //#if MC>=1_26_02
                //$$ , GpuFormat.RGBA8_UNORM
                //#endif
        );
        //#if MC>=1_21_06 && MC<1_21_11
        //$$ target.getColorTexture().setUseMipmaps(false);
        //$$ target.getDepthTexture().setUseMipmaps(false);
        //#endif
        return target;
        //#else
        //$$ try (var binding = new LegacyFramebufferBinding()) {
            //#if MC>=1_21_02
            //$$ return new TextureTarget(width, height, true);
            //#else
            //$$ return new TextureTarget(width, height, true, Minecraft.ON_OSX);
            //#endif
        //$$ }
        //#endif
    }

    private static void destroy(TextureTarget target) {
        //#if MC<1_21_05
        //$$ try (var binding = new LegacyFramebufferBinding()) { target.destroyBuffers(); }
        //#else
        target.destroyBuffers();
        //#endif
    }

    public static void beginFrame() {
        RenderTarget output = currentMainTarget();
        OpenGlMaskOrdering.Stamp backend = SuperResolutionCompat.sequentialFinalHookAvailable()
                && !SuperResolutionCompat.isHackConfigured() && output != null
                && targetMatches(output, output.width, output.height) ? OpenGlMaskOrdering.observe() : null;
        policy.beginFrame(SuperResolutionCompat.currentFrameEpoch(), backend != null);
        frame = selected() && backend != null ? new Evidence(SuperResolutionCompat.currentFrameEpoch(), output,
                //#if MC>=1_21_05
                output.getColorTexture(), output.getDepthTexture(),
                //#else
                //$$ output.getColorTextureId(), output.getDepthTextureId(),
                //#endif
                output.width, output.height, IrisCompat.isShaderActive(), IrisCompat.isActiveSrRuntime(), backend) : null;
        if (SuperResolutionCompat.ownsSharedMaskFrame()) {
            GlowCaptureManager.releaseAllPerStateTargets();
            var plan = SuperResolutionCompat.currentStreamingFramePlan();
            var srBackend = SuperResolutionCompat.sharedMaskBackend();
            masks.beginFrame(plan.epoch(), plan.expectedDisplayWidth(), plan.expectedDisplayHeight(),
                    srBackend, srBackend::current);
        } else if (frame != null) {
            // Change ownership before accepting captures, including cold entries in the pool.
            GlowCaptureManager.releaseAllPerStateTargets();
            masks.beginFrame(frame.epoch(), frame.width(), frame.height(), backend, backend::current);
        } else masks.close();
    }

    public static boolean selected() { return policy.selected(); }
    public static boolean consume() { return policy.consume(SuperResolutionCompat.currentFrameEpoch()); }
    public static boolean current() {
        return selected() && frame != null && frame.current()
                && SuperResolutionCompat.sequentialFinalHookCurrent()
                && (!frame.sr() || SuperResolutionCompat.hasCompletedShaderCompatDispatch(frame.width(), frame.height()));
    }
    public static boolean payloadReady(GlowCaptureState state) {
        return selected() && frame != null && state.guiEntity == null && state.maskTarget == null
                && state.canBeginOrdinaryReplay(frame.epoch()) && GlowCaptureManager.replayPayloadAvailable(state)
                && state.capturedProjectionMatrix4fValid && state.capturedProjectionType != null
                && state.capturedModelViewMatrixValid && state.capturedModelViewMatrix != null;
    }
    public static boolean captureMatches(GlowCaptureState state, int width, int height) {
        return payloadReady(state) && frame.width() == width && frame.height() == height;
    }
    public static boolean maskMatches(TextureTarget mask) {
        return frame != null && targetMatches(mask, frame.width(), frame.height());
    }
    public static MaskRenderContext<TextureTarget>.Frame prepareOrdinary() {
        return current() ? masks.prepareOrdinaryFrame() : null;
    }
    public static MaskRenderContext<TextureTarget>.Frame prepareStreaming() { return masks.prepareFrame(); }
    public static void abortStreaming() { masks.close(); }
    public static void abortOrdinary() {
        policy.fail(SuperResolutionCompat.currentFrameEpoch());
        try { GlowCaptureManager.abortPendingPayloads(); } finally { masks.close(); }
    }
    public static void dispose() { masks.close(); policy.reset(); frame = null; }
}
