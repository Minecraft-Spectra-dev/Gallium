package cn.spectra.gallium.glowoutline.capture;

import cn.spectra.gallium.glowoutline.ItemEffectConfig;
import com.mojang.blaze3d.ProjectionType;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.pipeline.TextureTarget;
import net.minecraft.client.renderer.RenderBuffers;
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import org.joml.Matrix4f;
import org.jspecify.annotations.Nullable;

public final class GlowCaptureState {
    public final InteractionHand hand;

    public @Nullable TextureTarget maskTarget;
    public @Nullable RenderBuffers captureBuffers;
    public @Nullable FeatureRenderDispatcher captureDispatcher;
    public boolean capturedThisFrame;

    public @Nullable ItemStack item = ItemStack.EMPTY;
    public @Nullable ItemEffectConfig config;

    public @Nullable Matrix4f capturedModelViewMatrix;
    public @Nullable GpuBufferSlice capturedProjectionMatrix;
    public @Nullable ProjectionType capturedProjectionType;

    public GlowCaptureState(InteractionHand hand) {
        this.hand = hand;
    }

    public void resetFrame() {
        capturedThisFrame = false;
        item = ItemStack.EMPTY;
        config = null;
        capturedModelViewMatrix = null;
        capturedProjectionMatrix = null;
        capturedProjectionType = null;
    }
}
