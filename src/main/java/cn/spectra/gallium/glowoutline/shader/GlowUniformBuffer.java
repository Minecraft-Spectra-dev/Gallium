package cn.spectra.gallium.glowoutline.shader;

import cn.spectra.gallium.Gallium;
import cn.spectra.gallium.glowoutline.ItemEffectConfig;
import cn.spectra.gallium.glowoutline.ShaderParam;
//#if MC>=1_21_06
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.buffers.Std140Builder;
import com.mojang.blaze3d.systems.CommandEncoder;
//#endif
import com.mojang.blaze3d.systems.RenderSystem;
import java.nio.BufferOverflowException;
import java.nio.ByteBuffer;
import java.util.function.Supplier;
import org.lwjgl.system.MemoryStack;

public class GlowUniformBuffer implements AutoCloseable {

    //#if MC>=1_21_06
    // Keep the established 4096-byte payload capacity, plus the optional bounds tail.
    // Existing offsets and previously fitting parameter sets remain valid.
    private static final int BUFFER_CAPACITY = 4096 + 16
            //#if MC>=1_21_06 && MC<1_26_02
            + 32
            //#endif
            ;
    // writeToBuffer requires USAGE_COPY_DST; UNIFORM marks the buffer as a UBO target.
    private static final int BUFFER_USAGE_FLAGS = GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_COPY_DST;

    private final GpuBuffer buffer;
    private final GpuBufferSlice fullSlice;
    private boolean overflowLogged;
    //#if MC>=1_21_06 && MC<1_26_02
    private NativeMaskAtlas.Entry maskStorage;
    void setMaskStorage(NativeMaskAtlas.Entry storage) { maskStorage = storage; }
    private net.minecraft.client.renderer.MappableRingBuffer batchBuffer;
    private GpuBufferSlice[] batchSlices;
    private CommandEncoder batchEncoder;
    private GpuBufferSlice selectedSlice;
    private int batchStride, batchIndex, batchCount;
    private boolean batchPreparing, batchReady, batchUploaded, batchBufferUsed;
    private ByteBuffer batchCpu;
    private int[] batchLengths;

    void beginBatch(CommandEncoder encoder, int count) {
        if (count <= 0 || count > 256 || batchPreparing || batchReady)
            throw new IllegalStateException("Invalid uniform batch");
        if (batchCpu == null) {
            batchCpu = ByteBuffer.allocateDirect(BUFFER_CAPACITY * 256);
            batchLengths = new int[256];
        }
        batchEncoder = encoder;
        batchIndex = 0;
        batchCount = count;
        batchPreparing = true;
        batchUploaded = batchBufferUsed = false;
    }

    void uploadBatch() {
        if (!batchPreparing || batchIndex != batchCount) throw new IllegalStateException("Incomplete glow uniform batch");
        batchPreparing = false;
        batchReady = true;
    }

    boolean selectBatchSlice(int index) {
        if (!batchReady || index < 0 || index >= batchIndex) return false;
        uploadFallbackBatch();
        selectedSlice = batchSlices[index];
        maskStorage = null;
        return true;
    }

    ByteBuffer batchValue(int index, int size) {
        if (!batchReady || index < 0 || index >= batchCount || size <= 0 || size > BUFFER_CAPACITY) return null;
        return batchCpu.slice(index * BUFFER_CAPACITY, size);
    }

    /** Instances upload their own compact array. Allocate and upload this second buffer
     * only when a state actually falls back to the original, single-item pipeline. */
    private void uploadFallbackBatch() {
        if (batchUploaded) return;
        if (batchBuffer == null) {
            int alignment = RenderSystem.getDevice().getUniformOffsetAlignment();
            batchStride = ((BUFFER_CAPACITY + alignment - 1) / alignment) * alignment;
            batchBuffer = new net.minecraft.client.renderer.MappableRingBuffer(
                    () -> "Glow atlas uniforms", GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_MAP_WRITE,
                    batchStride * 256);
            batchSlices = new GpuBufferSlice[256];
        }
        var buffer = batchBuffer.currentBuffer();
        batchBufferUsed = true;
        try (var mapping = batchEncoder.mapBuffer(buffer.slice(0, Math.multiplyExact(batchCount, batchStride)), false, true)) {
            var data = mapping.data();
            for (int i = 0; i < batchCount; i++) {
                data.put(i * batchStride, batchCpu, i * BUFFER_CAPACITY, batchLengths[i]);
                batchSlices[i] = buffer.slice(Math.multiplyExact(i, batchStride), BUFFER_CAPACITY);
            }
        }
        batchUploaded = true;
    }

    void finishBatch() {
        try {
            if (batchBufferUsed && batchBuffer != null) batchBuffer.rotate();
        } finally {
            batchEncoder = null; batchPreparing = batchReady = batchUploaded = batchBufferUsed = false;
            selectedSlice = null; maskStorage = null;
        }
    }
    //#endif

    public GlowUniformBuffer(String label) {
        Supplier<String> labelSupplier = () -> label;
        this.buffer = RenderSystem.getDevice().createBuffer(labelSupplier, BUFFER_USAGE_FLAGS, BUFFER_CAPACITY);
        this.fullSlice = this.buffer.slice();
    }
    //#else
    //$$ // UBO-backed uniform buffers are only supported on 1.21.6+. On pre-1.21.6,
    //$$ // per-config uniforms are baked into individual pipeline uniform declarations
    //$$ // (see GlowPipeline.getOrCreate(cfg) on 1.21.5). No call site constructs a
    //$$ // GlowUniformBuffer on these versions, so the constructor is a no-op.
    //$$ public GlowUniformBuffer(String label) {}
    //#endif

    //#if MC>=1_21_06
    public void update(float frameTimeCounter, int screenWidth, int screenHeight, ItemEffectConfig cfg) {
        update(frameTimeCounter, screenWidth, screenHeight, 1.0f, 1.0f, cfg);
    }

    public void update(float frameTimeCounter, int screenWidth, int screenHeight,
                       float maskUvFactor, float sceneUvFactor,
                       ItemEffectConfig cfg) {
        update(frameTimeCounter, screenWidth, screenHeight,
                maskUvFactor, sceneUvFactor, maskUvFactor, sceneUvFactor, cfg);
    }

    public void update(float frameTimeCounter, int screenWidth, int screenHeight,
                       float maskUvFactorX, float sceneUvFactorX,
                       float maskUvFactorY, float sceneUvFactorY,
                       ItemEffectConfig cfg) {
        // Bare encoder: used when the caller doesn't own a CommandEncoder (GUI glow path).
        // World glow MUST use writeToEncoder(CommandEncoder, ...) so the UBO write and the
        // subsequent RenderPass share one encoder — without that, another drawGlow iteration
        // may overwrite the UBO before the previous iteration's RenderPass has read it, so
        // outlines at overlapping screen positions appear with the wrong item's parameters.
        writeToEncoder(RenderSystem.getDevice().createCommandEncoder(),
                frameTimeCounter, screenWidth, screenHeight,
                maskUvFactorX, sceneUvFactorX, maskUvFactorY, sceneUvFactorY,
                0.0f, 0.0f, 0.0f, 0.0f, cfg);
    }

    /**
     * Writes the UBO data onto {@code encoder}. The caller MUST create the subsequent
     * {@link RenderPass} from the same encoder so the buffer write is guaranteed to
     * complete before the shader reads it — even on deferred-backend drivers that
     * execute different encoders' commands in an unspecified order.
     */
    public void writeToEncoder(CommandEncoder encoder,
                               float frameTimeCounter, int screenWidth, int screenHeight,
                               float maskUvFactor, float sceneUvFactor,
                               ItemEffectConfig cfg) {
        writeToEncoder(encoder, frameTimeCounter, screenWidth, screenHeight,
                maskUvFactor, sceneUvFactor, maskUvFactor, sceneUvFactor, cfg);
    }

    public void writeToEncoder(CommandEncoder encoder,
                               float frameTimeCounter, int screenWidth, int screenHeight,
                               float maskUvFactorX, float sceneUvFactorX,
                               float maskUvFactorY, float sceneUvFactorY,
                               ItemEffectConfig cfg) {
        writeToEncoder(encoder, frameTimeCounter, screenWidth, screenHeight,
                maskUvFactorX, sceneUvFactorX, maskUvFactorY, sceneUvFactorY,
                0.0f, 0.0f, 0.0f, 0.0f, cfg);
    }

    /**
     * Writes scale and sub-pixel offset data for the world composite. Offsets are full-texture
     * UV units, so the fragment shader can convert them to physical texels without knowing the
     * pack's internal scale. The mask and scene offsets stay independent when replay and scene
     * depth do not share the same projection.
     */
    public void writeToEncoder(CommandEncoder encoder,
                               float frameTimeCounter, int screenWidth, int screenHeight,
                               float maskUvFactorX, float sceneUvFactorX,
                               float maskUvFactorY, float sceneUvFactorY,
                               float maskUvOffsetX, float sceneUvOffsetX,
                               float maskUvOffsetY, float sceneUvOffsetY,
                               ItemEffectConfig cfg) {
        writeToEncoder(encoder, frameTimeCounter, screenWidth, screenHeight,
                maskUvFactorX, sceneUvFactorX, maskUvFactorY, sceneUvFactorY,
                maskUvOffsetX, sceneUvOffsetX, maskUvOffsetY, sceneUvOffsetY, 0.0f, 0.0f, 0.0f, cfg);
    }

    /** Optional world metadata is appended after the established layout. Existing packs and
     * GUI callers keep their original offsets; first-person/unavailable distance is zero. */
    public void writeToEncoder(CommandEncoder encoder,
                               float frameTimeCounter, int screenWidth, int screenHeight,
                               float maskUvFactorX, float sceneUvFactorX,
                               float maskUvFactorY, float sceneUvFactorY,
                               float maskUvOffsetX, float sceneUvOffsetX,
                               float maskUvOffsetY, float sceneUvOffsetY,
                               float itemDistance, float worldToUvX, float worldToUvY,
                               ItemEffectConfig cfg) {
        writeToEncoder(encoder, frameTimeCounter, screenWidth, screenHeight,
                maskUvFactorX, sceneUvFactorX, maskUvFactorY, sceneUvFactorY,
                maskUvOffsetX, sceneUvOffsetX, maskUvOffsetY, sceneUvOffsetY,
                itemDistance, worldToUvX, worldToUvY, 0, 0, 0, 0, cfg);
    }

    /** Optional physical-pixel bounds follow every established field; zero means unavailable. */
    public void writeToEncoder(CommandEncoder encoder,
                               float frameTimeCounter, int screenWidth, int screenHeight,
                               float maskUvFactorX, float sceneUvFactorX,
                               float maskUvFactorY, float sceneUvFactorY,
                               float maskUvOffsetX, float sceneUvOffsetX,
                               float maskUvOffsetY, float sceneUvOffsetY,
                               float itemDistance, float worldToUvX, float worldToUvY,
                               float maskMinX, float maskMinY, float maskMaxX, float maskMaxY,
                               ItemEffectConfig cfg) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            Std140Builder builder = Std140Builder.onStack(stack, BUFFER_CAPACITY);
            ByteBuffer data;
            try {
                // Layout (std140):
                //   float FrameTimeCounter; vec2 ScreenSize; <user params...>;
                //   vec4 ShaderAlign; vec4 ShaderOffset;
                //   float GalliumItemDistance; vec2 GalliumWorldToUv;
                // ShaderAlign sits at the tail on purpose: packs that pre-date Iris alignment
                // keep reading header + params at their original offsets.
                //
                // ShaderAlign vec4 (not vec3): a following scalar would slot into the vec3's
                // 4-byte tail (std140 offset 12) producing a Java↔GLSL mismatch. With a vec4
                // tail keeps the binary contract explicit for future extensions.
                builder.putFloat(frameTimeCounter);
                builder.putVec2((float) screenWidth, (float) screenHeight);
                for (ShaderParam param : cfg.params()) {
                    param.pack(builder);
                }
                // ShaderAlign = (maskScaleX, sceneScaleX, maskScaleY, sceneScaleY). The first
                // two components retain the original isotropic contract for older packs.
                builder.putVec4(maskUvFactorX, sceneUvFactorX,
                        maskUvFactorY, sceneUvFactorY);
                // ShaderOffset = (maskOffsetX, sceneOffsetX, maskOffsetY, sceneOffsetY).
                // Offsets are UV units, not NDC units; Iris's NDC jitter is divided by two by
                // GlowCaptureManager before it reaches this buffer.
                builder.putVec4(maskUvOffsetX, sceneUvOffsetX,
                        maskUvOffsetY, sceneUvOffsetY);
                builder.putFloat(itemDistance);
                // vec2 starts eight bytes after ShaderOffset's end; the scalar retains
                // its offset and the total tail size stays 16 bytes.
                builder.putFloat(0.0f);
                builder.putVec2(worldToUvX, worldToUvY);
                builder.putVec4(maskMinX, maskMinY, maskMaxX, maskMaxY);
                //#if MC>=1_21_06 && MC<1_26_02
                var stored = maskStorage;
                maskStorage = null;
                if (stored == null) {
                    builder.putVec4(0, 0, 0, 0);
                    builder.putVec4(0, 0, 0, 0);
                } else {
                    builder.putVec4(stored.x(), stored.y(), stored.width(), stored.height());
                    builder.putVec4(stored.offsetX(), stored.offsetY(), stored.visibility()==null ? 1 : 2, stored.state().firstPerson ? 1 : 0);
                }
                //#endif
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
            //#if MC>=1_21_06 && MC<1_26_02
            if (batchPreparing) {
                if (batchIndex >= batchCount) throw new IllegalStateException("Too many glow uniforms");
                batchCpu.put(batchIndex * BUFFER_CAPACITY, data, data.position(), data.remaining());
                batchLengths[batchIndex] = data.remaining();
                batchIndex++;
                return;
            }
            selectedSlice = null;
            //#endif
            encoder.writeToBuffer(this.fullSlice, data);
        }
    }

    public GpuBufferSlice getSlice() {
        //#if MC>=1_21_06 && MC<1_26_02
        if (selectedSlice != null) return selectedSlice;
        //#endif
        return this.fullSlice;
    }
    //#endif

    /** Idempotent; safe to call regardless of MC version. On pre-1.21.6 the buffer
     *  field is absent (constructor is a no-op), so close is a no-op too. */
    @Override
    public void close() {
        //#if MC>=1_21_06
        this.buffer.close();
        //#if MC>=1_21_06 && MC<1_26_02
        if (batchBuffer != null) batchBuffer.close();
        batchBuffer = null; batchSlices = null; batchCpu = null; batchLengths = null; batchEncoder = null;
        selectedSlice = null; batchPreparing = batchReady = batchUploaded = batchBufferUsed = false;
        //#endif
        //#endif
    }
}
