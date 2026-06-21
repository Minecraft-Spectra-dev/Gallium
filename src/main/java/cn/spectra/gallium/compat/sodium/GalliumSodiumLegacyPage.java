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

//#if MC==1_21_01
import cn.spectra.gallium.config.GalliumConfigIO;
import cn.spectra.gallium.glowoutline.GlowOutlineConfig.Group;
import cn.spectra.gallium.glowoutline.GlowOutlineConfig.Toggle;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.List;
//#endif

/**
 * Builds Gallium option pages compatible with the legacy Sodium 0.6.x / 0.7.x
 * internal API.
 * <p>
 * On 1.21.3–1.21.10 the Sodium 0.6/0.7 internal classes are on the compile
 * classpath and we use them directly. On 1.21.1 the project compiles against
 * Sodium 0.8 (the 0.6 API classes aren't on the classpath), so the page
 * builder uses reflection through {@link #buildReflective()}.
 * <p>
 * In the legacy API {@code OptionGroup} has no {@code setName} (unlike the
 * 0.8 API's {@code OptionGroupBuilder}), so we produce two pages instead of
 * one page with two named groups. Each page's name becomes the tab label in
 * Sodium's tab bar.
 * <p>
 * Toggle values live on the static {@link GlowOutlineConfig.Toggle} enum —
 * the storage's {@code getData()} sentinel is never dereferenced by the
 * binding lambdas. On "Apply" the storage's {@code save()} fires
 * {@link GalliumConfigIO#save()} so changes are persisted to disk.
 * <p>
 * On 1.21.11+/26.1 this class is a stub — those versions use
 * {@link GalliumSodiumConfig} through the 0.8 public API.
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
//$$     @SuppressWarnings("unchecked")
//$$     public static List<Object> buildReflective() {
//$$         OptionGroup.Builder globalBuilder = OptionGroup.createBuilder();
//$$         OptionGroup.Builder targetsBuilder = OptionGroup.createBuilder();
//$$         for (Toggle t : Toggle.values()) {
//$$             if (t.group() == Group.GLOBAL) {
//$$                 globalBuilder.add(toOption(t));
//$$             } else if (t.group() == Group.RENDER_TARGET) {
//$$                 targetsBuilder.add(toOption(t));
//$$             }
//$$         }
//$$         return List.of(new OptionPage(
//$$             Component.translatable("gallium.options.page_title"),
//$$             ImmutableList.of(globalBuilder.build(), targetsBuilder.build())));
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
//#elseif MC==1_21_01
public final class GalliumSodiumLegacyPage {

    private static final Logger LOGGER = LoggerFactory.getLogger("gallium-legacy-page");
    private static final Object SENTINEL = new Object();

    // Cached reflectively-loaded Sodium 0.6 classes
    private static boolean classesLoaded;
    private static Class<?> optionInterfaceClass;
    private static Class<?> optionStorageClass;
    private static Class<?> optionGroupClass;
    private static Class<?> optionImplClass;
    private static Class<?> optionPageClass;
    private static Class<?> tickBoxControlClass;
    private static Class<?> immutableListClass;
    private static Object storageProxy;

    /**
     * Builds pages via reflection against the Sodium 0.6/0.7 internal API.
     * Called from the ScreenEvents listener in Gallium.onInitializeClient()
     * on 1.21.1 when Sodium 0.6 is detected.
     */
    @SuppressWarnings("unchecked")
    public static List<Object> buildReflective() throws Exception {
        ensureClassesLoaded();

        // Build two OptionGroups (global + render-target) under a single OptionPage.
        // Legacy Sodium 0.6/0.7 OptionGroup has no setName, so we use one named page
        // instead of two unnamed pages — cleaner tab bar for the user.
        Method createBuilder = optionGroupClass.getMethod("createBuilder");
        Method addMethod = null;
        Method buildMethod = null;

        Object globalBuilder = createBuilder.invoke(null);
        Object targetsBuilder = createBuilder.invoke(null);
        for (Toggle t : Toggle.values()) {
            Object b = t.group() == Group.GLOBAL ? globalBuilder : targetsBuilder;
            if (addMethod == null) addMethod = b.getClass().getMethod("add", optionInterfaceClass);
            addMethod.invoke(b, toOptionReflective(t));
        }

        if (buildMethod == null) buildMethod = globalBuilder.getClass().getMethod("build");
        Object globalGroup = buildMethod.invoke(globalBuilder);
        Object targetsGroup = buildMethod.invoke(targetsBuilder);

        // ImmutableList.of(globalGroup, targetsGroup)
        Object groupList = immutableListClass.getMethod("of", Object.class, Object.class)
                .invoke(null, globalGroup, targetsGroup);

        // new OptionPage(Component.translatable("gallium.options.page_title"), groupList)
        Constructor<?> ctor = optionPageClass.getConstructor(Component.class, immutableListClass);
        return List.of(ctor.newInstance(
                Component.translatable("gallium.options.page_title"), groupList));
    }

    private static Object toOptionReflective(Toggle toggle) throws Exception {
        ensureClassesLoaded();

        // OptionImpl.createBuilder(Class<T>, OptionStorage<T>)
        Method createBuilder = optionImplClass.getMethod("createBuilder", Class.class, optionStorageClass);
        Object builder = createBuilder.invoke(null, boolean.class, storageProxy);

        // .setName(Component)
        builder.getClass().getMethod("setName", Component.class)
                .invoke(builder, Component.translatable("gallium.options." + toggle.sodiumId()));

        // .setTooltip(Component)
        builder.getClass().getMethod("setTooltip", Component.class)
                .invoke(builder, Component.translatable("gallium.options." + toggle.sodiumId() + ".tooltip"));

        // .setControl(Function<OptionImpl<Object,Boolean>, TickBoxControl>)
        // TickBoxControl's constructor takes Option (the interface), not OptionImpl
        builder.getClass().getMethod("setControl", java.util.function.Function.class)
                .invoke(builder, (java.util.function.Function<Object, Object>) option -> {
            try {
                return tickBoxControlClass.getConstructor(optionInterfaceClass).newInstance(option);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        // .setBinding(BiConsumer<OptionStorage<Object>, Boolean>,
        //              Function<OptionStorage<Object>, Boolean>)
        builder.getClass().getMethod("setBinding",
                        java.util.function.BiConsumer.class,
                        java.util.function.Function.class)
                .invoke(builder,
                        (java.util.function.BiConsumer<Object, Boolean>)
                                (opts, v) -> toggle.set(v),
                        (java.util.function.Function<Object, Boolean>)
                                opts -> toggle.get());

        // .build() → OptionImpl<Object, Boolean>
        return builder.getClass().getMethod("build").invoke(builder);
    }

    private static void ensureClassesLoaded() throws ClassNotFoundException {
        if (classesLoaded) return;


        optionInterfaceClass = Class.forName(
                "net.caffeinemc.mods.sodium.client.gui.options.Option");
        optionStorageClass = Class.forName(
                "net.caffeinemc.mods.sodium.client.gui.options.storage.OptionStorage");
        optionGroupClass = Class.forName(
                "net.caffeinemc.mods.sodium.client.gui.options.OptionGroup");
        optionImplClass = Class.forName(
                "net.caffeinemc.mods.sodium.client.gui.options.OptionImpl");
        optionPageClass = Class.forName(
                "net.caffeinemc.mods.sodium.client.gui.options.OptionPage");
        tickBoxControlClass = Class.forName(
                "net.caffeinemc.mods.sodium.client.gui.options.control.TickBoxControl");
        immutableListClass = Class.forName(
                "com.google.common.collect.ImmutableList");

        // Build an OptionStorage<Object> proxy whose getData() returns a
        // sentinel and save() flushes GalliumConfigIO.
        storageProxy = Proxy.newProxyInstance(
                optionStorageClass.getClassLoader(),
                new Class<?>[] { optionStorageClass },
                new InvocationHandler() {
                    @Override
                    public Object invoke(Object proxy, Method method, Object[] args) {
                        switch (method.getName()) {
                            case "getData":
                                return SENTINEL;
                            case "save":
                                GalliumConfigIO.save();
                                return null;
                            case "hashCode":
                                return System.identityHashCode(proxy);
                            case "equals":
                                return proxy == args[0];
                            case "toString":
                                return "GalliumOptionStorageProxy";
                            default:
                                LOGGER.warn("Unhandled OptionStorage method: {}", method.getName());
                                return null;
                        }
                    }
                });
        classesLoaded = true;
    }

    private GalliumSodiumLegacyPage() {}
}
//#else
//$$ public final class GalliumSodiumLegacyPage {
//$$     private GalliumSodiumLegacyPage() {}
//$$ }
//#endif
