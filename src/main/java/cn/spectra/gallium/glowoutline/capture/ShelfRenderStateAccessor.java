package cn.spectra.gallium.glowoutline.capture;

import net.minecraft.world.item.ItemStack;

public interface ShelfRenderStateAccessor {
    ItemStack gallium$getItemStack(int slot);
    void gallium$setItemStack(int slot, ItemStack stack);
}
