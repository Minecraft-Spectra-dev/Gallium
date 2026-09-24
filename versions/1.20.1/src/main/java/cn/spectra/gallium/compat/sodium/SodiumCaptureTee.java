package cn.spectra.gallium.compat.sodium;

import cn.spectra.gallium.glowoutline.capture.CaptureSites.ReusableTeeMultiBufferSource.TeeVertexConsumer;
import cn.spectra.gallium.glowoutline.capture.GlowCaptureState;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.caffeinemc.mods.sodium.api.vertex.format.VertexFormatDescription;
import net.caffeinemc.mods.sodium.api.vertex.buffer.VertexBufferWriter;
import net.minecraft.client.renderer.RenderType;
import org.lwjgl.system.MemoryStack;

/** Loaded only with Sodium. Both delegates receive the original complete vertex stream. */
public final class SodiumCaptureTee extends TeeVertexConsumer implements VertexBufferWriter {
    private static final java.lang.invoke.MethodHandle FORMAT_CHECK = formatCheck();
    private VertexBufferWriter originalWriter, captureWriter;
    public SodiumCaptureTee(RenderType type) { super(type); }

    @Override protected TeeVertexConsumer reset(VertexConsumer original, VertexConsumer capture, GlowCaptureState state) {
        super.reset(original, capture, state);
        originalWriter = VertexBufferWriter.tryOf(original);
        captureWriter = VertexBufferWriter.tryOf(capture);
        return this;
    }

    @Override public boolean canUseIntrinsics() {
        return originalWriter != null && (!captureEnabled || captureWriter != null);
    }

    // Sodium 0.8 adds this overload. Keeping it as an ordinary method also loads on 0.6.
    public boolean canUseIntrinsics(VertexFormatDescription format) {
        if (!canUseIntrinsics()) return false;
        if (FORMAT_CHECK == null) return true;
        try {
            return (boolean) FORMAT_CHECK.invokeExact(originalWriter, format)
                    && (!captureEnabled || (boolean) FORMAT_CHECK.invokeExact(captureWriter, format));
        } catch (Throwable unavailable) { return false; }
    }

    private static java.lang.invoke.MethodHandle formatCheck() {
        try {
            return java.lang.invoke.MethodHandles.publicLookup().findVirtual(VertexBufferWriter.class,
                    "canUseIntrinsics", java.lang.invoke.MethodType.methodType(boolean.class, VertexFormatDescription.class));
        } catch (NoSuchMethodException | IllegalAccessException olderSodium) { return null; }
    }

    @Override public void push(MemoryStack stack, long pointer, int count, VertexFormatDescription format) {
        if (captureEnabled && captureWriter != null && count > 0) {
            // Writers may transform their input in place. Copy the first branch before
            // either delegate touches the caller's memory, as Sodium's multi-writer does.
            VertexBufferWriter.copyInto(captureWriter, stack, pointer, count, format);
            mark();
        }
        originalWriter.push(stack, pointer, count, format);
    }

    @Override protected void detach() {
        super.detach(); originalWriter = captureWriter = null;
    }
}
