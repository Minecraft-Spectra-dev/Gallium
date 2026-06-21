package cn.spectra.gallium.glowoutline.mixin;

//#if MC>=1_21_03 && MC<1_21_11
//$$ import cn.spectra.gallium.compat.sodium.GalliumSodiumLegacyPage;
//$$ import net.caffeinemc.mods.sodium.client.gui.SodiumOptionsGUI;
//$$ import net.caffeinemc.mods.sodium.client.gui.options.OptionPage;
//$$ import org.spongepowered.asm.mixin.Final;
//$$ import org.spongepowered.asm.mixin.Mixin;
//$$ import org.spongepowered.asm.mixin.Shadow;
//$$ import org.spongepowered.asm.mixin.injection.At;
//$$ import org.spongepowered.asm.mixin.injection.Inject;
//$$ import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
//$$
//$$ import java.util.List;
//#endif

/**
 * Adds Gallium's option page to Sodium's options screen on Sodium 0.6.13 / 0.7.3
 * (1.21.3–1.21.10). Sodium 0.6/0.7 has no public config API, so we hook
 * {@link net.caffeinemc.mods.sodium.client.gui.SodiumOptionsGUI}'s constructor and
 * push our {@code OptionPage}s onto its private {@code pages} list, the same trick
 * sodium-extra has used since 0.6.0 (see
 * {@code me.flashyreese.mods.sodiumextra.mixin.compat.MixinSodiumOptionsGUI}).
 * <p>
 * The {@code remap = false} on the @Mixin annotation suppresses MC class-name
 * remapping for the target — Sodium's own classes aren't part of the MC mapping
 * namespace and never get obfuscated at runtime, so the target name is already
 * the runtime name. This matches sodium-extra's mixin. The package-private blaze3d
 * caveat from {@code gallium-mixin-remap-pkg-private} does not apply here:
 * {@code SodiumOptionsGUI} is public and so is its package; the shadowed
 * {@code pages} field is private but on a public class.
 * <p>
 * On 1.21.11+/26.1 and on 1.21.1 we go through Sodium 0.8's public
 * {@code ConfigEntryPoint} instead (see
 * {@link cn.spectra.gallium.compat.sodium.GalliumSodiumConfig}). The class
 * collapses to a stub on those versions and is stripped from the runtime
 * mixin config via {@code STUB_MIXIN_CLASSES_LEGACY_SODIUM} (in
 * {@code common.gradle}).
 */
//#if MC>=1_21_03 && MC<1_21_11
//$$ @Mixin(value = SodiumOptionsGUI.class, remap = false)
//$$ public abstract class SodiumOptionsGUIMixin {
//$$
//$$     @Shadow
//$$     @Final
//$$     private List<OptionPage> pages;
//$$
//$$     @Inject(method = "<init>", at = @At("TAIL"))
//$$     private void gallium$addPage(CallbackInfo ci) {
//$$         this.pages.addAll(GalliumSodiumLegacyPage.build());
//$$     }
//$$ }
//#else
public final class SodiumOptionsGUIMixin {
    private SodiumOptionsGUIMixin() {}
}
//#endif
