package cn.spectra.gallium.compat.sodium;

import cn.spectra.gallium.glowoutline.GlowOutlineConfig;
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
        public void afterSave() {}
    };

    @Override
    public void registerConfigLate(ConfigBuilder configBuilder) {
        configBuilder.registerOwnModOptions()
                .setIcon(Identifier.parse("gallium:textures/gui/sodium_icon.png"))
                .addPage(createGlowPage(configBuilder));
    }

    private OptionPageBuilder createGlowPage(ConfigBuilder builder) {
        return builder.createOptionPage()
                .setName(Component.translatable("gallium.options.page_title"))
                .addOptionGroup(createGlowGroup(builder));
    }

    private OptionGroupBuilder createGlowGroup(ConfigBuilder builder) {
        return builder.createOptionGroup()
                .setName(Component.translatable("gallium.options.group_glow"))
                .addOption(createEnabledOption(builder))
                .addOption(createIntensityOption(builder));
    }

    private OptionBuilder createEnabledOption(ConfigBuilder builder) {
        return builder.createBooleanOption(Identifier.parse("gallium:glow_enabled"))
                .setName(Component.translatable("gallium.options.glow_enabled"))
                .setTooltip(Component.translatable("gallium.options.glow_enabled.tooltip"))
                .setDefaultValue(true)
                .setStorageHandler(STORAGE_HANDLER)
                .setBinding(GlowOutlineConfig::setEnabled, GlowOutlineConfig::isEnabled);
    }

    private OptionBuilder createIntensityOption(ConfigBuilder builder) {
        return builder.createIntegerOption(Identifier.parse("gallium:glow_intensity"))
                .setName(Component.translatable("gallium.options.glow_intensity"))
                .setTooltip(Component.translatable("gallium.options.glow_intensity.tooltip"))
                .setRange(0, 200, 10)
                .setDefaultValue(100)
                .setValueFormatter(v -> Component.literal(v + "%"))
                .setStorageHandler(STORAGE_HANDLER)
                .setBinding(
                        v -> GlowOutlineConfig.setIntensity(v / 100.0f),
                        () -> Math.round(GlowOutlineConfig.getIntensity() * 100)
                );
    }
}
