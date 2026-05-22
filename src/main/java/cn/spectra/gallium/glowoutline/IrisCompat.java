package cn.spectra.gallium.glowoutline;

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
    private static boolean savedRenderWithExtended;

    static {
        IRIS_LOADED = FabricLoader.getInstance().isModLoaded("iris");
        if (IRIS_LOADED) {
            try {
                Class<?> irisApi = Class.forName("net.irisshaders.iris.api.v0.IrisApi");
                getInstanceMethod = irisApi.getMethod("getInstance");
                isShaderPackInUseMethod = irisApi.getMethod("isShaderPackInUse");
                isRenderingShadowPassMethod = irisApi.getMethod("isRenderingShadowPass");
            } catch (Exception ignored) {}
            try {
                Class<?> immediateState = Class.forName("net.irisshaders.iris.vertices.ImmediateState");
                bypassField = immediateState.getField("bypass");
                renderWithExtendedField = immediateState.getField("renderWithExtendedVertexFormat");
            } catch (Exception ignored) {}
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

    public static boolean setBypass(boolean value) {
        if (!IRIS_LOADED) return false;
        try {
            boolean oldBypass = false;
            if (bypassField != null) {
                oldBypass = bypassField.getBoolean(null);
                bypassField.setBoolean(null, value);
            }
            if (renderWithExtendedField != null && value) {
                savedRenderWithExtended = renderWithExtendedField.getBoolean(null);
                renderWithExtendedField.setBoolean(null, false);
            }
            return oldBypass;
        } catch (Exception e) {
            return false;
        }
    }

    public static void restoreBypass(boolean oldBypass) {
        if (!IRIS_LOADED) return;
        try {
            if (bypassField != null) {
                bypassField.setBoolean(null, oldBypass);
            }
            if (renderWithExtendedField != null) {
                renderWithExtendedField.setBoolean(null, savedRenderWithExtended);
            }
        } catch (Exception ignored) {}
    }
}
