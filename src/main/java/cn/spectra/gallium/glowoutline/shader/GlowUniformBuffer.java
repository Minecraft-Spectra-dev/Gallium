package cn.spectra.gallium.glowoutline.shader;

import cn.spectra.gallium.glowoutline.ItemEffectConfig;
import cn.spectra.gallium.glowoutline.ShaderParam;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.buffers.Std140Builder;
import com.mojang.blaze3d.buffers.Std140SizeCalculator;
import com.mojang.blaze3d.systems.RenderSystem;
import java.nio.ByteBuffer;
import org.lwjgl.system.MemoryStack;

public class GlowUniformBuffer implements AutoCloseable {

    private static final int MAX_UBO_SIZE = 512;

    private final GpuBuffer buffer;

    public GlowUniformBuffer() {
        this.buffer = RenderSystem.getDevice().createBuffer(
                () -> "Glow Uniform Buffer",
                136,
                MAX_UBO_SIZE
        );
    }

    public void update(float frameTimeCounter, int screenWidth, int screenHeight, float globalIntensity, ItemEffectConfig cfg) {
        int totalSize = computeSize(cfg);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            Std140Builder builder = Std140Builder.onStack(stack, totalSize);
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

    private static int computeSize(ItemEffectConfig cfg) {
        Std140SizeCalculator calc = new Std140SizeCalculator()
                .putFloat()
                .putVec2()
                .putFloat();
        for (ShaderParam param : cfg.params()) {
            switch (param) {
                case ShaderParam.Float f -> calc.putFloat();
                case ShaderParam.Vec2 v -> calc.putVec2();
                case ShaderParam.Vec3 v -> calc.putVec3();
                case ShaderParam.Vec4 v -> calc.putVec4();
            }
        }
        return calc.get();
    }

    public GpuBufferSlice getSlice() {
        return this.buffer.slice();
    }

    @Override
    public void close() {
        this.buffer.close();
    }
}
