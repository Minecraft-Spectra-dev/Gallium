package cn.spectra.gallium.glowoutline.capture;

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
 *   <li>Wrapping the {@code SubmitNodeCollector} into a duplicating storage when capturable.</li>
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
        if (IrisCompat.isShadowPass()) return original;
        if (stack == null || stack.isEmpty()) return original;
        if (featureFlag != null && !featureFlag.get()) return original;
        if (!GlowCaptureManager.beginItemCapture(stack, firstPerson)) return original;
        return original instanceof SubmitNodeStorage storage
                ? new DuplicatingSubmitNodeStorage(storage)
                : original;
    }

    public static void end() {
        GlowCaptureManager.endItemCapture();
    }
}
