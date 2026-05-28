package cn.spectra.gallium.glowoutline.mixin;

//#if MC<1_21_11
//$$ import cn.spectra.gallium.glowoutline.capture.ArmedEntityRenderStateAccessor;
//$$ import net.minecraft.client.renderer.entity.state.ArmedEntityRenderState;
//$$ import net.minecraft.client.renderer.item.ItemModelResolver;
//$$ import net.minecraft.world.entity.HumanoidArm;
//$$ import net.minecraft.world.entity.LivingEntity;
//$$ import net.minecraft.world.item.ItemStack;
//$$ import org.spongepowered.asm.mixin.Mixin;
//$$ import org.spongepowered.asm.mixin.Unique;
//$$ import org.spongepowered.asm.mixin.injection.At;
//$$ import org.spongepowered.asm.mixin.injection.Inject;
//$$ import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
//#endif

/**
 * Snapshots both hand-held {@link net.minecraft.world.item.ItemStack}s onto
 * {@code ArmedEntityRenderState} during render-state extraction.
 * <p>
 * Only needed on 1.21.10 — see {@link cn.spectra.gallium.glowoutline.capture.ArmedEntityRenderStateAccessor}.
 * On 1.21.11+ the class collapses to an empty stub and is stripped from the runtime
 * mixin config via {@code STUB_MIXIN_CLASSES_FROM_1_21_11} (in {@code common.gradle}),
 * because the target API ({@code extractArmedEntityRenderState} without an {@code ItemStack}
 * parameter) only exists on 1.21.10.
 */
//#if MC<1_21_11
//$$ @Mixin(ArmedEntityRenderState.class)
//$$ public abstract class ArmedEntityRenderStateMixin implements ArmedEntityRenderStateAccessor {
//$$
//$$     @Unique
//$$     private ItemStack gallium$rightHandStack = ItemStack.EMPTY;
//$$     @Unique
//$$     private ItemStack gallium$leftHandStack = ItemStack.EMPTY;
//$$
//$$     @Override
//$$     public ItemStack gallium$getHandStack(HumanoidArm arm) {
//$$         return arm == HumanoidArm.RIGHT ? gallium$rightHandStack : gallium$leftHandStack;
//$$     }
//$$
//$$     @Override
//$$     public void gallium$setHandStacks(ItemStack right, ItemStack left) {
//$$         gallium$rightHandStack = right == null ? ItemStack.EMPTY : right;
//$$         gallium$leftHandStack = left == null ? ItemStack.EMPTY : left;
//$$     }
//$$
//$$     // extractArmedEntityRenderState is the single Mojang entry point that fills
//$$     // ArmedEntityRenderState's per-hand ItemStackRenderStates from a LivingEntity.
//$$     // We piggy-back on it to also snapshot the raw ItemStacks — needed by
//$$     // ItemInHandLayerMixin on this version because submitArmWithItem doesn't
//$$     // receive an ItemStack parameter (added in 1.21.11).
//$$     @Inject(
//$$         method = "extractArmedEntityRenderState(Lnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/client/renderer/entity/state/ArmedEntityRenderState;Lnet/minecraft/client/renderer/item/ItemModelResolver;)V",
//$$         at = @At("HEAD")
//$$     )
//$$     private static void gallium$snapshotHandStacks(LivingEntity livingEntity,
//$$                                                     ArmedEntityRenderState state,
//$$                                                     ItemModelResolver resolver,
//$$                                                     CallbackInfo ci) {
//$$         ((ArmedEntityRenderStateAccessor) (Object) state).gallium$setHandStacks(
//$$             livingEntity.getItemHeldByArm(HumanoidArm.RIGHT),
//$$             livingEntity.getItemHeldByArm(HumanoidArm.LEFT));
//$$     }
//$$ }
//#else
public final class ArmedEntityRenderStateMixin {
    private ArmedEntityRenderStateMixin() {}
}
//#endif
