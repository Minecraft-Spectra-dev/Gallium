package cn.spectra.gallium.glowoutline.capture;

import cn.spectra.gallium.glowoutline.ItemEffectConfig;
import com.mojang.blaze3d.ProjectionType;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.pipeline.TextureTarget;
import net.minecraft.client.renderer.RenderBuffers;
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher;
import org.joml.Matrix4f;
import org.jspecify.annotations.Nullable;

public final class GlowCaptureState {

    public @Nullable TextureTarget maskTarget;
    public @Nullable RenderBuffers captureBuffers;
    public @Nullable FeatureRenderDispatcher captureDispatcher;
    public boolean capturedThisFrame;
    public boolean active;
    public boolean firstPerson;

    public @Nullable ItemEffectConfig config;

    public @Nullable Matrix4f capturedModelViewMatrix;
    public boolean capturedModelViewMatrixValid;
    public @Nullable GpuBufferSlice capturedProjectionMatrix;
    public @Nullable ProjectionType capturedProjectionType;
    /** Plain Matrix4f form of {@link #capturedProjectionMatrix} when available. We need it
     *  to apply VertexDownscaling for shader-pack scale alignment on the mask render; when
     *  {@link #capturedProjectionMatrix4fValid} is {@code false} the mask render falls back
     *  to the unmodified slice (no downscale). Final & owned per-state to avoid per-frame
     *  Matrix4f allocations: the pool keeps one of these alive per slot, refilled in place
     *  via {@link ProjectionMatrixTracker#lookupInto}. */
    public final Matrix4f capturedProjectionMatrix4f = new Matrix4f();
    public boolean capturedProjectionMatrix4fValid;
    /** Scale that was actually applied during the most recent mask render. {@code 1.0f} means
     *  the mask is in full-resolution screen space; {@code <1.0f} means the mask was rasterized
     *  into the same {@code [0, scale]²} subrect that Iris uses, and the composite shader must
     *  match by reading mask uvs through the same scale. */
    public float lastMaskScale = 1.0f;

    public void resetFrame() {
        capturedThisFrame = false;
        active = false;
        firstPerson = false;
        config = null;
        capturedModelViewMatrixValid = false;
        capturedProjectionMatrix = null;
        capturedProjectionType = null;
        capturedProjectionMatrix4fValid = false;
        lastMaskScale = 1.0f;
    }
}
