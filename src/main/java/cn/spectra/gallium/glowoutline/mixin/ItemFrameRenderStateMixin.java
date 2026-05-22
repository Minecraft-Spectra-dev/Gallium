package cn.spectra.gallium.glowoutline.mixin;

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
