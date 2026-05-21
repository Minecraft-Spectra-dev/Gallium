package cn.spectra.gallium.glowoutline.shader;

import cn.spectra.gallium.glowoutline.ItemEffectConfig;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.buffers.Std140Builder;
import com.mojang.blaze3d.buffers.Std140SizeCalculator;
import com.mojang.blaze3d.systems.RenderSystem;
import java.nio.ByteBuffer;
import org.lwjgl.system.MemoryStack;

public class GlowUniformBuffer implements AutoCloseable {
    private static final int UBO_SIZE = new Std140SizeCalculator()
            .putFloat()   // FrameTimeCounter
            .putVec2()    // ScreenSize
            .putFloat()   // DepthThreshold
            .putFloat()   // Intensity
            .putFloat()   // PulseSpeed
            .putFloat()   // WaveSpeed
            .putVec3()    // InnerColor
            .putVec3()    // OuterColor
            .get();

    private final GpuBuffer buffer;

    public GlowUniformBuffer() {
        this.buffer = RenderSystem.getDevice().createBuffer(
                () -> "Glow Uniform Buffer",
                136,
                UBO_SIZE
        );
    }

    public void update(float frameTimeCounter, int screenWidth, int screenHeight,
                       float depthThreshold, ItemEffectConfig cfg) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            ByteBuffer data = Std140Builder.onStack(stack, UBO_SIZE)
                    .putFloat(frameTimeCounter)
                    .putVec2((float) screenWidth, (float) screenHeight)
                    .putFloat(depthThreshold)
                    .putFloat(cfg.intensity())
                    .putFloat(cfg.pulseSpeed())
                    .putFloat(cfg.waveSpeed())
                    .putVec3(cfg.innerColor().x, cfg.innerColor().y, cfg.innerColor().z)
                    .putVec3(cfg.outerColor().x, cfg.outerColor().y, cfg.outerColor().z)
                    .get();
            RenderSystem.getDevice().createCommandEncoder().writeToBuffer(this.buffer.slice(), data);
        }
    }

    public GpuBufferSlice getSlice() {
        return this.buffer.slice();
    }

    @Override
    public void close() {
        this.buffer.close();
    }
}
