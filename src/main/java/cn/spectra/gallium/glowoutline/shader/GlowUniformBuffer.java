package cn.spectra.gallium.glowoutline.shader;

import cn.spectra.gallium.glowoutline.ItemEffectConfig;
import cn.spectra.gallium.glowoutline.ShaderParam;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.buffers.Std140Builder;
import com.mojang.blaze3d.systems.RenderSystem;
import java.nio.ByteBuffer;
import java.util.function.Supplier;
import org.lwjgl.system.MemoryStack;

public class GlowUniformBuffer implements AutoCloseable {

    private static final int BUFFER_CAPACITY = 512;
    private static final int BUFFER_USAGE_FLAGS = 136;

    private final GpuBuffer buffer;

    public GlowUniformBuffer(String label) {
        Supplier<String> labelSupplier = () -> label;
        this.buffer = RenderSystem.getDevice().createBuffer(labelSupplier, BUFFER_USAGE_FLAGS, BUFFER_CAPACITY);
    }

    public void update(float frameTimeCounter, int screenWidth, int screenHeight, float globalIntensity, ItemEffectConfig cfg) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            Std140Builder builder = Std140Builder.onStack(stack, BUFFER_CAPACITY);
            builder.putFloat(frameTimeCounter);
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
