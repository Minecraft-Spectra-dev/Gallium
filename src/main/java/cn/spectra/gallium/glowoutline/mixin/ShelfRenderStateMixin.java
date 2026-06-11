package cn.spectra.gallium.glowoutline.mixin;

//#if MC>=1_21_09
import cn.spectra.gallium.glowoutline.capture.ShelfRenderStateAccessor;
import net.minecraft.client.renderer.blockentity.state.ShelfRenderState;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

@Mixin(ShelfRenderState.class)
public class ShelfRenderStateMixin implements ShelfRenderStateAccessor {

    @Unique
    private final ItemStack[] gallium$items = new ItemStack[]{ItemStack.EMPTY, ItemStack.EMPTY, ItemStack.EMPTY};

    @Override
    public ItemStack gallium$getItemStack(int slot) {
        return slot >= 0 && slot < gallium$items.length ? gallium$items[slot] : ItemStack.EMPTY;
    }

    @Override
    public void gallium$setItemStack(int slot, ItemStack stack) {
        if (slot >= 0 && slot < gallium$items.length) {
            gallium$items[slot] = stack;
        }
    }
}
//#else
//$$ // Shelves (ShelfRenderState) are a 1.21.9 feature; the target class does not exist
//$$ // on 1.21.6–1.21.8, so this collapses to an empty stub stripped from mixins.json
//$$ // via STUB_MIXIN_CLASSES_PRE_1_21_09 in common.gradle.
//$$ public final class ShelfRenderStateMixin {
//$$     private ShelfRenderStateMixin() {}
//$$ }
//#endif
