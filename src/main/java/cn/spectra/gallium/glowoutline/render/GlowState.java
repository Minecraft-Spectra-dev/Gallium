package cn.spectra.gallium.glowoutline.render;

import com.mojang.blaze3d.pipeline.TextureTarget;
import net.minecraft.world.item.ItemStack;

public class GlowState {
    private static TextureTarget itemBefore;
    private static TextureTarget itemAfter;
    private static boolean captured;
    private static boolean active;

    private static ItemStack mainHandItem = ItemStack.EMPTY;
    private static ItemStack offHandItem = ItemStack.EMPTY;

    public static TextureTarget getItemBefore() { return itemBefore; }
    public static void setItemBefore(TextureTarget t) { itemBefore = t; }
    public static TextureTarget getItemAfter() { return itemAfter; }
    public static void setItemAfter(TextureTarget t) { itemAfter = t; }

    public static boolean isCaptured() { return captured; }
    public static void setCaptured(boolean v) { captured = v; }

    public static boolean isActive() { return active; }
    public static void setActive(boolean v) { active = v; }

    public static ItemStack getMainHandItem() { return mainHandItem; }
    public static void setMainHandItem(ItemStack s) { mainHandItem = s; }
    public static ItemStack getOffHandItem() { return offHandItem; }
    public static void setOffHandItem(ItemStack s) { offHandItem = s; }

    public static void resetFrame() {
        captured = false;
        active = false;
        mainHandItem = ItemStack.EMPTY;
        offHandItem = ItemStack.EMPTY;
    }
}
