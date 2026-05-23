package cn.spectra.gallium.glowoutline;

import cn.spectra.gallium.Gallium;
import net.fabricmc.loader.api.FabricLoader;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

public final class IrisCompat {

    private static final boolean IRIS_LOADED;
    private static Method getInstanceMethod;
    private static Method isShaderPackInUseMethod;
    private static Method isRenderingShadowPassMethod;
    private static Field bypassField;
    private static Field renderWithExtendedField;

    public record BypassSnapshot(boolean bypass, boolean renderWithExtended, boolean valid) {
        public static final BypassSnapshot NONE = new BypassSnapshot(false, false, false);
    }

    static {
        IRIS_LOADED = FabricLoader.getInstance().isModLoaded("iris");
        if (IRIS_LOADED) {
            try {
                Class<?> irisApi = Class.forName("net.irisshaders.iris.api.v0.IrisApi");
                getInstanceMethod = irisApi.getMethod("getInstance");
                isShaderPackInUseMethod = irisApi.getMethod("isShaderPackInUse");
                isRenderingShadowPassMethod = irisApi.getMethod("isRenderingShadowPass");
            } catch (Exception e) {
                Gallium.LOGGER.warn("Iris detected but IrisApi reflection failed: {}", e.toString());
            }
            try {
                Class<?> immediateState = Class.forName("net.irisshaders.iris.vertices.ImmediateState");
                bypassField = immediateState.getField("bypass");
                renderWithExtendedField = immediateState.getField("renderWithExtendedVertexFormat");
            } catch (Exception e) {
                Gallium.LOGGER.warn("Iris detected but ImmediateState reflection failed: {}", e.toString());
            }
        }
    }

    public static boolean isShaderActive() {
        if (!IRIS_LOADED || isShaderPackInUseMethod == null) return false;
        try {
            Object instance = getInstanceMethod.invoke(null);
            return (boolean) isShaderPackInUseMethod.invoke(instance);
        } catch (Exception e) {
            return false;
        }
    }

    public static boolean isShadowPass() {
        if (!IRIS_LOADED || isRenderingShadowPassMethod == null) return false;
        try {
            Object instance = getInstanceMethod.invoke(null);
            return (boolean) isRenderingShadowPassMethod.invoke(instance);
        } catch (Exception e) {
            return false;
        }
    }

    public static BypassSnapshot setBypass(boolean value) {
        if (!IRIS_LOADED || (bypassField == null && renderWithExtendedField == null)) return BypassSnapshot.NONE;
        try {
            boolean oldBypass = bypassField != null ? bypassField.getBoolean(null) : false;
            boolean oldExtended = renderWithExtendedField != null ? renderWithExtendedField.getBoolean(null) : false;
            if (bypassField != null) bypassField.setBoolean(null, value);
            if (renderWithExtendedField != null && value) renderWithExtendedField.setBoolean(null, false);
            return new BypassSnapshot(oldBypass, oldExtended, true);
        } catch (Exception e) {
            return BypassSnapshot.NONE;
        }
    }

    public static void restoreBypass(BypassSnapshot snapshot) {
        if (!IRIS_LOADED || snapshot == null || !snapshot.valid()) return;
        try {
            if (bypassField != null) bypassField.setBoolean(null, snapshot.bypass());
            if (renderWithExtendedField != null) renderWithExtendedField.setBoolean(null, snapshot.renderWithExtended());
        } catch (Exception ignored) {}
    }
}
