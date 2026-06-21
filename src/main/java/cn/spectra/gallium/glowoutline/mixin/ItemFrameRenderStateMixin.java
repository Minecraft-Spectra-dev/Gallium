package cn.spectra.gallium.glowoutline.mixin;

//#if MC>=1_21_02
import cn.spectra.gallium.glowoutline.capture.ItemFrameRenderStateAccessor;
import net.minecraft.client.renderer.entity.state.ItemFrameRenderState;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

@Mixin(ItemFrameRenderState.class)
public class ItemFrameRenderStateMixin implements ItemFrameRenderStateAccessor {

    @Unique
    private ItemStack gallium$itemStack = ItemStack.EMPTY;

    @Override
    public ItemStack gallium$getItemStack() {
        return gallium$itemStack;
    }

    @Override
    public void gallium$setItemStack(ItemStack stack) {
        gallium$itemStack = stack;
    }
}
//#else
//$$ // 1.21.1 predates the 1.21.2 render-state rework: ItemFrameRenderState doesn't
//$$ // exist. Empty stub, stripped from mixins.json via STUB_MIXIN_CLASSES_PRE_1_21_02.
//$$ public final class ItemFrameRenderStateMixin {
//$$     private ItemFrameRenderStateMixin() {}
//$$ }
//#endif
