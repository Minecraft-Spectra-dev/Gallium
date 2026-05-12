package cn.spectra.gallium.compat.sodium;

import net.caffeinemc.mods.sodium.api.config.ConfigEntryPoint;
import net.caffeinemc.mods.sodium.api.config.StorageEventHandler;
import net.caffeinemc.mods.sodium.api.config.structure.ConfigBuilder;
import net.caffeinemc.mods.sodium.api.config.structure.OptionBuilder;
import net.caffeinemc.mods.sodium.api.config.structure.OptionGroupBuilder;
import net.caffeinemc.mods.sodium.api.config.structure.OptionPageBuilder;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.packs.PackSelectionScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

import java.util.function.Consumer;
import java.util.function.Supplier;

public class GalliumSodiumConfig implements ConfigEntryPoint {

    // 预留的配置值存储
    private static boolean booleanOptionValue = true;
    private static int integerOptionValue = 50;
    private static TestEnum enumOptionValue = TestEnum.OPTION_A;

    // 存储事件处理器
    private static final StorageEventHandler STORAGE_HANDLER = new StorageEventHandler() {
        @Override
        public void afterSave() {
            // 配置保存后回调，可以在这里执行额外操作
        }
    };

    @Override
    public void registerConfigLate(ConfigBuilder configBuilder) {
        configBuilder.registerOwnModOptions()
                .setIcon(Identifier.parse("gallium:textures/gui/sodium_icon.png"))
                // 添加配置选项页面
                .addPage(createOptionPage(configBuilder))
                // 保留原有的外部页面
                .addPage(configBuilder
                        .createExternalPage()
                        .setName(Component.translatable("gallium.options.external_page"))
                        .setScreenConsumer(parentScreen -> {
                            var mc = Minecraft.getInstance();
                            mc.setScreen(new PackSelectionScreen(
                                    mc.getResourcePackRepository(),
                                    repo -> mc.setScreen(parentScreen),
                                    mc.getResourcePackDirectory(),
                                    Component.translatable("resourcePack.title")
                            ));
                        })
                );
    }

    /**
     * 创建配置选项页面
     */
    private OptionPageBuilder createOptionPage(ConfigBuilder configBuilder) {
        return configBuilder
                .createOptionPage()
                .setName(Component.translatable("gallium.options.page_title"))
                .addOptionGroup(createGeneralOptionsGroup(configBuilder));
    }

    /**
     * 创建通用设置选项组
     */
    private OptionGroupBuilder createGeneralOptionsGroup(ConfigBuilder configBuilder) {
        return configBuilder
                .createOptionGroup()
                .setName(Component.translatable("gallium.options.group_general"))
                .addOption(createBooleanOption(configBuilder))
                .addOption(createIntegerOption(configBuilder))
                .addOption(createEnumOption(configBuilder));
    }

    /**
     * 创建布尔开关选项
     */
    private OptionBuilder createBooleanOption(ConfigBuilder configBuilder) {
        return configBuilder
                .createBooleanOption(Identifier.parse("gallium:boolean_option"))
                .setName(Component.translatable("gallium.options.boolean_option"))
                .setTooltip(Component.translatable("gallium.options.boolean_option.tooltip"))
                .setDefaultValue(true)
                .setStorageHandler(STORAGE_HANDLER)
                .setBinding(
                        (value) -> booleanOptionValue = value,
                        () -> booleanOptionValue
                );
    }

    /**
     * 创建整数滑块选项
     */
    private OptionBuilder createIntegerOption(ConfigBuilder configBuilder) {
        return configBuilder
                .createIntegerOption(Identifier.parse("gallium:integer_option"))
                .setName(Component.translatable("gallium.options.integer_option"))
                .setTooltip(Component.translatable("gallium.options.integer_option.tooltip"))
                .setRange(0, 100, 1)
                .setDefaultValue(50)
                .setValueFormatter((value) -> Component.literal(value + "%"))
                .setStorageHandler(STORAGE_HANDLER)
                .setBinding(
                        (value) -> integerOptionValue = value,
                        () -> integerOptionValue
                );
    }

    /**
     * 创建枚举循环选项
     */
    private OptionBuilder createEnumOption(ConfigBuilder configBuilder) {
        return configBuilder
                .createEnumOption(Identifier.parse("gallium:enum_option"), TestEnum.class)
                .setName(Component.translatable("gallium.options.enum_option"))
                .setTooltip(Component.translatable("gallium.options.enum_option.tooltip"))
                .setDefaultValue(TestEnum.OPTION_A)
                .setElementNameProvider((value) -> Component.translatable(value.translationKey))
                .setStorageHandler(STORAGE_HANDLER)
                .setBinding(
                        (value) -> enumOptionValue = value,
                        () -> enumOptionValue
                );
    }

    /**
     * 测试枚举 - 用于循环选项
     */
    public enum TestEnum {
        OPTION_A("gallium.enum.option_a"),
        OPTION_B("gallium.enum.option_b"),
        OPTION_C("gallium.enum.option_c");

        public final String translationKey;

        TestEnum(String translationKey) {
            this.translationKey = translationKey;
        }
    }
}
