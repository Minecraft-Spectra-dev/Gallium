package cn.spectra.gallium.glowoutline;

import cn.spectra.gallium.Gallium;
import cn.spectra.gallium.glowoutline.capture.GlowCaptureManager;
import cn.spectra.gallium.glowoutline.capture.GlowCaptureState;
//#if MC==1_21_11 || MC==1_26_01
import cn.spectra.gallium.glowoutline.capture.OpenGlMaskOrdering;
//#endif
import cn.spectra.gallium.glowoutline.capture.GlowCaptureManager.SceneDepthSnapshotStatus;
import cn.spectra.gallium.glowoutline.shader.GlowComposite;
import cn.spectra.gallium.glowoutline.shader.GlowResources;
import cn.spectra.gallium.glowoutline.sr.streaming.SrStreamingCoordinator;
import cn.spectra.gallium.glowoutline.sr.streaming.SrStreamingCoordinator.CapabilityTracker;
import cn.spectra.gallium.glowoutline.sr.streaming.SrStreamingCoordinator.CaptureDomain;
import cn.spectra.gallium.glowoutline.sr.streaming.SrStreamingCoordinator.CaptureMode;
import cn.spectra.gallium.glowoutline.sr.streaming.SrStreamingCoordinator.CaptureStage;
import cn.spectra.gallium.glowoutline.sr.streaming.SrStreamingCoordinator.DomainLifecycle;
import cn.spectra.gallium.glowoutline.sr.streaming.SrStreamingCoordinator.Eligibility;
import cn.spectra.gallium.glowoutline.sr.streaming.SrStreamingCoordinator.FrameIntent;
import cn.spectra.gallium.glowoutline.sr.streaming.SrStreamingCoordinator.FramePolicy;
import cn.spectra.gallium.glowoutline.sr.streaming.SrStreamingCoordinator.HandDomainSnapshot;
import cn.spectra.gallium.glowoutline.sr.streaming.SrStreamingCoordinator.HookCapabilityState;
import cn.spectra.gallium.glowoutline.sr.streaming.SrStreamingCoordinator.HookKind;
import cn.spectra.gallium.glowoutline.sr.streaming.SrStreamingCoordinator.ModeCapability;
import cn.spectra.gallium.glowoutline.sr.streaming.SrStreamingCoordinator.SceneDepthCapturePolicy;
import cn.spectra.gallium.glowoutline.sr.streaming.SrStreamingCoordinator.SnapshotAuthority;
import cn.spectra.gallium.glowoutline.sr.streaming.SrStreamingCoordinator.SnapshotSite;
import cn.spectra.gallium.glowoutline.sr.streaming.SrStreamingCoordinator.SnapshotUpdateDecision;
import cn.spectra.gallium.glowoutline.sr.streaming.SrStreamingCoordinator.SrFramePlan;
import cn.spectra.gallium.glowoutline.sr.streaming.SrStreamingCoordinator.WorldDomainSnapshot;
import com.mojang.blaze3d.pipeline.RenderTarget;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;

import java.lang.reflect.Method;
import java.util.Optional;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Predicate;

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
    /** Fixed at renderLevel HEAD; never switch an in-flight payload back to legacy replay. */
    private static boolean lateReplayFrame;
    private static boolean lateReplayFinalConsumed;
    private static boolean sharedMaskFrame;
    //#if MC==1_21_11 || MC==1_26_01
    private static OpenGlMaskOrdering.Stamp sharedMaskBackend;

    public static OpenGlMaskOrdering.Stamp sharedMaskBackend() {
        return sharedMaskBackend;
    }
    //#endif
    /** Per-mode evidence; replay and mask ownership selection are frozen at HEAD. */
    private static final CapabilityTracker streamingCapabilities = new CapabilityTracker();
    private static final DomainLifecycle streamingDomains = new DomainLifecycle(0L);
    private static SrFramePlan streamingFramePlan = legacyFramePlan(0L);
    private static WorldDomainSnapshot worldDomainSnapshot;
    private static HandDomainSnapshot handDomainSnapshot;
    private static long upscaleCompletedEpoch = -1L;
    private static boolean requiredAuthoritativeWorldHookObserved;
    private static boolean worldObservedByClientRender;
    /**
     * Mainline builds deliberately defer world composition to the final pre-HUD call point from
     * their very first frame.  These flags turn a missing optional injector into a one-frame
     * probe: the next client frame logs once and permanently restores the legacy renderLevel TAIL
     * path instead of silently dropping every later outline.
     */
    private static boolean worldFrameAwaitingFinalHook;
    private static boolean finalHookObservedThisFrame;
    private static boolean finalHookObserved;
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

    static {
        GlowResources.register(SuperResolutionCompat::resetStreamingAndCaptureResources);
    }

    private SuperResolutionCompat() {}

    /** Current render-level epoch used by the Phase 0 shadow capture lifecycle. */
    public static long currentFrameEpoch() {
        return frameEpoch;
    }

    public static SrFramePlan currentStreamingFramePlan() {
        return streamingFramePlan;
    }

    static long upscaleCompletedEpoch() {
        return upscaleCompletedEpoch;
    }

    private static SrFramePlan legacyFramePlan(long epoch) {
        return new SrFramePlan(epoch, new FramePolicy(
                SrStreamingCoordinator.FrameIntent.LEGACY,
                SrStreamingCoordinator.StreamingCapability.UNKNOWN,
                SrStreamingCoordinator.MaskOwnershipMode.PER_STATE,
                SceneDepthCapturePolicy.SNAPSHOT_AND_PREFILL),
                CaptureMode.UNKNOWN, 0, 0, 0, 0,
                false, false, 0, 0);
    }

    private static SrFramePlan buildStreamingFramePlan() {
        boolean hackConfigured = isHackConfigured();
        CaptureMode mode = hackConfigured ? streamingMode(captureMode()) : CaptureMode.UNKNOWN;
        Minecraft minecraft = Minecraft.getInstance();
        boolean firstPerson = minecraft.options.getCameraType().isFirstPerson();
        Object handler = hackConfigured ? currentHandler() : null;
        int handlerFingerprint = handler == null ? 0
                : System.identityHashCode(handler.getClass());
        int algorithmFingerprint = eventAlgorithm == null ? 0 : eventAlgorithm.hashCode();

        streamingCapabilities.observeHandlerAbiFingerprint(mode, handlerFingerprint);
        if (mode != CaptureMode.UNKNOWN) {
            ModeCapability capability = streamingCapabilities.capability(mode);
            if (capability.backendOrdering() == HookCapabilityState.UNKNOWN) {
                streamingCapabilities.update(
                        mode, HookKind.BACKEND_ORDERING, HookCapabilityState.PROBING);
            }
            if (finalHookUnavailable) {
                streamingCapabilities.update(mode, HookKind.FINAL, HookCapabilityState.DISABLED);
            }
        }
        ModeCapability capability = streamingCapabilities.capability(mode);
        lateReplayFrame = selectsLateReplayAtHead(
                isActive(), mode, firstPerson, capability);
        sharedMaskFrame = false;
        //#if MC==1_21_11 || MC==1_26_01
        sharedMaskBackend = lateReplayFrame ? OpenGlMaskOrdering.observe() : null;
        if (sharedMaskBackend != null) {
            markCapability(mode, HookKind.BACKEND_ORDERING, HookCapabilityState.ENABLED);
            sharedMaskFrame = SrStreamingCoordinator.sharedReuseReadiness(
                    streamingCapabilities.capability(mode), firstPerson)
                    == SrStreamingCoordinator.StreamingCapability.ENABLED;
        }
        //#endif
        FramePolicy policy = SrStreamingCoordinator.derivePhaseOnePolicy(
                usesDeferredFinalHookBuild(), hackConfigured, mode, firstPerson, capability);
        if (lateReplayFrame) {
            policy = new FramePolicy(FrameIntent.HACK_STREAMING_CANDIDATE,
                    SrStreamingCoordinator.StreamingCapability.ENABLED,
                    sharedMaskFrame ? SrStreamingCoordinator.MaskOwnershipMode.STREAMING_SHARED
                            : SrStreamingCoordinator.MaskOwnershipMode.PER_STATE,
                    SceneDepthCapturePolicy.SNAPSHOT_ONLY);
        }
        return new SrFramePlan(frameEpoch, policy, mode,
                eventRenderWidth, eventRenderHeight, eventScreenWidth, eventScreenHeight,
                IrisCompat.isShaderActive(), firstPerson,
                handlerFingerprint, algorithmFingerprint);
    }

    /** Resource reload/world teardown invalidates metadata before any later capture can use it. */
    public static void resetStreamingMetadata() {
        frameEpoch++;
        lateReplayFrame = false;
        lateReplayFinalConsumed = false;
        sharedMaskFrame = false;
        for (var state : GlowCaptureManager.getActiveStates()) {
            CaptureStage stage = state.captureStage();
            if (stage != CaptureStage.IDLE && stage != CaptureStage.INVALID
                    && stage != CaptureStage.COMPOSITED) {
                state.invalidateCapture();
            }
        }
        streamingCapabilities.reset();
        streamingDomains.reset(frameEpoch);
        streamingFramePlan = legacyFramePlan(frameEpoch);
        worldDomainSnapshot = null;
        handDomainSnapshot = null;
        upscaleCompletedEpoch = -1L;
        requiredAuthoritativeWorldHookObserved = false;
    }

    /** One render-thread transaction: epoch/discard first, then release every owned resource. */
    private static void resetStreamingAndCaptureResources() {
        resetStreamingMetadata();
        GlowCaptureManager.clearAll();
    }

    /** Vanilla's pre-hand hook; authority and capture policy come from the HEAD frame plan. */
    public static void captureVanillaSceneDepth(RenderTarget source) {
        captureWorldDepth(source, SnapshotSite.VANILLA_PRE_HAND);
    }

    /** Candidate frames reject captures that begin after their matching domain hook closed. */
    public static boolean canBeginStreamingCapture(boolean firstPerson) {
        return canAcceptStreamingCapture(frameEpoch, firstPerson);
    }

    /** Also rejects a capture that crossed the domain closure before its first real submit. */
    public static boolean canAcceptStreamingCapture(long captureEpoch, boolean firstPerson) {
        if (streamingFramePlan.policy().intent() != FrameIntent.HACK_STREAMING_CANDIDATE) {
            return true;
        }
        CaptureDomain domain = firstPerson
                ? CaptureDomain.FIRST_PERSON : CaptureDomain.WORLD;
        return streamingDomains.canBeginCapture(captureEpoch, domain);
    }

    record WorldHookDecision(
            boolean authoritativeSite,
            boolean closeDomain,
            boolean abortWorldDomain) {}

    static WorldHookDecision worldHookDecision(
            CaptureMode mode, SnapshotSite site, SceneDepthSnapshotStatus status) {
        if (mode == null || site == null || status == null) {
            throw new IllegalArgumentException("mode/site/status");
        }
        SnapshotAuthority authority = mode == CaptureMode.UNKNOWN
                ? (site == SnapshotSite.VANILLA_PRE_HAND
                ? SnapshotAuthority.AUTHORITATIVE : SnapshotAuthority.PROVISIONAL)
                : SrStreamingCoordinator.worldSnapshotAuthority(mode, site);
        boolean authoritative = mode != CaptureMode.UNKNOWN
                && authority == SnapshotAuthority.AUTHORITATIVE;
        return new WorldHookDecision(
                authoritative,
                SrStreamingCoordinator.shouldCloseWorldDomain(
                        site, authority, status == SceneDepthSnapshotStatus.READY),
                status == SceneDepthSnapshotStatus.FAILED);
    }

    private static void captureWorldDepth(RenderTarget source, SnapshotSite site) {
        SrFramePlan plan = streamingFramePlan;
        CaptureMode mode = plan.captureMode();
        SnapshotAuthority incoming = mode == CaptureMode.UNKNOWN
                ? SnapshotAuthority.AUTHORITATIVE
                : SrStreamingCoordinator.worldSnapshotAuthority(mode, site);
        boolean authoritativeSite = mode != CaptureMode.UNKNOWN
                && incoming == SnapshotAuthority.AUTHORITATIVE;
        if (authoritativeSite) {
            requiredAuthoritativeWorldHookObserved = true;
            markCapability(mode, HookKind.WORLD, HookCapabilityState.ENABLED);
        }
        Optional<SnapshotAuthority> existing = worldDomainSnapshot != null
                && worldDomainSnapshot.epoch() == frameEpoch
                && worldDomainSnapshot.snapshotSucceeded()
                ? Optional.of(worldDomainSnapshot.authority()) : Optional.empty();
        SnapshotUpdateDecision decision =
                SrStreamingCoordinator.decideSnapshotUpdate(existing, incoming);
        GlowCaptureManager.SceneDepthSnapshotResult result;
        if (decision == SnapshotUpdateDecision.VALIDATE_ONLY) {
            WorldDomainSnapshot frozen = worldDomainSnapshot;
            if (!GlowCaptureManager.needsSceneDepthCapture()) {
                result = GlowCaptureManager.SceneDepthSnapshotResult.notRequired(
                        GlowCaptureManager.getSceneDepthGeneration());
            } else if (frozen != null && GlowCaptureManager.isSceneDepthSnapshotCurrent(
                    frozen.depthGeneration(), frozen.width(), frozen.height())) {
                result = GlowCaptureManager.SceneDepthSnapshotResult.ready(
                        false, frozen.depthGeneration(), frozen.width(), frozen.height());
            } else {
                result = GlowCaptureManager.SceneDepthSnapshotResult.failed(
                        false, GlowCaptureManager.getSceneDepthGeneration(),
                        frozen == null ? 0 : frozen.width(),
                        frozen == null ? 0 : frozen.height());
            }
        } else {
            result = GlowCaptureManager.captureSceneDepth(source,
                    plan.policy().sceneDepthCapturePolicy(),
                    decision == SnapshotUpdateDecision.REPLACE);
        }

        if (decision != SnapshotUpdateDecision.VALIDATE_ONLY && result.ready()) {
            WorldDomainSnapshot candidate = new WorldDomainSnapshot(
                    frameEpoch, true, site, incoming, true,
                    result.width(), result.height(), result.generation(),
                    source == null ? 0 : System.identityHashCode(source), depthIdentity(source));
            worldDomainSnapshot = SrStreamingCoordinator.selectFrozenWorldSnapshot(
                    worldDomainSnapshot, candidate);
        }

        WorldHookDecision hookDecision = worldHookDecision(mode, site, result.status());
        if (hookDecision.abortWorldDomain()) {
            GlowCaptureManager.abortPayloadsInDomain(CaptureDomain.WORLD);
        }
        if (hookDecision.closeDomain()) {
            closeWorldDomain(result.status(), incoming);
        }
    }

    private static void closeWorldDomain(
            SceneDepthSnapshotStatus status, SnapshotAuthority fallbackAuthority) {
        if (status == SceneDepthSnapshotStatus.NOT_REQUIRED
                || status == SceneDepthSnapshotStatus.FAILED) {
            closeDomainAndMarkEligible(CaptureDomain.WORLD, -1L, fallbackAuthority);
            return;
        }
        WorldDomainSnapshot usable = worldDomainSnapshot;
        if (usable == null || !usable.snapshotSucceeded()
                || !GlowCaptureManager.isSceneDepthSnapshotCurrent(
                usable.depthGeneration(), usable.width(), usable.height())) {
            GlowCaptureManager.abortPayloadsInDomain(CaptureDomain.WORLD);
            closeDomainAndMarkEligible(CaptureDomain.WORLD, -1L, fallbackAuthority);
            return;
        }
        closeDomainAndMarkEligible(
                CaptureDomain.WORLD, usable.depthGeneration(), usable.authority());
    }

    private static int closeDomainAndMarkEligible(
            CaptureDomain domain, long generation, SnapshotAuthority authority) {
        if (!streamingDomains.close(frameEpoch, domain)) return 0;
        int scheduled = 0;
        for (var state : GlowCaptureManager.getActiveStates()) {
            if (state.captureDomain != domain) continue;
            Optional<Eligibility> eligibility = streamingDomains.eligibilityFor(
                    state.captureEpoch, domain, state.captureStage(), generation, authority);
            if (eligibility.isPresent() && state.markStreamingEligible(eligibility.get())) {
                scheduled++;
            }
        }
        return scheduled;
    }

    record HandHookDecision(boolean scheduleFirstPerson, boolean copyForeground) {}

    static HandHookDecision handHookDecision(
            boolean irisShaderActive, boolean firstPersonCamera,
            boolean hasPendingWorldCapture) {
        return new HandHookDecision(true, needsHandHook(
                irisShaderActive, firstPersonCamera, hasPendingWorldCapture));
    }

    private static int closeHandDomain(CaptureMode mode) {
        markCapability(mode, HookKind.FIRST_PERSON, HookCapabilityState.ENABLED);
        return closeDomainAndMarkEligible(
                CaptureDomain.FIRST_PERSON, -1L, SnapshotAuthority.AUTHORITATIVE);
    }

    private static void freezeHandSnapshot(
            RenderTarget source, SnapshotSite site, int scheduled) {
        Minecraft minecraft = Minecraft.getInstance();
        HandHookDecision decision = handHookDecision(
                IrisCompat.isShaderActive(),
                minecraft.options.getCameraType().isFirstPerson(),
                hasPendingWorldCapture());
        long before = GlowCaptureManager.getForegroundDepthGeneration();
        if (decision.copyForeground() && source != null) {
            try {
                GlowCaptureManager.captureForegroundDepth(source);
            } catch (RuntimeException | Error failure) {
                GlowCaptureManager.abortPendingPayloads();
                throw failure;
            }
        }
        long after = GlowCaptureManager.getForegroundDepthGeneration();
        boolean foregroundReady = !decision.copyForeground()
                || GlowCaptureManager.getForegroundDepthTarget() != null;
        handDomainSnapshot = new HandDomainSnapshot(
                frameEpoch, true, site, decision.scheduleFirstPerson(), scheduled,
                decision.copyForeground(), foregroundReady,
                !decision.copyForeground() ? -1L : foregroundReady ? after : before,
                source == null ? 0 : source.width,
                source == null ? 0 : source.height);
    }

    private static void markCapability(
            CaptureMode mode, HookKind hook, HookCapabilityState state) {
        if (mode != CaptureMode.UNKNOWN) streamingCapabilities.update(mode, hook, state);
    }

    private static CaptureMode streamingMode(Mode mode) {
        return switch (mode) {
            case A -> CaptureMode.A;
            case B -> CaptureMode.B;
            case C -> CaptureMode.C;
            case UNKNOWN -> CaptureMode.UNKNOWN;
        };
    }

    private static int depthIdentity(RenderTarget target) {
        if (target == null) return 0;
        //#if MC>=1_21_05
        return System.identityHashCode(target.getDepthTexture());
        //#else
        //$$ return target.getDepthTextureId();
        //#endif
    }

    static boolean completedUpscaleForEpoch(long expectedEpoch, long completedEpoch) {
        return expectedEpoch == completedEpoch;
    }

    /** Reset scheduling flags together with Gallium's capture pool at GameRenderer.renderLevel HEAD. */
    public static void beginFrame() {
        if (usesDeferredFinalHookBuild() && !finalHookUnavailable) {
            worldFrameAwaitingFinalHook = true;
        }
        // Shader-compat dispatch events occur inside renderLevel. Initialize at HEAD so the first
        // world frame is observed; waiting for the hack-mode TAIL query would miss that dispatch.
        initializeBridge();
        frameEpoch++;
        lateReplayFrame = false;
        lateReplayFinalConsumed = false;
        sharedMaskFrame = false;
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
        upscaleCompletedEpoch = -1L;
        worldDomainSnapshot = null;
        handDomainSnapshot = null;
        requiredAuthoritativeWorldHookObserved = false;
        streamingDomains.reset(frameEpoch);
        streamingFramePlan = buildStreamingFramePlan();
    }

    static boolean selectsLateReplayAtHead(
            boolean active, CaptureMode mode, boolean firstPerson, ModeCapability capability) {
        //#if MC==1_21_11 || MC==1_26_01
        return active && (mode == CaptureMode.A || mode == CaptureMode.C)
                && SrStreamingCoordinator.lateReplayReadiness(capability, firstPerson)
                == SrStreamingCoordinator.StreamingCapability.ENABLED;
        //#else
        //$$ return false;
        //#endif
    }

    public static boolean ownsLateReplayFrame() {
        return lateReplayFrame;
    }

    public static boolean ownsSharedMaskFrame() {
        return sharedMaskFrame;
    }

    /** Evidence about the vanilla final call point, independent of SR capabilities and modes. */
    public static boolean sequentialFinalHookAvailable() {
        return finalHookObserved && shouldDeferLegacyCompositeAtTail();
    }

    public static boolean sequentialFinalHookCurrent() {
        return sequentialFinalHookAvailable() && finalHookObservedThisFrame;
    }

    /** Freeze the pack transform at the former prepare site, before SR changes its live state. */
    private static void freezeLateReplayInputs(boolean firstPerson) {
        //#if MC==1_21_11 || MC==1_26_01
        if (!lateReplayFrame) return;
        ShaderPackHint.ProjectionTransform transform = IrisCompat.getShaderProjectionTransform(
                streamingFramePlan.expectedDisplayWidth(), streamingFramePlan.expectedDisplayHeight());
        for (var state : GlowCaptureManager.getActiveStates()) {
            if (state.firstPerson == firstPerson && state.captureEpoch == frameEpoch
                    && state.captureStage() == CaptureStage.ELIGIBLE
                    && state.lateReplayProjection == null) {
                state.lateReplayProjection = transform;
            }
        }
        //#endif
    }

    /** Runs once per GameRenderer frame, including title/loading/GUI-only frames. */
    public static void beginClientRenderFrame() {
        if (missedDeferredFinalHook(
                usesDeferredFinalHookBuild(), clientRenderFrameSeen,
                worldFrameAwaitingFinalHook, finalHookObservedThisFrame)) {
            finalHookUnavailable = true;
            markCapability(streamingFramePlan.captureMode(),
                    HookKind.FINAL, HookCapabilityState.DISABLED);
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
        boolean worldPresent = Minecraft.getInstance().level != null;
        if (worldObservedByClientRender && !worldPresent) {
            // Runs on the render thread. Invalidate epochs before releasing capture resources so
            // no stale payload can be observed between the two teardown steps.
            resetStreamingAndCaptureResources();
        }
        worldObservedByClientRender = worldPresent;
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
        if (!hackBridgeAvailable) {
            captureWorldDepth(null, SnapshotSite.SR_PRE_UPSCALE);
            return;
        }
        if (source == null) source = currentMainTarget();
        if (source == null) {
            captureWorldDepth(null, SnapshotSite.SR_PRE_UPSCALE);
            return;
        }

        compatFrame = true;
        // Mode B replaces the provisional vanilla snapshot here. A/C retain their authoritative
        // pre-hand snapshot and use this callback only as validation.
        captureWorldDepth(source, SnapshotSite.SR_PRE_UPSCALE);
        int width = screenWidth();
        int height = screenHeight();
        if (!hackBridgeAvailable) return;
        if (lateReplayFrame) {
            freezeLateReplayInputs(false);
            return;
        }
        GlowCaptureManager.prepareSuperResolutionMasks(
                Minecraft.getInstance(), width, height, false);
        hackWorldPrepareReady = true;
    }

    /** Called from SR's separated-hand handler before CaptureMode C restores the world target. */
    public static void beforeSeparatedHandRestore(Object handler) {
        if (!isActive() || captureMode() != Mode.C) return;
        hackHandHookHit = true;
        int scheduled = closeHandDomain(CaptureMode.C);
        refreshRuntimeSnapshot();
        RenderTarget source = handTarget(handler);
        if (!hackBridgeAvailable) {
            freezeHandSnapshot(null, SnapshotSite.SR_SEPARATED_HAND_PRE_RESTORE, scheduled);
            return;
        }
        if (source == null) source = currentMainTarget();
        freezeHandSnapshot(source, SnapshotSite.SR_SEPARATED_HAND_PRE_RESTORE, scheduled);
        if (lateReplayFrame) {
            freezeLateReplayInputs(true);
            return;
        }
        int width = screenWidth();
        int height = screenHeight();
        if (!hackBridgeAvailable) return;
        GlowCaptureManager.prepareSuperResolutionMasks(
                Minecraft.getInstance(), width, height, true);
    }

    static boolean usesInlineHandCompletion(CaptureMode mode, boolean irisActive) {
        if (mode == CaptureMode.A || mode == CaptureMode.B) return true;
        //#if MC==1_21_11 || MC==1_26_01
        // SR skips its separated-hand path when Iris renders the hand inside renderLevel.
        // The subsequent vanilla hand TAIL is still reached, after both Iris hand phases.
        return mode == CaptureMode.C && irisActive;
        //#else
        //$$ return false;
        //#endif
    }

    /** Called at the actual vanilla renderItemInHand TAIL, after Iris's inline hand rendering. */
    public static void afterInlineHandRender() {
        if (!isActive()) return;
        Mode mode = captureMode();
        if (!usesInlineHandCompletion(streamingMode(mode), IrisCompat.isShaderActive())) return;
        hackHandHookHit = true;
        CaptureMode captureMode = streamingMode(mode);
        int scheduled = closeHandDomain(captureMode);
        refreshRuntimeSnapshot();
        RenderTarget source = mode == Mode.A ? scaledTarget(currentHandler()) : currentMainTarget();
        if (!hackBridgeAvailable) {
            freezeHandSnapshot(null, SnapshotSite.INLINE_HAND_TAIL, scheduled);
            return;
        }
        if (source == null) source = currentMainTarget();
        freezeHandSnapshot(source, SnapshotSite.INLINE_HAND_TAIL, scheduled);
        if (lateReplayFrame) {
            freezeLateReplayInputs(true);
            return;
        }
        // Probe/legacy C+Iris retains its original final fallback replay.
        if (mode == Mode.C) return;
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
        upscaleCompletedEpoch = frameEpoch;
        markCapability(streamingFramePlan.captureMode(),
                HookKind.UPSCALE_FINISH, HookCapabilityState.ENABLED);
    }

    /** True while the SR-specific prepare/composite scheduler owns world glow for this frame. */
    public static boolean ownsWorldComposite() {
        return compatFrame && hackWorldPrepareReady
                && hackUpscaleFinishHookHit && upscaleFinished
                && completedUpscaleForEpoch(frameEpoch, upscaleCompletedEpoch) && isActive();
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
        finalHookObserved = true;
        worldFrameAwaitingFinalHook = false;
        markCapability(streamingFramePlan.captureMode(),
                HookKind.FINAL, HookCapabilityState.ENABLED);
        Minecraft minecraft = Minecraft.getInstance();
        RenderTarget display = currentMainTarget();
        //#if MC==1_21_11 || MC==1_26_01
        // Ownership was frozen at HEAD. Even a mode/backend change must not send a shared
        // ordinary payload through an SR prepare or a null per-state fallback this frame.
        if (GlowCaptureManager.ownsSequentialSharedMaskFrame()) {
            GlowComposite.composite(minecraft, display);
            return true;
        }
        if (lateReplayFrame) {
            compositeLateReplayFrame(minecraft, display);
            return true;
        }
        //#endif
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
        boolean pendingFirstPersonCapture = hasPendingFirstPersonCapture();
        boolean handHookRequired = needsHandHook(
                IrisCompat.isShaderActive(),
                minecraft.options.getCameraType().isFirstPerson(),
                hasPendingWorldCapture());
        HackCompositeDecision decision = decideHackComposite(
                true, isActive() && compatFrame && hackWorldPrepareReady
                        && requiredAuthoritativeWorldHookObserved,
                hackUpscaleFinishHookHit && upscaleFinished
                        && completedUpscaleForEpoch(frameEpoch, upscaleCompletedEpoch),
                hackHandHookHit,
                handHookRequired, hasUnpreparedCapture);

        if (decision == HackCompositeDecision.FALLBACK) {
            CaptureMode mode = streamingFramePlan.captureMode();
            if (!requiredAuthoritativeWorldHookObserved) {
                markCapability(mode, HookKind.WORLD, HookCapabilityState.DISABLED);
            }
            if (!hackUpscaleFinishHookHit
                    || !completedUpscaleForEpoch(frameEpoch, upscaleCompletedEpoch)) {
                markCapability(mode, HookKind.UPSCALE_FINISH, HookCapabilityState.DISABLED);
            }
            if (missedFirstPersonDomainHook(
                    pendingFirstPersonCapture, hackHandHookHit)) {
                markCapability(mode, HookKind.FIRST_PERSON, HookCapabilityState.DISABLED);
            }
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

    /** Execute the live per-state sequence. Failure is terminal for the frame, never a retry. */
    static boolean replayPreparedStates(List<GlowCaptureState> states,
                                       Predicate<GlowCaptureState> replay,
                                       Predicate<GlowCaptureState> composite,
                                       Runnable abort) {
        boolean complete = false;
        try {
            for (GlowCaptureState state : states) {
                if (state.captureStage() != CaptureStage.SCHEDULED
                        || !replay.test(state)
                        || state.captureStage() != CaptureStage.REPLAY_ATTEMPTED
                        || !composite.test(state)
                        || state.captureStage() != CaptureStage.COMPOSITED) return false;
            }
            complete = true;
            return true;
        } finally {
            if (!complete) abort.run();
        }
    }

    //#if MC==1_21_11 || MC==1_26_01
    private static boolean lateFrameEvidenceCurrent(SrFramePlan plan, Minecraft minecraft,
                                                    RenderTarget display) {
        if (!lateReplayFrame || plan.epoch() != frameEpoch || !isActive()
                || (sharedMaskFrame && (sharedMaskBackend == null || !sharedMaskBackend.current()))
                || plan.captureMode() != streamingMode(captureMode())
                || plan.irisActive() != IrisCompat.isShaderActive()
                || plan.cameraFirstPerson() != minecraft.options.getCameraType().isFirstPerson()
                || display == null || display != currentMainTarget()
                || display.width != plan.expectedDisplayWidth()
                || display.height != plan.expectedDisplayHeight()
                || eventRenderWidth != plan.expectedRenderWidth()
                || eventRenderHeight != plan.expectedRenderHeight()
                || eventScreenWidth != plan.expectedDisplayWidth()
                || eventScreenHeight != plan.expectedDisplayHeight()
                || eventAlgorithm.hashCode() != plan.algorithmFingerprint()
                || !finalHookObservedThisFrame || !hackWorldPrepareHookHit
                || !requiredAuthoritativeWorldHookObserved
                || !streamingDomains.isClosed(CaptureDomain.WORLD)
                || !hackUpscaleFinishHookHit || !upscaleFinished
                || upscaleCompletedEpoch != frameEpoch) return false;
        Object handler = currentHandler();
        if (handler == null || System.identityHashCode(handler.getClass()) != plan.handlerAbiFingerprint()) return false;
        if (plan.cameraFirstPerson() && (!hackHandHookHit || handDomainSnapshot == null
                || handDomainSnapshot.epoch() != frameEpoch || !handDomainSnapshot.ready()
                || !streamingDomains.isClosed(CaptureDomain.FIRST_PERSON))) return false;
        return true;
    }

    private static boolean lateEligibilityCurrent(GlowCaptureState state) {
        Eligibility eligibility = state.streamingEligibility();
        if (eligibility == null || state.captureEpoch != frameEpoch
                || eligibility.epoch() != frameEpoch || eligibility.domain() != state.captureDomain
                || state.captureStage() != CaptureStage.ELIGIBLE
                || !streamingDomains.isClosed(state.captureDomain)
                || !GlowCaptureManager.lateReplayPayloadReady(state)) return false;
        if (state.captureDomain == CaptureDomain.FIRST_PERSON) {
            return hackHandHookHit && handDomainSnapshot != null
                    && handDomainSnapshot.epoch() == frameEpoch && handDomainSnapshot.ready();
        }
        return worldDomainSnapshot != null && worldDomainSnapshot.epoch() == frameEpoch
                && worldDomainSnapshot.authority() == SnapshotAuthority.AUTHORITATIVE
                && eligibility.snapshotGeneration() == worldDomainSnapshot.depthGeneration()
                && eligibility.snapshotAuthority() == worldDomainSnapshot.authority()
                && GlowCaptureManager.isSceneDepthSnapshotCurrent(worldDomainSnapshot.depthGeneration(),
                worldDomainSnapshot.width(), worldDomainSnapshot.height());
    }

    /** Complete one HEAD-selected late frame; any failure drops it without invoking legacy. */
    private static void compositeLateReplayFrame(Minecraft minecraft, RenderTarget display) {
        if (lateReplayFinalConsumed) return;
        lateReplayFinalConsumed = true;
        boolean complete = false;
        try {
            SrFramePlan head = streamingFramePlan;
            refreshRuntimeSnapshot();
            if (!lateFrameEvidenceCurrent(head, minecraft, display)) {
                // A genuinely missing callback disables late replay on the NEXT frame. A resize
                // or stale extent alone is just a dropped transition frame, not a missing ABI.
                if (head.epoch() == frameEpoch && head.captureMode() == streamingMode(captureMode())) {
                    if (!requiredAuthoritativeWorldHookObserved) {
                        markCapability(head.captureMode(), HookKind.WORLD, HookCapabilityState.DISABLED);
                    }
                    if (!hackUpscaleFinishHookHit) {
                        markCapability(head.captureMode(), HookKind.UPSCALE_FINISH, HookCapabilityState.DISABLED);
                    }
                    if (head.cameraFirstPerson() && !hackHandHookHit) {
                        markCapability(head.captureMode(), HookKind.FIRST_PERSON, HookCapabilityState.DISABLED);
                    }
                }
                return;
            }
            List<GlowCaptureState> states = new ArrayList<>();
            List<Eligibility> eligibility = new ArrayList<>();
            for (var state : GlowCaptureManager.getActiveStates()) {
                if (!state.capturedThisFrame || state.compositedThisFrame) continue;
                if (!lateEligibilityCurrent(state)) return;
                states.add(state);
                eligibility.add(state.streamingEligibility());
            }
            if (states.isEmpty()) {
                complete = true;
                return;
            }
            var factory = new SrStreamingCoordinator.PreparedFramePlanFactory(frameEpoch, upscaleCompletedEpoch);
            if (!factory.eligibilityPreflight(eligibility, true)) return;
            // These are facts from this frame, not the historical capability used for HEAD selection.
            var observed = new ModeCapability(HookCapabilityState.ENABLED,
                    hackHandHookHit ? HookCapabilityState.ENABLED : HookCapabilityState.UNKNOWN,
                    HookCapabilityState.ENABLED, HookCapabilityState.ENABLED,
                    streamingCapabilities.capability(head.captureMode()).backendOrdering());
            if (!factory.selectHackExecutionMode(sharedMaskFrame
                    ? SrStreamingCoordinator.FrameExecutionMode.HACK_SHARED_OUTPUT
                    : SrStreamingCoordinator.FrameExecutionMode.HACK_PER_STATE_OUTPUT,
                    observed, head.cameraFirstPerson())) return;
            int width = head.expectedDisplayWidth(), height = head.expectedDisplayHeight();
            boolean hasWorld = states.stream().anyMatch(state -> !state.firstPerson);
            if (!GlowCaptureManager.prepareLateReplayTargets(states, width, height)) return;
            try (var masks = sharedMaskFrame ? GlowCaptureManager.prepareSharedMaskFrame() : null;
                 var composite = GlowComposite.prepareLateCompositeFrame(minecraft, display)) {
                if (composite == null || (sharedMaskFrame && masks == null)) return;
                boolean foreground = GlowCaptureManager.getForegroundDepthTarget() != null;
                boolean displayDepth = GlowCaptureManager.getSuperResolutionDisplaySceneDepthTarget() != null;
                var resources = new SrStreamingCoordinator.PreparedFrameResources(
                        sharedMaskFrame ? SrStreamingCoordinator.ReplayTargetMode.SHARED_MASK
                                : SrStreamingCoordinator.ReplayTargetMode.STATE_MASK,
                        hasWorld ? worldDomainSnapshot.depthGeneration() : -1L,
                        foreground ? GlowCaptureManager.getForegroundDepthGeneration() : -1L,
                        -1L, width, height);
                if (!factory.prepareFrameResources(resources, true)) return;
                refreshRuntimeSnapshot();
                boolean valid = lateFrameEvidenceCurrent(head, minecraft, display) && composite.valid();
                for (var state : states) {
                    var mask = sharedMaskFrame ? masks.target() : state.maskTarget;
                    valid &= lateEligibilityCurrent(state) && composite.canComposite(state, mask)
                            && GlowCaptureManager.lateReplayTargetMatches(mask, width, height);
                }
                if (!factory.validateFinalResources(valid)) return;
                var prepared = factory.createFramePlan();
                if (prepared.isEmpty()) return;
                var frame = prepared.get();
                var plans = frame.replayPlanFactory();
                for (var state : states) {
                    var spec = SrStreamingCoordinator.currentHackOutputStateSpec(state.captureDomain,
                            head.irisActive(), displayDepth, foreground, head.cameraFirstPerson());
                    var plan = plans.createPlan(state.streamingEligibility(), spec);
                    if (plan.isEmpty() || !state.scheduleStreamingReplay(plan.get())) return;
                }
                complete = replayPreparedStates(states,
                        state -> lateFrameEvidenceCurrent(head, minecraft, display)
                                && state.streamingReplayPlan().preparedFramePlan() == frame
                                && (!sharedMaskFrame || masks.beginState(state))
                                && GlowCaptureManager.replayScheduledMask(state, minecraft,
                                sharedMaskFrame ? masks.targetFor(state) : state.maskTarget),
                        state -> lateFrameEvidenceCurrent(head, minecraft, display)
                                && composite.compositePreparedState(state,
                                sharedMaskFrame ? masks.targetFor(state) : state.maskTarget)
                                && (!sharedMaskFrame || masks.finishState(state)),
                        GlowCaptureManager::abortPendingPayloads);
            }
        } finally {
            if (!complete) {
                try {
                    GlowCaptureManager.abortPendingPayloads();
                } finally {
                    if (sharedMaskFrame) GlowCaptureManager.abortSharedMaskFrame();
                }
            }
        }
    }
    //#endif

    private static boolean hasPendingWorldCapture() {
        for (var state : GlowCaptureManager.getActiveStates()) {
            if (state.capturedThisFrame && !state.compositedThisFrame
                    && !state.firstPerson) return true;
        }
        return false;
    }

    private static boolean hasPendingFirstPersonCapture() {
        for (var state : GlowCaptureManager.getActiveStates()) {
            if (state.capturedThisFrame && !state.compositedThisFrame
                    && state.firstPerson) return true;
        }
        return false;
    }

    static boolean missedFirstPersonDomainHook(
            boolean hasPendingFirstPersonCapture, boolean handHookHit) {
        return hasPendingFirstPersonCapture && !handHookHit;
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
