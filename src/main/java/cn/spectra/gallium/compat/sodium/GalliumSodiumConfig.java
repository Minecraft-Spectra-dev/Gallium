package cn.spectra.gallium.compat.sodium;

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
import net.minecraft.resources.Identifier;

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
                .setIcon(Identifier.parse("gallium:textures/gui/sodium_icon.png"))
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
        return builder.createBooleanOption(Identifier.parse("gallium:" + toggle.sodiumId()))
                .setName(Component.translatable("gallium.options." + toggle.sodiumId()))
                .setTooltip(Component.translatable("gallium.options." + toggle.sodiumId() + ".tooltip"))
                .setDefaultValue(true)
                .setStorageHandler(STORAGE_HANDLER)
                .setBinding(toggle::set, toggle::get);
    }
}
