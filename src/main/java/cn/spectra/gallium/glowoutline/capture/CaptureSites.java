package cn.spectra.gallium.glowoutline.capture;

//#if MC>=1_21_09
import cn.spectra.gallium.glowoutline.GlowOutlineConfig;
import cn.spectra.gallium.glowoutline.IrisCompat;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.SubmitNodeStorage;
import net.minecraft.world.item.ItemStack;

/**
 * Shared capture-site glue used by the per-renderer mixins. Handles the common pattern of:
 * <ol>
 *   <li>Skipping if shadow pass / empty stack / feature toggle off.</li>
 *   <li>Calling {@link GlowCaptureManager#beginItemCapture}.</li>
 *   <li>Wrapping the {@code SubmitNodeCollector} into a duplicating storage when capturable
 *       and the original collector is a {@link SubmitNodeStorage}.</li>
 * </ol>
 * Mixins call {@link #beginIfCapturable} for the pre-flight, then unconditionally invoke
 * {@link GlowCaptureManager#endItemCapture()} after the original call.
 */
public final class CaptureSites {

    private CaptureSites() {}

    /**
     * Returns a collector to pass to the original call. If capture is active and the original
     * collector is a {@link SubmitNodeStorage}, returns a {@link DuplicatingSubmitNodeStorage};
     * otherwise returns the original collector unchanged.
     * <p>
     * Callers MUST always call {@link GlowCaptureManager#endItemCapture()} after the original
     * invocation, even if capture didn't begin — {@code endItemCapture()} is no-op when nothing
     * is currently captured.
     */
    public static SubmitNodeCollector beginIfCapturable(ItemStack stack,
                                                         SubmitNodeCollector original,
                                                         GlowOutlineConfig.Toggle featureFlag) {
        return beginIfCapturable(stack, original, featureFlag, false);
    }

    public static SubmitNodeCollector beginIfCapturable(ItemStack stack,
                                                         SubmitNodeCollector original,
                                                         GlowOutlineConfig.Toggle featureFlag,
                                                         boolean firstPerson) {
        if (!GlowOutlineConfig.isEnabled()) return original;
        if (!featureFlag.get()) return original;
        if (IrisCompat.isShadowPass()) return original;
        if (stack == null || stack.isEmpty()) return original;
        if (!GlowCaptureManager.beginItemCapture(stack, firstPerson)) return original;
        return original instanceof SubmitNodeStorage storage
                ? new DuplicatingSubmitNodeStorage(storage)
                : original;
    }

    public static void end() {
        GlowCaptureManager.endItemCapture();
    }
}
//#else
//$$ import cn.spectra.gallium.glowoutline.GlowOutlineConfig;
//$$ import cn.spectra.gallium.glowoutline.IrisCompat;
//$$ import com.mojang.blaze3d.vertex.VertexConsumer;
//$$ import com.mojang.blaze3d.vertex.VertexMultiConsumer;
//$$ import net.minecraft.client.renderer.MultiBufferSource;
//$$ import net.minecraft.client.renderer.RenderType;
//$$ import net.minecraft.world.item.ItemStack;
//$$ import com.mojang.blaze3d.vertex.BufferBuilder;
//$$ import com.mojang.blaze3d.vertex.ByteBufferBuilder;
//$$ import java.util.ArrayList;
//$$ import java.util.List;
//$$ import java.util.Set;
//$$
//$$ public final class CaptureSites {
//$$     private CaptureSites() {}
//$$
//$$     // Vanilla glint layer names. These layers must be excluded from the mask: glint uses a
//$$     // time-varying, 8x-scaled texture matrix and special depth/layering state that produces
//$$     // a geometry explosion when replayed under our swapped projection + bypass at composite
//$$     // time. The glow outline only needs the item's solid layers. Named explicitly rather than
//$$     // matched by substring to avoid silently dropping a modded layer whose name contains
//$$     // "glint" as a coincidence.
//$$     private static final Set<String> GLINT_LAYER_NAMES = Set.of(
//$$             "glint", "entity_glint", "armor_entity_glint", "glint_translucent"
//$$     );
//$$ 
//$$     public static MultiBufferSource beginIfCapturable(ItemStack stack,
//$$                                                        MultiBufferSource original,
//$$                                                        GlowOutlineConfig.Toggle featureFlag) {
//$$         return beginIfCapturable(stack, original, featureFlag, false);
//$$     }
//$$
//$$     public static class DelayingMultiBufferSource implements MultiBufferSource {
//$$         // One capture layer per RenderType. The native ByteBufferBuilder (256 KiB off-heap)
//$$         // is RETAINED across frames to avoid per-frame malloc/free churn — vanilla's
//$$         // MultiBufferSource.BufferSource pools its buffers the same way. Only the lightweight
//$$         // BufferBuilder wrapper is recreated each frame (it is single-use after build()).
//$$         private static final class Layer {
//$$             final RenderType type;
//$$             final ByteBufferBuilder nativeBuffer;
//$$             BufferBuilder builder; // per-frame wrapper; null between frames
//$$             Layer(RenderType type, ByteBufferBuilder nativeBuffer) {
//$$                 this.type = type;
//$$                 this.nativeBuffer = nativeBuffer;
//$$             }
//$$         }
//$$         private final List<Layer> layers = new ArrayList<>();
//$$
//$$         @Override
//$$         public VertexConsumer getBuffer(RenderType renderType) {
//$$             // RenderType does not override equals; identity comparison is intentional.
//$$             // Vanilla memoizes all built-in RenderTypes so the same RenderType constant
//$$             // is passed on every render call, making == correct and cheap.
//$$             for (Layer l : layers) {
//$$                 if (l.type == renderType) {
//$$                     if (l.builder == null) l.builder = newBuilder(l.nativeBuffer, renderType);
//$$                     return l.builder;
//$$                 }
//$$             }
//$$             ByteBufferBuilder nb = new ByteBufferBuilder(262144);
//$$             Layer l = new Layer(renderType, nb);
//$$             l.builder = newBuilder(nb, renderType);
//$$             layers.add(l);
//$$             return l.builder;
//$$         }
//$$
//$$         private static BufferBuilder newBuilder(ByteBufferBuilder nativeBuffer, RenderType renderType) {
//$$             // Build the capture buffer with the VANILLA vertex format, not Iris's extended one.
//$$             // Two Iris hooks promote the format and BOTH must be suppressed here:
//$$             //   1. RenderType.format() delegates to RenderPipeline.getVertexFormat(), which
//$$             //      Iris's MixinRenderPipeline extends to IrisVertexFormats.ENTITY whenever
//$$             //      renderWithExtendedVertexFormat && isRenderingLevel. setBypass(true) clears
//$$             //      renderWithExtendedVertexFormat so format()/mode() return the vanilla layout.
//$$             //   2. The BufferBuilder constructor itself re-extends via MixinBufferBuilder unless
//$$             //      skipExtension is set.
//$$             // Our capture mesh is replayed at composite time under the vanilla NEW_ENTITY
//$$             // pipeline (also bypassed), so an extended capture buffer would mismatch the
//$$             // pipeline stride and geometry-explode under shaders. No-op when Iris is absent.
//$$             var prevBypass = IrisCompat.setBypass(true);
//$$             boolean prevSkip = IrisCompat.setSkipExtension(true);
//$$             try {
//$$                 return new BufferBuilder(nativeBuffer, renderType.mode(), renderType.format());
//$$             } finally {
//$$                 IrisCompat.setSkipExtension(prevSkip);
//$$                 IrisCompat.restoreBypass(prevBypass);
//$$             }
//$$         }
//$$
//$$         // Build each captured layer and draw it into the mask, then rewind the native buffers
//$$         // for reuse next frame (MeshData.close() via try-with-resources rewinds the builder).
//$$         public void flush() {
//$$             for (Layer l : layers) {
//$$                 if (l.builder == null) continue;
//$$                 try (var mesh = l.builder.build()) {
//$$                     if (mesh != null) {
//$$                         l.type.draw(mesh);
//$$                     }
//$$                 } catch (Exception e) {
//$$                     cn.spectra.gallium.Gallium.LOGGER.error("Error drawing mesh", e);
//$$                 }
//$$                 l.builder = null;
//$$             }
//$$         }
//$$
//$$         // End-of-frame cleanup when flush did not run (e.g. renderCapturedNodes early-returned).
//$$         // Finalizes any in-progress builder so its native buffer rewinds to empty for reuse;
//$$         // the off-heap native buffers themselves are retained. Call free() to release them.
//$$         public void endFrame() {
//$$             for (Layer l : layers) {
//$$                 if (l.builder == null) continue;
//$$                 try (var mesh = l.builder.build()) {
//$$                     // Discarded, not drawn — building rewinds the native buffer for reuse.
//$$                 } catch (Exception ignored) {
//$$                 }
//$$                 l.builder = null;
//$$             }
//$$         }
//$$
//$$         // Terminal: release every retained native ByteBufferBuilder. Called when the owning
//$$         // GlowCaptureState is released (pool shrink, resource reload).
//$$         public void free() {
//$$             for (Layer l : layers) {
//$$                 l.nativeBuffer.close();
//$$             }
//$$             layers.clear();
//$$         }
//$$     }
//$$ 
//$$     public static MultiBufferSource beginIfCapturable(ItemStack stack,
//$$                                                        MultiBufferSource original,
//$$                                                        GlowOutlineConfig.Toggle featureFlag,
//$$                                                        boolean firstPerson) {
//$$         if (!GlowOutlineConfig.isEnabled()) return original;
//$$         if (!featureFlag.get()) return original;
//$$         if (IrisCompat.isShadowPass()) return original;
//$$         if (stack == null) return original;
//$$         boolean capturable = GlowCaptureManager.beginItemCapture(stack, firstPerson);
//$$         if (!capturable) return original;
//$$ 
//$$         GlowCaptureState state = GlowCaptureManager.currentCapture();
//$$         if (state == null) return original;
//$$
//$$         if (state.customBufferSource == null) {
//$$             state.customBufferSource = new DelayingMultiBufferSource();
//$$         }
//$$         DelayingMultiBufferSource captureSource = state.customBufferSource;
//$$ 
//$$         return renderType -> {
//$$             VertexConsumer orig = original.getBuffer(renderType);
//$$             // Skip enchantment-glint layers — replaying them into the mask under our swapped
//$$             // projection + bypass causes a geometry explosion. See GLINT_LAYER_NAMES.
//$$             if (GLINT_LAYER_NAMES.contains(renderType.getName())) {
//$$                 return orig;
//$$             }
//$$             state.capturedThisFrame = true;
//$$             VertexConsumer cap = captureSource.getBuffer(renderType);
//$$             return VertexMultiConsumer.create(orig, cap);
//$$         };
//$$     }
//$$ 
//$$     public static void end() {
//$$         GlowCaptureManager.endItemCapture();
//$$     }
//$$ }
//#endif
