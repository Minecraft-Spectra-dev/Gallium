package cn.spectra.gallium.glowoutline.shader;

import cn.spectra.gallium.Gallium;
import cn.spectra.gallium.glowoutline.ItemEffectConfig;
import cn.spectra.gallium.glowoutline.ShaderParam;
import com.mojang.blaze3d.buffers.GpuBuffer;
//#if MC>=1_21_06
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.buffers.Std140Builder;
//#endif
import com.mojang.blaze3d.systems.RenderSystem;
import java.nio.BufferOverflowException;
import java.nio.ByteBuffer;
import java.util.function.Supplier;
import org.lwjgl.system.MemoryStack;

public class GlowUniformBuffer implements AutoCloseable {

    //#if MC>=1_21_06
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
    //#else
    //$$ public GlowUniformBuffer(String label) {}
    //#endif

    //#if MC>=1_21_06
    public void update(float frameTimeCounter, int screenWidth, int screenHeight, ItemEffectConfig cfg) {
        update(frameTimeCounter, screenWidth, screenHeight, 1.0f, 1.0f, cfg);
    }

    public void update(float frameTimeCounter, int screenWidth, int screenHeight,
                       float maskUvFactor, float sceneUvFactor,
                       ItemEffectConfig cfg) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            Std140Builder builder = Std140Builder.onStack(stack, BUFFER_CAPACITY);
            ByteBuffer data;
            try {
                // Layout (std140):
                //   float FrameTimeCounter; vec2 ScreenSize; <user params...>; vec4 ShaderAlign;
                // ShaderAlign sits at the *tail* on purpose — packs that pre-date Iris internal-
                // resolution scaling never declared it, and putting it here means their shaders
                // don't need to know about it at all. Old packs read header + params at exactly
                // the same offsets they always did; the trailing 16 bytes are unused (a UBO can
                // be larger than what the shader declares without GLSL caring). Packs that need
                // alignment declare {vec4 ShaderAlign} as the LAST member of their uniform block.
                //
                // ShaderAlign vec4 (not vec3): a following scalar would slot into the vec3's
                // 4-byte tail (std140 offset 12) producing a Java↔GLSL mismatch. With a vec4
                // tail there's no scalar after, but keeping vec4 documents the contract for
                // anyone re-extending the block here.
                builder.putFloat(frameTimeCounter);
                builder.putVec2((float) screenWidth, (float) screenHeight);
                for (ShaderParam param : cfg.params()) {
                    param.pack(builder);
                }
                builder.putVec4(maskUvFactor, sceneUvFactor, 0.0f, 0.0f);
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
    //#endif

    @Override
    public void close() {
        //#if MC>=1_21_06
        this.buffer.close();
        //#endif
    }
}
