package cn.spectra.gallium.glowoutline;

import cn.spectra.gallium.Gallium;
import cn.spectra.gallium.glowoutline.sr.SrShaderPackContext;
import cn.spectra.gallium.glowoutline.sr.SrShaderPackResolver;
import cn.spectra.gallium.glowoutline.sr.SrShaderPackResolver.Session;
import cn.spectra.gallium.glowoutline.sr.runtime.ShaderPackProjectionResolver;
import cn.spectra.gallium.glowoutline.sr.runtime.SrProjectionResolver;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.Reader;
import java.io.StringReader;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import net.minecraft.client.Minecraft;
import org.jspecify.annotations.Nullable;

/**
 * Resolves the active shader pack's projection transform through Iris. Standard Super Resolution
 * legacy/schema-v1-v3 definitions are read automatically. A live standard SR definition wins
 * over legacy pack hints; an explicit {@code gallium.json} becomes authoritative for SR only when
 * it opts in with {@code override_sr_definition=true}. Internal-resolution scale and temporal
 * jitter are never inferred from arbitrary uniform names.
 * <p>
 * The hint file maps a pack-defined option to a float scale, optionally with an enum→float lookup
 * table for packs that expose the resolution as an integer step rather than a literal float:
 * <pre>{@code
 *   { "internal_resolution_scale": { "option": "ResolutionScale" } }
 * }</pre>
 * or
 * <pre>{@code
 *   { "internal_resolution_scale": {
 *       "option": "FSR2_SCALE",
 *       "values": { "0": 0.75, "1": 0.6667, "2": 0.5882, "3": 0.5 }
 *   } }
 * }</pre>
 * <p>
 * Iris reload is detected by identity comparison on {@code Iris.getCurrentPack()} — Iris always
 * constructs a fresh {@code ShaderPack} on reload, so a changed instance reference means the
 * hint and option lookup must re-run. Per-frame projection results are cached by Iris frame counter
 * and viewport size, so every captured item in a frame shares one immutable result.
 */
public final class ShaderPackHint {

    private static final float MIN_SCALE = 0.1f;
    private static final float MAX_SCALE = 1.0f;
    private static final String HINT_FILE_NAME = "gallium.json";
    private static final String SCALE_KEY = "internal_resolution_scale";
    private static final String JITTER_KEY = "temporal_jitter";
    private static final String REQUIREMENTS_KEY = "requires";
    private static final String OVERRIDE_SR_DEFINITION_KEY = "override_sr_definition";
    private static final String R2_SEQUENCE = "r2";
    private static final String NDC_PER_VIEW_SIZE = "ndc_per_view_size";
    private static final String AFTER_INTERNAL_SCALE = "after_internal_scale";

    /**
     * Post-projection transform for one Iris frame. Jitter values are NDC offsets applied after
     * the internal-resolution scale/translation. {@code exactTemporalJitter=false} means callers
     * must retain their generic depth-pool or z-bias fallback.
     */
    public record ProjectionTransform(float scaleX, float scaleY,
                                      float jitterX, float jitterY,
                                      float viewportOriginX, float viewportOriginY,
                                      boolean exactTemporalJitter) {
        public static final ProjectionTransform IDENTITY =
                new ProjectionTransform(1.0f, 1.0f, 0.0f, 0.0f,
                        0.0f, 0.0f, false);

        /** Backwards-compatible constructor for gallium.json declarations with origin (0,0). */
        public ProjectionTransform(float scaleX, float scaleY,
                                   float jitterX, float jitterY,
                                   boolean exactTemporalJitter) {
            this(scaleX, scaleY, jitterX, jitterY, 0.0f, 0.0f,
                    exactTemporalJitter);
        }

        public boolean changesProjection() {
            return scaleX != 1.0f || scaleY != 1.0f
                    || jitterX != 0.0f || jitterY != 0.0f
                    || viewportOriginX != 0.0f || viewportOriginY != 0.0f;
        }

        public ProjectionTransform withoutTemporalJitter() {
            return new ProjectionTransform(scaleX, scaleY, 0.0f, 0.0f,
                    viewportOriginX, viewportOriginY, false);
        }

        /** NDC translation supplied to Gallium's post-projection scale matrix. */
        public float projectionOffsetX() { return 2.0f * viewportOriginX + jitterX; }
        public float projectionOffsetY() { return 2.0f * viewportOriginY + jitterY; }

        /** Full-texture UV offsets consumed by the world composite shader. */
        public float uvOffsetX() { return viewportOriginX + jitterX * 0.5f; }
        public float uvOffsetY() { return viewportOriginY + jitterY * 0.5f; }
    }

    private record TemporalJitter(float phase, float xMultiplier, float yMultiplier,
                                  int indexOffset, int period) {}

    private record ScaleResolution(float scale, boolean resolved) {}

    private record HintConfig(float scale, boolean scaleResolved, boolean floorScaleToPixels,
                              @Nullable TemporalJitter temporalJitter,
                              boolean overrideSrDefinition) {
        private static final HintConfig DEFAULT =
                new HintConfig(1.0f, false, false, null, false);
    }

    /** One immutable publication unit for every value tied to an Iris pack identity. */
    private record PackSnapshot(@Nullable Object packRef, HintConfig hint,
                                @Nullable Session srDefinition) {
        private static final PackSnapshot EMPTY =
                new PackSnapshot(null, HintConfig.DEFAULT, null);
    }

    // Resolved at static init; null when Iris is missing or the layout has shifted under us.
    @Nullable private static final MethodHandle GET_CURRENT_PACK;
    @Nullable private static final MethodHandle GET_PACK_OPTIONS;
    @Nullable private static final MethodHandle GET_OPTION_VALUES;
    @Nullable private static final MethodHandle GET_STRING_VALUE_OR_DEFAULT;
    @Nullable private static final MethodHandle GET_BOOLEAN_VALUE_OR_DEFAULT;
    @Nullable private static final MethodHandle GET_OPTION_SET;
    @Nullable private static final MethodHandle IS_BOOLEAN_OPTION;
    @Nullable private static final Field SHADERPACKS_DIRECTORY_FIELD;
    @Nullable private static final Field CURRENT_PACK_NAME_FIELD;
    private static final boolean REFLECTION_OK;

    private static volatile PackSnapshot cachedPackSnapshot = PackSnapshot.EMPTY;
    private static int cachedFrameCounter = Integer.MIN_VALUE;
    private static int cachedViewWidth = -1;
    private static int cachedViewHeight = -1;
    private static ProjectionTransform cachedProjection = ProjectionTransform.IDENTITY;
    private static volatile PackSnapshot cachedProjectionPackSnapshot = PackSnapshot.EMPTY;
    private static boolean cachedProjectionFromActiveSr;
    private static boolean cachedActiveSrRuntime;
    private static int reportedSrRenderWidth = -1;
    private static int reportedSrRenderHeight = -1;
    private static int reportedSrScreenWidth = -1;
    private static int reportedSrScreenHeight = -1;
    private static boolean reportedSrHintConflict;
    private static float reportedPackRuntimeScaleX = Float.NaN;
    private static float reportedPackRuntimeScaleY = Float.NaN;
    private static int reportedPackRuntimeExactJitter = -1;
    private static boolean reportedActiveSrConservativeJitter;

    static {
        MethodHandle getCurrentPack = null;
        MethodHandle getPackOptions = null;
        MethodHandle getOptionValues = null;
        MethodHandle getStringValueOrDefault = null;
        MethodHandle getBooleanValueOrDefault = null;
        MethodHandle getOptionSet = null;
        MethodHandle isBooleanOption = null;
        Field shaderpacksDirectory = null;
        Field currentPackName = null;
        boolean ok = false;

        try {
            Class<?> irisClass = Class.forName("net.irisshaders.iris.Iris");
            MethodHandles.Lookup lookup = MethodHandles.lookup();
            Method m = irisClass.getMethod("getCurrentPack");
            getCurrentPack = lookup.unreflect(m);
            try {
                Class<?> shaderPackClass = Class.forName(
                        "net.irisshaders.iris.shaderpack.ShaderPack");
                Class<?> packOptionsClass = Class.forName(
                        "net.irisshaders.iris.shaderpack.option.ShaderPackOptions");
                Class<?> optionValuesClass = Class.forName(
                        "net.irisshaders.iris.shaderpack.option.values.OptionValues");

                m = shaderPackClass.getMethod("getShaderPackOptions");
                getPackOptions = lookup.unreflect(m);
                m = packOptionsClass.getMethod("getOptionValues");
                getOptionValues = lookup.unreflect(m);
                m = optionValuesClass.getMethod("getStringValueOrDefault", String.class);
                getStringValueOrDefault = lookup.unreflect(m);

                // Boolean and string shader-pack options are kept in separate Iris maps. Do not
                // use getBooleanValueOrDefault for an unknown option: Iris returns true there.
                m = optionValuesClass.getMethod("getBooleanValueOrDefault", String.class);
                getBooleanValueOrDefault = lookup.unreflect(m);
                m = optionValuesClass.getMethod("getOptionSet");
                getOptionSet = lookup.unreflect(m);
                Class<?> optionSetClass = Class.forName(
                        "net.irisshaders.iris.shaderpack.option.OptionSet");
                m = optionSetClass.getMethod("isBooleanOption", String.class);
                isBooleanOption = lookup.unreflect(m);
            } catch (Throwable t) {
                Gallium.LOGGER.debug(
                        "Iris option reflection unavailable; gallium.json option lookup disabled: {}",
                        t.toString());
            }

            // These fields are only a directory-pack fallback. The constructor mixin captures
            // the authoritative shaders path and remains usable if Iris renames either field.
            try {
                shaderpacksDirectory = irisClass.getDeclaredField("shaderpacksDirectory");
                shaderpacksDirectory.setAccessible(true);
                currentPackName = irisClass.getDeclaredField("currentPackName");
                currentPackName.setAccessible(true);
            } catch (Throwable t) {
                Gallium.LOGGER.debug("Iris shaderpacks path fallback unavailable: {}", t.toString());
            }
            ok = true;
        } catch (Throwable t) {
            Gallium.LOGGER.debug("Iris reflection for ShaderPackHint not available: {}", t.toString());
        }

        GET_CURRENT_PACK = getCurrentPack;
        GET_PACK_OPTIONS = getPackOptions;
        GET_OPTION_VALUES = getOptionValues;
        GET_STRING_VALUE_OR_DEFAULT = getStringValueOrDefault;
        GET_BOOLEAN_VALUE_OR_DEFAULT = getBooleanValueOrDefault;
        GET_OPTION_SET = getOptionSet;
        IS_BOOLEAN_OPTION = isBooleanOption;
        SHADERPACKS_DIRECTORY_FIELD = shaderpacksDirectory;
        CURRENT_PACK_NAME_FIELD = currentPackName;
        REFLECTION_OK = ok;
    }

    private ShaderPackHint() {}

    /**
     * Returns the active shader pack's internal-resolution scale, clamped to [0.1, 1.0].
     * Returns 1.0 when Iris is unavailable, no pack is loaded, the hint file is missing or
     * malformed, or the named option cannot be resolved.
     */
    public static float getInternalScale() {
        PackSnapshot snapshot = currentPackSnapshot();
        HintConfig hint = snapshot.hint();
        ProjectionTransform sr = resolveSrProjection(snapshot.srDefinition());
        if (hint.overrideSrDefinition() && hint.scaleResolved()) return hint.scale();
        if (sr != null) return sr.scaleX();
        int viewWidth = cachedViewWidth;
        int viewHeight = cachedViewHeight;
        if (viewWidth <= 0 || viewHeight <= 0) {
            try {
                viewWidth = Minecraft.getInstance().getWindow().getWidth();
                viewHeight = Minecraft.getInstance().getWindow().getHeight();
            } catch (Throwable ignored) {
                viewWidth = -1;
                viewHeight = -1;
            }
        }
        ProjectionTransform packRuntime = viewWidth > 0 && viewHeight > 0
                ? resolvePackRuntimeProjection(
                        snapshot.srDefinition(), viewWidth, viewHeight) : null;
        if (packRuntime != null) return packRuntime.scaleX();
        if (hint.scaleResolved()) return hint.scale();
        return 1.0f;
    }

    /**
     * Returns the exact declared projection transform for {@code frameCounter}, or a scale-only
     * transform with {@code exactTemporalJitter=false} when the temporal declaration is absent or
     * the Iris frame counter is unavailable.
     */
    public static ProjectionTransform getProjectionTransform(
            int frameCounter, int viewWidth, int viewHeight) {
        PackSnapshot snapshot = currentPackSnapshot();
        HintConfig hint = snapshot.hint();
        // A failed Iris frame-counter lookup is represented by -1. It is not a stable frame key:
        // SR jitter and activation can still change, so caching it would freeze the first value.
        boolean cacheable = frameCounter >= 0;
        if (cacheable && snapshot == cachedProjectionPackSnapshot
                && frameCounter == cachedFrameCounter
                && viewWidth == cachedViewWidth
                && viewHeight == cachedViewHeight) {
            return cachedProjection;
        }
        ProjectionTransform sr = resolveSrProjection(snapshot.srDefinition());
        ProjectionTransform packRuntime = resolvePackRuntimeProjection(
                snapshot.srDefinition(), viewWidth, viewHeight);
        sr = mergeCompatibleRuntimeJitter(sr, packRuntime);
        ProjectionTransform explicit = hint.scaleResolved()
                ? computeProjectionTransform(hint, frameCounter, viewWidth, viewHeight) : null;
        // A live standard SR definition wins by default. This lets a pack keep an older
        // gallium.json for its built-in FSR/TAA path without freezing SR's independent runtime
        // scale. Non-standard SR projection conventions opt back into whole-file override.
        ProjectionTransform automatic = sr != null ? sr : packRuntime;
        if (!reportedSrHintConflict && automatic != null && explicit != null
                && !hint.overrideSrDefinition()
                && (Math.abs(automatic.scaleX() - explicit.scaleX())
                * Math.max(1, viewWidth) > 1.0f
                || Math.abs(automatic.scaleY() - explicit.scaleY())
                * Math.max(1, viewHeight) > 1.0f)) {
            reportedSrHintConflict = true;
            Gallium.LOGGER.warn(
                    "SR-compatible automatic projection overrides a conflicting gallium.json "
                            + "({}x{} vs live {}x{}). Add override_sr_definition=true only if "
                            + "the pack intentionally uses a non-standard SR projection.",
                    explicit.scaleX(), explicit.scaleY(),
                    automatic.scaleX(), automatic.scaleY());
        }
        ProjectionTransform projection = selectProjection(
                explicit, hint.overrideSrDefinition(), sr, packRuntime);
        cachedProjectionFromActiveSr = selectsActiveSrProjection(
                explicit, hint.overrideSrDefinition(), sr);
        cachedActiveSrRuntime = hasActiveSrRuntime(sr);
        if (!reportedActiveSrConservativeJitter
                && sr != null && !sr.exactTemporalJitter()
                && packRuntime != null && packRuntime.exactTemporalJitter()
                && projection == sr) {
            reportedActiveSrConservativeJitter = true;
            Gallium.LOGGER.info(
                    "Keeping non-exact active SR projection scale-only; input jitter is not "
                            + "replayed onto the de-jittered post-upscale outline");
        }
        if (cacheable) {
            cachedFrameCounter = frameCounter;
            cachedViewWidth = viewWidth;
            cachedViewHeight = viewHeight;
            cachedProjection = projection;
            // Publish the identity key last. A result computed from an old pack may race a
            // reload, but it can never satisfy the new snapshot's cache key.
            cachedProjectionPackSnapshot = snapshot;
        }
        return projection;
    }

    /** Whether the transform returned for the current Iris frame came from a live SR dispatch. */
    public static boolean isCurrentProjectionFromActiveSr() {
        return cachedProjectionFromActiveSr;
    }

    /** True when a standard SR definition resolved against an active live SR dispatch. */
    public static boolean isActiveSrRuntime() {
        return cachedActiveSrRuntime;
    }

    static boolean selectsActiveSrProjection(
            @Nullable ProjectionTransform explicit,
            boolean overrideSrDefinition,
            @Nullable ProjectionTransform sr) {
        return sr != null && !(overrideSrDefinition && explicit != null);
    }

    static boolean hasActiveSrRuntime(@Nullable ProjectionTransform sr) {
        return sr != null;
    }

    static ProjectionTransform selectProjection(
            @Nullable ProjectionTransform explicit,
            boolean overrideSrDefinition,
            @Nullable ProjectionTransform sr) {
        return selectProjection(explicit, overrideSrDefinition, sr, null);
    }

    static ProjectionTransform selectProjection(
            @Nullable ProjectionTransform explicit,
            boolean overrideSrDefinition,
            @Nullable ProjectionTransform sr,
            @Nullable ProjectionTransform packRuntime) {
        if (overrideSrDefinition && explicit != null) return explicit;
        if (sr != null) return sr;
        if (packRuntime != null) return packRuntime;
        if (explicit != null) return explicit;
        return sr == null ? ProjectionTransform.IDENTITY : sr;
    }

    /**
     * Keep an active SR definition authoritative for extent/origin and use effective Iris
     * programs only to validate an exact schema jitter contract. A legacy/v1 non-exact SR
     * transform is intentionally not promoted: the final temporal output is de-jittered, and
     * replaying its input jitter at the post-upscale overlay stage can visibly shimmer.
     */
    static @Nullable ProjectionTransform mergeCompatibleRuntimeJitter(
            @Nullable ProjectionTransform sr,
            @Nullable ProjectionTransform packRuntime) {
        if (sr == null || packRuntime == null) return sr;
        if (Float.compare(sr.scaleX(), packRuntime.scaleX()) != 0
                || Float.compare(sr.scaleY(), packRuntime.scaleY()) != 0
                || Float.compare(sr.viewportOriginX(), packRuntime.viewportOriginX()) != 0
                || Float.compare(sr.viewportOriginY(), packRuntime.viewportOriginY()) != 0) {
            return sr;
        }
        if (!packRuntime.exactTemporalJitter()) {
            // The effective scene/mask programs were inspected for this same viewport and did
            // not establish one global call-site convention. A schema value cannot make that
            // disagreement exact, so retain scale only.
            return sr.exactTemporalJitter() ? sr.withoutTemporalJitter() : sr;
        }
        if (sr.exactTemporalJitter()) {
            // The schema/runtime value and the final preprocessed call site are independent
            // evidence. Disagreement means neither is safe to advertise as a global exact
            // replay contract; retain only the extent and the bounded depth fallback.
            return nearlyEqual(sr.jitterX(), packRuntime.jitterX())
                    && nearlyEqual(sr.jitterY(), packRuntime.jitterY())
                    ? sr : sr.withoutTemporalJitter();
        }
        return sr;
    }

    private static boolean nearlyEqual(float left, float right) {
        return Float.isFinite(left) && Float.isFinite(right)
                && Math.abs(left - right) <= 1.0e-7f;
    }

    private static @Nullable ProjectionTransform resolvePackRuntimeProjection(
            @Nullable Session session, int viewWidth, int viewHeight) {
        // The SR compatibility file is the opt-in contract. Runtime adapters only fill semantics
        // used by the same pack while external SR is inactive; arbitrary non-SR packs are never
        // guessed from uniform names alone.
        if (session == null) return null;
        Optional<ShaderPackProjectionResolver.ProjectionResolution> resolved =
                session.resolvePackProjection(viewWidth, viewHeight);
        if (resolved.isEmpty()) return null;
        ShaderPackProjectionResolver.ProjectionResolution value = resolved.get();
        int exactJitter = value.exactTemporalJitter() ? 1 : 0;
        if (Float.compare(value.scaleX(), reportedPackRuntimeScaleX) != 0
                || Float.compare(value.scaleY(), reportedPackRuntimeScaleY) != 0
                || exactJitter != reportedPackRuntimeExactJitter) {
            reportedPackRuntimeScaleX = value.scaleX();
            reportedPackRuntimeScaleY = value.scaleY();
            reportedPackRuntimeExactJitter = exactJitter;
            Gallium.LOGGER.info(
                    "Using shader-pack runtime projection {}: scale {}x{} for {}x{}, exactJitter={}",
                    value.source(), value.scaleX(), value.scaleY(), viewWidth, viewHeight,
                    value.exactTemporalJitter());
        }
        return new ProjectionTransform(
                value.scaleX(), value.scaleY(), value.jitterX(), value.jitterY(),
                value.originX(), value.originY(), value.exactTemporalJitter());
    }

    private static @Nullable ProjectionTransform resolveSrProjection(
            @Nullable Session session) {
        if (session == null) return null;
        Optional<SrProjectionResolver.ProjectionResolution> resolved = session.resolve();
        if (resolved.isEmpty()) return null;
        SrProjectionResolver.ProjectionResolution value = resolved.get();
        if (value.renderWidth() != reportedSrRenderWidth
                || value.renderHeight() != reportedSrRenderHeight
                || value.screenWidth() != reportedSrScreenWidth
                || value.screenHeight() != reportedSrScreenHeight) {
            reportedSrRenderWidth = value.renderWidth();
            reportedSrRenderHeight = value.renderHeight();
            reportedSrScreenWidth = value.screenWidth();
            reportedSrScreenHeight = value.screenHeight();
            Gallium.LOGGER.info(
                    "Using SR projection {} [{}]: render {}x{} -> screen {}x{}, scale {}x{}, exactJitter={}",
                    session.fileName(), session.definition().format(),
                    value.renderWidth(), value.renderHeight(),
                    value.screenWidth(), value.screenHeight(),
                    value.scaleX(), value.scaleY(), value.exactJitterTransform());
        }
        return projectionFromSrResolution(value);
    }

    /** Testable conversion from SR's pixel-space protocol into Gallium's NDC/UV contract. */
    static @Nullable ProjectionTransform projectionFromSrResolution(
            SrProjectionResolver.ProjectionResolution value) {
        if (value == null) return null;
        if (!value.active() || !value.activationResolved() || !value.scaleResolved()) return null;
        float jitterX = 0.0f;
        float jitterY = 0.0f;
        boolean exactJitter = false;
        if (value.exactJitterTransform()
                && value.renderWidth() > 0 && value.renderHeight() > 0
                && value.screenWidth() > 0 && value.screenHeight() > 0) {
            // The pack first maps normal full-screen NDC into the active scaled viewport and
            // then adds jitter. A render-pixel offset therefore becomes
            // scale * (2 * offset / renderExtent), exactly 2 * offset / screenExtent.
            jitterX = 2.0f * value.jitterPixelsX() / (float) value.screenWidth();
            jitterY = 2.0f * value.jitterPixelsY() / (float) value.screenHeight();
            // The runtime adapter returns SR's shader-facing convention (including the standard
            // algorithm-specific Y adaptation).  The current offset is sufficient for replay;
            // an unavailable sequence length affects prediction, not this frame's alignment.
            exactJitter = true;
        }
        return new ProjectionTransform(
                value.scaleX(), value.scaleY(), jitterX, jitterY,
                value.originX(), value.originY(), exactJitter);
    }

    /** Test seam: bypass Iris reflection and resolve a hint via a caller-provided option lookup. */
    public static float resolveFromHint(@Nullable Path hintPath,
                                         Function<String, @Nullable String> optionLookup) {
        if (hintPath == null || !Files.exists(hintPath)) return 1.0f;
        try (Reader reader = Files.newBufferedReader(hintPath, StandardCharsets.UTF_8)) {
            return resolveFromHintReader(reader, optionLookup);
        } catch (Throwable t) {
            Gallium.LOGGER.warn("Failed to read shader pack hint {}: {}", hintPath, t.toString());
            return 1.0f;
        }
    }

    /** Test seam, content variant. */
    public static float resolveFromHintReader(Reader reader,
                                                Function<String, @Nullable String> optionLookup) {
        try {
            JsonElement root = JsonParser.parseReader(reader);
            if (!root.isJsonObject()) return 1.0f;
            return resolveHint(root.getAsJsonObject(), optionLookup).scale();
        } catch (Throwable t) {
            Gallium.LOGGER.warn("Malformed shader pack hint: {}", t.toString());
            return 1.0f;
        }
    }

    /** Test seam for exact temporal-jitter and pixel-rounded scale declarations. */
    static ProjectionTransform resolveProjectionFromHintReader(
            Reader reader, Function<String, @Nullable String> optionLookup,
            int frameCounter, int viewWidth, int viewHeight) {
        return resolveProjectionFromHintReader(
                reader, optionLookup, frameCounter, viewWidth, viewHeight, null);
    }

    /** Test seam for precedence between a legacy/nonstandard hint and an active SR definition. */
    static ProjectionTransform resolveProjectionFromHintReader(
            Reader reader, Function<String, @Nullable String> optionLookup,
            int frameCounter, int viewWidth, int viewHeight,
            @Nullable ProjectionTransform srProjection) {
        try {
            JsonElement root = JsonParser.parseReader(reader);
            if (!root.isJsonObject()) return ProjectionTransform.IDENTITY;
            HintConfig hint = resolveHint(root.getAsJsonObject(), optionLookup);
            ProjectionTransform explicit = hint.scaleResolved()
                    ? computeProjectionTransform(
                    hint, frameCounter, viewWidth, viewHeight) : null;
            return selectProjection(explicit, hint.overrideSrDefinition(), srProjection);
        } catch (Throwable t) {
            Gallium.LOGGER.warn("Malformed shader pack hint: {}", t.toString());
            return ProjectionTransform.IDENTITY;
        }
    }

    private static synchronized PackSnapshot currentPackSnapshot() {
        if (!REFLECTION_OK) return PackSnapshot.EMPTY;
        Object pack = invokeOrNull(GET_CURRENT_PACK);
        if (pack instanceof Optional<?> opt) pack = opt.orElse(null);
        if (pack == null) {
            if (cachedPackSnapshot != PackSnapshot.EMPTY) {
                cachedPackSnapshot = PackSnapshot.EMPTY;
                invalidateProjectionCache();
            }
            return PackSnapshot.EMPTY;
        }
        PackSnapshot current = cachedPackSnapshot;
        if (pack == current.packRef()) return current;

        // Pack instance changed -> reload triggered. Recompute first, then publish the identity,
        // hint, and definition session together so no reader can observe a mixed-pack tuple.
        PackSnapshot recomputed = recomputePack(pack);
        cachedPackSnapshot = recomputed;
        invalidateProjectionCache();
        return recomputed;
    }

    private static PackSnapshot recomputePack(Object pack) {
        Session definition = loadDefinitionForReload(pack).orElse(null);
        try {
            String hint = readHintContent(pack);
            if (hint == null) return new PackSnapshot(pack, HintConfig.DEFAULT, definition);
            Object packOptions = GET_PACK_OPTIONS.invoke(pack);
            if (packOptions == null) {
                Gallium.LOGGER.debug("Iris ShaderPack.getShaderPackOptions() returned null; gallium.json ignored");
                return new PackSnapshot(pack, HintConfig.DEFAULT, definition);
            }
            Object optionValues = GET_OPTION_VALUES.invoke(packOptions);
            if (optionValues == null) {
                Gallium.LOGGER.debug("Iris ShaderPackOptions.getOptionValues() returned null; gallium.json ignored");
                return new PackSnapshot(pack, HintConfig.DEFAULT, definition);
            }
            Function<String, String> lookup = name -> lookupOptionValue(optionValues, name);
            JsonElement root = JsonParser.parseReader(new StringReader(hint));
            if (!root.isJsonObject()) {
                return new PackSnapshot(pack, HintConfig.DEFAULT, definition);
            }
            return new PackSnapshot(
                    pack, resolveHint(root.getAsJsonObject(), lookup), definition);
        } catch (Throwable t) {
            Gallium.LOGGER.debug("Failed to recompute shader pack projection hint: {}", t.toString());
            return new PackSnapshot(pack, HintConfig.DEFAULT, definition);
        }
    }

    /** Isolates a broken/new pack context from the definition cached for the previous pack. */
    static Optional<Session> loadDefinitionForReload(Object pack) {
        try {
            Optional<Session> value = loadSrDefinition(pack);
            return value == null ? Optional.empty() : value;
        } catch (Throwable t) {
            Gallium.LOGGER.debug("Failed to resolve reloaded shader pack SR definition: {}", t.toString());
            return Optional.empty();
        }
    }

    /**
     * Looks up either Iris option kind while preserving string-option behavior. Boolean lookup
     * is allowed only after {@code OptionSet} confirms membership; Iris otherwise defaults an
     * unknown boolean name to {@code true}, which would make a missing requirement unsafe.
     */
    private static @Nullable String lookupOptionValue(Object optionValues, String name) {
        try {
            Object result = GET_STRING_VALUE_OR_DEFAULT.invoke(optionValues, name);
            if (result != null) return result.toString();
        } catch (Throwable ignored) {
            // Boolean options are not present in Iris's string-option map.
        }

        if (GET_BOOLEAN_VALUE_OR_DEFAULT == null
                || GET_OPTION_SET == null
                || IS_BOOLEAN_OPTION == null) {
            return null;
        }
        try {
            Object optionSet = GET_OPTION_SET.invoke(optionValues);
            if (!Boolean.TRUE.equals(IS_BOOLEAN_OPTION.invoke(optionSet, name))) return null;
            Object result = GET_BOOLEAN_VALUE_OR_DEFAULT.invoke(optionValues, name);
            return result == null ? null : result.toString();
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static HintConfig resolveHint(
            JsonObject json, Function<String, @Nullable String> optionLookup) {
        ScaleResolution scaleResolution = resolveScale(json.get(SCALE_KEY), optionLookup);
        boolean floorScale = false;
        JsonElement scaleNode = json.get(SCALE_KEY);
        if (scaleNode != null && scaleNode.isJsonObject()) {
            JsonElement rounding = scaleNode.getAsJsonObject().get("pixel_rounding");
            floorScale = rounding != null && rounding.isJsonPrimitive()
                    && "floor".equals(rounding.getAsString());
        }
        TemporalJitter jitter = scaleResolution.resolved()
                ? resolveTemporalJitter(json.get(JITTER_KEY), optionLookup) : null;
        JsonElement overrideNode = json.get(OVERRIDE_SR_DEFINITION_KEY);
        boolean overrideSrDefinition = overrideNode != null
                && overrideNode.isJsonPrimitive()
                && overrideNode.getAsJsonPrimitive().isBoolean()
                && overrideNode.getAsBoolean();
        return new HintConfig(scaleResolution.scale(), scaleResolution.resolved(), floorScale,
                jitter, overrideSrDefinition);
    }

    private static ScaleResolution resolveScale(
            @Nullable JsonElement scaleNode,
            Function<String, @Nullable String> optionLookup) {
        try {
            if (scaleNode == null || !scaleNode.isJsonObject()) {
                return new ScaleResolution(1.0f, false);
            }
            JsonObject scaleObj = scaleNode.getAsJsonObject();
            JsonElement optionEl = scaleObj.get("option");
            if (optionEl == null || !optionEl.isJsonPrimitive()) {
                return new ScaleResolution(1.0f, false);
            }
            String optionValue = optionLookup.apply(optionEl.getAsString());
            if (optionValue == null) return new ScaleResolution(1.0f, false);

            JsonElement valuesEl = scaleObj.get("values");
            float scale;
            if (valuesEl != null && valuesEl.isJsonObject()) {
                JsonElement mapped = valuesEl.getAsJsonObject().get(optionValue);
                if (mapped == null || !mapped.isJsonPrimitive()) {
                    return new ScaleResolution(1.0f, false);
                }
                scale = mapped.getAsFloat();
            } else {
                scale = Float.parseFloat(optionValue);
            }
            if (!Float.isFinite(scale)) return new ScaleResolution(1.0f, false);
            return new ScaleResolution(clamp(scale), true);
        } catch (Throwable ignored) {
            return new ScaleResolution(1.0f, false);
        }
    }

    private static @Nullable TemporalJitter resolveTemporalJitter(
            @Nullable JsonElement jitterNode,
            Function<String, @Nullable String> optionLookup) {
        try {
            if (jitterNode == null || !jitterNode.isJsonObject()) return null;
            JsonObject jitter = jitterNode.getAsJsonObject();
            if (!R2_SEQUENCE.equals(requiredString(jitter, "sequence"))) return null;
            if (!NDC_PER_VIEW_SIZE.equals(requiredString(jitter, "units"))) return null;
            if (!AFTER_INTERNAL_SCALE.equals(requiredString(jitter, "transform_order"))) return null;
            if (!requirementsMatch(jitter.get(REQUIREMENTS_KEY), optionLookup)) return null;

            float phase = requiredFloat(jitter, "phase");
            float xMultiplier = requiredFloat(jitter, "x_multiplier");
            float yMultiplier = requiredFloat(jitter, "y_multiplier");
            int indexOffset = requiredInt(jitter, "index_offset");
            if (!Float.isFinite(phase)
                    || !Float.isFinite(xMultiplier)
                    || !Float.isFinite(yMultiplier)) {
                return null;
            }

            int period = 0;
            JsonElement periodNode = jitter.get("period");
            if (periodNode != null) {
                period = resolvePeriod(periodNode, optionLookup);
                if (period <= 0) return null;
            }
            return new TemporalJitter(phase, xMultiplier, yMultiplier, indexOffset, period);
        } catch (Throwable ignored) {
            // An incomplete declaration must never partially activate exact replay.
            return null;
        }
    }

    private static int resolvePeriod(
            JsonElement periodNode,
            Function<String, @Nullable String> optionLookup) {
        if (periodNode.isJsonPrimitive()) return periodNode.getAsInt();
        if (!periodNode.isJsonObject()) return -1;
        JsonObject period = periodNode.getAsJsonObject();
        String option = requiredString(period, "option");
        String optionValue = optionLookup.apply(option);
        if (optionValue == null) return -1;
        JsonElement valuesNode = period.get("values");
        if (valuesNode == null || !valuesNode.isJsonObject()) return -1;
        JsonElement mapped = valuesNode.getAsJsonObject().get(optionValue);
        return mapped != null && mapped.isJsonPrimitive() ? mapped.getAsInt() : -1;
    }

    /**
     * Exact replay is opt-in for one concrete shader-pack mode. A pack can require its own
     * resolved option values (for example, disabling a screenshot mode with a different TAA
     * sequence) so scale-only fallback remains active whenever the declaration is not exact.
     */
    private static boolean requirementsMatch(@Nullable JsonElement requirementsNode,
                                              Function<String, @Nullable String> optionLookup) {
        if (requirementsNode == null) return true;
        if (!requirementsNode.isJsonObject()) return false;
        for (Map.Entry<String, JsonElement> requirement
                : requirementsNode.getAsJsonObject().entrySet()) {
            JsonElement expectedNode = requirement.getValue();
            if (!expectedNode.isJsonPrimitive()) return false;
            String actual = optionLookup.apply(requirement.getKey());
            if (actual == null || !expectedNode.getAsString().equals(actual)) return false;
        }
        return true;
    }

    private static ProjectionTransform computeProjectionTransform(
            HintConfig hint, int frameCounter, int viewWidth, int viewHeight) {
        if (viewWidth <= 0 || viewHeight <= 0) return ProjectionTransform.IDENTITY;

        float scaleX = scaleForDimension(hint.scale(), hint.floorScaleToPixels(), viewWidth);
        float scaleY = scaleForDimension(hint.scale(), hint.floorScaleToPixels(), viewHeight);
        TemporalJitter jitter = hint.temporalJitter();
        if (jitter == null || frameCounter < 0) {
            return new ProjectionTransform(scaleX, scaleY, 0.0f, 0.0f, false);
        }

        int sequenceIndex = jitter.period() > 0
                ? Math.floorMod(frameCounter, jitter.period())
                : frameCounter;
        float index = (float) sequenceIndex + jitter.indexOffset();
        float rawX = r2Sample(jitter.phase(), jitter.xMultiplier(), index);
        float rawY = r2Sample(jitter.phase(), jitter.yMultiplier(), index);
        return new ProjectionTransform(
                scaleX, scaleY, rawX / viewWidth, rawY / viewHeight, true);
    }

    private static float scaleForDimension(float scale, boolean floorToPixels, int dimension) {
        if (!floorToPixels) return scale;
        // Iris evaluates shader-pack expressions as IEEE-754 float values. Keep the
        // multiplication in float precision before floor so odd viewport sizes resolve to
        // exactly the same internal texel count as the pack.
        float pixels = (float) Math.floor((float) dimension * scale);
        return Math.max(1.0f, pixels) / dimension;
    }

    private static float r2Sample(float phase, float multiplier, float index) {
        float value = phase + index * multiplier;
        float fractional = value - (float) Math.floor(value);
        return fractional * 2.0f - 1.0f;
    }

    private static String requiredString(JsonObject object, String key) {
        JsonElement value = object.get(key);
        if (value == null || !value.isJsonPrimitive()) throw new IllegalArgumentException(key);
        return value.getAsString();
    }

    private static float requiredFloat(JsonObject object, String key) {
        JsonElement value = object.get(key);
        if (value == null || !value.isJsonPrimitive()) throw new IllegalArgumentException(key);
        return value.getAsFloat();
    }

    private static int requiredInt(JsonObject object, String key) {
        JsonElement value = object.get(key);
        if (value == null || !value.isJsonPrimitive()) throw new IllegalArgumentException(key);
        return value.getAsInt();
    }

    private static void invalidateProjectionCache() {
        cachedFrameCounter = Integer.MIN_VALUE;
        cachedViewWidth = -1;
        cachedViewHeight = -1;
        cachedProjection = ProjectionTransform.IDENTITY;
        cachedProjectionPackSnapshot = PackSnapshot.EMPTY;
        cachedProjectionFromActiveSr = false;
        cachedActiveSrRuntime = false;
        reportedSrRenderWidth = -1;
        reportedSrRenderHeight = -1;
        reportedSrScreenWidth = -1;
        reportedSrScreenHeight = -1;
        reportedSrHintConflict = false;
        reportedPackRuntimeScaleX = Float.NaN;
        reportedPackRuntimeScaleY = Float.NaN;
        reportedPackRuntimeExactJitter = -1;
        reportedActiveSrConservativeJitter = false;
    }

    /**
     * Reads {@code gallium.json} contents for the active pack. Iris's constructor hook captures
     * both directory and already-mounted zip paths; the field-based lookup below is only a
     * directory fallback for an unknown Iris constructor ABI. Returns {@code null} when absent.
     */
    private static @Nullable String readHintContent(Object pack) {
        if (pack instanceof SrShaderPackContext context
                && context.gallium$hasCapturedShaderPackContext()) {
            return context.gallium$getGalliumHint().orElse(null);
        }
        if (SHADERPACKS_DIRECTORY_FIELD == null || CURRENT_PACK_NAME_FIELD == null) return null;
        try {
            Path packsDir = (Path) SHADERPACKS_DIRECTORY_FIELD.get(null);
            String name = (String) CURRENT_PACK_NAME_FIELD.get(null);
            if (packsDir == null || name == null) return null;
            // Directory pack
            Path dirCandidate = packsDir.resolve(name).resolve(HINT_FILE_NAME);
            if (Files.exists(dirCandidate)) {
                return Files.readString(dirCandidate, StandardCharsets.UTF_8);
            }
            // Iris keeps zip filesystems mounted and may select a nested pack root. The
            // constructor mixin above captures that real path; never reopen or guess a zip here.
            return null;
        } catch (Throwable t) {
            Gallium.LOGGER.debug("Failed to read gallium.json for active pack: {}", t.toString());
            return null;
        }
    }

    /** Loads the first universal SR definition from either a directory or zip shader pack. */
    private static Optional<Session> loadSrDefinition(Object pack) {
        if (pack instanceof SrShaderPackContext context
                && context.gallium$hasCapturedShaderPackContext()) {
            return context.gallium$getSrDefinitionSession();
        }
        if (SHADERPACKS_DIRECTORY_FIELD == null || CURRENT_PACK_NAME_FIELD == null) {
            return Optional.empty();
        }
        try {
            Path packsDir = (Path) SHADERPACKS_DIRECTORY_FIELD.get(null);
            String name = (String) CURRENT_PACK_NAME_FIELD.get(null);
            if (packsDir == null || name == null) return Optional.empty();

            Path candidate = packsDir.resolve(name);
            if (Files.isDirectory(candidate)) {
                return SrShaderPackResolver.load(candidate.resolve("shaders"), pack);
            }
            // A supported Iris constructor should always implement SrShaderPackContext. If it
            // does not, failing closed is safer than colliding with Iris's mounted ZipFS or
            // choosing the wrong outer directory.
            if (Files.isRegularFile(candidate) && name.endsWith(".zip")) {
                Gallium.LOGGER.warn("Cannot resolve SR definition root for zip shader pack {}; "
                        + "Iris ShaderPack constructor hook was not applied", name);
            }
        } catch (Throwable t) {
            Gallium.LOGGER.debug("Failed to load SR shader-pack definition: {}", t.toString());
        }
        return Optional.empty();
    }

    private static @Nullable Object invokeOrNull(@Nullable MethodHandle handle) {
        if (handle == null) return null;
        try {
            return handle.invoke();
        } catch (Throwable t) {
            return null;
        }
    }

    static float clamp(float value) {
        if (Float.isNaN(value)) return 1.0f;
        if (value < MIN_SCALE) return MIN_SCALE;
        if (value > MAX_SCALE) return MAX_SCALE;
        return value;
    }

    /** For tests: build an option-lookup function from a fixed map. */
    static Function<String, String> mapLookup(Map<String, String> values) {
        Map<String, String> copy = new HashMap<>(values);
        return copy::get;
    }
}
