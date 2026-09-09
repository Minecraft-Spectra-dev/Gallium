package cn.spectra.gallium.glowoutline;

import cn.spectra.gallium.Gallium;
import cn.spectra.gallium.glowoutline.capture.GlowCaptureManager;
import cn.spectra.gallium.glowoutline.shader.GlowComposite;
import com.mojang.blaze3d.pipeline.RenderTarget;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;

import java.lang.reflect.Method;
import java.util.function.Consumer;

/**
 * Optional bridge for IReallyWantToSleep/Super Resolution's shader-interface and hack modes.
 *
 * <p>The dependency deliberately stays reflective: Gallium must load unchanged when SR is not
 * installed, and the accompanying {@code @Pseudo} mixin is the only code that targets an SR
 * implementation class.  The bridge owns only per-frame scheduling.  Captured geometry and GPU
 * resources remain owned by {@link GlowCaptureManager}.</p>
 */
public final class SuperResolutionCompat {
    private static final String MOD_ID = "super_resolution";
    private static final String CONFIG =
            "io.homo.superresolution.common.config.SuperResolutionConfig";
    private static final String API = "io.homo.superresolution.api.SuperResolutionAPI";
    private static final String WORK_MODES =
            "io.homo.superresolution.common.workmode.SRWorkModeManager";
    private static final String VULKAN_PRESENTATION =
            "io.homo.superresolution.common.presentation.vulkan.VulkanPresentationFeature";

    private static boolean bridgeInitialized;
    private static boolean bridgeAvailable;
    private static boolean shaderCompatBridgeAvailable;
    private static boolean hackBridgeAvailable;
    private static boolean bridgeWarningLogged;
    private static boolean shaderCompatBridgeWarningLogged;
    private static boolean hackBridgeWarningLogged;
    private static Method isEnableUpscale;
    private static Method getCaptureMode;
    private static Method getScreenWidth;
    private static Method getScreenHeight;
    private static Method getRenderWidth;
    private static Method getRenderHeight;
    private static Method getCurrentAlgorithmDescription;
    private static Method isCurrentWorkMode;
    private static Method getRenderHandler;
    private static Method isVulkanPresentationRequested;

    private static boolean compatFrame;
    private static boolean upscaleFinished;
    private static volatile boolean dispatchInProgress;
    private static volatile boolean dispatchObservedThisFrame;
    private static volatile boolean dispatchFinishedThisFrame;
    private static volatile boolean dispatchListenersAvailable;
    private static volatile int eventRenderWidth;
    private static volatile int eventRenderHeight;
    private static volatile int eventScreenWidth;
    private static volatile int eventScreenHeight;
    private static volatile int eventOutputWidth;
    private static volatile int eventOutputHeight;
    private static volatile String eventAlgorithm = "";
    private static volatile long frameEpoch;
    private static volatile long dispatchEpoch = -1L;
    private static volatile Object dispatchAlgorithmIdentity;
    private static boolean dispatchEventWarningLogged;
    private static boolean shaderCompatDisplayReported;
    /** The marker mixin only identifies a compatible handler class.  These per-frame flags prove
     * that its optional {@code require=0} injection points still matched the installed SR ABI. */
    private static boolean hackWorldPrepareHookHit;
    private static boolean hackWorldPrepareReady;
    private static boolean hackUpscaleFinishHookHit;
    private static boolean hackHandHookHit;
    /**
     * Mainline builds deliberately defer world composition to the final pre-HUD call point from
     * their very first frame.  These flags turn a missing optional injector into a one-frame
     * probe: the next client frame logs once and permanently restores the legacy renderLevel TAIL
     * path instead of silently dropping every later outline.
     */
    private static boolean worldFrameAwaitingFinalHook;
    private static boolean finalHookObservedThisFrame;
    private static boolean finalHookUnavailable;
    private static boolean finalHookFallbackWarningLogged;
    private static boolean hackHookFallbackWarningLogged;
    private static boolean clientRenderFrameSeen;
    private static volatile boolean renderSystemFrameCleanupObserved = true;
    private static boolean vulkanCleanupReported;
    private static boolean vulkanCleanupProbeWarningLogged;
    // Strong references: the reflected event bus stores Consumers, and some implementations use
    // weak listener wrappers internally.
    private static Consumer<Object> dispatchStartListener;
    private static Consumer<Object> dispatchFinishListener;

    private SuperResolutionCompat() {}

    /** Reset scheduling flags together with Gallium's capture pool at GameRenderer.renderLevel HEAD. */
    public static void beginFrame() {
        if (usesDeferredFinalHookBuild() && !finalHookUnavailable) {
            worldFrameAwaitingFinalHook = true;
        }
        // Shader-compat dispatch events occur inside renderLevel. Initialize at HEAD so the first
        // world frame is observed; waiting for the hack-mode TAIL query would miss that dispatch.
        initializeBridge();
        frameEpoch++;
        compatFrame = false;
        upscaleFinished = false;
        hackWorldPrepareHookHit = false;
        hackWorldPrepareReady = false;
        hackUpscaleFinishHookHit = false;
        hackHandHookHit = false;
        dispatchInProgress = false;
        dispatchObservedThisFrame = false;
        dispatchFinishedThisFrame = false;
        eventRenderWidth = 0;
        eventRenderHeight = 0;
        eventScreenWidth = 0;
        eventScreenHeight = 0;
        eventOutputWidth = 0;
        eventOutputHeight = 0;
        eventAlgorithm = "";
        dispatchEpoch = -1L;
        dispatchAlgorithmIdentity = null;
        if (bridgeAvailable) refreshRuntimeSnapshot();
    }

    /** Runs once per GameRenderer frame, including title/loading/GUI-only frames. */
    public static void beginClientRenderFrame() {
        if (missedDeferredFinalHook(
                usesDeferredFinalHookBuild(), clientRenderFrameSeen,
                worldFrameAwaitingFinalHook, finalHookObservedThisFrame)) {
            finalHookUnavailable = true;
            if (!finalHookFallbackWarningLogged) {
                finalHookFallbackWarningLogged = true;
                Gallium.LOGGER.warn(
                        "Gallium's final SR composite hook did not run; restoring the legacy "
                                + "renderLevel TAIL path for subsequent frames");
            }
        }
        worldFrameAwaitingFinalHook = false;
        finalHookObservedThisFrame = false;
        initializeBridge();
        repairSkippedVulkanPresentationCleanup();
        clientRenderFrameSeen = true;
        renderSystemFrameCleanupObserved = false;
    }

    /** Called from RenderSystem.flipFrame TAIL; a cancelling SR mixin deliberately skips it. */
    public static void onRenderSystemFrameCleanup() {
        renderSystemFrameCleanupObserved = true;
    }

    static boolean shouldRepairVulkanCleanup(
            boolean priorRenderFrameSeen, boolean cleanupObserved,
            boolean presentationRequested, boolean fallbackSupported) {
        return fallbackSupported && priorRenderFrameSeen
                && !cleanupObserved && presentationRequested;
    }

    /**
     * SR 0.9.1's 1.21.11 Vulkan presentation mixin cancels all of flipFrame, not only the OpenGL
     * swap. Repair the skipped global frame cleanup at the next client-render HEAD. Waiting until
     * the next frame keeps every DynamicUniform slice valid for the frame that recorded it.
     */
    private static void repairSkippedVulkanPresentationCleanup() {
        // 26.2 owns a different presentation/cleanup lifecycle. Never infer missing cleanup from
        // flipFrame there: doing so can rotate DynamicUniforms and LevelRenderer resources twice.
        //#if MC>=1_21_11 && MC<1_26_02
        if (!shouldRepairVulkanCleanup(
                clientRenderFrameSeen, renderSystemFrameCleanupObserved,
                vulkanPresentationRequested(), true)) return;
        try {
            com.mojang.blaze3d.systems.RenderSystem.getDynamicUniforms().reset();
            Minecraft minecraft = Minecraft.getInstance();
            if (minecraft.levelRenderer != null) minecraft.levelRenderer.endFrame();
            if (!vulkanCleanupReported) {
                vulkanCleanupReported = true;
                Gallium.LOGGER.info(
                        "Enabled SR Vulkan presentation fallback for skipped Minecraft frame cleanup");
            }
        } catch (Throwable t) {
            if (!vulkanCleanupProbeWarningLogged) {
                vulkanCleanupProbeWarningLogged = true;
                Gallium.LOGGER.warn(
                        "Could not repair SR Vulkan presentation frame cleanup: {}", t.toString());
            }
        }
        //#endif
    }

    private static boolean vulkanPresentationRequested() {
        Method method = isVulkanPresentationRequested;
        if (method == null) return false;
        try {
            return Boolean.TRUE.equals(method.invoke(null));
        } catch (Throwable t) {
            if (!vulkanCleanupProbeWarningLogged) {
                vulkanCleanupProbeWarningLogged = true;
                Gallium.LOGGER.warn(
                        "Could not query SR Vulkan presentation state: {}", t.toString());
            }
            isVulkanPresentationRequested = null;
            return false;
        }
    }

    /**
     * Whether this renderLevel frame completed a real SR shader-interface upscale into the
     * current display extent. This path is independent of the hack RenderTarget handler.
     */
    public static boolean hasCompletedShaderCompatDispatch(int displayWidth, int displayHeight) {
        if (!bridgeAvailable || !shaderCompatBridgeAvailable
                || displayWidth <= 0 || displayHeight <= 0) return false;
        try {
            boolean enabled = Boolean.TRUE.equals(isEnableUpscale.invoke(null));
            boolean shaderCompat = Boolean.TRUE.equals(
                    isCurrentWorkMode.invoke(null, "shader_compat"));
            boolean completed = completedShaderCompatFrameState(
                    enabled, shaderCompat, dispatchListenersAvailable,
                    dispatchObservedThisFrame, dispatchFinishedThisFrame, dispatchInProgress,
                    eventRenderWidth, eventRenderHeight,
                    eventScreenWidth, eventScreenHeight,
                    eventOutputWidth, eventOutputHeight,
                    displayWidth, displayHeight, eventAlgorithm);
            if (completed && !shaderCompatDisplayReported) {
                shaderCompatDisplayReported = true;
                Gallium.LOGGER.info(
                        "Enabled SR shader-compat display-space outline replay "
                                + "({}x{} -> {}x{}, algorithm={})",
                        eventRenderWidth, eventRenderHeight,
                        eventScreenWidth, eventScreenHeight, eventAlgorithm);
            }
            return completed;
        } catch (Throwable t) {
            disableCoreBridge("querying the SR shader-compat frame", t);
            return false;
        }
    }

    static boolean completedShaderCompatFrameState(
            boolean enabled, boolean shaderCompat, boolean listenersAvailable,
            boolean dispatchObserved, boolean dispatchFinished, boolean dispatchInProgress,
            int renderWidth, int renderHeight, int screenWidth, int screenHeight,
            int outputWidth, int outputHeight,
            int displayWidth, int displayHeight, String algorithm) {
        String code = algorithm == null ? "" : algorithm.trim().toLowerCase(java.util.Locale.ROOT);
        return enabled && shaderCompat && listenersAvailable
                && dispatchObserved && dispatchFinished && !dispatchInProgress
                && renderWidth > 0 && renderHeight > 0
                && screenWidth == displayWidth && screenHeight == displayHeight
                && outputWidth == screenWidth && outputHeight == screenHeight
                && renderWidth <= screenWidth && renderHeight <= screenHeight
                && !code.isEmpty() && !code.equals("none");
    }

    /** Whether SR is configured to use target replacement, independent of hook ABI support. */
    public static boolean isHackConfigured() {
        if (!initializeBridge()) return false;
        try {
            return Boolean.TRUE.equals(isEnableUpscale.invoke(null))
                    && Boolean.TRUE.equals(isCurrentWorkMode.invoke(null, "hack"));
        } catch (Throwable t) {
            disableCoreBridge("querying Super Resolution runtime state", t);
            return false;
        }
    }

    /**
     * Whether the configured hack path also exposes every ABI surface needed for exact prepared
     * output-space replay. Marker or capture-mode failure does not make hack configuration
     * disappear; it merely selects the render-call fallback.
     */
    public static boolean isActive() {
        if (!isHackConfigured() || !hackBridgeAvailable) return false;
        return hookedHandlerInstalled() && captureMode() != Mode.UNKNOWN;
    }

    /** Called inside SR immediately before it restores/resizes the world input and dispatches. */
    public static void beforeWorldUpscale(Object handler) {
        if (!isActive()) return;
        hackWorldPrepareHookHit = true;
        refreshRuntimeSnapshot();
        RenderTarget source = scaledTarget(handler);
        if (!hackBridgeAvailable) return;
        if (source == null) source = currentMainTarget();
        if (source == null) return;

        compatFrame = true;
        // Mode B reaches this point before vanilla's normal pre-hand depth hook.  Calling the
        // idempotent snapshot method here covers B while remaining a no-op for A/C.
        GlowCaptureManager.captureSceneDepth(source);
        int width = screenWidth();
        int height = screenHeight();
        if (!hackBridgeAvailable) return;
        GlowCaptureManager.prepareSuperResolutionMasks(
                Minecraft.getInstance(), width, height, false);
        hackWorldPrepareReady = true;
    }

    /** Called from SR's separated-hand handler before CaptureMode C restores the world target. */
    public static void beforeSeparatedHandRestore(Object handler) {
        if (!isActive() || captureMode() != Mode.C) return;
        hackHandHookHit = true;
        refreshRuntimeSnapshot();
        RenderTarget source = handTarget(handler);
        if (!hackBridgeAvailable) return;
        if (source == null) source = currentMainTarget();
        if (source != null) GlowCaptureManager.captureForegroundDepth(source);
        int width = screenWidth();
        int height = screenHeight();
        if (!hackBridgeAvailable) return;
        GlowCaptureManager.prepareSuperResolutionMasks(
                Minecraft.getInstance(), width, height, true);
    }

    /** Called at vanilla renderItemInHand TAIL.  A/B still have their matching source target here. */
    public static void afterInlineHandRender() {
        if (!isActive()) return;
        Mode mode = captureMode();
        if (mode != Mode.A && mode != Mode.B) return;
        hackHandHookHit = true;
        refreshRuntimeSnapshot();
        RenderTarget source = mode == Mode.A ? scaledTarget(currentHandler()) : currentMainTarget();
        if (!hackBridgeAvailable) return;
        if (source == null) source = currentMainTarget();
        if (source != null) GlowCaptureManager.captureForegroundDepth(source);
        int width = screenWidth();
        int height = screenHeight();
        if (!hackBridgeAvailable) return;
        GlowCaptureManager.prepareSuperResolutionMasks(
                Minecraft.getInstance(), width, height, true);
    }

    /** Called at SR handler TAIL, after upscale and CaptureMode-C hand composition have completed. */
    public static void afterWorldUpscale() {
        if (!isActive()) return;
        hackUpscaleFinishHookHit = true;
        upscaleFinished = true;
    }

    /** True while the SR-specific prepare/composite scheduler owns world glow for this frame. */
    public static boolean ownsWorldComposite() {
        return compatFrame && hackWorldPrepareReady
                && hackUpscaleFinishHookHit && upscaleFinished && isActive();
    }

    /**
     * Decide whether renderLevel TAIL may suppress its legacy composite.  Supported mainline
     * builds defer from their first world frame, including when SR is absent: the final hook owns
     * both SR and ordinary legacy composition.  If that optional hook misses, the next client
     * frame permanently restores TAIL.
     */
    public static boolean shouldDeferLegacyCompositeAtTail() {
        return shouldDeferLegacyCompositeAtTail(
                usesDeferredFinalHookBuild(), finalHookUnavailable);
    }

    static boolean shouldDeferLegacyCompositeAtTail(
            boolean deferredFinalHookBuild, boolean finalHookUnavailable) {
        return deferredFinalHookBuild && !finalHookUnavailable;
    }

    static boolean missedDeferredFinalHook(
            boolean deferredFinalHookBuild, boolean priorClientFrameSeen,
            boolean worldFrameAwaitingFinalHook, boolean finalHookObserved) {
        return deferredFinalHookBuild && priorClientFrameSeen
                && worldFrameAwaitingFinalHook && !finalHookObserved;
    }

    /** Only these current SR-mainline render pipelines expose the shared pre-HUD final point. */
    static boolean usesDeferredFinalHookBuild() {
        //#if MC==1_21_11 || MC==1_26_01 || MC==1_26_02
        return true;
        //#else
        //$$ return false;
        //#endif
    }

    /** The invasive RenderTarget/event layer is never enabled on frozen non-mainline builds. */
    static boolean hasMainlineRenderingLayer() {
        //#if MC==1_21_01 || MC==1_21_11 || MC==1_26_01 || MC==1_26_02
        return true;
        //#else
        //$$ return false;
        //#endif
    }

    enum HackCompositeDecision { NOT_HACK, COMPAT, FALLBACK }

    static HackCompositeDecision decideHackComposite(
            boolean hackConfigured, boolean worldPrepareReady,
            boolean upscaleFinishHit, boolean handHookHit,
            boolean handHookRequired, boolean hasUnpreparedCapture) {
        if (!hackConfigured) return HackCompositeDecision.NOT_HACK;
        boolean handReady = !handHookRequired || handHookHit;
        return worldPrepareReady && upscaleFinishHit && handReady && !hasUnpreparedCapture
                ? HackCompositeDecision.COMPAT
                : HackCompositeDecision.FALLBACK;
    }

    static boolean needsHandHook(
            boolean irisShaderActive, boolean firstPersonCamera,
            boolean hasPendingWorldCapture) {
        // Iris renders both hand phases inside renderLevel and the retained scene snapshot already
        // includes them. Vanilla needs a separate hand-depth hook, but only when a first-person
        // hand can actually occlude a pending world outline.
        return !irisShaderActive && firstPersonCamera && hasPendingWorldCapture;
    }

    /**
     * Complete one SR frame at the deterministic final pre-HUD call point. Shader-interface
     * frames replay there after Iris color conversion and vanilla post effects. Configured hack
     * frames use prepared output-space masks when every required hook proved itself, otherwise the
     * legacy fallback. Returns true whenever an SR path consumed the frame so older callers cannot
     * run a second composite.
     */
    public static boolean compositeDisplayFrame() {
        // Mark this before any optional reflection: the scheduling watchdog is about the vanilla
        // call point itself, not whether SR happened to be installed or enabled this frame.
        finalHookObservedThisFrame = true;
        worldFrameAwaitingFinalHook = false;
        Minecraft minecraft = Minecraft.getInstance();
        RenderTarget display = currentMainTarget();
        boolean hackConfigured = isHackConfigured();

        if (!hackConfigured) {
            if (display == null
                    || !hasCompletedShaderCompatDispatch(display.width, display.height)) {
                return false;
            }
            if (GlowComposite.hasAnyValidCapture()) {
                GlowComposite.composite(minecraft, display);
            }
            return true;
        }

        // Hook completeness matters only when there is something to consume.  Do not turn an
        // idle/third-person hack frame into a permanent ABI warning merely because SR skipped a
        // capture callback that Gallium did not need.
        if (display == null || !GlowComposite.hasAnyValidCapture()) return true;

        boolean hasUnpreparedCapture = hasUnpreparedCapture();
        boolean handHookRequired = needsHandHook(
                IrisCompat.isShaderActive(),
                minecraft.options.getCameraType().isFirstPerson(),
                hasPendingWorldCapture());
        HackCompositeDecision decision = decideHackComposite(
                true, isActive() && compatFrame && hackWorldPrepareReady,
                hackUpscaleFinishHookHit && upscaleFinished, hackHandHookHit,
                handHookRequired, hasUnpreparedCapture);

        if (decision == HackCompositeDecision.FALLBACK) {
            warnHackHookFallback(hasUnpreparedCapture, handHookRequired);
            compositeLegacyFallbackAtRenderCallPoint(
                    minecraft, display, handHookRequired);
            return true;
        }

        int width = screenWidth();
        int height = screenHeight();
        if (!hackBridgeAvailable) return true;
        if (display.width != width || display.height != height) {
            return true; // fail closed on a resize/target transition frame
        }
        if (GlowComposite.hasAnyPreparedCapture()) {
            GlowComposite.composite(minecraft, display);
        }
        return true;
    }

    private static boolean hasUnpreparedCapture() {
        for (var state : GlowCaptureManager.getActiveStates()) {
            if (state.capturedThisFrame && !state.compositedThisFrame
                    && !state.maskPreparedThisFrame) return true;
        }
        return false;
    }

    private static boolean hasPendingWorldCapture() {
        for (var state : GlowCaptureManager.getActiveStates()) {
            if (state.capturedThisFrame && !state.compositedThisFrame
                    && !state.firstPerson) return true;
        }
        return false;
    }

    private static boolean hasUnpreparedCaptureAtDifferentSize(int width, int height) {
        var sceneDepth = GlowCaptureManager.getSceneDepthTarget();
        boolean sceneDepthMismatch = sceneDepth != null
                && (sceneDepth.width != width || sceneDepth.height != height);
        for (var state : GlowCaptureManager.getActiveStates()) {
            boolean unprepared = state.capturedThisFrame && !state.compositedThisFrame
                    && !state.maskPreparedThisFrame;
            boolean maskMatches = state.maskTarget != null
                    && state.maskTarget.width == width && state.maskTarget.height == height;
            if (needsOutputSpacePromotion(
                    unprepared, maskMatches, !sceneDepthMismatch)) {
                return true;
            }
        }
        return false;
    }

    static boolean needsOutputSpacePromotion(
            boolean unpreparedCapture, boolean maskMatches,
            boolean sceneDepthMatches) {
        return unpreparedCapture && (!maskMatches || !sceneDepthMatches);
    }

    private static void warnHackHookFallback(
            boolean hasUnpreparedCapture, boolean handHookRequired) {
        if (hackHookFallbackWarningLogged) return;
        hackHookFallbackWarningLogged = true;
        boolean handlerMarked = hackBridgeAvailable && hookedHandlerInstalled();
        Mode mode = captureMode();
        Gallium.LOGGER.warn(
                "SR hack compatibility ABI was incomplete; using the render-call legacy fallback "
                        + "(handlerMarked={}, captureMode={}, worldPrepareHit={}, "
                        + "worldPrepareReady={}, upscaleFinishHit={}, handHookHit={}, "
                        + "handHookRequired={}, unpreparedCapture={}).",
                handlerMarked, mode, hackWorldPrepareHookHit, hackWorldPrepareReady,
                hackUpscaleFinishHookHit, hackHandHookHit, handHookRequired,
                hasUnpreparedCapture);
    }

    /**
     * Best-effort fallback at the deterministic final pre-HUD call point. First try any capture
     * that already matches the restored target.  If render-size masks remain, promote/replay them
     * into display space and try once more.  {@code compositedThisFrame} makes both attempts and a
     * legacy TAIL draw mutually exclusive.
     */
    private static void compositeLegacyFallbackAtRenderCallPoint(
            Minecraft minecraft, RenderTarget display, boolean handHookRequired) {
        if (!GlowComposite.hasAnyValidCapture()) return;

        // A renamed/moved hand callback is one of the reasons this fallback exists.  At the final
        // pre-HUD call point the restored display target is our last safe opportunity to retain
        // foreground depth. captureForegroundDepth is idempotent, so a valid earlier A/B/C hand
        // snapshot wins; otherwise this best-effort display snapshot prevents a missing optional
        // hook from turning every first-person surface into an x-ray outline.
        if (handHookRequired) GlowCaptureManager.captureForegroundDepth(display);

        // renderCapturedNodes' normal path deliberately rejects a mask whose physical extent no
        // longer matches the restored main target. Promote such captures before asking the legacy
        // composite to replay them, otherwise that exact-size guard would invalidate the state and
        // leave nothing for the second attempt.
        if (hasUnpreparedCaptureAtDifferentSize(display.width, display.height)) {
            GlowCaptureManager.prepareSuperResolutionMasks(
                    minecraft, display.width, display.height, false);
            GlowCaptureManager.prepareSuperResolutionMasks(
                    minecraft, display.width, display.height, true);
        }
        GlowComposite.composite(minecraft, display);
        if (!GlowComposite.hasAnyValidCapture()) return;

        GlowCaptureManager.prepareSuperResolutionMasks(
                minecraft, display.width, display.height, false);
        GlowCaptureManager.prepareSuperResolutionMasks(
                minecraft, display.width, display.height, true);
        if (GlowComposite.hasAnyValidCapture()) {
            GlowComposite.composite(minecraft, display);
        }
    }

    private static Mode captureMode() {
        if (!bridgeAvailable || !hackBridgeAvailable || getCaptureMode == null) {
            return Mode.UNKNOWN;
        }
        try {
            Object value = getCaptureMode.invoke(null);
            if (value instanceof Enum<?> e) {
                return switch (e.name()) {
                    case "A" -> Mode.A;
                    case "B" -> Mode.B;
                    case "C" -> Mode.C;
                    default -> Mode.UNKNOWN;
                };
            }
        } catch (Throwable t) {
            disableHackBridge("reading Super Resolution capture mode", t);
        }
        return Mode.UNKNOWN;
    }

    private static int screenWidth() {
        int captured = eventScreenWidth;
        if (captured > 0) return captured;
        try {
            return Math.max(1, ((Number) getScreenWidth.invoke(null)).intValue());
        } catch (Throwable t) {
            disableHackBridge("reading Super Resolution screen width", t);
            return 1;
        }
    }

    private static int screenHeight() {
        int captured = eventScreenHeight;
        if (captured > 0) return captured;
        try {
            return Math.max(1, ((Number) getScreenHeight.invoke(null)).intValue());
        } catch (Throwable t) {
            disableHackBridge("reading Super Resolution screen height", t);
            return 1;
        }
    }

    private static RenderTarget currentMainTarget() {
        Minecraft minecraft = Minecraft.getInstance();
        //#if MC>=1_26_02
        //$$ return minecraft.gameRenderer.mainRenderTarget();
        //#else
        return minecraft.getMainRenderTarget();
        //#endif
    }

    /** Selects SR's real render-size target on 26.2, where Minecraft's field is not replaced. */
    public static RenderTarget worldDepthSource(RenderTarget fallback) {
        if (!isHackConfigured() || !hackBridgeAvailable) return fallback;
        RenderTarget scaled = scaledTarget(currentHandler());
        if (!hackBridgeAvailable) return fallback;
        return scaled != null ? scaled : fallback;
    }

    private static Object currentHandler() {
        if (getRenderHandler == null) return null;
        try {
            return getRenderHandler.invoke(null);
        } catch (Throwable t) {
            disableHackBridge("reading the Super Resolution render handler", t);
            return null;
        }
    }

    private static RenderTarget scaledTarget(Object handler) {
        if (handler == null) return null;
        try {
            Object framebuffer = handler.getClass().getMethod("getScaledRenderTarget")
                    .invoke(handler);
            return asMinecraftTarget(framebuffer);
        } catch (Throwable t) {
            disableHackBridge("reading the Super Resolution scaled target", t);
            return null;
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static RenderTarget handTarget(Object handler) {
        if (handler == null) return null;
        try {
            ClassLoader loader = SuperResolutionCompat.class.getClassLoader();
            Class<?> type = Class.forName(
                    "io.homo.superresolution.common.minecraft.MinecraftRenderTargetType",
                    false, loader);
            Object hand = Enum.valueOf((Class<? extends Enum>) type.asSubclass(Enum.class), "HAND");
            Object framebuffer = handler.getClass().getMethod("getRenderTarget", type)
                    .invoke(handler, hand);
            return asMinecraftTarget(framebuffer);
        } catch (Throwable t) {
            disableHackBridge("reading the Super Resolution hand target", t);
            return null;
        }
    }

    private static RenderTarget asMinecraftTarget(Object framebuffer) {
        if (framebuffer == null) return null;
        if (framebuffer instanceof RenderTarget target) return target;
        try {
            Object target = framebuffer.getClass().getMethod("asMcRenderTarget").invoke(framebuffer);
            return target instanceof RenderTarget renderTarget ? renderTarget : null;
        } catch (Throwable t) {
            disableHackBridge("adapting a Super Resolution framebuffer", t);
            return null;
        }
    }

    private static synchronized boolean initializeBridge() {
        if (bridgeInitialized) return bridgeAvailable;
        bridgeInitialized = true;
        // Keep the universal definition reader/runtime-value resolver in every JAR, but do not
        // activate any event, RenderTarget, capture-mode, or presentation integration on frozen
        // Minecraft lines. This is a compile-time property and therefore cannot be bypassed by a
        // misleading backport version string.
        if (!hasMainlineRenderingLayer()) return false;
        if (!FabricLoader.getInstance().isModLoaded(MOD_ID)) return false;
        String installedVersion = FabricLoader.getInstance().getModContainer(MOD_ID)
                .map(container -> container.getMetadata().getVersion().getFriendlyString())
                .orElse("");
        if (!isMainlineVersion(installedVersion)) {
            Gallium.LOGGER.info(
                    "Super Resolution {} is outside Gallium's 0.9+ mainline compatibility gate",
                    installedVersion);
            return false;
        }

        try {
            ClassLoader loader = SuperResolutionCompat.class.getClassLoader();
            initializeVulkanPresentationProbe(loader);
            Class<?> config = Class.forName(CONFIG, false, loader);
            Class<?> api = Class.forName(API, false, loader);
            Class<?> workModes = Class.forName(WORK_MODES, false, loader);
            isEnableUpscale = config.getMethod("isEnableUpscale");
            isCurrentWorkMode = workModes.getMethod("isCurrentMode", String.class);
            bridgeAvailable = true;
            initializeShaderCompatBridge(api, loader);
            initializeHackBridge(config, api, loader);
            Gallium.LOGGER.info(
                    "Initialized optional Super Resolution compatibility "
                            + "(shader-interface={}, hack-target={})",
                    shaderCompatBridgeAvailable, hackBridgeAvailable);
        } catch (Throwable t) {
            disableCoreBridge("initializing the optional bridge", t);
        }
        return bridgeAvailable;
    }

    private static void initializeVulkanPresentationProbe(ClassLoader loader) {
        try {
            Class<?> feature = Class.forName(VULKAN_PRESENTATION, false, loader);
            isVulkanPresentationRequested = feature.getMethod("isRequested");
        } catch (Throwable t) {
            isVulkanPresentationRequested = null;
            Gallium.LOGGER.debug(
                    "SR Vulkan presentation cleanup probe unavailable: {}", t.toString());
        }
    }

    /** Shader-interface events are optional and independent of hack target replacement. */
    private static void initializeShaderCompatBridge(Class<?> api, ClassLoader loader) {
        try {
            getCurrentAlgorithmDescription =
                    api.getMethod("getCurrentAlgorithmDescription");
            refreshRuntimeSnapshot();
            if (!registerDispatchListeners(api, loader)) return;
            shaderCompatBridgeAvailable = true;
        } catch (Throwable t) {
            getCurrentAlgorithmDescription = null;
            disableShaderCompatBridge("initializing the SR shader-interface event bridge", t);
        }
    }

    /** Hack target replacement is optional and must not gate the shader-interface event path. */
    private static void initializeHackBridge(
            Class<?> config, Class<?> api, ClassLoader loader) {
        try {
            getCaptureMode = config.getMethod("getCaptureMode");
            getScreenWidth = api.getMethod("getScreenWidth");
            getScreenHeight = api.getMethod("getScreenHeight");
            getRenderWidth = api.getMethod("getRenderWidth");
            getRenderHeight = api.getMethod("getRenderHeight");
            Class<?> manager = Class.forName(
                    "io.homo.superresolution.common.minecraft.handler.RenderHandlerManager",
                    false, loader);
            getRenderHandler = manager.getMethod("getHandler");
            hackBridgeAvailable = true;
        } catch (Throwable t) {
            getCaptureMode = null;
            getScreenWidth = null;
            getScreenHeight = null;
            getRenderWidth = null;
            getRenderHeight = null;
            getRenderHandler = null;
            disableHackBridge("initializing the SR hack RenderTarget bridge", t);
        }
    }

    /** Mirrors the current 0.9+ API event bus without introducing an optional link dependency. */
    private static boolean registerDispatchListeners(Class<?> api, ClassLoader loader) {
        try {
            Object bus = api.getField("EVENT_BUS").get(null);
            Class<?> dispatch = Class.forName(
                    "io.homo.superresolution.api.event.AlgorithmDispatchEvent", false, loader);
            Class<?> finish = Class.forName(
                    "io.homo.superresolution.api.event.AlgorithmDispatchFinishEvent", false, loader);
            Class<?> busType = Class.forName("net.neoforged.bus.api.IEventBus", false, loader);
            Method addListener = busType.getMethod(
                    "addListener", Class.class, Consumer.class);
            Method dispatchAlgorithm = dispatch.getMethod("getAlgorithm");
            Method dispatchResource = dispatch.getMethod("getDispatchResource");
            Class<?> resource = Class.forName(
                    "io.homo.superresolution.common.upscale.DispatchResource", false, loader);
            Method resourceRenderWidth = resource.getMethod("renderWidth");
            Method resourceRenderHeight = resource.getMethod("renderHeight");
            Method resourceScreenWidth = resource.getMethod("screenWidth");
            Method resourceScreenHeight = resource.getMethod("screenHeight");
            Method finishAlgorithm = finish.getMethod("getAlgorithm");
            Method finishOutput = finish.getMethod("getOutput");
            Class<?> framebuffer = Class.forName(
                    "io.homo.superresolution.core.graphics.impl.framebuffer.IFrameBuffer",
                    false, loader);
            Method outputWidth = framebuffer.getMethod("getWidth");
            Method outputHeight = framebuffer.getMethod("getHeight");

            dispatchStartListener = event -> {
                try {
                    Object algorithm = dispatchAlgorithm.invoke(event);
                    Object dispatchData = dispatchResource.invoke(event);
                    int renderWidth = positiveInt(resourceRenderWidth.invoke(dispatchData));
                    int renderHeight = positiveInt(resourceRenderHeight.invoke(dispatchData));
                    int screenWidth = positiveInt(resourceScreenWidth.invoke(dispatchData));
                    int screenHeight = positiveInt(resourceScreenHeight.invoke(dispatchData));

                    // Capture the algorithm name before replacing the API size snapshot with the
                    // immutable dimensions carried by this exact dispatch resource.
                    refreshRuntimeSnapshot();
                    eventRenderWidth = renderWidth;
                    eventRenderHeight = renderHeight;
                    eventScreenWidth = screenWidth;
                    eventScreenHeight = screenHeight;
                    eventOutputWidth = 0;
                    eventOutputHeight = 0;
                    dispatchAlgorithmIdentity = algorithm;
                    dispatchEpoch = frameEpoch;
                    dispatchFinishedThisFrame = false;
                    dispatchInProgress = true;
                    dispatchObservedThisFrame = true;
                } catch (Throwable t) {
                    rejectDispatchEvent("reading its start payload", t);
                }
            };
            dispatchFinishListener = event -> {
                try {
                    Object algorithm = finishAlgorithm.invoke(event);
                    Object output = finishOutput.invoke(event);
                    int width = positiveInt(outputWidth.invoke(output));
                    int height = positiveInt(outputHeight.invoke(output));
                    boolean matchesStart = dispatchObservedThisFrame
                            && dispatchEpoch == frameEpoch
                            && algorithm != null
                            && algorithm == dispatchAlgorithmIdentity;
                    dispatchInProgress = false;
                    if (!matchesStart) {
                        dispatchFinishedThisFrame = false;
                        eventOutputWidth = 0;
                        eventOutputHeight = 0;
                        return;
                    }
                    eventOutputWidth = width;
                    eventOutputHeight = height;
                    dispatchFinishedThisFrame = true;
                } catch (Throwable t) {
                    rejectDispatchEvent("reading its finish payload", t);
                }
            };
            addListener.invoke(bus, dispatch, dispatchStartListener);
            addListener.invoke(bus, finish, dispatchFinishListener);
            dispatchListenersAvailable = true;
            return true;
        } catch (Throwable t) {
            // Handler timing remains authoritative. The event snapshot is an optional ABI-safe
            // fast path and must not disable target compatibility on an EventBus implementation
            // change.
            dispatchListenersAvailable = false;
            disableShaderCompatBridge("registering SR dispatch event listeners", t);
            return false;
        }
    }

    private static int positiveInt(Object value) {
        if (!(value instanceof Number number)) return 0;
        int result = number.intValue();
        return result > 0 ? result : 0;
    }

    private static void rejectDispatchEvent(String action, Throwable t) {
        dispatchInProgress = false;
        dispatchObservedThisFrame = false;
        dispatchFinishedThisFrame = false;
        dispatchEpoch = -1L;
        dispatchAlgorithmIdentity = null;
        eventOutputWidth = 0;
        eventOutputHeight = 0;
        if (!dispatchEventWarningLogged) {
            dispatchEventWarningLogged = true;
            Gallium.LOGGER.warn(
                    "Ignoring an SR dispatch while {}: {}", action, t.toString());
        }
    }

    private static void refreshRuntimeSnapshot() {
        try {
            if (getRenderWidth != null) {
                eventRenderWidth = Math.max(1, ((Number) getRenderWidth.invoke(null)).intValue());
            }
            if (getRenderHeight != null) {
                eventRenderHeight = Math.max(1, ((Number) getRenderHeight.invoke(null)).intValue());
            }
            if (getScreenWidth != null) {
                eventScreenWidth = Math.max(1, ((Number) getScreenWidth.invoke(null)).intValue());
            }
            if (getScreenHeight != null) {
                eventScreenHeight = Math.max(1, ((Number) getScreenHeight.invoke(null)).intValue());
            }
            if (getCurrentAlgorithmDescription != null) {
                Object description = getCurrentAlgorithmDescription.invoke(null);
                if (description != null) {
                    try {
                        Object code = description.getClass().getMethod("getCodeName")
                                .invoke(description);
                        eventAlgorithm = code == null ? "" : code.toString();
                    } catch (Throwable ignored) {
                        eventAlgorithm = description.toString();
                    }
                }
            }
        } catch (Throwable ignored) {
            // A resize-transition event may race a temporarily unavailable target. Live API
            // getters remain the fallback and the next dispatch refreshes the snapshot.
        }
    }

    static boolean isMainlineVersion(String version) {
        if (version == null) return false;
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("(?:^|-)(\\d+)\\.(\\d+)(?=\\.|-|$)")
                .matcher(version.trim());
        int major = -1;
        int minor = -1;
        // Published Modrinth versions may be prefixed with the Minecraft line
        // (for example 26.1-0.9.1-...).  The final semantic-version pair belongs to SR.
        while (matcher.find()) {
            major = Integer.parseInt(matcher.group(1));
            minor = Integer.parseInt(matcher.group(2));
        }
        if (major < 0) return false;
        return major > 0 || minor >= 9;
    }

    private static void disableCoreBridge(String action, Throwable t) {
        bridgeAvailable = false;
        shaderCompatBridgeAvailable = false;
        hackBridgeAvailable = false;
        if (!bridgeWarningLogged) {
            bridgeWarningLogged = true;
            Gallium.LOGGER.warn(
                    "Super Resolution compatibility disabled while {}: {}", action, t.toString());
        }
    }

    private static void disableShaderCompatBridge(String action, Throwable t) {
        shaderCompatBridgeAvailable = false;
        dispatchListenersAvailable = false;
        if (!shaderCompatBridgeWarningLogged) {
            shaderCompatBridgeWarningLogged = true;
            Gallium.LOGGER.warn(
                    "SR shader-interface display replay disabled while {}; "
                            + "hack RenderTarget compatibility remains independent: {}",
                    action, t.toString());
        }
    }

    private static void disableHackBridge(String action, Throwable t) {
        hackBridgeAvailable = false;
        if (!hackBridgeWarningLogged) {
            hackBridgeWarningLogged = true;
            Gallium.LOGGER.warn(
                    "SR hack RenderTarget compatibility disabled while {}; "
                            + "shader-interface display replay remains enabled: {}",
                    action, t.toString());
        }
    }

    private static boolean hookedHandlerInstalled() {
        if (getRenderHandler == null) return false;
        try {
            Object handler = currentHandler();
            return handler instanceof HookedHandler;
        } catch (Throwable t) {
            disableHackBridge("checking the Super Resolution handler hook", t);
            return false;
        }
    }

    /** Marker mixed into the supported SR handler; its absence selects the legacy Gallium path. */
    public interface HookedHandler {}

    private enum Mode { A, B, C, UNKNOWN }
}
