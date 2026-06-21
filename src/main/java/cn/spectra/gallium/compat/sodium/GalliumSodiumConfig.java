package cn.spectra.gallium.compat.sodium;

//#if MC>=1_21_11 || MC==1_21_01
import cn.spectra.gallium.config.GalliumConfigIO;
import cn.spectra.gallium.glowoutline.GlowOutlineConfig;
import cn.spectra.gallium.glowoutline.GlowOutlineConfig.Group;
import cn.spectra.gallium.glowoutline.GlowOutlineConfig.Toggle;
import net.caffeinemc.mods.sodium.api.config.ConfigEntryPoint;
import net.caffeinemc.mods.sodium.api.config.StorageEventHandler;
import net.caffeinemc.mods.sodium.api.config.structure.ConfigBuilder;
import net.caffeinemc.mods.sodium.api.config.structure.OptionBuilder;
import net.caffeinemc.mods.sodium.api.config.structure.OptionGroupBuilder;
import net.caffeinemc.mods.sodium.api.config.structure.OptionPageBuilder;
import net.minecraft.network.chat.Component;
//#if MC>=1_21_09
import net.minecraft.resources.Identifier;
//#else
//$$ // Sodium 0.8.12-beta.1 (used on 1.21.1) predates the Identifier rename — its
//$$ // ConfigBuilder/ModOptionsBuilder still takes ResourceLocation.
//$$ import net.minecraft.resources.ResourceLocation;
//#endif

public class GalliumSodiumConfig implements ConfigEntryPoint {

    private static final StorageEventHandler STORAGE_HANDLER = new StorageEventHandler() {
        @Override
        public void afterSave() {
            GalliumConfigIO.save();
        }
    };

    @Override
    public void registerConfigLate(ConfigBuilder configBuilder) {
        configBuilder.registerOwnModOptions()
                //#if MC>=1_21_09
                .setIcon(Identifier.parse("gallium:textures/gui/sodium_icon.png"))
                //#else
                //$$ .setIcon(ResourceLocation.parse("gallium:textures/gui/sodium_icon.png"))
                //#endif
                .addPage(createGlowPage(configBuilder));
    }

    private OptionPageBuilder createGlowPage(ConfigBuilder builder) {
        OptionPageBuilder page = builder.createOptionPage()
                .setName(Component.translatable("gallium.options.page_title"));
        page.addOptionGroup(createGroup(builder, Group.GLOBAL, "gallium.options.group_global"));
        page.addOptionGroup(createGroup(builder, Group.RENDER_TARGET, "gallium.options.group_targets"));
        return page;
    }

    private OptionGroupBuilder createGroup(ConfigBuilder builder, Group group, String nameKey) {
        OptionGroupBuilder g = builder.createOptionGroup().setName(Component.translatable(nameKey));
        for (Toggle t : Toggle.values()) {
            if (t.group() == group) g.addOption(createToggle(builder, t));
        }
        return g;
    }

    private OptionBuilder createToggle(ConfigBuilder builder, Toggle toggle) {
        //#if MC>=1_21_09
        return builder.createBooleanOption(Identifier.parse("gallium:" + toggle.sodiumId()))
        //#else
        //$$ return builder.createBooleanOption(ResourceLocation.parse("gallium:" + toggle.sodiumId()))
        //#endif
                .setName(Component.translatable("gallium.options." + toggle.sodiumId()))
                .setTooltip(Component.translatable("gallium.options." + toggle.sodiumId() + ".tooltip"))
                .setDefaultValue(true)
                .setStorageHandler(STORAGE_HANDLER)
                .setBinding(toggle::set, toggle::get);
    }
}
//#else
//$$ // Sodium config API was introduced in Sodium 0.8.x. On 1.21.3–1.21.10 we ship
//$$ // Sodium 0.6.13 / 0.7.3 (no public config API) and add the page via the legacy
//$$ // SodiumOptionsGUIMixin instead. On those versions the sodium:config_api_user
//$$ // entrypoint is omitted from fabric.mod.json (see common.gradle) and the class
//$$ // body collapses to an empty stub so the symbol still resolves if any reflection
//$$ // probe touches it.
//$$ public final class GalliumSodiumConfig {
//$$     private GalliumSodiumConfig() {}
//$$ }
//#endif
