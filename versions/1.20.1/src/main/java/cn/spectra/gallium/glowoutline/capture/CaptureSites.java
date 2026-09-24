package cn.spectra.gallium.glowoutline.capture;

import cn.spectra.gallium.glowoutline.GlowOutlineConfig;
import cn.spectra.gallium.glowoutline.IrisCompat;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;

import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexMultiConsumer;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.world.item.ItemStack;

public final class CaptureSites {
    private CaptureSites() {}

    public static MultiBufferSource beginIfCapturable(ItemStack stack,
                                                       MultiBufferSource original,
                                                       GlowOutlineConfig.Toggle featureFlag) {
        return beginIfCapturable(stack, original, featureFlag, false);
    }

    public static MultiBufferSource beginIfCapturable(ItemStack stack,
                                                       MultiBufferSource original,
                                                       GlowOutlineConfig.Toggle featureFlag,
                                                       boolean firstPerson) {
        GlowCaptureManager.beginItemCaptureScope();
        try {
            if (!GlowOutlineConfig.isEnabled()) return original;
            if (featureFlag == null || !featureFlag.get()) return original;
            if (IrisCompat.isShadowPass()) return original;
            if (stack == null || stack.isEmpty()) return original;
            if (!GlowCaptureManager.beginItemCapture(stack, firstPerson)) return original;
            GlowCaptureManager.markItemCaptureScopeStarted();

            GlowCaptureState state = GlowCaptureManager.currentCapture();
            if (state == null) return original;
            if (state.customBufferSource == null) {
                state.customBufferSource = new DelayingMultiBufferSource();
            }
            DelayingMultiBufferSource captureSource = state.customBufferSource;

            ReusableTeeMultiBufferSource tee = state.reusableTee;
            if (tee == null) {
                tee = new ReusableTeeMultiBufferSource();
                state.reusableTee = tee;
            }
            tee.reset(original, captureSource, state);
            return tee;
        } catch (RuntimeException | Error e) {
            GlowCaptureManager.cancelItemCaptureScope();
            throw e;
        }
    }

    public static final class ReusableTeeMultiBufferSource implements MultiBufferSource {
        private MultiBufferSource original;
        private DelayingMultiBufferSource capture;
        private GlowCaptureState state;
        private boolean captureEnabled;
        private final List<TeeVertexConsumer> consumers = new ArrayList<>();

        public void reset(MultiBufferSource original, DelayingMultiBufferSource capture,
                          GlowCaptureState state) {
            this.original = original;
            this.capture = capture;
            this.state = state;
            this.captureEnabled = true;
        }

        public void disableCapture() {
            captureEnabled = false;
            for (int i = 0; i < consumers.size(); i++) consumers.get(i).disableCapture();
        }

        public void detach() {
            captureEnabled = false;
            original = null;
            capture = null;
            state = null;
            for (int i = 0; i < consumers.size(); i++) consumers.get(i).detach();
        }

        @Override
        public VertexConsumer getBuffer(RenderType renderType) {
            if (original == null) {
                throw new IllegalStateException("Detached capture buffer wrapper");
            }
            VertexConsumer orig = original.getBuffer(renderType);
            if (!captureEnabled) return orig;
            if (capture == null || state == null) {
                throw new IllegalStateException("Detached capture buffer wrapper");
            }
            String name = renderType == null ? null : renderType.toString();
            if (name != null && name.contains("glint")) return orig;
            VertexConsumer cap = capture.getBuffer(renderType);
            for (int i = 0; i < consumers.size(); i++) {
                TeeVertexConsumer consumer = consumers.get(i);
                if (consumer.type == renderType) return consumer.reset(orig, cap, state);
            }
            TeeVertexConsumer consumer = newConsumer(renderType);
            consumers.add(consumer);
            return consumer.reset(orig, cap, state);
        }

        private static boolean sodiumBulk = cn.spectra.gallium.platform.PlatformServices.isModLoaded("sodium");
        private static TeeVertexConsumer newConsumer(RenderType type) {
            if (sodiumBulk) {
                try { return SodiumFactory.create(type); }
                catch (LinkageError unsupported) {
                    sodiumBulk = false;
                    cn.spectra.gallium.Gallium.LOGGER.warn("Sodium bulk capture unavailable: {}", unsupported.toString());
                }
            }
            return new TeeVertexConsumer(type);
        }

        // Keep the optional subtype out of the enclosing class's verifier graph on Java 17.
        private static final class SodiumFactory {
            static TeeVertexConsumer create(RenderType type) {
                return new cn.spectra.gallium.compat.sodium.SodiumCaptureTee(type);
            }
        }

        public static class TeeVertexConsumer implements VertexConsumer {
            private final RenderType type;
            protected VertexConsumer original;
            protected VertexConsumer capture;
            private GlowCaptureState state;
            private boolean marked;
            protected boolean captureEnabled;
            private boolean mirrorCurrentVertex;
            protected TeeVertexConsumer(RenderType type) { this.type = type; }
            protected TeeVertexConsumer reset(VertexConsumer original, VertexConsumer capture,
                                            GlowCaptureState state) {
                this.original = original; this.capture = capture; this.state = state;
                this.marked = false; this.captureEnabled = true;
                this.mirrorCurrentVertex = false; return this;
            }
            private void disableCapture() { captureEnabled = false; }
            protected void detach() {
                original = null; capture = null; state = null; marked = false;
                captureEnabled = false; mirrorCurrentVertex = false;
            }
            protected final void mark() {
                if (!marked) { GlowCaptureManager.markCaptured(state); marked = true; }
            }
            @Override public void endVertex() {
                original.endVertex();
                if (mirrorCurrentVertex) capture.endVertex();
                mirrorCurrentVertex = false;
            }
            @Override public void defaultColor(int r, int g, int b, int a) {
                original.defaultColor(r, g, b, a);
                if (captureEnabled && capture != null) capture.defaultColor(r, g, b, a);
            }
            @Override public void unsetDefaultColor() {
                original.unsetDefaultColor();
                if (capture != null) capture.unsetDefaultColor();
            }
            @Override public VertexConsumer vertex(double x, double y, double z) {
                original.vertex(x, y, z);
                mirrorCurrentVertex = captureEnabled && capture != null;
                if (mirrorCurrentVertex) {
                    capture.vertex(x, y, z); mark();
                }
                return this;
            }
            @Override public VertexConsumer color(int r, int g, int b, int a) {
                original.color(r, g, b, a);
                if (mirrorCurrentVertex) capture.color(r, g, b, a);
                return this;
            }
            @Override public VertexConsumer uv(float u, float v) {
                original.uv(u, v); if (mirrorCurrentVertex) capture.uv(u, v); return this;
            }
            @Override public VertexConsumer overlayCoords(int u, int v) {
                original.overlayCoords(u, v); if (mirrorCurrentVertex) capture.overlayCoords(u, v); return this;
            }
            @Override public VertexConsumer uv2(int u, int v) {
                original.uv2(u, v); if (mirrorCurrentVertex) capture.uv2(u, v); return this;
            }
            @Override public VertexConsumer normal(float x, float y, float z) {
                original.normal(x, y, z);
                if (mirrorCurrentVertex) capture.normal(x, y, z);
                return this;
            }
        }
    }

    // Substring match (rather than the exact-name Set used on 1.21.5+) because
    // pre-1.21.5 RenderType.toString includes a "renderType[<name>]" wrapper or
    // appended modifier in some cases — exact equality on the bare layer name
    // misses those, so glint geometry would slip into the capture buffer and
    // produce a brightly-shaded outline. Substring is broader (matches anything
    // containing "glint") which is the intended behaviour: any layer ultimately
    // drawing glint vertices should not feed the mask, regardless of how its
    // toString is formatted.
    private static VertexConsumer teeVertexConsumer(MultiBufferSource original,
                                                    DelayingMultiBufferSource capture,
                                                    RenderType renderType) {
        VertexConsumer orig = original.getBuffer(renderType);
        String name = renderType.toString();
        if (name != null && name.contains("glint")) return orig;
        return VertexMultiConsumer.create(orig, capture.getBuffer(renderType));
    }

    /** One native 1.20.1 builder per layer, retained across captures. */
    public static class DelayingMultiBufferSource implements MultiBufferSource {
        private static final class Layer {
            final RenderType type;
            final BufferBuilder builder = new BufferBuilder(GlowCaptureManager.INITIAL_CAPTURE_VERTEX_BYTES);
            Layer(RenderType type) { this.type = type; }
        }
        private final List<Layer> layers = new ArrayList<>();
        @Override public VertexConsumer getBuffer(RenderType type) {
            Layer layer = null;
            for (Layer existing : layers) if (existing.type == type) { layer = existing; break; }
            if (layer == null) { layer = new Layer(type); layers.add(layer); }
            if (!layer.builder.building()) {
                var bypass = IrisCompat.setBypass(true);
                boolean skip = IrisCompat.setSkipExtension(true);
                try { layer.builder.begin(type.mode(), type.format()); }
                finally { IrisCompat.setSkipExtension(skip); IrisCompat.restoreBypass(bypass); }
            }
            return layer.builder;
        }
        public void flushToTarget(TextureTarget target) {
            if (target == null) return;
            try {
                for (Layer layer : layers) {
                    if (!layer.builder.building()) continue;
                    var mesh = layer.builder.endOrDiscardIfEmpty();
                    if (mesh == null) continue;
                    boolean submitted = false;
                    boolean output = LegacyOutputBindings.enter(layer.type);
                    try {
                        layer.type.setupRenderState();
                        try {
                            target.bindWrite(true);
                            MaskBoundsTracker.record(mesh, layer.type);
                            submitted = true; // BufferUploader / VertexBuffer.upload releases the rendered buffer.
                            com.mojang.blaze3d.vertex.BufferUploader.drawWithShader(mesh);
                        } finally { layer.type.clearRenderState(); }
                    } finally {
                        LegacyOutputBindings.restore(output);
                        if (!submitted) mesh.release();
                    }
                }
            } finally { target.unbindWrite(); }
        }
        public void endFrame() {
            for (Layer layer : layers) if (layer.builder.building()) {
                var mesh = layer.builder.endOrDiscardIfEmpty();
                if (mesh != null) mesh.release();
            }
        }
        public void free() {
            endFrame();
            for (Layer layer : layers) {
                var access = (cn.spectra.gallium.glowoutline.mixin.accessor.LegacyBufferBuilderAccessor) layer.builder;
                org.lwjgl.system.MemoryUtil.memFree(access.gallium$buffer());
            }
            layers.clear();
        }
    }
    public static MultiBufferSource teeGuiNonGlint(MultiBufferSource original, DelayingMultiBufferSource capture) {
        return type -> teeVertexConsumer(original, capture, type);
    }
    public static void end() { GlowCaptureManager.endItemCapture(); }
}
