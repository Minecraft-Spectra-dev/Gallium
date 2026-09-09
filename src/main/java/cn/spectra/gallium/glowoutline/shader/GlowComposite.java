package cn.spectra.gallium.glowoutline.shader;

import cn.spectra.gallium.glowoutline.IrisCompat;
import cn.spectra.gallium.glowoutline.capture.GlowCaptureManager;
import cn.spectra.gallium.glowoutline.capture.GlowCaptureState;
//#if MC>=1_21_05
import com.mojang.blaze3d.pipeline.RenderPipeline;
//#endif
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
//#if MC>=1_26_02
//$$ import com.mojang.blaze3d.GpuFormat;
//#endif
//#if MC>=1_21_05
import com.mojang.blaze3d.systems.RenderPass;
//#endif
//#if MC>=1_21_06
import com.mojang.blaze3d.systems.CommandEncoder;
//#endif
import com.mojang.blaze3d.systems.RenderSystem;
//#if MC<1_21_05
//$$ import com.mojang.blaze3d.vertex.BufferBuilder;
//$$ import com.mojang.blaze3d.vertex.BufferUploader;
//$$ import com.mojang.blaze3d.vertex.DefaultVertexFormat;
//$$ import com.mojang.blaze3d.vertex.Tesselator;
//$$ import com.mojang.blaze3d.vertex.VertexFormat;
//#if MC>=1_21_02
//$$ import net.minecraft.client.renderer.CompiledShaderProgram;
//#else
//$$ import com.mojang.blaze3d.vertex.VertexSorting;
//$$ import net.minecraft.client.renderer.ShaderInstance;
//#endif
//#endif
//#if MC>=1_21_05
import com.mojang.blaze3d.textures.AddressMode;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuTexture;
//#endif
//#if MC>=1_26_02
//$$ import java.util.Optional;
//#else
import java.util.OptionalInt;
//#endif
import net.minecraft.client.Minecraft;
import org.jspecify.annotations.Nullable;

/**
 * Owns the GPU resources used for the world-space glow composite pass.
 * Centralizing them here lets {@link GlowResources} dispose them on resource reload.
 */
public final class GlowComposite {

    @Nullable private static TextureTarget tempColorTarget;
    @Nullable private static GlowUniformBuffer uniformBuffer;
    //#if MC>=1_26_02
    //$$ /** One R32F mask-depth normalization scratch, reused in encoder order by every state. */
    //$$ @Nullable private static TextureTarget maskDepthForwardZScratch;
    //#endif

    //#if MC>=1_21_06
    /**
     * Per-composite cache for 26.2's normalized world scene depth. The fields also exist on
     * 1.21.6-26.1 so the modern draw path can keep one preprocessor-independent signature; those
     * versions never read them.
     */
    private static final class CompositeDepthContext {
        private @Nullable GpuTexture worldSceneSourceTexture;
        private com.mojang.blaze3d.textures.@Nullable GpuTextureView worldSceneForwardView;
        private boolean worldSceneFlipAttempted;

        private void reset() {
            this.worldSceneSourceTexture = null;
            this.worldSceneForwardView = null;
            this.worldSceneFlipAttempted = false;
        }
    }
    private static final CompositeDepthContext COMPOSITE_DEPTH_CONTEXT = new CompositeDepthContext();
    //#endif

    static {
        GlowResources.register(GlowComposite::dispose);
    }

    private GlowComposite() {}

    //#if MC>=1_21_05
    private static boolean textureSizeMatches(@Nullable GpuTexture texture, int w, int h) {
        return texture != null && w > 0 && h > 0
                && texture.getWidth(0) == w && texture.getHeight(0) == h;
    }

    private static boolean colorTargetSizeMatches(@Nullable TextureTarget target, int w, int h) {
        return target != null && target.width == w && target.height == h
                && textureSizeMatches(target.getColorTexture(), w, h);
    }

    private static boolean captureTargetSizeMatches(@Nullable TextureTarget target, int w, int h) {
        return colorTargetSizeMatches(target, w, h)
                && (!target.useDepth || textureSizeMatches(target.getDepthTexture(), w, h));
    }

    private static boolean hasAnyExactCapture(int w, int h) {
        for (GlowCaptureState state : GlowCaptureManager.getActiveStates()) {
            //#if MC==1_21_11 || MC==1_26_01
            if (GlowCaptureManager.sequentialCaptureMatches(state, w, h)) return true;
            //#endif
            if (state.capturedThisFrame && !state.compositedThisFrame && state.config != null
                    && captureTargetSizeMatches(state.maskTarget, w, h)) return true;
        }
        return false;
    }
    //#endif

    public static boolean hasAnyValidCapture() {
        for (GlowCaptureState state : GlowCaptureManager.getActiveStates()) {
            //#if MC==1_21_11 || MC==1_26_01
            if (GlowCaptureManager.sequentialPayloadReady(state)) return true;
            //#endif
            if (state.capturedThisFrame && !state.compositedThisFrame
                    && state.config != null && state.maskTarget != null) return true;
        }
        return false;
    }

    public static boolean hasAnyPreparedCapture() {
        for (GlowCaptureState state : GlowCaptureManager.getActiveStates()) {
            if (state.capturedThisFrame && state.maskPreparedThisFrame
                    && !state.compositedThisFrame && state.config != null
                    && state.maskTarget != null) return true;
        }
        return false;
    }

    //#if MC==1_21_11 || MC==1_26_01
    /** One pre-glow snapshot and encoder for a final-hook replay/composite sequence. */
    public static @Nullable LateCompositeFrame prepareLateCompositeFrame(
            Minecraft minecraft, RenderTarget target) {
        int w = target.width, h = target.height;
        if (!textureSizeMatches(target.getColorTexture(), w, h)) return null;
        if (!colorTargetSizeMatches(tempColorTarget, w, h)) {
            if (tempColorTarget != null) tempColorTarget.destroyBuffers();
            tempColorTarget = new TextureTarget("GlowColor", w, h, false);
        }
        if (!colorTargetSizeMatches(tempColorTarget, w, h)) return null;
        if (uniformBuffer == null) uniformBuffer = new GlowUniformBuffer("Glow Uniform Buffer");
        CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();
        encoder.copyTextureToTexture(target.getColorTexture(), tempColorTarget.getColorTexture(),
                0, 0, 0, 0, 0, w, h);
        return new LateCompositeFrame(minecraft, target, encoder);
    }

    public static final class LateCompositeFrame implements AutoCloseable {
        private final Minecraft minecraft;
        private final RenderTarget target;
        private final GpuTexture outputColor;
        private final GpuTexture diffuseColor;
        private final int width, height;
        private final CommandEncoder encoder;
        private final CompositeDepthContext depths = new CompositeDepthContext();
        private boolean closed;

        private LateCompositeFrame(Minecraft minecraft, RenderTarget target, CommandEncoder encoder) {
            this.minecraft = minecraft;
            this.target = target;
            this.encoder = encoder;
            this.outputColor = target.getColorTexture();
            this.diffuseColor = tempColorTarget.getColorTexture();
            this.width = target.width;
            this.height = target.height;
        }

        public boolean valid() {
            return !closed && minecraft.getMainRenderTarget() == target
                    && target.width == width && target.height == height
                    && target.getColorTexture() == outputColor
                    && !outputColor.isClosed() && !diffuseColor.isClosed()
                    && textureSizeMatches(outputColor, width, height)
                    && colorTargetSizeMatches(tempColorTarget, width, height)
                    && tempColorTarget.getColorTexture() == diffuseColor;
        }

        public boolean canComposite(GlowCaptureState state, TextureTarget mask) {
            return valid() && state.config != null && GlowPipeline.get(state.config.shader()) != null
                    && captureTargetSizeMatches(mask, width, height);
        }

        /** This entry never invokes the ordinary implicit geometry replay. */
        public boolean compositePreparedState(GlowCaptureState state, TextureTarget mask) {
            if (!canComposite(state, mask) || !state.maskPreparedThisFrame || state.compositedThisFrame
                    || state.captureStage() != cn.spectra.gallium.glowoutline.sr.streaming
                    .SrStreamingCoordinator.CaptureStage.REPLAY_ATTEMPTED) return false;
            drawGlow(state, minecraft, target, encoder, depths, mask);
            return state.compositedThisFrame && state.markStreamingComposited();
        }

        /** Ordinary sequence has its own consumption guard, not a synthetic SR ReplayPlan. */
        public boolean compositeOrdinaryState(GlowCaptureState state, TextureTarget mask) {
            if (!canComposite(state, mask) || !state.maskPreparedThisFrame || state.compositedThisFrame
                    || !state.hasPayloadReplayAttempted() || state.streamingReplayPlan() != null) return false;
            drawGlow(state, minecraft, target, encoder, depths, mask);
            return state.compositedThisFrame;
        }

        @Override
        public void close() {
            closed = true;
            depths.reset();
        }
    }
    //#endif

    public static void composite(Minecraft minecraft, RenderTarget mainTarget) {
        // A late frame has an explicit per-state sequence; TAIL/fallback cannot consume it again.
        if (cn.spectra.gallium.glowoutline.SuperResolutionCompat.ownsLateReplayFrame()) return;
        //#if MC==1_21_11 || MC==1_26_01
        if (GlowCaptureManager.ownsSequentialSharedMaskFrame()) {
            compositeSequentialSharedFrame(minecraft, mainTarget);
            return;
        }
        //#endif
        //#if MC>=1_21_06
        int w = mainTarget.width;
        int h = mainTarget.height;
        GpuTexture mainColor = mainTarget.getColorTexture();
        if (!textureSizeMatches(mainColor, w, h) || !hasAnyExactCapture(w, h)) return;

        if (!colorTargetSizeMatches(tempColorTarget, w, h)) {
            if (tempColorTarget != null) tempColorTarget.destroyBuffers();
            tempColorTarget = new TextureTarget("GlowColor", w, h, false
                    //#if MC>=1_26_02
                    //$$ , GpuFormat.RGBA8_UNORM
                    //#endif
            );
            //#if MC<1_21_11
            //$$ // See GlowCaptureManager: 1.21.10 sampler completeness needs useMipmaps=false on
            //$$ // single-mip render targets. 1.21.11+ moved this off GpuTexture entirely.
            //$$ tempColorTarget.getColorTexture().setUseMipmaps(false);
            //#endif
        }
        if (uniformBuffer == null) uniformBuffer = new GlowUniformBuffer("Glow Uniform Buffer");

        //#if MC>=1_26_02
        //$$ // All native reverse-Z states share one R32F scratch. Every state mask is already
        //$$ // required to match mainTarget, so allocate/resize exactly once before recording
        //$$ // flip A -> draw A -> flip B -> draw B into the shared encoder.
        //$$ if (!IrisCompat.usesForwardDepthCompatibility()
        //$$         && DepthFlipPipeline.isReady()) {
        //$$     try {
        //$$         maskDepthForwardZScratch = DepthFlipPipeline.ensureForwardZTarget(
        //$$                 maskDepthForwardZScratch, "GlowMaskDepthForwardZ", w, h);
        //$$     } catch (RuntimeException ignored) {
        //$$         return;
        //$$     }
        //$$     if (!colorTargetSizeMatches(maskDepthForwardZScratch, w, h)) return;
        //$$ }
        //#endif

        // Snapshot the pre-glow image for DiffuseSampler. HD-screenshot capture boundaries can
        // expose stale RenderTarget metadata/attachments; require both real textures to match
        // the logical frame exactly. On a skew, skip this transition frame instead of crashing
        // or smearing a cropped snapshot across the final image.
        GpuTexture tempColor = tempColorTarget.getColorTexture();
        if (!textureSizeMatches(mainColor, w, h) || !textureSizeMatches(tempColor, w, h)) return;

        // Keep the pre-glow color snapshot, every depth normalization pass, every UBO write, and
        // every composite draw in one command stream. This both cuts encoder churn and makes the
        // data hazards explicit on deferred backends.
        CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();
        encoder.copyTextureToTexture(mainColor, tempColor,
                0, 0, 0, 0, 0, w, h);
        CompositeDepthContext depthContext = COMPOSITE_DEPTH_CONTEXT;
        depthContext.reset();
        try {
            for (GlowCaptureState state : GlowCaptureManager.getActiveStates()) {
                drawGlow(state, minecraft, mainTarget, encoder, depthContext, state.maskTarget);
            }
        } finally {
            // Do not retain a live frame texture through the static scratch context.
            depthContext.reset();
        }
        //#elseif MC>=1_21_05
        //$$ // 1.21.5: GpuTexture-based path — no GpuTextureView / SamplerHelper / GlowUniformBuffer.
        //$$ int w = mainTarget.width;
        //$$ int h = mainTarget.height;
        //$$ GpuTexture mainColor = mainTarget.getColorTexture();
        //$$ if (!textureSizeMatches(mainColor, w, h) || !hasAnyExactCapture(w, h)) return;
        //$$
        //$$ if (!colorTargetSizeMatches(tempColorTarget, w, h)) {
        //$$     if (tempColorTarget != null) tempColorTarget.destroyBuffers();
        //$$     tempColorTarget = new TextureTarget("GlowColor", w, h, false);
        //$$ }
        //$$
        //$$ // [issue #1] Require exact source/destination extents for the pre-glow snapshot.
        //$$ GpuTexture tempColor = tempColorTarget.getColorTexture();
        //$$ if (textureSizeMatches(mainColor, w, h) && textureSizeMatches(tempColor, w, h)) {
        //$$     RenderSystem.getDevice().createCommandEncoder().copyTextureToTexture(
        //$$             mainColor, tempColor,
        //$$             0, 0, 0, 0, 0, w, h);
        //$$ } else {
        //$$     return;
        //$$ }
        //$$
        //$$ for (GlowCaptureState state : GlowCaptureManager.getActiveStates()) {
        //$$     drawGlow(state, minecraft, mainTarget);
        //$$ }
        //#else
        //$$ int w = mainTarget.width;
        //$$ int h = mainTarget.height;
        //$$ if (tempColorTarget == null || tempColorTarget.width != w || tempColorTarget.height != h) {
        //$$     if (tempColorTarget != null) tempColorTarget.destroyBuffers();
//#if MC>=1_21_02
        //$$     tempColorTarget = new TextureTarget(w, h, false);
//#else
        //$$     tempColorTarget = new TextureTarget(w, h, false, net.minecraft.client.Minecraft.ON_OSX);
//#endif
        //$$ }
        //$$ com.mojang.blaze3d.platform.GlStateManager._glBindFramebuffer(36008, mainTarget.frameBufferId);
        //$$ com.mojang.blaze3d.platform.GlStateManager._glBindFramebuffer(36009, tempColorTarget.frameBufferId);
        //$$ com.mojang.blaze3d.platform.GlStateManager._glBlitFrameBuffer(0, 0, w, h, 0, 0, w, h, 16384, 9728);
        //$$ com.mojang.blaze3d.platform.GlStateManager._glBindFramebuffer(36160, 0);
        //$$ try {
        //$$     for (GlowCaptureState state : GlowCaptureManager.getActiveStates()) {
        //$$         drawGlow(state, minecraft, mainTarget);
        //$$     }
        //$$ } finally {
        //$$     // Unconditionally restore mainTarget binding before returning to vanilla.
        //$$     // The blit above ends with FB=0 (default framebuffer); each drawGlow that
        //$$     // *succeeds* leaves mainTarget bound, but a drawGlow that returns early
        //$$     // (e.g. program failed to compile, or capturedThisFrame was false) does
        //$$     // not. Without this finally the next vanilla call after composite —
        //$$     // GameRenderer.render's RenderSystem.clear(256) — would clear the default
        //$$     // framebuffer instead of mainTarget, leaving stale world depth on
        //$$     // mainTarget and corrupting the GUI pass that follows.
        //$$     mainTarget.bindWrite(true);
        //$$ }
        //#endif
    }

    //#if MC==1_21_11 || MC==1_26_01
    /** Same ordinary depth/transform/draw path, with one borrowed native mask per state. */
    private static void compositeSequentialSharedFrame(Minecraft minecraft, RenderTarget output) {
        if (!GlowCaptureManager.consumeSequentialFrame()) return;
        boolean complete = false;
        try {
            if (output == null || !GlowCaptureManager.sequentialFrameCurrent()) return;
            var states = GlowCaptureManager.getActiveStates().stream()
                    .filter(state -> state.capturedThisFrame && !state.compositedThisFrame).toList();
            if (states.isEmpty()) {
                complete = true;
                return;
            }
            // Preflight before allocating or replaying anything. Never skip a failed state and
            // accidentally treat its shared texture contents as those of the next state.
            for (var state : states) {
                if (!GlowCaptureManager.sequentialPayloadReady(state)
                        || GlowPipeline.get(state.config.shader()) == null) return;
            }
            long sceneGeneration = GlowCaptureManager.getSceneDepthGeneration();
            try (var masks = GlowCaptureManager.prepareSequentialMaskFrame();
                 var composite = prepareLateCompositeFrame(minecraft, output)) {
                if (masks == null || composite == null) return;
                for (var state : states) {
                    if (!GlowCaptureManager.sequentialFrameCurrent() || !composite.valid()
                            || sceneGeneration != GlowCaptureManager.getSceneDepthGeneration()
                            || !masks.beginOrdinaryState(state)
                            || !GlowCaptureManager.replaySequentialMask(state, minecraft, masks.targetFor(state))
                            || !GlowCaptureManager.sequentialFrameCurrent()
                            || !composite.compositeOrdinaryState(state, masks.targetFor(state))
                            || !masks.finishOrdinaryState(state)) return;
                }
                complete = true;
            }
        } finally {
            if (!complete) GlowCaptureManager.abortSequentialFrame();
        }
    }
    //#endif

    //#if MC>=1_21_06
    private static void drawGlow(GlowCaptureState state, Minecraft minecraft, RenderTarget mainTarget,
                                 CommandEncoder encoder, CompositeDepthContext depthContext, TextureTarget mask) {
        if (!state.capturedThisFrame || state.compositedThisFrame
                || state.config == null || mask == null) return;

        int w = mainTarget.width;
        int h = mainTarget.height;
        // The composite-level gate only proves that at least one capture matches. A resize can
        // occur between SR world and hand preparation, so reject each stale prepared state here
        // instead of stretching it alongside a different state that matches the new output.
        if (!captureTargetSizeMatches(mask, w, h)) {
            state.invalidateCapture();
            return;
        }

        if (!state.maskPreparedThisFrame) {
            GlowCaptureManager.renderCapturedNodes(state, minecraft);
        }
        if (!state.capturedThisFrame) return;

        if (mask.getColorTextureView() == null || mask.getDepthTextureView() == null) {
            state.invalidateCapture();
            return;
        }

        RenderPipeline pipeline = GlowPipeline.get(state.config.shader());
        if (pipeline == null) return;

        float maskScaleX = state.lastMaskScaleX;
        float maskScaleY = state.lastMaskScaleY;
        float sceneScaleX = state.lastSceneScaleX;
        float sceneScaleY = state.lastSceneScaleY;
        // Sign of sceneScaleY carries exact/fallback state without expanding the UBO contract.
        float encodedSceneScaleY = state.exactDepthAlignment ? sceneScaleY : -sceneScaleY;
        float maskOffsetX = state.lastMaskOffsetX;
        float sceneOffsetX = state.lastSceneOffsetX;
        float maskOffsetY = state.lastMaskOffsetY;
        float sceneOffsetY = state.lastSceneOffsetY;

        com.mojang.blaze3d.textures.GpuTextureView sceneDepthView = selectSceneDepthView(
                state, mask, mainTarget, minecraft);
        if (sceneDepthView == null) return;
        // The fragment shader maps all three depth/color inputs to one integer texel. Linear
        // filtering here would blend neighboring item silhouettes before that mapping and create
        // a one-pixel halo/penetration at internal-resolution boundaries.
        FilterMode maskFilter = FilterMode.NEAREST;

        //#if MC>=1_26_02
        //$$ // Pack-author shaders consume forward-Z (0=near, 1=far). Native 26.2 renders in
        //$$ // reverse-Z and therefore needs DepthFlipPipeline. Iris 1.11.x, however, installs
        //$$ // UndoReverseZ mixins on the OpenGL backend while a shader pack is active: it
        //$$ // restores a forward-Z projection, reverses compare ops, and transforms clear
        //$$ // values. Those mixins key
        //$$ // off Iris.isPackInUseQuick(), not ImmediateState.bypass, so the capture replay is
        //$$ // forward-Z too. Iris depth must be bound raw; applying 1-depth again is the bug.
        //$$ com.mojang.blaze3d.textures.GpuTextureView sceneDepthViewToBind;
        //$$ com.mojang.blaze3d.textures.GpuTextureView maskDepthViewToBind;
        //$$ boolean irisForwardDepth = IrisCompat.usesForwardDepthCompatibility();
        //$$ if (irisForwardDepth) {
        //$$     // MAX-pooling exists only to make the replay depth test stable. Feeding the pooled
        //$$     // values to the pack shader's isOtherItem() test would suppress valid outlines, so
        //$$     // expose the unpooled scene view for both comparisons. The replayed mask color has
        //$$     // already applied LEQUAL and remains the source of occlusion truth.
        //$$     maskDepthViewToBind = mask.getDepthTextureView();
        //$$     sceneDepthViewToBind = state.firstPerson ? maskDepthViewToBind : sceneDepthView;
        //$$ } else {
        //$$     // Native reverse-Z must never reach a forward-Z pack shader. Every allocation
        //$$     // and flip is therefore fail-closed instead of binding raw depth as a fallback.
        //$$     if (!cn.spectra.gallium.glowoutline.shader.DepthFlipPipeline.isReady()) {
        //$$         state.invalidateCapture();
        //$$         return;
        //$$     }
        //$$     GpuTexture rawSceneTexture = state.firstPerson ? null : sceneDepthView.texture();
        //$$     if (!state.firstPerson && (rawSceneTexture == null
        //$$             || (depthContext.worldSceneFlipAttempted
        //$$             && (depthContext.worldSceneSourceTexture != rawSceneTexture
        //$$             || depthContext.worldSceneForwardView == null)))) {
        //$$         // Reject a mixed-source transition before spending this state's mask flip.
        //$$         state.invalidateCapture();
        //$$         return;
        //$$     }
        //$$     try {
        //$$         TextureTarget maskDepthForward = maskDepthForwardZScratch;
        //$$         if (maskDepthForward == null
        //$$                 || !colorTargetSizeMatches(maskDepthForward, mask.width, mask.height)
        //$$                 || maskDepthForward.getColorTextureView() == null) {
        //$$             state.invalidateCapture();
        //$$             return;
        //$$         }
        //$$         if (!cn.spectra.gallium.glowoutline.shader.DepthFlipPipeline.flip(
        //$$                 encoder, mask.getDepthTextureView(), maskDepthForward)) {
        //$$             state.invalidateCapture();
        //$$             return;
        //$$         }
        //$$         maskDepthViewToBind = maskDepthForward.getColorTextureView();
        //$$
        //$$         if (state.firstPerson) {
        //$$             sceneDepthViewToBind = maskDepthViewToBind;
        //$$         } else {
        //$$             if (depthContext.worldSceneFlipAttempted) {
        //$$                 // Identity and readiness were checked before the mask flip above.
        //$$                 sceneDepthViewToBind = depthContext.worldSceneForwardView;
        //$$             } else {
        //$$                 depthContext.worldSceneFlipAttempted = true;
        //$$                 depthContext.worldSceneSourceTexture = rawSceneTexture;
        //$$                 TextureTarget liveScene = cn.spectra.gallium.glowoutline.capture
        //$$                         .GlowCaptureManager.ensureLiveSceneDepthForwardZTarget(
        //$$                                 mainTarget.width, mainTarget.height);
        //$$                 if (liveScene == null || liveScene.getColorTextureView() == null
        //$$                         || !cn.spectra.gallium.glowoutline.shader.DepthFlipPipeline.flip(
        //$$                         encoder, sceneDepthView, liveScene)) {
        //$$                     state.invalidateCapture();
        //$$                     return;
        //$$                 }
        //$$                 depthContext.worldSceneForwardView = liveScene.getColorTextureView();
        //$$                 sceneDepthViewToBind = depthContext.worldSceneForwardView;
        //$$             }
        //$$         }
        //$$     } catch (RuntimeException ignored) {
        //$$         state.invalidateCapture();
        //$$         return;
        //$$     }
        //$$ }
        //$$ if (!depthNormalizationComplete(
        //$$         maskDepthViewToBind != null,
        //$$         state.firstPerson, sceneDepthViewToBind != null)) {
        //$$     state.invalidateCapture();
        //$$     return;
        //$$ }
        //#endif

        // Use ONE CommandEncoder for both the UBO write and the RenderPass so the
        // write is guaranteed to complete before the shader reads GlowUniforms,
        // even on deferred-backend drivers that reorder independent encoders.
        // Without this, the next drawGlow iteration can overwrite the UBO before
        // the current RenderPass reads it — two items with different effects whose
        // outlines overlap in screen space would each sample the other item's params.
        uniformBuffer.writeToEncoder(encoder, GlowTime.worldSecondsFloat(), w, h,
                maskScaleX, sceneScaleX, maskScaleY, encodedSceneScaleY,
                maskOffsetX, sceneOffsetX, maskOffsetY, sceneOffsetY, state.config);

        try (RenderPass pass = encoder.createRenderPass(() -> "Glow", mainTarget.getColorTextureView(),
                //#if MC>=1_26_02
                //$$ Optional.empty()
                //#else
                OptionalInt.empty()
                //#endif
        )) {
            pass.setPipeline(pipeline);
            pass.setUniform("GlowUniforms", uniformBuffer.getSlice());
            SamplerHelper.bindClampToEdge(pass, "DiffuseSampler",
                    tempColorTarget.getColorTextureView(), FilterMode.LINEAR);
            SamplerHelper.bindClampToEdge(pass, "MaskSampler",
                    mask.getColorTextureView(), maskFilter);
            //#if MC>=1_26_02
            //$$ // Both branches above expose forward-Z: raw depth under Iris, normalized R32F
            //$$ // color views under native 26.2. From GLSL both remain sampler2D `.r` reads.
            //$$ SamplerHelper.bindClampToEdge(pass, "MaskDepthSampler",
            //$$         maskDepthViewToBind, FilterMode.NEAREST);
            //$$ SamplerHelper.bindClampToEdge(pass, "SceneDepthSampler",
            //$$         sceneDepthViewToBind, FilterMode.NEAREST);
            //#else
            SamplerHelper.bindClampToEdge(pass, "MaskDepthSampler",
                    mask.getDepthTextureView(), FilterMode.NEAREST);
            SamplerHelper.bindClampToEdge(pass, "SceneDepthSampler",
                    sceneDepthView, FilterMode.NEAREST);
            //#endif
            //#if MC<1_21_09
            //$$ pass.setVertexBuffer(0, RenderSystem.getQuadVertexBuffer());
            //#endif
            //#if MC>=1_26_02
            //$$ pass.draw(3, 1, 0, 0);
            //#else
            pass.draw(0, 3);
            //#endif
        }
        state.compositedThisFrame = true;
    }
    //#elseif MC>=1_21_05
    //$$ // 1.21.5: GpuTexture path — no GpuTextureView / SamplerHelper / GlowUniformBuffer.
    //$$ private static void drawGlow(GlowCaptureState state, Minecraft minecraft, RenderTarget mainTarget) {
    //$$     if (!state.capturedThisFrame || state.compositedThisFrame
    //$$             || state.config == null || state.maskTarget == null) return;
    //$$
    //$$     TextureTarget mask = state.maskTarget;
    //$$     int w = mainTarget.width;
    //$$     int h = mainTarget.height;
    //$$     if (!captureTargetSizeMatches(mask, w, h)) {
    //$$         state.invalidateCapture();
    //$$         return;
    //$$     }
    //$$
    //$$     if (!state.maskPreparedThisFrame) GlowCaptureManager.renderCapturedNodes(state, minecraft);
    //$$     if (!state.capturedThisFrame) return;
    //$$
    //$$     if (mask.getColorTexture() == null || mask.getDepthTexture() == null) {
    //$$         state.invalidateCapture();
    //$$         return;
    //$$     }
    //$$
    //$$     RenderPipeline pipeline = GlowPipeline.get(state.config);
    //$$     if (pipeline == null) return;
    //$$
    //$$     float maskScaleX = state.lastMaskScaleX;
    //$$     float maskScaleY = state.lastMaskScaleY;
    //$$     float sceneScaleX = state.lastSceneScaleX;
    //$$     float sceneScaleY = state.exactDepthAlignment
    //$$             ? state.lastSceneScaleY : -state.lastSceneScaleY;
    //$$     float maskOffsetX = state.lastMaskOffsetX;
    //$$     float sceneOffsetX = state.lastSceneOffsetX;
    //$$     float maskOffsetY = state.lastMaskOffsetY;
    //$$     float sceneOffsetY = state.lastSceneOffsetY;
    //$$
    //$$     // Set up sampler state directly on GpuTexture (1.21.5 API)
    //$$     var diffTex = tempColorTarget.getColorTexture();
    //$$     diffTex.setTextureFilter(FilterMode.LINEAR, false);
    //$$     diffTex.setAddressMode(AddressMode.CLAMP_TO_EDGE, AddressMode.CLAMP_TO_EDGE);
    //$$
    //$$     var maskTex = mask.getColorTexture();
    //$$     maskTex.setTextureFilter(FilterMode.NEAREST, false);
    //$$     maskTex.setAddressMode(AddressMode.CLAMP_TO_EDGE, AddressMode.CLAMP_TO_EDGE);
    //$$
    //$$     var maskDepthTex = mask.getDepthTexture();
    //$$     maskDepthTex.setTextureFilter(FilterMode.NEAREST, false);
    //$$     maskDepthTex.setAddressMode(AddressMode.CLAMP_TO_EDGE, AddressMode.CLAMP_TO_EDGE);
    //$$
    //$$     GpuTexture sceneDepthTex;
    //$$     if (state.firstPerson) {
    //$$         sceneDepthTex = mask.getDepthTexture();
    //$$     } else if (state.superResolutionPrepared) {
    //$$         TextureTarget foreground = GlowCaptureManager.getForegroundDepthTarget();
    //$$         sceneDepthTex = switch (chooseSuperResolutionSceneDepth(
    //$$                 false, IrisCompat.isShaderActive(), false,
    //$$                 foreground != null, minecraft.options.getCameraType().isFirstPerson())) {
    //$$             case MASK, DISPLAY_SCENE -> mask.getDepthTexture();
    //$$             case FOREGROUND -> foreground.getDepthTexture();
    //$$             case NONE -> null;
    //$$         };
    //$$         if (sceneDepthTex == null) return;
    //$$     } else if (usesLiveMainDepth(state.firstPerson,
    //$$             minecraft.options.getCameraType().isFirstPerson(), IrisCompat.isShaderActive())) {
    //$$         // With no Iris in first person, vanilla clears world depth and then writes the
    //$$         // hand. The live attachment is the only source that can clip a world item's
    //$$         // expanded outline at the hand silhouette.
    //$$         sceneDepthTex = mainTarget.getDepthTexture();
    //$$     } else {
    //$$         // In third person the same clear is not followed by a hand pass, so live depth is
    //$$         // all far and would let outlines pass through the world. The pre-clear snapshot
    //$$         // keeps completed world depth; under Iris it also contains the custom hand rendered
    //$$         // inside LevelRenderer before vanilla's later hand draw is suppressed.
    //$$         TextureTarget sd = GlowCaptureManager.getSceneDepthTarget();
    //$$         sceneDepthTex = sd != null ? sd.getDepthTexture() : mainTarget.getDepthTexture();
    //$$     }
    //$$     sceneDepthTex.setTextureFilter(FilterMode.NEAREST, false);
    //$$     sceneDepthTex.setAddressMode(AddressMode.CLAMP_TO_EDGE, AddressMode.CLAMP_TO_EDGE);
    //$$
    //$$     try (RenderPass pass = RenderSystem.getDevice()
    //$$             .createCommandEncoder()
    //$$             .createRenderPass(mainTarget.getColorTexture(), OptionalInt.empty())) {
    //$$         pass.setPipeline(pipeline);
    //$$         pass.bindSampler("DiffuseSampler", diffTex);
    //$$         pass.bindSampler("MaskSampler", maskTex);
    //$$         pass.bindSampler("MaskDepthSampler", maskDepthTex);
    //$$         pass.bindSampler("SceneDepthSampler", sceneDepthTex);
    //$$         // 1.21.5: individual uniforms instead of UBO
    //$$         pass.setUniform("FrameTimeCounter", GlowTime.worldSecondsFloat());
    //$$         pass.setUniform("ScreenSize", (float) w, (float) h);
    //$$         pass.setUniform("ShaderAlign",
    //$$                 maskScaleX, sceneScaleX, maskScaleY, sceneScaleY);
    //$$         pass.setUniform("ShaderOffset",
    //$$                 maskOffsetX, sceneOffsetX, maskOffsetY, sceneOffsetY);
    //$$         for (cn.spectra.gallium.glowoutline.ShaderParam p : state.config.params()) {
    //$$             switch (p) {
    //$$                 case cn.spectra.gallium.glowoutline.ShaderParam.Float f2 ->
    //$$                     pass.setUniform(f2.name(), f2.value());
    //$$                 case cn.spectra.gallium.glowoutline.ShaderParam.Vec2 v2 ->
    //$$                     pass.setUniform(v2.name(), v2.x(), v2.y());
    //$$                 case cn.spectra.gallium.glowoutline.ShaderParam.Vec3 v3 ->
    //$$                     pass.setUniform(v3.name(), v3.x(), v3.y(), v3.z());
    //$$                 case cn.spectra.gallium.glowoutline.ShaderParam.Vec4 v4 ->
    //$$                     pass.setUniform(v4.name(), v4.x(), v4.y(), v4.z(), v4.w());
    //$$             }
    //$$         }
    //$$         pass.setVertexBuffer(0, RenderSystem.getQuadVertexBuffer());
    //$$         pass.draw(0, 3);
    //$$     }
    //$$     state.compositedThisFrame = true;
    //$$ }
    //#else
    //$$ // Pre-1.21.5 legacy GL composite. Covers 1.21.2–1.21.4 (CompiledShaderProgram path) and
    //$$ // 1.21.1 (ShaderInstance path) — the two diverge only at the program type, the sampler
    //$$ // bind method name, the setShader overload, and the projection-type enum. Each difference
    //$$ // is a small nested //#if MC>=1_21_02 gate; the surrounding GL state setup, texture filter
    //$$ // params, sampler routing, and screen-quad geometry are identical across the two.
    //$$ private static void drawGlow(GlowCaptureState state, Minecraft minecraft, RenderTarget mainTarget) {
    //$$     if (!state.capturedThisFrame || state.compositedThisFrame
    //$$             || state.config == null || state.maskTarget == null) return;
    //$$     TextureTarget mask = state.maskTarget;
    //$$     int w = mainTarget.width;
    //$$     int h = mainTarget.height;
    //$$     if (mask.width != w || mask.height != h) {
    //$$         state.invalidateCapture();
    //$$         return;
    //$$     }
    //$$     if (!state.maskPreparedThisFrame) GlowCaptureManager.renderCapturedNodes(state, minecraft);
    //$$     if (!state.capturedThisFrame) return;
    //#if MC>=1_21_02
    //$$     CompiledShaderProgram program = GlowPipeline.getOrCreate(state.config);
    //#else
    //$$     ShaderInstance program = GlowPipeline.getOrCreate(state.config);
    //#endif
    //$$     if (program == null || tempColorTarget == null) return;
    //$$
    //$$     // SceneDepthSampler choice mirrors selectSceneDepthView() on 1.21.6+:
    //$$     //   firstPerson    → mask.depth (self-compare; outline never occluded by world)
    //$$     //   Iris active    → sceneDepthTarget (Iris renders both hand phases inside
    //$$     //                    LevelRenderer before our pre-clear snapshot, then suppresses
    //$$     //                    vanilla's later hand draw; the snapshot contains world + hand)
    //$$     //   no Iris + first person → mainTarget.depth at composite time. The renderLevel
    //$$     //                    hand-stage clear is followed by the held-item pass, so this
    //$$     //                    captures "what's in front" including the player's own hand.
    //$$     //   no Iris + third person → sceneDepthTarget. The same clear is not followed by
    //$$     //                    a hand pass in third person, so mainTarget.depth is otherwise
    //$$     //                    all far and lets every outline pass through world geometry.
    //$$     int sceneDepth;
    //$$     if (state.firstPerson) {
    //$$         sceneDepth = mask.getDepthTextureId();
    //$$     } else if (state.superResolutionPrepared) {
    //$$         TextureTarget foreground = GlowCaptureManager.getForegroundDepthTarget();
    //$$         sceneDepth = switch (chooseSuperResolutionSceneDepth(
    //$$                 false, IrisCompat.isShaderActive(), false,
    //$$                 foreground != null, minecraft.options.getCameraType().isFirstPerson())) {
    //$$             case MASK, DISPLAY_SCENE -> mask.getDepthTextureId();
    //$$             case FOREGROUND -> foreground.getDepthTextureId();
    //$$             case NONE -> -1;
    //$$         };
    //$$         if (sceneDepth == -1) return;
    //$$     } else if (usesLiveMainDepth(state.firstPerson,
    //$$             minecraft.options.getCameraType().isFirstPerson(), IrisCompat.isShaderActive())) {
    //$$         sceneDepth = mainTarget.getDepthTextureId();
    //$$     } else if (IrisCompat.isShaderActive()
    //$$             && GlowCaptureManager.getSceneDepthTarget() != null) {
    //$$         sceneDepth = GlowCaptureManager.getSceneDepthTarget().getDepthTextureId();
    //$$     } else {
    //$$         TextureTarget scene = GlowCaptureManager.getSceneDepthTarget();
    //$$         sceneDepth = scene != null ? scene.getDepthTextureId() : mainTarget.getDepthTextureId();
    //$$     }
    //$$
    //$$     setTextureLinear(tempColorTarget.getColorTextureId());
    //$$     setTextureNearest(mask.getColorTextureId());
    //$$     setTextureNearest(mask.getDepthTextureId());
    //$$     setTextureNearest(sceneDepth);
    //$$
    //#if MC>=1_21_02
    //$$     program.bindSampler("DiffuseSampler", tempColorTarget.getColorTextureId());
    //$$     program.bindSampler("MaskSampler", mask.getColorTextureId());
    //$$     program.bindSampler("MaskDepthSampler", mask.getDepthTextureId());
    //$$     program.bindSampler("SceneDepthSampler", sceneDepth);
    //#else
    //$$     program.setSampler("DiffuseSampler", tempColorTarget.getColorTextureId());
    //$$     program.setSampler("MaskSampler", mask.getColorTextureId());
    //$$     program.setSampler("MaskDepthSampler", mask.getDepthTextureId());
    //$$     program.setSampler("SceneDepthSampler", sceneDepth);
    //#endif
    //$$     program.safeGetUniform("FrameTimeCounter").set(GlowTime.worldSecondsFloat());
    //$$     program.safeGetUniform("ScreenSize").set((float) w, (float) h);
    //$$     float maskScaleX = state.lastMaskScaleX;
    //$$     float maskScaleY = state.lastMaskScaleY;
    //$$     float sceneScaleX = state.lastSceneScaleX;
    //$$     float sceneScaleY = state.exactDepthAlignment
    //$$             ? state.lastSceneScaleY : -state.lastSceneScaleY;
    //$$     float maskOffsetX = state.lastMaskOffsetX;
    //$$     float sceneOffsetX = state.lastSceneOffsetX;
    //$$     float maskOffsetY = state.lastMaskOffsetY;
    //$$     float sceneOffsetY = state.lastSceneOffsetY;
    //$$     program.safeGetUniform("ShaderAlign").set(
    //$$             maskScaleX, sceneScaleX, maskScaleY, sceneScaleY);
    //$$     program.safeGetUniform("ShaderOffset").set(
    //$$             maskOffsetX, sceneOffsetX, maskOffsetY, sceneOffsetY);
    //$$     for (cn.spectra.gallium.glowoutline.ShaderParam p : state.config.params()) {
    //$$         switch (p) {
    //$$             case cn.spectra.gallium.glowoutline.ShaderParam.Float f -> program.safeGetUniform(f.name()).set(f.value());
    //$$             case cn.spectra.gallium.glowoutline.ShaderParam.Vec2 v -> program.safeGetUniform(v.name()).set(v.x(), v.y());
    //$$             case cn.spectra.gallium.glowoutline.ShaderParam.Vec3 v -> program.safeGetUniform(v.name()).set(v.x(), v.y(), v.z());
    //$$             case cn.spectra.gallium.glowoutline.ShaderParam.Vec4 v -> program.safeGetUniform(v.name()).set(v.x(), v.y(), v.z(), v.w());
    //$$         }
    //$$     }
    //$$
    //$$     mainTarget.bindWrite(true);
    //$$     RenderSystem.disableDepthTest();
    //$$     RenderSystem.depthMask(false);
    //$$     RenderSystem.disableCull();
    //$$     RenderSystem.enableBlend();
    //$$     RenderSystem.blendFuncSeparate(1, 1, 1, 0);
    //#if MC>=1_21_02
    //$$     RenderSystem.setShader(program);
    //#else
    //$$     final ShaderInstance shaderRef = program;
    //$$     RenderSystem.setShader(() -> shaderRef);
    //#endif
    //$$     RenderSystem.backupProjectionMatrix();
    //$$     // Wide ortho z range so z=500 is comfortably inside the clip volume regardless of
    //$$     // JOML setOrtho convention quirks. Near=-1000, far=3000 gives view-z range that
    //$$     // straddles 0; vertex z=500 maps to roughly NDC.z=-0.75, well inside [-1, 1].
    //#if MC>=1_21_02
    //$$     RenderSystem.setProjectionMatrix(new org.joml.Matrix4f().setOrtho(0.0f, (float) w, (float) h, 0.0f, -1000.0f, 3000.0f), com.mojang.blaze3d.ProjectionType.ORTHOGRAPHIC);
    //#else
    //$$     RenderSystem.setProjectionMatrix(new org.joml.Matrix4f().setOrtho(0.0f, (float) w, (float) h, 0.0f, -1000.0f, 3000.0f), VertexSorting.ORTHOGRAPHIC_Z);
    //#endif
    //$$     RenderSystem.getModelViewStack().pushMatrix();
    //$$     RenderSystem.getModelViewStack().identity();
    //$$     try {
    //$$         BufferBuilder bb = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.BLIT_SCREEN);
    //$$         bb.addVertex(0.0f, 0.0f, 500.0f);
    //$$         bb.addVertex((float) w, 0.0f, 500.0f);
    //$$         bb.addVertex((float) w, (float) h, 500.0f);
    //$$         bb.addVertex(0.0f, (float) h, 500.0f);
    //$$         BufferUploader.drawWithShader(bb.buildOrThrow());
    //$$     } finally {
    //$$         RenderSystem.getModelViewStack().popMatrix();
    //$$         RenderSystem.restoreProjectionMatrix();
    //$$         RenderSystem.defaultBlendFunc();
    //$$         RenderSystem.disableBlend();
    //$$         RenderSystem.enableCull();
    //$$         RenderSystem.depthMask(true);
    //$$         RenderSystem.enableDepthTest();
    //$$     }
    //$$     state.compositedThisFrame = true;
    //$$ }
    //$$ private static void setTextureLinear(int id) { setTextureParams(id, 9729); }
    //$$ private static void setTextureNearest(int id) { setTextureParams(id, 9728); }
    //$$ private static void setTextureParams(int id, int filter) {
    //$$     RenderSystem.bindTexture(id);
    //$$     com.mojang.blaze3d.platform.GlStateManager._texParameter(3553, 10241, filter);
    //$$     com.mojang.blaze3d.platform.GlStateManager._texParameter(3553, 10240, filter);
    //$$     com.mojang.blaze3d.platform.GlStateManager._texParameter(3553, 10242, 33071);
    //$$     com.mojang.blaze3d.platform.GlStateManager._texParameter(3553, 10243, 33071);
    //$$ }
    //#endif

    //#if MC>=1_21_06
    private static com.mojang.blaze3d.textures.GpuTextureView selectSceneDepthView(
            GlowCaptureState state, TextureTarget mask, RenderTarget mainTarget, Minecraft minecraft) {
        // First-person uses mask self-compare: hud3d projection captured at the hand pass
        // doesn't match the level/entity projection used for sceneDepthTarget, so depth
        // comparison would be meaningless. Self-compare = no world occlusion in first-
        // person, but first-person doesn't show the player's own body anyway.
        if (state.firstPerson) return mask.getDepthTextureView();
        if (state.superResolutionPrepared) {
            TextureTarget displayScene =
                    GlowCaptureManager.getSuperResolutionDisplaySceneDepthTarget();
            TextureTarget foreground = GlowCaptureManager.getForegroundDepthTarget();
            return switch (chooseSuperResolutionSceneDepth(
                    false, IrisCompat.isShaderActive(), displayScene != null,
                    foreground != null, minecraft.options.getCameraType().isFirstPerson())) {
                case MASK -> mask.getDepthTextureView();
                case DISPLAY_SCENE -> displayScene.getDepthTextureView();
                case FOREGROUND -> foreground.getDepthTextureView();
                case NONE -> null;
            };
        }
        if (usesLiveMainDepth(state.firstPerson,
                minecraft.options.getCameraType().isFirstPerson(), IrisCompat.isShaderActive())) {
            // Vanilla clears mainTarget.depth before the first-person hand pass. In first person
            // the hand writes depth again, so this live view is the only source that includes the
            // hand when it occludes a glowing world item. In third person the same clear still
            // happens, but no hand pass follows it; use the early world snapshot below instead.
            return mainTarget.getDepthTextureView();
        }
        // Iris's custom hand is already present in this pre-clear snapshot. Its redirected
        // vanilla hand pass writes nothing after the clear, so post-clear main depth is not a
        // useful fallback while a shader pack is active.
        TextureTarget sceneDepth = GlowCaptureManager.getSceneDepthTarget();
        return sceneDepth != null ? sceneDepth.getDepthTextureView() : mainTarget.getDepthTextureView();
    }
    //#endif

    /**
     * Returns whether the post-clear main depth still represents a useful occluder for a
     * world-space mask. Vanilla repopulates the depth attachment during the first-person hand
     * pass, but performs the same clear in third person without drawing a hand afterward. Iris
     * renders its hand before the snapshot and suppresses that later vanilla pass, so it must use
     * the captured scene instead of the live attachment.
     */
    static boolean usesLiveMainDepth(boolean stateFirstPerson, boolean cameraFirstPerson,
                                     boolean shaderActive) {
        return !stateFirstPerson && cameraFirstPerson && !shaderActive;
    }

    /** Pure fail-closed contract for 26.2 depth normalization. */
    static boolean depthNormalizationComplete(
            boolean maskDepthReady, boolean firstPerson, boolean sceneDepthReady) {
        if (!maskDepthReady) return false;
        return firstPerson || sceneDepthReady;
    }

    enum SuperResolutionSceneDepth {
        MASK, DISPLAY_SCENE, FOREGROUND, NONE
    }

    /** Chooses one coordinate-coherent display-space depth source for a prepared SR mask. */
    static SuperResolutionSceneDepth chooseSuperResolutionSceneDepth(
            boolean stateFirstPerson, boolean irisActive,
            boolean displaySceneAvailable, boolean foregroundAvailable,
            boolean cameraFirstPerson) {
        if (stateFirstPerson) return SuperResolutionSceneDepth.MASK;
        // Iris renders both hand phases before Gallium's pre-upscale snapshot. Prefer that
        // combined world+hand image over Mode-B's restored origin depth, which is not populated.
        if (irisActive) {
            return displaySceneAvailable
                    ? SuperResolutionSceneDepth.DISPLAY_SCENE
                    : SuperResolutionSceneDepth.MASK;
        }
        if (foregroundAvailable) return SuperResolutionSceneDepth.FOREGROUND;
        return cameraFirstPerson ? SuperResolutionSceneDepth.NONE
                : SuperResolutionSceneDepth.MASK;
    }

    private static void dispose() {
        //#if MC>=1_26_02
        //$$ if (maskDepthForwardZScratch != null) {
        //$$     maskDepthForwardZScratch.destroyBuffers();
        //$$     maskDepthForwardZScratch = null;
        //$$ }
        //#endif
        if (tempColorTarget != null) {
            tempColorTarget.destroyBuffers();
            tempColorTarget = null;
        }
        if (uniformBuffer != null) {
            uniformBuffer.close();
            uniformBuffer = null;
        }
    }
}
