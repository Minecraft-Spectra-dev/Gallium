package cn.spectra.gallium.glowoutline.shader;

import cn.spectra.gallium.glowoutline.ItemEffectConfig;
import cn.spectra.gallium.glowoutline.ShaderParam;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.buffers.Std140Builder;
import com.mojang.blaze3d.systems.RenderSystem;
import java.nio.ByteBuffer;
import org.lwjgl.system.MemoryStack;

public class GuiGlowUniformBuffer implements AutoCloseable {

    private static final int MAX_UBO_SIZE = 512;

    private final GpuBuffer buffer;

    public GuiGlowUniformBuffer() {
        this.buffer = RenderSystem.getDevice().createBuffer(
                () -> "GUI Glow Uniform Buffer",
                136,
                MAX_UBO_SIZE
        );
    }

    public void update(float frameTime, int screenWidth, int screenHeight, float globalIntensity, ItemEffectConfig cfg) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            Std140Builder builder = Std140Builder.onStack(stack, MAX_UBO_SIZE);
            builder.putFloat(frameTime);
            builder.putVec2((float) screenWidth, (float) screenHeight);
            builder.putFloat(globalIntensity);
            for (ShaderParam param : cfg.params()) {
                param.pack(builder);
            }
            ByteBuffer data = builder.get();
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
