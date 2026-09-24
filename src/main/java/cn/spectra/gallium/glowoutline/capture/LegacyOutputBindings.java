package cn.spectra.gallium.glowoutline.capture;

//#if MC<1_21_05
//$$ import cn.spectra.gallium.glowoutline.IrisCompat;
//$$ import com.mojang.blaze3d.systems.RenderSystem;
//$$ import net.minecraft.client.renderer.RenderStateShard;
//$$ import net.minecraft.client.renderer.RenderType;
//$$
//$$ /** The capture caller binds its own target after native layer setup and unbinds it at the end. */
//$$ public final class LegacyOutputBindings extends RenderStateShard {
//$$     private static boolean active;
//$$
//$$     private LegacyOutputBindings() { super("gallium_mask_output", () -> {}, () -> {}); }
//$$
//$$     static boolean eligible(RenderType type) {
//$$         if (!RenderSystem.isOnRenderThread() || IrisCompat.isShaderActive()
//$$                 || IrisCompat.isActiveSrRuntime()) return false;
//$$         try { return MaskBoundsTracker.nativeType(type); }
//$$         catch (RuntimeException | LinkageError unknown) { return false; }
//$$     }
//$$
//$$     static boolean enter(RenderType type) {
//$$         boolean previous = active;
//$$         active = eligible(type);
//$$         return previous;
//$$     }
//$$
//$$     static void restore(boolean previous) { active = previous; }
//$$
//$$     public static boolean skip(RenderStateShard shard) {
//$$         return active && (shard == MAIN_TARGET || shard == ITEM_ENTITY_TARGET);
//$$     }
//$$ }
//#else
public final class LegacyOutputBindings { private LegacyOutputBindings() {} }
//#endif
