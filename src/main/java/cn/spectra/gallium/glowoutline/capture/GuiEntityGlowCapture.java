package cn.spectra.gallium.glowoutline.capture;

import cn.spectra.gallium.Gallium;
import cn.spectra.gallium.glowoutline.ItemEffectConfig;
import cn.spectra.gallium.glowoutline.shader.GlowComposite;
import cn.spectra.gallium.glowoutline.shader.GlowResources;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.systems.RenderSystem;
//#if MC>=1_21_05
import com.mojang.blaze3d.textures.GpuTexture;
//#endif
//#if MC>=1_21_09
import net.minecraft.client.renderer.RenderBuffers;
//#endif
import net.minecraft.client.Minecraft;
import org.jspecify.annotations.Nullable;
import java.util.ArrayList;
import java.util.List;

/** A preview owns its captures and depth. It never joins the world/SR frame lifecycle. */
public final class GuiEntityGlowCapture implements AutoCloseable {
    private static final int MAX_ITEMS = 64;
    private static final long MAX_MASK_BYTES = 128L * 1024 * 1024;
    private static final List<GuiEntityGlowCapture> POOL = new ArrayList<>();
    private static @Nullable GuiEntityGlowCapture current;
    private static int nesting;
    private static long nextEpoch;
    static { GlowResources.register(GuiEntityGlowCapture::disposeAll); }

    private final List<GlowCaptureState> states = new ArrayList<>();
    private final GlowComposite.PreviewResources compositeResources = new GlowComposite.PreviewResources();
    private @Nullable GuiEntityGlowCapture parent;
    private int width, height, count;
    private float scale;
    private long epoch;
    private boolean completed, closed, failureLogged, blitGlow;
    private @Nullable TextureTarget target;
    //#if MC>=1_26_02
    //$$ private @Nullable TextureTarget forwardSceneDepth;
    //$$ public TextureTarget forwardSceneDepth() {
    //$$     forwardSceneDepth = cn.spectra.gallium.glowoutline.shader.DepthFlipPipeline.ensureForwardZTarget(
    //$$             forwardSceneDepth, "Gallium Preview Forward Depth", width, height);
    //$$     return forwardSceneDepth;
    //$$ }
    //#endif
    //#if MC>=1_21_09
    private @Nullable RenderBuffers buffers;
    //#endif

    //#if MC<1_21_06
    //$$ private CaptureSites.@Nullable DelayingMultiBufferSource body;
    //$$ public CaptureSites.DelayingMultiBufferSource legacyBody() {
    //$$     if (body == null) body = new CaptureSites.DelayingMultiBufferSource();
    //$$     return body;
    //$$ }
    //#endif

    private GuiEntityGlowCapture() {}

    public static GuiEntityGlowCapture begin(int width, int height, float scale) {
        if (nesting == POOL.size()) POOL.add(new GuiEntityGlowCapture());
        GuiEntityGlowCapture scope = POOL.get(nesting++);
        scope.parent = current;
        scope.width = width;
        scope.height = height;
        scope.scale = scale;
        scope.epoch = ++nextEpoch;
        scope.count = 0;
        scope.completed = false;
        scope.blitGlow = false;
        scope.closed = false;
        current = scope;
        return scope;
    }

    public static @Nullable GuiEntityGlowCapture current() { return current; }

    @Nullable GlowCaptureState acquire(ItemEffectConfig config) {
        if (completed || closed || width <= 0 || height <= 0 || count >= MAX_ITEMS
                || (long) width * height > MAX_MASK_BYTES / 8L / (count + 1)) return null;
        GlowCaptureState state;
        if (count == states.size()) {
            state = new GlowCaptureState();
            states.add(state);
        } else {
            state = states.get(count);
        }
        state.resetFrame();
        state.beginCaptureLifecycle(epoch, false);
        state.guiEntity = this;
        state.config = config;
        state.active = true;
        state.itemDistance = 0.0f;
        if (scale > 0 && Float.isFinite(scale)) state.itemWorldToUv.set(scale / width, scale / height);
        count++;
        return state;
    }

    //#if MC>=1_21_09
    RenderBuffers buffers() {
        if (buffers == null) buffers = new RenderBuffers(1);
        return buffers;
    }
    //#endif

    public TextureTarget target() {
        target = resizeTarget(target, width, height, "Gallium Entity Preview");
        return target;
    }

    static TextureTarget resizeTarget(@Nullable TextureTarget target, int width, int height, String label) {
        if (target != null && (target.width != width || target.height != height)) {
            target.destroyBuffers();
            target = null;
        }
        if (target == null) {
            //#if MC>=1_21_05
            target = new TextureTarget(label, width, height, true
                    //#if MC>=1_26_02
                    //$$ , com.mojang.blaze3d.GpuFormat.RGBA8_UNORM
                    //#endif
            );
            //#if MC>=1_21_06 && MC<1_21_11
            //$$ target.getColorTexture().setUseMipmaps(false);
            //$$ target.getDepthTexture().setUseMipmaps(false);
            //#elseif MC==1_21_05
            //$$ target.getColorTexture().setTextureFilter(com.mojang.blaze3d.textures.FilterMode.NEAREST, false);
            //$$ target.getDepthTexture().setTextureFilter(com.mojang.blaze3d.textures.FilterMode.NEAREST, false);
            //#endif
            //#elseif MC>=1_21_02
            //$$ target = new TextureTarget(width, height, true);
            //#else
            //$$ target = new TextureTarget(width, height, true, Minecraft.ON_OSX);
            //#endif
        }
        return target;
    }

    public void complete() {
        if (completed || closed) return;
        completed = true;
        if (count == 0 || target == null) return;
        Minecraft mc = Minecraft.getInstance();
        for (int i = 0; i < count; i++) {
            GlowCaptureState state = states.get(i);
            if (!state.capturedThisFrame) continue;
            try {
                state.maskTarget = resizeTarget(state.maskTarget, width, height, "Gallium Preview Mask");
                GlowCaptureManager.renderGuiEntityMask(state, mc, target);
                if (!state.maskPreparedThisFrame) state.invalidateCapture();
            } catch (RuntimeException failure) {
                // A dispatcher may retain a partially prepared frame/batch. Retire this
                // preview's owners together; none of them belong to the world renderer.
                for (GlowCaptureState captured : states) GlowCaptureManager.releaseGuiEntityState(captured);
                //#if MC>=1_21_09
                GlowCaptureManager.releasePreviewBuffers(buffers);
                buffers = null;
                //#endif
                reportFailure("Unable to render entity preview glow mask", failure);
                return;
            }
        }
        GlowComposite.compositePreview(mc, target, states.subList(0, count), compositeResources);
    }

    //#if MC>=1_21_06
    /** Runs after vanilla flushed the entire entity, including all its non-glowing occluders. */
    public void completePip(GpuTexture color, GpuTexture depth) {
        if (completed || count == 0 || color == null || depth == null) return;
        if (color.getWidth(0) != width || color.getHeight(0) != height
                || depth.getWidth(0) != width || depth.getHeight(0) != height) return;
        try {
            TextureTarget output = target();
            var encoder = RenderSystem.getDevice().createCommandEncoder();
            encoder.copyTextureToTexture(color, output.getColorTexture(), 0, 0, 0, 0, 0, width, height);
            encoder.copyTextureToTexture(depth, output.getDepthTexture(), 0, 0, 0, 0, 0, width, height);
            complete();
            RenderSystem.getDevice().createCommandEncoder().copyTextureToTexture(
                    output.getColorTexture(), color, 0, 0, 0, 0, 0, width, height);
            blitGlow = states.subList(0, count).stream().anyMatch(state -> state.compositedThisFrame);
        } catch (RuntimeException failure) {
            // The original PIP texture is untouched until the successful final copy.
            reportFailure("Unable to composite entity preview glow", failure);
        }
    }
    //#endif

    private void reportFailure(String message, RuntimeException failure) {
        if (!failureLogged) {
            failureLogged = true;
            Gallium.LOGGER.warn(message, failure);
        }
    }

    public boolean hasGlowForBlit() { return blitGlow; }

    public @Nullable TextureTarget scene() { return target; }

    @Override
    public void close() {
        if (closed) return;
        if (current != this) throw new IllegalStateException("Unbalanced entity preview scope");
        current = parent;
        parent = null;
        nesting--;
        closed = true;
        for (GlowCaptureState state : states) state.resetFrame();
        //#if MC<1_21_06
        //$$ if (body != null) body.endFrame();
        //#endif
        //#if MC>=1_26_02
        //$$ if (buffers != null) buffers.endFrame();
        //#endif
    }

    private static void disposeAll() {
        for (GuiEntityGlowCapture scope : POOL) {
            for (GlowCaptureState state : scope.states) GlowCaptureManager.releaseGuiEntityState(state);
            scope.states.clear();
            scope.compositeResources.close();
            //#if MC<1_21_06
            //$$ if (scope.body != null) scope.body.free();
            //$$ scope.body = null;
            //#endif
            if (scope.target != null) scope.target.destroyBuffers();
            scope.target = null;
            //#if MC>=1_26_02
            //$$ if (scope.forwardSceneDepth != null) scope.forwardSceneDepth.destroyBuffers();
            //$$ scope.forwardSceneDepth = null;
            //#endif
            //#if MC>=1_21_09
            GlowCaptureManager.releasePreviewBuffers(scope.buffers);
            scope.buffers = null;
            //#endif
        }
        POOL.clear();
        current = null;
        nesting = 0;
    }
}
