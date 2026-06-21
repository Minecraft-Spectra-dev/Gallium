package cn.spectra.gallium.compat.sodium;

//#if MC>=1_21_03 && MC<1_21_11
//$$ import cn.spectra.gallium.config.GalliumConfigIO;
//$$ import cn.spectra.gallium.glowoutline.GlowOutlineConfig;
//$$ import cn.spectra.gallium.glowoutline.GlowOutlineConfig.Group;
//$$ import cn.spectra.gallium.glowoutline.GlowOutlineConfig.Toggle;
//$$ import com.google.common.collect.ImmutableList;
//$$ import net.caffeinemc.mods.sodium.client.gui.options.OptionGroup;
//$$ import net.caffeinemc.mods.sodium.client.gui.options.OptionImpl;
//$$ import net.caffeinemc.mods.sodium.client.gui.options.OptionPage;
//$$ import net.caffeinemc.mods.sodium.client.gui.options.control.TickBoxControl;
//$$ import net.caffeinemc.mods.sodium.client.gui.options.storage.OptionStorage;
//$$ import net.minecraft.network.chat.Component;
//$$
//$$ import java.util.List;
//#endif

/**
 * Builds Gallium {@link OptionPage}s compatible with Sodium 0.6.x / 0.7.x
 * (1.21.3–1.21.10). Those versions lack the Sodium 0.8 public config API
 * ({@code ConfigEntryPoint}); the pages are injected into Sodium's built-in
 * {@code SodiumOptionsGUI} via {@code SodiumOptionsGUIMixin}.
 * <p>
 * In the legacy API {@link OptionGroup} has no {@code setName} (unlike the
 * 0.8 API's {@code OptionGroupBuilder}), so we produce two pages instead of
 * one page with two named groups. The constructor sets each page's name,
 * giving the user clear tabs in Sodium's tab bar.
 * <p>
 * The builder pattern mirrors sodium-extra's
 * {@code SodiumExtraGameOptionPages.animation()} under the
 * {@code mc1.21.10-0.7.1} tag.
 * <p>
 * Toggle values live on the static {@link GlowOutlineConfig.Toggle} enum —
 * the {@code OptionStorage<Object>.getData()} sentinel is never dereferenced
 * by the binding lambdas. On "Apply" the storage's {@code save()} fires
 * {@link GalliumConfigIO#save()} so changes are persisted to disk.
 * <p>
 * On 1.21.11+/26.1 and on 1.21.1 this class is a stub — those versions
 * use {@link GalliumSodiumConfig} through the 0.8 public API.
 */
//#if MC>=1_21_03 && MC<1_21_11
//$$ public final class GalliumSodiumLegacyPage {
//$$
//$$     private static final Object SENTINEL = new Object();
//$$
//$$     private static final OptionStorage<Object> STORAGE = new OptionStorage<>() {
//$$         @Override
//$$         public Object getData() {
//$$             // Toggle values are static — the binding lambdas below
//$$             // ignore this sentinel. It only needs to be non-null so
//$$             // OptionImpl.reset() doesn't NPE on storage.getData().
//$$             return SENTINEL;
//$$         }
//$$
//$$         @Override
//$$         public void save() {
//$$             GalliumConfigIO.save();
//$$         }
//$$     };
//$$
//$$     public static List<OptionPage> build() {
//$$         return List.of(
//$$             buildPage(Group.GLOBAL, "gallium.options.group_global"),
//$$             buildPage(Group.RENDER_TARGET, "gallium.options.group_targets"));
//$$     }
//$$
//$$     private static OptionPage buildPage(Group group, String nameKey) {
//$$         OptionGroup.Builder b = OptionGroup.createBuilder();
//$$         for (Toggle t : Toggle.values()) {
//$$             if (t.group() == group) {
//$$                 b.add(toOption(t));
//$$             }
//$$         }
//$$         return new OptionPage(
//$$             Component.translatable(nameKey),
//$$             ImmutableList.of(b.build()));
//$$     }
//$$
//$$     private static OptionImpl<Object, Boolean> toOption(Toggle toggle) {
//$$         return OptionImpl.createBuilder(boolean.class, STORAGE)
//$$             .setName(Component.translatable("gallium.options." + toggle.sodiumId()))
//$$             .setTooltip(Component.translatable("gallium.options." + toggle.sodiumId() + ".tooltip"))
//$$             .setControl(TickBoxControl::new)
//$$             .setBinding(
//$$                 (opts, v) -> toggle.set(v),
//$$                 opts -> toggle.get())
//$$             .build();
//$$     }
//$$
//$$     private GalliumSodiumLegacyPage() {}
//$$ }
//#else
public final class GalliumSodiumLegacyPage {
    private GalliumSodiumLegacyPage() {}
}
//#endif
