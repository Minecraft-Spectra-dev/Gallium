package cn.spectra.gallium.glowoutline.capture;

import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.item.ItemStack;

/**
 * Caches per-arm {@link ItemStack}s on {@code ArmedEntityRenderState}. Needed only on
 * 1.21.10, where {@code ItemInHandLayer.submitArmWithItem} does not receive an
 * {@code ItemStack} parameter (it works off {@code ItemStackRenderState} alone), so
 * the only way to recover the matching stack at the layer site is to snapshot it
 * during the render-state extract step.
 * <p>
 * On 1.21.11+ the layer already gets the {@code ItemStack} directly, so this accessor
 * is unused (and the extract-time mixin is gated to {@code <1_21_11}).
 */
public interface ArmedEntityRenderStateAccessor {
    ItemStack gallium$getHandStack(HumanoidArm arm);
    void gallium$setHandStacks(ItemStack right, ItemStack left);
}
