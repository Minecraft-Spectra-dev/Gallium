package cn.spectra.gallium.glowoutline;

import cn.spectra.gallium.Gallium;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.function.BooleanSupplier;
import java.util.function.IntSupplier;
//#if MC>=1_26_02
//$$ import com.mojang.blaze3d.systems.RenderSystem;
//#endif
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
    private static final IntSupplier FRAME_COUNTER;

    private static final MethodHandle BYPASS_GETTER;
    private static final MethodHandle BYPASS_SETTER;
    private static final MethodHandle EXTENDED_GETTER;
    private static final MethodHandle EXTENDED_SETTER;
    private static final boolean BYPASS_FIELD_OK;
    private static final boolean EXTENDED_FIELD_OK;
    private static final boolean BYPASS_AVAILABLE;

    /** Iris's {@code ImmediateState.skipExtension} ThreadLocal, captured once (the field is
     *  {@code final}). When set {@code true} around a {@code new BufferBuilder(...)}, Iris's
     *  {@code MixinBufferBuilder.iris$extendFormat} leaves the vertex format vanilla instead of
     *  promoting {@code NEW_ENTITY → IrisVertexFormats.ENTITY}. The pre-1.21.9 capture path relies
     *  on this so its tee'd capture buffer holds vanilla-format vertices that the bypass (vanilla
     *  pipeline) mask render can actually draw. {@code null} when Iris is absent or the field
     *  moved. */
    private static final ThreadLocal<Boolean> SKIP_EXTENSION;

    public record BypassSnapshot(boolean bypass, boolean renderWithExtended,
                                  boolean bypassValid, boolean extendedValid,
                                  boolean shaderBypassEnabled) {
        public static final BypassSnapshot NONE =
                new BypassSnapshot(false, false, false, false, false);
        public boolean valid() { return bypassValid || extendedValid; }
    }

    static {
        IRIS_LOADED = FabricLoader.getInstance().isModLoaded("iris");

        BooleanSupplier shaderActive = () -> false;
        BooleanSupplier shadowPass = () -> false;
        IntSupplier frameCounter = () -> -1;
        MethodHandle bypassGet = null, bypassSet = null, extendedGet = null, extendedSet = null;
        ThreadLocal<Boolean> skipExtension = null;

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
                Class<?> systemTimeUniforms = Class.forName(
                        "net.irisshaders.iris.uniforms.SystemTimeUniforms");
                Field counterField = systemTimeUniforms.getDeclaredField("COUNTER");
                counterField.setAccessible(true);
                Object counter = counterField.get(null);
                Method getAsInt = counter.getClass().getDeclaredMethod("getAsInt");
                getAsInt.setAccessible(true);
                MethodHandle counterGetter = MethodHandles.lookup()
                        .unreflect(getAsInt)
                        .bindTo(counter);
                frameCounter = () -> {
                    try {
                        return (int) counterGetter.invokeExact();
                    } catch (Throwable t) {
                        return -1;
                    }
                };
            } catch (Throwable t) {
                Gallium.LOGGER.debug(
                        "Iris frame-counter reflection failed; exact temporal replay disabled: {}",
                        t.toString());
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
                try {
                    // skipExtension is a `public static final ThreadLocal<Boolean>`. Read the
                    // instance once; toggling it later is a plain ThreadLocal.set, no reflection
                    // on the hot path.
                    Field skip = immediateState.getField("skipExtension");
                    Object value = skip.get(null);
                    if (value instanceof ThreadLocal<?> tl) {
                        skipExtension = castThreadLocal(tl);
                    }
                } catch (Throwable t) {
                    Gallium.LOGGER.debug("Iris ImmediateState.skipExtension reflection failed: {}", t.toString());
                }
            }
        }

        IS_SHADER_ACTIVE = shaderActive;
        IS_SHADOW_PASS = shadowPass;
        FRAME_COUNTER = frameCounter;
        BYPASS_GETTER = bypassGet;
        BYPASS_SETTER = bypassSet;
        EXTENDED_GETTER = extendedGet;
        EXTENDED_SETTER = extendedSet;
        BYPASS_FIELD_OK = bypassGet != null && bypassSet != null;
        EXTENDED_FIELD_OK = extendedGet != null && extendedSet != null;
        BYPASS_AVAILABLE = BYPASS_FIELD_OK || EXTENDED_FIELD_OK;
        SKIP_EXTENSION = skipExtension;
    }

    private IrisCompat() {}

    public static boolean isShaderActive() {
        return IS_SHADER_ACTIVE.getAsBoolean();
    }

    /**
     * Whether Iris has replaced Minecraft 26.2's native reverse-Z convention with its OpenGL
     * forward-Z compatibility convention for the active shader pack.
     *
     * <p>Iris's UndoReverseZ mixins are disabled by its Vulkan plugin, so shader-pack activity
     * alone is not sufficient. {@code isZZeroToOne()} distinguishes that backend from the OpenGL
     * path where Iris restores the projection, comparison operators, and clear values together.
     */
    public static boolean usesForwardDepthCompatibility() {
        //#if MC>=1_26_02
        //$$ return isShaderActive()
        //$$         && !RenderSystem.getDevice().getDeviceInfo().isZZeroToOne();
        //#else
        return false;
        //#endif
    }

    public static boolean isShadowPass() {
        return IS_SHADOW_PASS.getAsBoolean();
    }

    /**
     * Effective internal-resolution scale applied by the active shader pack to its world/hand
     * passes (e.g. Kappa/Nostalgia {@code VertexDownscaling}, iterationRP {@code fsrRenderScale}).
     * <p>
     * Resolved from a standard Super Resolution definition when present, otherwise from the
     * optional per-pack {@code gallium.json} override; see {@link ShaderPackHint}. Returns
     * {@code 1.0f} when no pack is in use or no safe runtime scale can be resolved.
     */
    public static float getShaderInternalScale() {
        if (!isShaderActive()) return 1.0f;
        return ShaderPackHint.getInternalScale();
    }

    /**
     * Returns the active pack's declared per-frame projection transform. The transform reports
     * {@code exactTemporalJitter=false} when Iris's frame counter or the pack declaration is not
     * available, allowing callers to retain their generic depth-tolerance fallback.
     */
    public static ShaderPackHint.ProjectionTransform getShaderProjectionTransform(
            int viewWidth, int viewHeight) {
        if (!isShaderActive()) return ShaderPackHint.ProjectionTransform.IDENTITY;
        return ShaderPackHint.getProjectionTransform(
                FRAME_COUNTER.getAsInt(), viewWidth, viewHeight);
    }

    /** True when this frame's selected transform is supplied by an active external SR runtime. */
    public static boolean isCurrentProjectionFromActiveSr() {
        return isShaderActive() && ShaderPackHint.isCurrentProjectionFromActiveSr();
    }

    /** True when external SR is live, even if gallium.json overrides its projection transform. */
    public static boolean isActiveSrRuntime() {
        return isShaderActive() && ShaderPackHint.isActiveSrRuntime();
    }

    /**
     * Whether the Iris shader-selection bypass field was found. Exact replay never clears its
     * mask depth based on the extended-vertex-format field alone: that field fixes vertex layout,
     * but does not guarantee that Iris leaves the vanilla replay pipeline selected.
     */
    public static boolean isShaderBypassAvailable() {
        return BYPASS_FIELD_OK;
    }

    public static BypassSnapshot setBypass(boolean value) {
        if (!BYPASS_AVAILABLE) return BypassSnapshot.NONE;
        boolean bypassValid = false, extendedValid = false, shaderBypassEnabled = false;
        boolean oldBypass = false, oldExtended = false;
        try {
            if (BYPASS_FIELD_OK) {
                oldBypass = (boolean) BYPASS_GETTER.invokeExact();
                // Mark valid before mutating: if the SETTER below throws partway, we still
                // owe the caller a restore back to the read-out value. Without this, a
                // partial mutation would leak the modified Iris state until the next reload.
                bypassValid = true;
                BYPASS_SETTER.invokeExact(value);
                shaderBypassEnabled = value;
            }
            if (EXTENDED_FIELD_OK) {
                oldExtended = (boolean) EXTENDED_GETTER.invokeExact();
                extendedValid = true;
                if (value) EXTENDED_SETTER.invokeExact(false);
            }
            return new BypassSnapshot(oldBypass, oldExtended, bypassValid, extendedValid,
                    shaderBypassEnabled);
        } catch (Throwable t) {
            return new BypassSnapshot(oldBypass, oldExtended, bypassValid, extendedValid,
                    shaderBypassEnabled);
        }
    }

    public static void restoreBypass(BypassSnapshot snapshot) {
        if (snapshot == null || !snapshot.valid()) return;
        try {
            if (snapshot.bypassValid() && BYPASS_FIELD_OK) BYPASS_SETTER.invokeExact(snapshot.bypass());
            if (snapshot.extendedValid() && EXTENDED_FIELD_OK) EXTENDED_SETTER.invokeExact(snapshot.renderWithExtended());
        } catch (Throwable ignored) {}
    }

    /**
     * Suppresses Iris's per-{@code BufferBuilder} vertex-format extension for the current thread.
     * Wrap a {@code new BufferBuilder(...)} construction in a {@code true}/{@code false} pair so
     * the resulting buffer keeps the vanilla format Iris would otherwise promote to its extended
     * entity/terrain/glyph layouts. No-op when Iris is absent. Returns the previous value so the
     * caller can restore it (always pass the returned value back to {@link #setSkipExtension}).
     */
    public static boolean setSkipExtension(boolean value) {
        if (SKIP_EXTENSION == null) return false;
        boolean old = Boolean.TRUE.equals(SKIP_EXTENSION.get());
        if (value) {
            SKIP_EXTENSION.set(true);
        } else {
            // Restore to the unset (null) state rather than storing explicit-false, so Iris
            // logic that distinguishes "not set" from "explicitly false" behaves correctly.
            SKIP_EXTENSION.remove();
        }
        return old;
    }

    @SuppressWarnings("unchecked")
    private static ThreadLocal<Boolean> castThreadLocal(ThreadLocal<?> tl) {
        return (ThreadLocal<Boolean>) tl;
    }
}
