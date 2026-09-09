package cn.spectra.gallium.glowoutline.sr.streaming;

import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Pure scheduling model for Super Resolution streaming replay.
 *
 * <p>This class deliberately has no Minecraft or GPU dependencies. Phase 0/1 can therefore
 * validate policy, hook capability, domain closure, and one-shot replay invariants on every
 * supported Minecraft line without changing the existing render path. The live renderer does not
 * acquire shared mask ownership until a later phase.</p>
 */
public final class SrStreamingCoordinator {

    private SrStreamingCoordinator() {}

    public enum FrameIntent {
        LEGACY,
        HACK_STREAMING_CANDIDATE
    }

    public enum StreamingCapability {
        UNKNOWN,
        PROBING,
        ENABLED,
        DISABLED
    }

    public enum MaskOwnershipMode {
        PER_STATE,
        STREAMING_SHARED
    }

    public enum SceneDepthCapturePolicy {
        SNAPSHOT_AND_PREFILL,
        SNAPSHOT_ONLY
    }

    public enum CaptureMode {
        A,
        B,
        C,
        UNKNOWN
    }

    public enum CaptureDomain {
        WORLD,
        FIRST_PERSON
    }

    public enum HookKind {
        WORLD,
        FIRST_PERSON,
        UPSCALE_FINISH,
        FINAL,
        BACKEND_ORDERING
    }

    public enum HookCapabilityState {
        UNKNOWN,
        PROBING,
        ENABLED,
        DISABLED
    }

    public enum CaptureStage {
        IDLE,
        CAPTURING,
        CAPTURED,
        ELIGIBLE,
        SCHEDULED,
        REPLAY_ATTEMPTED,
        COMPOSITED,
        INVALID
    }

    public enum SnapshotSite {
        VANILLA_PRE_HAND,
        SR_PRE_UPSCALE,
        INLINE_HAND_TAIL,
        SR_SEPARATED_HAND_PRE_RESTORE
    }

    public enum SnapshotAuthority {
        PROVISIONAL,
        AUTHORITATIVE
    }

    public enum SnapshotUpdateDecision {
        ACCEPT,
        REPLACE,
        VALIDATE_ONLY
    }

    public enum GenerationChange {
        UNCHANGED,
        INVALIDATE_PREPARED_DEPTH,
        SYSTEMIC_ABORT
    }

    public enum FrameExecutionMode {
        ORDINARY_NATIVE_FINAL,
        SHADER_INTERFACE_OUTPUT,
        HACK_PER_STATE_OUTPUT,
        HACK_SHARED_OUTPUT,
        DROP_FRAME
    }

    public enum ReplayTargetMode {
        STATE_MASK,
        SHARED_MASK
    }

    public enum PackTransformPolicy {
        NATIVE_INTERNAL,
        OUTPUT_FULL_EXTENT
    }

    /** Depth preparation used by each replay domain. */
    public enum MaskDepthStrategy {
        CURRENT_NATIVE,
        SOURCE_VISIBLE_NATIVE,
        CLEAR_FAR
    }

    /** Coordinate-coherent scene-depth routes used by the existing composite path. */
    public enum SceneDepthRoute {
        MASK,
        DISPLAY_SCENE,
        FOREGROUND,
        NONE
    }

    /** Five independent hook/backend facts retained for one SR capture mode. */
    public record ModeCapability(
            HookCapabilityState worldHook,
            HookCapabilityState firstPersonHook,
            HookCapabilityState upscaleFinishHook,
            HookCapabilityState finalHook,
            HookCapabilityState backendOrdering) {

        public ModeCapability {
            Objects.requireNonNull(worldHook, "worldHook");
            Objects.requireNonNull(firstPersonHook, "firstPersonHook");
            Objects.requireNonNull(upscaleFinishHook, "upscaleFinishHook");
            Objects.requireNonNull(finalHook, "finalHook");
            Objects.requireNonNull(backendOrdering, "backendOrdering");
        }

        public static ModeCapability unknown() {
            return new ModeCapability(
                    HookCapabilityState.UNKNOWN,
                    HookCapabilityState.UNKNOWN,
                    HookCapabilityState.UNKNOWN,
                    HookCapabilityState.UNKNOWN,
                    HookCapabilityState.UNKNOWN);
        }

        public HookCapabilityState hook(HookKind kind) {
            Objects.requireNonNull(kind, "kind");
            return switch (kind) {
                case WORLD -> worldHook;
                case FIRST_PERSON -> firstPersonHook;
                case UPSCALE_FINISH -> upscaleFinishHook;
                case FINAL -> finalHook;
                case BACKEND_ORDERING -> backendOrdering;
            };
        }

        public ModeCapability withHook(HookKind kind, HookCapabilityState state) {
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(state, "state");
            return switch (kind) {
                case WORLD -> new ModeCapability(
                        state, firstPersonHook, upscaleFinishHook, finalHook, backendOrdering);
                case FIRST_PERSON -> new ModeCapability(
                        worldHook, state, upscaleFinishHook, finalHook, backendOrdering);
                case UPSCALE_FINISH -> new ModeCapability(
                        worldHook, firstPersonHook, state, finalHook, backendOrdering);
                case FINAL -> new ModeCapability(
                        worldHook, firstPersonHook, upscaleFinishHook, state, backendOrdering);
                case BACKEND_ORDERING -> new ModeCapability(
                        worldHook, firstPersonHook, upscaleFinishHook, finalHook, state);
            };
        }
    }

    /** Mutable registry; every stored value remains immutable and isolated by capture mode. */
    public static final class CapabilityTracker {
        private final EnumMap<CaptureMode, ModeCapability> capabilityByMode =
                new EnumMap<>(CaptureMode.class);
        private final EnumMap<CaptureMode, Integer> handlerAbiFingerprintByMode =
                new EnumMap<>(CaptureMode.class);

        public CapabilityTracker() {
            reset();
        }

        public ModeCapability capability(CaptureMode mode) {
            Objects.requireNonNull(mode, "mode");
            return capabilityByMode.get(mode);
        }

        public void update(CaptureMode mode, HookKind hook, HookCapabilityState state) {
            Objects.requireNonNull(mode, "mode");
            Objects.requireNonNull(hook, "hook");
            Objects.requireNonNull(state, "state");
            ModeCapability current = capability(mode);
            // A proven-missing hook is terminal for this ABI. Ordinary observations cannot
            // resurrect candidate policy; only resetMode/reset after an ABI/reload change can.
            if (current.hook(hook) == HookCapabilityState.DISABLED) return;
            capabilityByMode.put(mode, current.withHook(hook, state));
        }

        /** Records ABI identity per capture mode and clears only that mode on a real change. */
        public boolean observeHandlerAbiFingerprint(CaptureMode mode, int fingerprint) {
            Objects.requireNonNull(mode, "mode");
            if (mode == CaptureMode.UNKNOWN || fingerprint == 0) return false;
            Integer previous = handlerAbiFingerprintByMode.put(mode, fingerprint);
            if (previous == null || previous == 0 || previous == fingerprint) return false;
            capabilityByMode.put(mode, ModeCapability.unknown());
            return true;
        }

        public void resetMode(CaptureMode mode) {
            Objects.requireNonNull(mode, "mode");
            capabilityByMode.put(mode, ModeCapability.unknown());
            handlerAbiFingerprintByMode.remove(mode);
        }

        public void reset() {
            handlerAbiFingerprintByMode.clear();
            for (CaptureMode mode : CaptureMode.values()) {
                capabilityByMode.put(mode, ModeCapability.unknown());
            }
        }

        public Map<CaptureMode, ModeCapability> snapshot() {
            return Collections.unmodifiableMap(new EnumMap<>(capabilityByMode));
        }
    }

    /** The three orthogonal policy axes plus their derived scene-depth capture behaviour. */
    public record FramePolicy(
            FrameIntent intent,
            StreamingCapability capability,
            MaskOwnershipMode ownershipMode,
            SceneDepthCapturePolicy sceneDepthCapturePolicy) {

        public FramePolicy {
            Objects.requireNonNull(intent, "intent");
            Objects.requireNonNull(capability, "capability");
            Objects.requireNonNull(ownershipMode, "ownershipMode");
            Objects.requireNonNull(sceneDepthCapturePolicy, "sceneDepthCapturePolicy");

            if (intent == FrameIntent.LEGACY) {
                if (ownershipMode != MaskOwnershipMode.PER_STATE
                        || sceneDepthCapturePolicy
                        != SceneDepthCapturePolicy.SNAPSHOT_AND_PREFILL) {
                    throw new IllegalArgumentException(
                            "Legacy frames require per-state masks and snapshot+prefill");
                }
            } else if (sceneDepthCapturePolicy != SceneDepthCapturePolicy.SNAPSHOT_ONLY) {
                throw new IllegalArgumentException("Candidate frames require snapshot-only depth");
            }

            if (capability == StreamingCapability.DISABLED
                    && intent != FrameIntent.LEGACY) {
                throw new IllegalArgumentException("Disabled capability cannot be a candidate");
            }
            if ((capability == StreamingCapability.UNKNOWN
                    || capability == StreamingCapability.PROBING)
                    && ownershipMode != MaskOwnershipMode.PER_STATE) {
                throw new IllegalArgumentException("Unknown/probing frames retain per-state masks");
            }
            if (ownershipMode == MaskOwnershipMode.STREAMING_SHARED
                    && (intent != FrameIntent.HACK_STREAMING_CANDIDATE
                    || capability != StreamingCapability.ENABLED)) {
                throw new IllegalArgumentException(
                        "Shared ownership requires an enabled candidate frame");
            }
        }
    }

    /** Immutable HEAD-time plan; authoritative world/hand snapshots are recorded separately. */
    public record SrFramePlan(
            long epoch,
            FramePolicy policy,
            CaptureMode captureMode,
            int expectedRenderWidth,
            int expectedRenderHeight,
            int expectedDisplayWidth,
            int expectedDisplayHeight,
            boolean irisActive,
            boolean cameraFirstPerson,
            int handlerAbiFingerprint,
            int algorithmFingerprint) {

        public SrFramePlan {
            if (epoch < 0L) throw new IllegalArgumentException("epoch must be non-negative");
            if (expectedRenderWidth < 0 || expectedRenderHeight < 0
                    || expectedDisplayWidth < 0 || expectedDisplayHeight < 0) {
                throw new IllegalArgumentException("expected extents cannot be negative");
            }
            Objects.requireNonNull(policy, "policy");
            Objects.requireNonNull(captureMode, "captureMode");
        }
    }

    /**
     * Aggregate the hook facts needed at frame HEAD. A first-person camera conservatively
     * requires the current mode's hand hook before any later shared-ownership phase may start.
     */
    public static StreamingCapability deriveStreamingCapability(
            ModeCapability capability, boolean firstPersonCamera) {
        // Preserve the Phase 0/1 HEAD policy while late replay remains model-only.
        return sharedReuseReadiness(capability, firstPersonCamera);
    }

    /** Hook readiness for late replay into independently owned per-state output masks. */
    public static StreamingCapability lateReplayReadiness(
            ModeCapability capability, boolean firstPersonCamera) {
        Objects.requireNonNull(capability, "capability");
        HookCapabilityState[] required = firstPersonCamera
                ? new HookCapabilityState[] {
                    capability.worldHook(),
                    capability.firstPersonHook(),
                    capability.upscaleFinishHook(),
                    capability.finalHook()
                }
                : new HookCapabilityState[] {
                    capability.worldHook(),
                    capability.upscaleFinishHook(),
                    capability.finalHook()
                };

        boolean allEnabled = true;
        for (HookCapabilityState state : required) {
            if (state == HookCapabilityState.DISABLED) return StreamingCapability.DISABLED;
            if (state != HookCapabilityState.ENABLED) allEnabled = false;
        }
        return allEnabled ? StreamingCapability.ENABLED : StreamingCapability.PROBING;
    }

    /** Reusing one shared mask additionally requires proven backend consumption ordering. */
    public static StreamingCapability sharedReuseReadiness(
            ModeCapability capability, boolean firstPersonCamera) {
        StreamingCapability replay = lateReplayReadiness(capability, firstPersonCamera);
        if (replay == StreamingCapability.DISABLED
                || capability.backendOrdering() == HookCapabilityState.DISABLED) {
            return StreamingCapability.DISABLED;
        }
        return replay == StreamingCapability.ENABLED
                && capability.backendOrdering() == HookCapabilityState.ENABLED
                ? StreamingCapability.ENABLED : StreamingCapability.PROBING;
    }

    /**
     * Derive the Phase 0/1 policy. It intentionally never selects shared ownership: that switch
     * belongs to the later resource-ownership phase.
     */
    public static FramePolicy derivePhaseOnePolicy(
            boolean modernFinalHookBuild,
            boolean hackConfigured,
            CaptureMode mode,
            boolean firstPersonCamera,
            ModeCapability modeCapability) {
        Objects.requireNonNull(mode, "mode");
        Objects.requireNonNull(modeCapability, "modeCapability");
        StreamingCapability capability = mode == CaptureMode.UNKNOWN
                ? StreamingCapability.UNKNOWN
                : deriveStreamingCapability(modeCapability, firstPersonCamera);

        if (!modernFinalHookBuild || !hackConfigured || mode == CaptureMode.UNKNOWN
                || capability == StreamingCapability.DISABLED) {
            return new FramePolicy(
                    FrameIntent.LEGACY,
                    capability,
                    MaskOwnershipMode.PER_STATE,
                    SceneDepthCapturePolicy.SNAPSHOT_AND_PREFILL);
        }
        return new FramePolicy(
                FrameIntent.HACK_STREAMING_CANDIDATE,
                capability,
                MaskOwnershipMode.PER_STATE,
                SceneDepthCapturePolicy.SNAPSHOT_ONLY);
    }

    /** Integer encoding used by the repository's preprocess graph. */
    public static boolean supportsModernFinalHookBuild(int encodedMinecraftVersion) {
        return encodedMinecraftVersion == 1_21_11
                || encodedMinecraftVersion == 1_26_01
                || encodedMinecraftVersion == 1_26_02;
    }

    public static boolean canTransition(CaptureStage from, CaptureStage to) {
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(to, "to");
        if (to == CaptureStage.INVALID) return true;
        return switch (from) {
            case IDLE -> to == CaptureStage.CAPTURING;
            case CAPTURING -> to == CaptureStage.CAPTURED;
            case CAPTURED -> to == CaptureStage.ELIGIBLE;
            case ELIGIBLE -> to == CaptureStage.SCHEDULED;
            case SCHEDULED -> to == CaptureStage.REPLAY_ATTEMPTED;
            case REPLAY_ATTEMPTED -> to == CaptureStage.COMPOSITED;
            case COMPOSITED, INVALID -> false;
        };
    }

    public static CaptureStage transition(CaptureStage from, CaptureStage to) {
        if (!canTransition(from, to)) {
            throw new IllegalStateException("Illegal capture transition " + from + " -> " + to);
        }
        return to;
    }

    /** Immutable evidence produced when a domain closes around an already-captured payload. */
    public record Eligibility(
            long epoch,
            CaptureDomain domain,
            long snapshotGeneration,
            SnapshotAuthority snapshotAuthority) {

        public Eligibility {
            if (epoch < 0L) throw new IllegalArgumentException("epoch must be non-negative");
            if (snapshotGeneration < -1L) {
                throw new IllegalArgumentException("snapshotGeneration must be -1 or newer");
            }
            Objects.requireNonNull(domain, "domain");
            Objects.requireNonNull(snapshotAuthority, "snapshotAuthority");
        }
    }

    /** Per-frame closure state. Captures beginning after their domain closes must fail closed. */
    public static final class DomainLifecycle {
        private long epoch;
        private final EnumSet<CaptureDomain> closed = EnumSet.noneOf(CaptureDomain.class);

        public DomainLifecycle(long epoch) {
            reset(epoch);
        }

        public long epoch() {
            return epoch;
        }

        public boolean canBeginCapture(long captureEpoch, CaptureDomain domain) {
            Objects.requireNonNull(domain, "domain");
            return captureEpoch == epoch && !closed.contains(domain);
        }

        public boolean close(long closeEpoch, CaptureDomain domain) {
            Objects.requireNonNull(domain, "domain");
            if (closeEpoch != epoch) return false;
            return closed.add(domain);
        }

        public boolean isClosed(CaptureDomain domain) {
            Objects.requireNonNull(domain, "domain");
            return closed.contains(domain);
        }

        public Optional<Eligibility> eligibilityFor(
                long stateEpoch,
                CaptureDomain domain,
                CaptureStage stage,
                long snapshotGeneration,
                SnapshotAuthority authority) {
            Objects.requireNonNull(domain, "domain");
            Objects.requireNonNull(stage, "stage");
            Objects.requireNonNull(authority, "authority");
            if (stateEpoch != epoch || !closed.contains(domain)
                    || stage != CaptureStage.CAPTURED) {
                return Optional.empty();
            }
            return Optional.of(new Eligibility(
                    epoch, domain, snapshotGeneration, authority));
        }

        public void reset(long nextEpoch) {
            if (nextEpoch < 0L) throw new IllegalArgumentException("epoch must be non-negative");
            epoch = nextEpoch;
            closed.clear();
        }
    }

    public record WorldDomainSnapshot(
            long epoch,
            boolean hookObserved,
            SnapshotSite site,
            SnapshotAuthority authority,
            boolean snapshotSucceeded,
            int width,
            int height,
            long depthGeneration,
            int sourceTargetIdentity,
            int sourceDepthIdentity) {

        public WorldDomainSnapshot {
            if (epoch < 0L) throw new IllegalArgumentException("epoch must be non-negative");
            if (width < 0 || height < 0 || depthGeneration < -1L) {
                throw new IllegalArgumentException("invalid snapshot dimensions/generation");
            }
            Objects.requireNonNull(site, "site");
            Objects.requireNonNull(authority, "authority");
        }

    }

    /** Hand scheduling remains independent from the optional foreground-depth copy. */
    public record HandDomainSnapshot(
            long epoch,
            boolean hookObserved,
            SnapshotSite site,
            boolean schedulingSucceeded,
            int scheduledFirstPersonCount,
            boolean foregroundRequired,
            boolean foregroundCopySucceeded,
            long foregroundDepthGeneration,
            int width,
            int height) {

        public HandDomainSnapshot {
            if (epoch < 0L) throw new IllegalArgumentException("epoch must be non-negative");
            if (scheduledFirstPersonCount < 0 || foregroundDepthGeneration < -1L
                    || width < 0 || height < 0) {
                throw new IllegalArgumentException("invalid hand snapshot values");
            }
            Objects.requireNonNull(site, "site");
        }

        public boolean ready() {
            return hookObserved && schedulingSucceeded
                    && (!foregroundRequired || foregroundCopySucceeded);
        }
    }

    /** Authority table for the two world snapshot call sites. */
    public static SnapshotAuthority worldSnapshotAuthority(
            CaptureMode mode, SnapshotSite site) {
        Objects.requireNonNull(mode, "mode");
        Objects.requireNonNull(site, "site");
        if (site != SnapshotSite.VANILLA_PRE_HAND
                && site != SnapshotSite.SR_PRE_UPSCALE) {
            throw new IllegalArgumentException("Not a world snapshot site: " + site);
        }
        if (mode == CaptureMode.B) {
            return site == SnapshotSite.SR_PRE_UPSCALE
                    ? SnapshotAuthority.AUTHORITATIVE
                    : SnapshotAuthority.PROVISIONAL;
        }
        if (mode == CaptureMode.A || mode == CaptureMode.C) {
            return site == SnapshotSite.VANILLA_PRE_HAND
                    ? SnapshotAuthority.AUTHORITATIVE
                    : SnapshotAuthority.PROVISIONAL;
        }
        return SnapshotAuthority.PROVISIONAL;
    }

    public static SnapshotUpdateDecision decideSnapshotUpdate(
            Optional<SnapshotAuthority> existing, SnapshotAuthority incoming) {
        Objects.requireNonNull(existing, "existing");
        Objects.requireNonNull(incoming, "incoming");
        if (existing.isEmpty()) return SnapshotUpdateDecision.ACCEPT;
        if (existing.get() == SnapshotAuthority.PROVISIONAL
                && incoming == SnapshotAuthority.AUTHORITATIVE) {
            return SnapshotUpdateDecision.REPLACE;
        }
        return SnapshotUpdateDecision.VALIDATE_ONLY;
    }

    /** An authoritative hook always closes; SR pre-upscale is the final provisional boundary. */
    public static boolean shouldCloseWorldDomain(
            SnapshotSite site, SnapshotAuthority authority, boolean snapshotSucceeded) {
        Objects.requireNonNull(site, "site");
        Objects.requireNonNull(authority, "authority");
        return site == SnapshotSite.SR_PRE_UPSCALE
                || authority == SnapshotAuthority.AUTHORITATIVE;
    }

    /** A failed replacement never destroys an already-frozen usable snapshot. */
    public static WorldDomainSnapshot selectFrozenWorldSnapshot(
            WorldDomainSnapshot current, WorldDomainSnapshot candidate) {
        Objects.requireNonNull(candidate, "candidate");
        if (candidate.snapshotSucceeded()) return candidate;
        return current != null && current.snapshotSucceeded() ? current : candidate;
    }

    /** Increment only after an accepted/replacement copy succeeded. */
    public static long nextSnapshotGeneration(
            long currentGeneration,
            SnapshotUpdateDecision decision,
            boolean copySucceeded) {
        if (currentGeneration < -1L) {
            throw new IllegalArgumentException("generation must be -1 or newer");
        }
        Objects.requireNonNull(decision, "decision");
        if (!copySucceeded || decision == SnapshotUpdateDecision.VALIDATE_ONLY) {
            return currentGeneration;
        }
        return Math.incrementExact(currentGeneration);
    }

    /** Decide how a changed authoritative generation affects prepared state. */
    public static GenerationChange generationChange(
            CaptureStage stage, long preparedGeneration, long currentGeneration) {
        Objects.requireNonNull(stage, "stage");
        if (preparedGeneration == currentGeneration) return GenerationChange.UNCHANGED;
        if (stage == CaptureStage.REPLAY_ATTEMPTED
                || stage == CaptureStage.COMPOSITED) {
            return GenerationChange.SYSTEMIC_ABORT;
        }
        return GenerationChange.INVALIDATE_PREPARED_DEPTH;
    }

    /**
     * The one immutable set of resources selected for a frame. These values are deliberately
     * separated from per-state eligibility so that preparing a second capture cannot silently
     * change the resources already validated for the frame.
     */
    public record PreparedFrameResources(
            ReplayTargetMode targetMode,
            long worldSnapshotGeneration,
            long foregroundSnapshotGeneration,
            long pooledDepthGeneration,
            int outputWidth,
            int outputHeight) {

        public PreparedFrameResources {
            if (worldSnapshotGeneration < -1L || foregroundSnapshotGeneration < -1L
                    || pooledDepthGeneration < -1L) {
                throw new IllegalArgumentException("invalid frame-resource generation");
            }
            if (outputWidth <= 0 || outputHeight <= 0) {
                throw new IllegalArgumentException("output extent must be positive");
            }
            Objects.requireNonNull(targetMode, "targetMode");
        }
    }

    /** One normalized eligibility requirement frozen for a capture domain at preflight. */
    public record DomainEligibilityRequirement(
            long snapshotGeneration,
            SnapshotAuthority snapshotAuthority) {

        public DomainEligibilityRequirement {
            if (snapshotGeneration < -1L) {
                throw new IllegalArgumentException("snapshotGeneration must be -1 or newer");
            }
            Objects.requireNonNull(snapshotAuthority, "snapshotAuthority");
        }
    }

    /**
     * Frame-global final-hook result. A successful final hook creates exactly one instance; every
     * state plan for that frame retains this same instance and therefore the same execution mode
     * and resource set. DROP_FRAME deliberately has no PreparedFramePlan representation.
     */
    public record PreparedFramePlan(
            long epoch,
            FrameExecutionMode frameMode,
            PreparedFrameResources resources,
            Map<CaptureDomain, DomainEligibilityRequirement> eligibleDomainRequirements,
            boolean outputDepthAlignmentExact) {

        public PreparedFramePlan {
            if (epoch < 0L) throw new IllegalArgumentException("epoch must be non-negative");
            Objects.requireNonNull(frameMode, "frameMode");
            Objects.requireNonNull(resources, "resources");
            Objects.requireNonNull(eligibleDomainRequirements, "eligibleDomainRequirements");
            if (frameMode == FrameExecutionMode.DROP_FRAME) {
                throw new IllegalArgumentException("DROP_FRAME cannot own a PreparedFramePlan");
            }
            if (frameMode == FrameExecutionMode.HACK_SHARED_OUTPUT
                    && resources.targetMode() != ReplayTargetMode.SHARED_MASK) {
                throw new IllegalArgumentException("Shared execution requires a shared target");
            }
            if (frameMode != FrameExecutionMode.HACK_SHARED_OUTPUT
                    && resources.targetMode() == ReplayTargetMode.SHARED_MASK) {
                throw new IllegalArgumentException("Only shared execution may use a shared target");
            }
            EnumMap<CaptureDomain, DomainEligibilityRequirement> frozenRequirements =
                    new EnumMap<>(CaptureDomain.class);
            for (Map.Entry<CaptureDomain, DomainEligibilityRequirement> entry
                    : eligibleDomainRequirements.entrySet()) {
                frozenRequirements.put(
                        Objects.requireNonNull(entry.getKey(), "eligibility domain"),
                        Objects.requireNonNull(entry.getValue(), "eligibility requirement"));
            }
            if (frozenRequirements.isEmpty()) {
                throw new IllegalArgumentException(
                        "PreparedFramePlan requires at least one eligible domain");
            }
            if (!hasConsistentWorldRequirement(resources, frozenRequirements)) {
                throw new IllegalArgumentException(
                        "WORLD eligibility does not match prepared world resources");
            }
            eligibleDomainRequirements = Collections.unmodifiableMap(frozenRequirements);
        }

        public ReplayPlanFactory replayPlanFactory() {
            return new ReplayPlanFactory(this);
        }
    }

    /** State-local decisions made only after the frame mode and resources are fixed. */
    public record StateReplayPlanSpec(
            PackTransformPolicy packTransformPolicy,
            MaskDepthStrategy maskDepthStrategy,
            SceneDepthRoute sceneDepthRoute) {

        public StateReplayPlanSpec {
            Objects.requireNonNull(packTransformPolicy, "packTransformPolicy");
            Objects.requireNonNull(maskDepthStrategy, "maskDepthStrategy");
            Objects.requireNonNull(sceneDepthRoute, "sceneDepthRoute");
        }
    }

    /**
     * WORLD uses source-grid self visibility to depth-test independent native mesh copies,
     * retaining native alpha coverage. FIRST_PERSON still clears to far and self-compares. The scene route
     * mirrors GlowComposite.chooseSuperResolutionSceneDepth and is checked against it in tests.
     */
    public static StateReplayPlanSpec currentHackOutputStateSpec(
            CaptureDomain domain, boolean irisActive, boolean displaySceneAvailable,
            boolean foregroundAvailable, boolean cameraFirstPerson) {
        Objects.requireNonNull(domain, "domain");
        if (domain == CaptureDomain.FIRST_PERSON) {
            return new StateReplayPlanSpec(
                    PackTransformPolicy.OUTPUT_FULL_EXTENT,
                    MaskDepthStrategy.CLEAR_FAR, SceneDepthRoute.MASK);
        }
        SceneDepthRoute route;
        if (irisActive) {
            route = displaySceneAvailable ? SceneDepthRoute.DISPLAY_SCENE : SceneDepthRoute.MASK;
        } else if (foregroundAvailable) {
            route = SceneDepthRoute.FOREGROUND;
        } else {
            route = cameraFirstPerson ? SceneDepthRoute.NONE : SceneDepthRoute.MASK;
        }
        return new StateReplayPlanSpec(
                PackTransformPolicy.OUTPUT_FULL_EXTENT, MaskDepthStrategy.SOURCE_VISIBLE_NATIVE, route);
    }

    /** Immutable per-state plan derived from the frame-global prepared plan. */
    public record ReplayPlan(
            PreparedFramePlan preparedFramePlan,
            CaptureDomain domain,
            long captureSnapshotGeneration,
            SnapshotAuthority captureSnapshotAuthority,
            StateReplayPlanSpec stateSpec) {

        public ReplayPlan {
            Objects.requireNonNull(preparedFramePlan, "preparedFramePlan");
            Objects.requireNonNull(domain, "domain");
            Objects.requireNonNull(captureSnapshotAuthority, "captureSnapshotAuthority");
            Objects.requireNonNull(stateSpec, "stateSpec");
            if (captureSnapshotGeneration < -1L) {
                throw new IllegalArgumentException("capture generation must be -1 or newer");
            }
        }

        public long epoch() {
            return preparedFramePlan.epoch();
        }

        public FrameExecutionMode frameMode() {
            return preparedFramePlan.frameMode();
        }

        public ReplayTargetMode targetMode() {
            return preparedFramePlan.resources().targetMode();
        }

        public PackTransformPolicy packTransformPolicy() {
            return stateSpec.packTransformPolicy();
        }

        public MaskDepthStrategy maskDepthStrategy() {
            return stateSpec.maskDepthStrategy();
        }

        public SceneDepthRoute sceneDepthRoute() {
            return stateSpec.sceneDepthRoute();
        }

        public long worldSnapshotGeneration() {
            return preparedFramePlan.resources().worldSnapshotGeneration();
        }

        public long foregroundSnapshotGeneration() {
            return preparedFramePlan.resources().foregroundSnapshotGeneration();
        }

        public long pooledDepthGeneration() {
            return preparedFramePlan.resources().pooledDepthGeneration();
        }

        public int outputWidth() {
            return preparedFramePlan.resources().outputWidth();
        }

        public int outputHeight() {
            return preparedFramePlan.resources().outputHeight();
        }

        public boolean outputDepthAlignmentExact() {
            return preparedFramePlan.outputDepthAlignmentExact();
        }
    }

    private static boolean matchesFrozenEligibility(
            PreparedFramePlan framePlan, Eligibility eligibility) {
        if (eligibility == null || eligibility.epoch() != framePlan.epoch()) return false;
        DomainEligibilityRequirement required =
                framePlan.eligibleDomainRequirements().get(eligibility.domain());
        return required != null
                && required.snapshotGeneration() == eligibility.snapshotGeneration()
                && required.snapshotAuthority() == eligibility.snapshotAuthority();
    }

    private static boolean hasConsistentWorldRequirement(
            PreparedFrameResources resources,
            Map<CaptureDomain, DomainEligibilityRequirement> requirements) {
        DomainEligibilityRequirement world = requirements.get(CaptureDomain.WORLD);
        return world == null || world.snapshotGeneration() >= 0L
                && world.snapshotGeneration() == resources.worldSnapshotGeneration();
    }

    private static boolean hasRequiredStateResources(
            PreparedFramePlan framePlan,
            Eligibility eligibility,
            StateReplayPlanSpec stateSpec) {
        PreparedFrameResources resources = framePlan.resources();
        if (eligibility.domain() == CaptureDomain.WORLD
                && eligibility.snapshotGeneration() != resources.worldSnapshotGeneration()) {
            return false;
        }
        if ((stateSpec.maskDepthStrategy() == MaskDepthStrategy.SOURCE_VISIBLE_NATIVE
                || stateSpec.sceneDepthRoute() == SceneDepthRoute.DISPLAY_SCENE)
                && resources.worldSnapshotGeneration() < 0L) {
            return false;
        }
        return stateSpec.sceneDepthRoute() != SceneDepthRoute.FOREGROUND
                || resources.foregroundSnapshotGeneration() >= 0L;
    }

    public enum PreparedFramePlanStage {
        NEW,
        ELIGIBILITY_PREFLIGHTED,
        MODE_SELECTED,
        RESOURCES_PREPARED,
        RESOURCES_VALIDATED,
        FRAME_PLAN_CREATED,
        DROPPED
    }

    /**
     * One-shot frame-global final-hook preparation. It enforces preflight, unique execution-mode
     * selection, resource preparation, and validation before exposing a PreparedFramePlan. It
     * does not consume a capture payload or move geometry replay.
     */
    public static final class PreparedFramePlanFactory {
        private final long epoch;
        private final long upscaleCompletedEpoch;
        private PreparedFramePlanStage stage = PreparedFramePlanStage.NEW;
        private FrameExecutionMode executionMode;
        private PreparedFrameResources preparedResources;
        private Map<CaptureDomain, DomainEligibilityRequirement> eligibleDomainRequirements;
        private PreparedFramePlan framePlan;

        public PreparedFramePlanFactory(long epoch, long upscaleCompletedEpoch) {
            if (epoch < 0L) throw new IllegalArgumentException("epoch must be non-negative");
            this.epoch = epoch;
            this.upscaleCompletedEpoch = upscaleCompletedEpoch;
        }

        public PreparedFramePlanStage stage() {
            return stage;
        }

        public boolean eligibilityPreflight(
                Iterable<Eligibility> candidates, boolean passed) {
            requireStage(PreparedFramePlanStage.NEW);
            if (!passed || candidates == null) {
                stage = PreparedFramePlanStage.DROPPED;
                return false;
            }
            EnumMap<CaptureDomain, DomainEligibilityRequirement> normalized =
                    new EnumMap<>(CaptureDomain.class);
            for (Eligibility candidate : candidates) {
                if (candidate == null || candidate.epoch() != epoch) {
                    stage = PreparedFramePlanStage.DROPPED;
                    return false;
                }
                DomainEligibilityRequirement requirement = new DomainEligibilityRequirement(
                        candidate.snapshotGeneration(), candidate.snapshotAuthority());
                DomainEligibilityRequirement previous = normalized.putIfAbsent(
                        candidate.domain(), requirement);
                if (previous != null && !previous.equals(requirement)) {
                    stage = PreparedFramePlanStage.DROPPED;
                    return false;
                }
            }
            if (normalized.isEmpty()) {
                stage = PreparedFramePlanStage.DROPPED;
                return false;
            }
            eligibleDomainRequirements = Collections.unmodifiableMap(normalized);
            stage = PreparedFramePlanStage.ELIGIBILITY_PREFLIGHTED;
            return true;
        }

        public boolean selectExecutionMode(FrameExecutionMode selectedMode) {
            requireStage(PreparedFramePlanStage.ELIGIBILITY_PREFLIGHTED);
            Objects.requireNonNull(selectedMode, "selectedMode");
            if (selectedMode == FrameExecutionMode.DROP_FRAME) {
                stage = PreparedFramePlanStage.DROPPED;
                return false;
            }
            executionMode = selectedMode;
            stage = PreparedFramePlanStage.MODE_SELECTED;
            return true;
        }

        /**
         * Model-only selection after preflight. Per-state replay does not depend on backend
         * ordering; shared reuse does. Neither readiness result grants live mask ownership.
         */
        public boolean selectHackExecutionMode(
                FrameExecutionMode selectedMode, ModeCapability capability,
                boolean firstPersonCamera) {
            requireStage(PreparedFramePlanStage.ELIGIBILITY_PREFLIGHTED);
            Objects.requireNonNull(selectedMode, "selectedMode");
            StreamingCapability readiness = switch (selectedMode) {
                case HACK_PER_STATE_OUTPUT -> lateReplayReadiness(capability, firstPersonCamera);
                case HACK_SHARED_OUTPUT -> sharedReuseReadiness(capability, firstPersonCamera);
                default -> throw new IllegalArgumentException("Expected a hack output mode");
            };
            return selectExecutionMode(readiness == StreamingCapability.ENABLED
                    ? selectedMode : FrameExecutionMode.DROP_FRAME);
        }

        public boolean prepareFrameResources(
                PreparedFrameResources resources, boolean succeeded) {
            requireStage(PreparedFramePlanStage.MODE_SELECTED);
            if (!succeeded) {
                stage = PreparedFramePlanStage.DROPPED;
                return false;
            }
            preparedResources = Objects.requireNonNull(resources, "resources");
            stage = PreparedFramePlanStage.RESOURCES_PREPARED;
            return true;
        }

        public boolean validateFinalResources(boolean valid) {
            requireStage(PreparedFramePlanStage.RESOURCES_PREPARED);
            if (!valid) {
                stage = PreparedFramePlanStage.DROPPED;
                return false;
            }
            stage = PreparedFramePlanStage.RESOURCES_VALIDATED;
            return true;
        }

        public Optional<PreparedFramePlan> createFramePlan() {
            if (stage == PreparedFramePlanStage.DROPPED) return Optional.empty();
            requireStage(PreparedFramePlanStage.RESOURCES_VALIDATED);
            if (requiresCompletedUpscale(executionMode)
                    && upscaleCompletedEpoch != epoch) {
                stage = PreparedFramePlanStage.DROPPED;
                return Optional.empty();
            }
            if (!hasConsistentWorldRequirement(
                    preparedResources, eligibleDomainRequirements)) {
                stage = PreparedFramePlanStage.DROPPED;
                return Optional.empty();
            }
            framePlan = new PreparedFramePlan(
                    epoch, executionMode, preparedResources,
                    eligibleDomainRequirements, false);
            stage = PreparedFramePlanStage.FRAME_PLAN_CREATED;
            return Optional.of(framePlan);
        }

        public Optional<PreparedFramePlan> framePlan() {
            return Optional.ofNullable(framePlan);
        }

        private void requireStage(PreparedFramePlanStage expected) {
            if (stage != expected) {
                throw new IllegalStateException(
                        "Expected prepared-frame stage " + expected + " but was " + stage);
            }
        }
    }

    /** Stateless factory deriving any number of immutable state plans from one frame plan. */
    public static final class ReplayPlanFactory {
        private final PreparedFramePlan framePlan;

        public ReplayPlanFactory(PreparedFramePlan framePlan) {
            this.framePlan = Objects.requireNonNull(framePlan, "framePlan");
        }

        public PreparedFramePlan framePlan() {
            return framePlan;
        }

        public Optional<ReplayPlan> createPlan(
                Eligibility eligibility, StateReplayPlanSpec stateSpec) {
            Objects.requireNonNull(stateSpec, "stateSpec");
            if (!matchesFrozenEligibility(framePlan, eligibility)
                    || !hasRequiredStateResources(framePlan, eligibility, stateSpec)) {
                return Optional.empty();
            }
            return Optional.of(new ReplayPlan(
                    framePlan,
                    eligibility.domain(),
                    eligibility.snapshotGeneration(),
                    eligibility.snapshotAuthority(),
                    stateSpec));
        }
    }

    public static boolean requiresCompletedUpscale(FrameExecutionMode mode) {
        Objects.requireNonNull(mode, "mode");
        return mode == FrameExecutionMode.HACK_PER_STATE_OUTPUT
                || mode == FrameExecutionMode.HACK_SHARED_OUTPUT;
    }

    /** Pure one-shot lifecycle used by tests now and by GlowCaptureState in a later integration. */
    public static final class CaptureLifecycle {
        private CaptureStage stage = CaptureStage.IDLE;
        private Eligibility eligibility;
        private ReplayPlan replayPlan;

        public CaptureStage stage() {
            return stage;
        }

        public Optional<Eligibility> eligibility() {
            return Optional.ofNullable(eligibility);
        }

        public Optional<ReplayPlan> replayPlan() {
            return Optional.ofNullable(replayPlan);
        }

        public void beginCapture() {
            stage = transition(stage, CaptureStage.CAPTURING);
        }

        public void markCaptured() {
            stage = transition(stage, CaptureStage.CAPTURED);
        }

        public void markEligible(Eligibility newEligibility) {
            Objects.requireNonNull(newEligibility, "newEligibility");
            stage = transition(stage, CaptureStage.ELIGIBLE);
            eligibility = newEligibility;
        }

        public void schedule(ReplayPlan plan) {
            Objects.requireNonNull(plan, "plan");
            if (replayPlan != null) throw new IllegalStateException("ReplayPlan is immutable");
            if (eligibility == null || plan.epoch() != eligibility.epoch()
                    || plan.domain() != eligibility.domain()
                    || plan.captureSnapshotGeneration() != eligibility.snapshotGeneration()
                    || plan.captureSnapshotAuthority() != eligibility.snapshotAuthority()) {
                throw new IllegalArgumentException("ReplayPlan does not match eligibility");
            }
            stage = transition(stage, CaptureStage.SCHEDULED);
            replayPlan = plan;
        }

        /** Must be called immediately before entering a dispatcher or delayed-buffer flush. */
        public boolean beginReplay() {
            if (stage != CaptureStage.SCHEDULED) return false;
            stage = transition(stage, CaptureStage.REPLAY_ATTEMPTED);
            return true;
        }

        public void markComposited() {
            stage = transition(stage, CaptureStage.COMPOSITED);
        }

        public void invalidate() {
            if (stage != CaptureStage.INVALID) {
                stage = transition(stage, CaptureStage.INVALID);
            }
        }

        public void reset() {
            stage = CaptureStage.IDLE;
            eligibility = null;
            replayPlan = null;
        }
    }
}
