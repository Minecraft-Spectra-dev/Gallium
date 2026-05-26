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
 * {@link MethodHandle} invocations against the underlying primitive fields. Gracefully no-ops
 * when Iris is absent or its internals change shape.
 * <p>
 * Per-field availability is tracked separately, so partial reflection failures (e.g. {@code bypass}
 * found but {@code renderWithExtendedVertexFormat} renamed) still produce consistent
 * snapshot/restore: only fields actually accessible are touched.
 */
public final class IrisCompat {

    private static final boolean IRIS_LOADED;

    private static final BooleanSupplier IS_SHADER_ACTIVE;
    private static final BooleanSupplier IS_SHADOW_PASS;

    private static final MethodHandle BYPASS_GETTER;
    private static final MethodHandle BYPASS_SETTER;
    private static final MethodHandle EXTENDED_GETTER;
    private static final MethodHandle EXTENDED_SETTER;
    private static final boolean BYPASS_FIELD_OK;
    private static final boolean EXTENDED_FIELD_OK;
    private static final boolean BYPASS_AVAILABLE;

    public record BypassSnapshot(boolean bypass, boolean renderWithExtended,
                                  boolean bypassValid, boolean extendedValid) {
        public static final BypassSnapshot NONE = new BypassSnapshot(false, false, false, false);
        public boolean valid() { return bypassValid || extendedValid; }
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

            Class<?> immediateState = null;
            try {
                immediateState = Class.forName("net.irisshaders.iris.vertices.ImmediateState");
            } catch (Throwable t) {
                Gallium.LOGGER.debug("Iris detected but ImmediateState class missing: {}", t.toString());
            }

            if (immediateState != null) {
                MethodHandles.Lookup lookup = MethodHandles.lookup();
                try {
                    Field bypass = immediateState.getField("bypass");
                    bypassGet = lookup.unreflectGetter(bypass);
                    bypassSet = lookup.unreflectSetter(bypass);
                } catch (Throwable t) {
                    Gallium.LOGGER.debug("Iris ImmediateState.bypass reflection failed: {}", t.toString());
                }
                try {
                    Field extended = immediateState.getField("renderWithExtendedVertexFormat");
                    extendedGet = lookup.unreflectGetter(extended);
                    extendedSet = lookup.unreflectSetter(extended);
                } catch (Throwable t) {
                    Gallium.LOGGER.debug("Iris ImmediateState.renderWithExtendedVertexFormat reflection failed: {}", t.toString());
                }
            }
        }

        IS_SHADER_ACTIVE = shaderActive;
        IS_SHADOW_PASS = shadowPass;
        BYPASS_GETTER = bypassGet;
        BYPASS_SETTER = bypassSet;
        EXTENDED_GETTER = extendedGet;
        EXTENDED_SETTER = extendedSet;
        BYPASS_FIELD_OK = bypassGet != null && bypassSet != null;
        EXTENDED_FIELD_OK = extendedGet != null && extendedSet != null;
        BYPASS_AVAILABLE = BYPASS_FIELD_OK || EXTENDED_FIELD_OK;
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
        boolean bypassValid = false, extendedValid = false;
        boolean oldBypass = false, oldExtended = false;
        try {
            if (BYPASS_FIELD_OK) {
                oldBypass = (boolean) BYPASS_GETTER.invokeExact();
                BYPASS_SETTER.invokeExact(value);
                bypassValid = true;
            }
            if (EXTENDED_FIELD_OK) {
                oldExtended = (boolean) EXTENDED_GETTER.invokeExact();
                if (value) EXTENDED_SETTER.invokeExact(false);
                extendedValid = true;
            }
            return new BypassSnapshot(oldBypass, oldExtended, bypassValid, extendedValid);
        } catch (Throwable t) {
            return new BypassSnapshot(oldBypass, oldExtended, bypassValid, extendedValid);
        }
    }

    public static void restoreBypass(BypassSnapshot snapshot) {
        if (snapshot == null || !snapshot.valid()) return;
        try {
            if (snapshot.bypassValid() && BYPASS_FIELD_OK) BYPASS_SETTER.invokeExact(snapshot.bypass());
            if (snapshot.extendedValid() && EXTENDED_FIELD_OK) EXTENDED_SETTER.invokeExact(snapshot.renderWithExtended());
        } catch (Throwable ignored) {}
    }
}
