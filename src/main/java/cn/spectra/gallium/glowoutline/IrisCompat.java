package cn.spectra.gallium.glowoutline;

import cn.spectra.gallium.Gallium;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.function.BooleanSupplier;
import net.fabricmc.loader.api.FabricLoader;

/**
 * Iris compatibility shim using cached MethodHandles for hot-path queries.
 * <p>
 * Iris reflection lookup happens once at static init; per-frame calls are reduced to direct
 * {@link MethodHandle} invocations and primitive field access. Gracefully no-ops when Iris
 * is absent or its internals change shape.
 */
public final class IrisCompat {

    private static final boolean IRIS_LOADED;

    private static final BooleanSupplier IS_SHADER_ACTIVE;
    private static final BooleanSupplier IS_SHADOW_PASS;

    private static final MethodHandle BYPASS_GETTER;
    private static final MethodHandle BYPASS_SETTER;
    private static final MethodHandle EXTENDED_GETTER;
    private static final MethodHandle EXTENDED_SETTER;
    private static final boolean BYPASS_AVAILABLE;

    public record BypassSnapshot(boolean bypass, boolean renderWithExtended, boolean valid) {
        public static final BypassSnapshot NONE = new BypassSnapshot(false, false, false);
    }

    static {
        IRIS_LOADED = FabricLoader.getInstance().isModLoaded("iris");

        BooleanSupplier shaderActive = () -> false;
        BooleanSupplier shadowPass = () -> false;
        MethodHandle bypassGet = null, bypassSet = null, extendedGet = null, extendedSet = null;

        if (IRIS_LOADED) {
            try {
                Class<?> irisApi = Class.forName("net.irisshaders.iris.api.v0.IrisApi");
                Method getInstance = irisApi.getMethod("getInstance");
                Object instance = getInstance.invoke(null);

                MethodHandles.Lookup lookup = MethodHandles.lookup();
                MethodHandle isShaderPackInUse = lookup.unreflect(irisApi.getMethod("isShaderPackInUse")).bindTo(instance);
                MethodHandle isRenderingShadowPass = lookup.unreflect(irisApi.getMethod("isRenderingShadowPass")).bindTo(instance);

                shaderActive = () -> {
                    try { return (boolean) isShaderPackInUse.invokeExact(); }
                    catch (Throwable t) { return false; }
                };
                shadowPass = () -> {
                    try { return (boolean) isRenderingShadowPass.invokeExact(); }
                    catch (Throwable t) { return false; }
                };
            } catch (Throwable t) {
                Gallium.LOGGER.debug("Iris detected but IrisApi reflection failed: {}", t.toString());
            }

            try {
                Class<?> immediateState = Class.forName("net.irisshaders.iris.vertices.ImmediateState");
                Field bypass = immediateState.getField("bypass");
                Field extended = immediateState.getField("renderWithExtendedVertexFormat");
                MethodHandles.Lookup lookup = MethodHandles.lookup();
                bypassGet = lookup.unreflectGetter(bypass);
                bypassSet = lookup.unreflectSetter(bypass);
                extendedGet = lookup.unreflectGetter(extended);
                extendedSet = lookup.unreflectSetter(extended);
            } catch (Throwable t) {
                Gallium.LOGGER.debug("Iris detected but ImmediateState reflection failed: {}", t.toString());
            }
        }

        IS_SHADER_ACTIVE = shaderActive;
        IS_SHADOW_PASS = shadowPass;
        BYPASS_GETTER = bypassGet;
        BYPASS_SETTER = bypassSet;
        EXTENDED_GETTER = extendedGet;
        EXTENDED_SETTER = extendedSet;
        BYPASS_AVAILABLE = bypassGet != null || extendedGet != null;
    }

    private IrisCompat() {}

    public static boolean isShaderActive() {
        return IS_SHADER_ACTIVE.getAsBoolean();
    }

    public static boolean isShadowPass() {
        return IS_SHADOW_PASS.getAsBoolean();
    }

    public static BypassSnapshot setBypass(boolean value) {
        if (!BYPASS_AVAILABLE) return BypassSnapshot.NONE;
        try {
            boolean oldBypass = BYPASS_GETTER != null && (boolean) BYPASS_GETTER.invokeExact();
            boolean oldExtended = EXTENDED_GETTER != null && (boolean) EXTENDED_GETTER.invokeExact();
            if (BYPASS_SETTER != null) BYPASS_SETTER.invokeExact(value);
            if (EXTENDED_SETTER != null && value) EXTENDED_SETTER.invokeExact(false);
            return new BypassSnapshot(oldBypass, oldExtended, true);
        } catch (Throwable t) {
            return BypassSnapshot.NONE;
        }
    }

    public static void restoreBypass(BypassSnapshot snapshot) {
        if (!BYPASS_AVAILABLE || snapshot == null || !snapshot.valid()) return;
        try {
            if (BYPASS_SETTER != null) BYPASS_SETTER.invokeExact(snapshot.bypass());
            if (EXTENDED_SETTER != null) EXTENDED_SETTER.invokeExact(snapshot.renderWithExtended());
        } catch (Throwable ignored) {}
    }
}
