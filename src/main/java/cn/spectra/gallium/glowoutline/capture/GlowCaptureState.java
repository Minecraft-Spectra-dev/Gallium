package cn.spectra.gallium.glowoutline.capture;

import cn.spectra.gallium.glowoutline.ItemEffectConfig;
//#if MC>=1_21_02
import com.mojang.blaze3d.ProjectionType;
//#else
//$$ import com.mojang.blaze3d.vertex.VertexSorting;
//#endif
//#if MC>=1_21_06
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
//#endif
import com.mojang.blaze3d.pipeline.TextureTarget;
import net.minecraft.client.renderer.RenderBuffers;
//#if MC>=1_21_09
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher;
//#endif
//#if MC>=1_26_02
//$$ import net.minecraft.client.renderer.SubmitNodeStorage;
//#endif
import org.joml.Matrix4f;
import org.jspecify.annotations.Nullable;

public final class GlowCaptureState {

    public @Nullable TextureTarget maskTarget;
    public @Nullable RenderBuffers captureBuffers;
    //#if MC>=1_21_09
    public @Nullable FeatureRenderDispatcher captureDispatcher;
    //#endif
    //#if MC>=1_26_02
    //$$ // 26.2: FeatureRenderDispatcher no longer owns a SubmitNodeStorage — renderAllFeatures
    //$$ // takes it per call. Each capture state holds its own storage that the duplicating
    //$$ // wrapper mirrors into and that renderAllFeatures drains via drainPhases.
    //$$ public @Nullable SubmitNodeStorage captureStorage;
    //$$ /** Forward-Z R32F color target holding {@code 1 - maskDepth} for native 26.2's
    //$$  *  reverse-Z path. Iris 1.11.x shader packs already restore forward-Z and bind the raw
    //$$  *  mask depth instead. Lazily allocated per state and resized with {@link #maskTarget}. */
    //$$ public @Nullable TextureTarget maskDepthForwardZTarget;
    //#endif
    // Pre-1.21.9 immediate-mode capture buffer. Retained across frames (its native buffers are
    // pooled like captureBuffers) and freed on release; see GlowCaptureManager.releaseState.
    //#if MC<1_21_09
    //$$ public CaptureSites.@Nullable DelayingMultiBufferSource customBufferSource;
    //#endif
    public boolean capturedThisFrame;
    public boolean active;
    public boolean firstPerson;
    /** Whether captureSceneDepth populated mask depth for this state in the current frame. Exact
     *  Iris replay intentionally leaves this false; if enabling Iris bypass fails later, replay
     *  restores the scene snapshot before drawing instead of treating an empty depth target as
     *  trustworthy. */
    public boolean maskDepthPrepared;
    /** Whether mask replay and scene depth share the exact same per-frame projection. False means
     *  the fragment shader must retain its bounded temporal-mismatch fallback. */
    public boolean exactDepthAlignment = true;
    public @Nullable ItemEffectConfig config;

    public @Nullable Matrix4f capturedModelViewMatrix;
    public boolean capturedModelViewMatrixValid;
    //#if MC>=1_21_06
    public @Nullable GpuBufferSlice capturedProjectionMatrix;
    /**
     * Projection UBO used only by this capture state when Iris downscaling/jitter must be
     * replayed. A shared buffer is unsafe on deferred backends: a later item's upload can replace
     * the matrix before an earlier item's render pass consumes it.
     */
    public @Nullable GpuBuffer scaledProjectionBuffer;
    public @Nullable GpuBufferSlice scaledProjectionSlice;
    //#endif
    //#if MC>=1_21_02
    public @Nullable ProjectionType capturedProjectionType;
    //#else
    //$$ // 1.21.1 has no ProjectionType; the (Matrix4f, VertexSorting) projection overload
    //$$ // carries the equivalent. backup/restoreProjectionMatrix preserves vertexSorting too.
    //$$ public @Nullable VertexSorting capturedProjectionType;
    //#endif
    /** Plain Matrix4f form of {@link #capturedProjectionMatrix} when available. We need it
     *  to apply VertexDownscaling for shader-pack scale alignment on the mask render; when
     *  {@link #capturedProjectionMatrix4fValid} is {@code false} the mask render falls back
     *  to the unmodified slice (no downscale). Final & owned per-state to avoid per-frame
     *  Matrix4f allocations: the pool keeps one of these alive per slot, refilled in place
     *  via {@link ProjectionMatrixTracker#lookupInto}. */
    public final Matrix4f capturedProjectionMatrix4f = new Matrix4f();
    public boolean capturedProjectionMatrix4fValid;
    /** Per-axis scale actually applied during the most recent mask replay. Shader packs that
     *  floor their internal pixel dimensions can produce slightly different X/Y ratios. */
    public float lastMaskScaleX = 1.0f;
    public float lastMaskScaleY = 1.0f;
    /** Per-axis scale of the selected scene-depth source. This can differ from the mask scale
     *  when projection capture fails and the replay must fall back to its unmodified matrix. */
    public float lastSceneScaleX = 1.0f;
    public float lastSceneScaleY = 1.0f;
    /**
     * Full-texture UV offsets used when mapping the final output pixel back to an internal
     * Iris texture. They are half the declared NDC jitter because NDC spans two UV units.
     * Mask and scene offsets are kept separate: a failed mask projection can still use the
     * exact offset of Iris's scene-depth snapshot while retaining the temporal fallback.
     */
    public float lastMaskOffsetX;
    public float lastMaskOffsetY;
    public float lastSceneOffsetX;
    public float lastSceneOffsetY;

    public void resetFrame() {
        capturedThisFrame = false;
        active = false;
        firstPerson = false;
        maskDepthPrepared = false;
        exactDepthAlignment = true;
        config = null;
        capturedModelViewMatrixValid = false;
        //#if MC>=1_21_06
        capturedProjectionMatrix = null;
        // Keep the state-owned slice alive across frame resets. The backing UBO is retained
        // until releaseState(), and uploadScaledProjection() only creates the slice when the
        // buffer is first allocated. Clearing the slice here would leave a live buffer with no
        // usable slice on the second frame.
        //#endif
        capturedProjectionType = null;
        capturedProjectionMatrix4fValid = false;
        lastMaskScaleX = 1.0f;
        lastMaskScaleY = 1.0f;
        lastSceneScaleX = 1.0f;
        lastSceneScaleY = 1.0f;
        lastMaskOffsetX = 0.0f;
        lastMaskOffsetY = 0.0f;
        lastSceneOffsetX = 0.0f;
        lastSceneOffsetY = 0.0f;
        // Pre-1.21.9: rewind any builder left open by an early-returned renderCapturedNodes
        // (see DelayingMultiBufferSource.endFrame). Native buffers are pooled, only released in releaseState.
        //#if MC<1_21_09
        //$$ if (customBufferSource != null) {
        //$$     customBufferSource.endFrame();
        //$$ }
        //#endif
    }
}
