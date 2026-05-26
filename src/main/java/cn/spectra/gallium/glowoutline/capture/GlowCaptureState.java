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
    public @Nullable GpuBufferSlice capturedProjectionMatrix;
    public @Nullable ProjectionType capturedProjectionType;

    public void resetFrame() {
        capturedThisFrame = false;
        active = false;
        firstPerson = false;
        config = null;
        capturedModelViewMatrix = null;
        capturedProjectionMatrix = null;
        capturedProjectionType = null;
    }
}
