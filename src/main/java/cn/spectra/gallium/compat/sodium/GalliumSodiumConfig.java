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
                .addOptionGroup(createGlobalGroup(builder))
                .addOptionGroup(createRenderTargetGroup(builder));
    }

    private OptionGroupBuilder createGlobalGroup(ConfigBuilder builder) {
        return builder.createOptionGroup()
                .setName(Component.translatable("gallium.options.group_global"))
                .addOption(createEnabledOption(builder));
    }

    private OptionGroupBuilder createRenderTargetGroup(ConfigBuilder builder) {
        return builder.createOptionGroup()
                .setName(Component.translatable("gallium.options.group_targets"))
                .addOption(createToggle(builder, "first_person", GlowOutlineConfig::setFirstPerson, GlowOutlineConfig::isFirstPerson))
                .addOption(createToggle(builder, "third_person", GlowOutlineConfig::setThirdPerson, GlowOutlineConfig::isThirdPerson))
                .addOption(createToggle(builder, "other_entities", GlowOutlineConfig::setOtherEntities, GlowOutlineConfig::isOtherEntities))
                .addOption(createToggle(builder, "dropped_items", GlowOutlineConfig::setDroppedItems, GlowOutlineConfig::isDroppedItems))
                .addOption(createToggle(builder, "armor", GlowOutlineConfig::setArmor, GlowOutlineConfig::isArmor));
    }

    private OptionBuilder createEnabledOption(ConfigBuilder builder) {
        return builder.createBooleanOption(Identifier.parse("gallium:glow_enabled"))
                .setName(Component.translatable("gallium.options.glow_enabled"))
                .setTooltip(Component.translatable("gallium.options.glow_enabled.tooltip"))
                .setDefaultValue(true)
                .setStorageHandler(STORAGE_HANDLER)
                .setBinding(GlowOutlineConfig::setEnabled, GlowOutlineConfig::isEnabled);
    }

    private OptionBuilder createToggle(ConfigBuilder builder, String id,
                                        java.util.function.Consumer<Boolean> setter,
                                        java.util.function.Supplier<Boolean> getter) {
        return builder.createBooleanOption(Identifier.parse("gallium:glow_" + id))
                .setName(Component.translatable("gallium.options.glow_" + id))
                .setTooltip(Component.translatable("gallium.options.glow_" + id + ".tooltip"))
                .setDefaultValue(true)
                .setStorageHandler(STORAGE_HANDLER)
                .setBinding(setter::accept, getter::get);
    }
}
