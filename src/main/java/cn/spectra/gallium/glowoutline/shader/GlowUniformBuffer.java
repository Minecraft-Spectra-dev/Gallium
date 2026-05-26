package cn.spectra.gallium.glowoutline.shader;

import cn.spectra.gallium.Gallium;
import cn.spectra.gallium.glowoutline.ItemEffectConfig;
import cn.spectra.gallium.glowoutline.ShaderParam;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.buffers.Std140Builder;
import com.mojang.blaze3d.systems.RenderSystem;
import java.nio.BufferOverflowException;
import java.nio.ByteBuffer;
import java.util.function.Supplier;
import org.lwjgl.system.MemoryStack;

public class GlowUniformBuffer implements AutoCloseable {

    // Header consumes 16B (float time at offset 0, vec2 size aligned to offset 8, std140).
    // Remaining 4080B fits ~255 vec4 params, well above any realistic shader's needs. If a
    // packed entry would still overflow we catch BufferOverflowException once and skip the
    // write rather than crashing.
    private static final int BUFFER_CAPACITY = 4096;
    // writeToBuffer requires USAGE_COPY_DST; UNIFORM marks the buffer as a UBO target.
    private static final int BUFFER_USAGE_FLAGS = GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_COPY_DST;

    private final GpuBuffer buffer;
    private boolean overflowLogged;

    public GlowUniformBuffer(String label) {
        Supplier<String> labelSupplier = () -> label;
        this.buffer = RenderSystem.getDevice().createBuffer(labelSupplier, BUFFER_USAGE_FLAGS, BUFFER_CAPACITY);
    }

    public void update(float frameTimeCounter, int screenWidth, int screenHeight, ItemEffectConfig cfg) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            Std140Builder builder = Std140Builder.onStack(stack, BUFFER_CAPACITY);
            ByteBuffer data;
            try {
                builder.putFloat(frameTimeCounter);
                builder.putVec2((float) screenWidth, (float) screenHeight);
                for (ShaderParam param : cfg.params()) {
                    param.pack(builder);
                }
                data = builder.get();
            } catch (BufferOverflowException e) {
                if (!overflowLogged) {
                    overflowLogged = true;
                    Gallium.LOGGER.warn(
                        "Glow UBO overflow for shader '{}' ({} params, capacity {}B). Excess params dropped this frame; further overflows silenced.",
                        cfg.shader(), cfg.params().size(), BUFFER_CAPACITY);
                }
                return;
            }
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
