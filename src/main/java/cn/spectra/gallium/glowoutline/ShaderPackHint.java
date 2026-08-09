package cn.spectra.gallium.glowoutline;

import cn.spectra.gallium.Gallium;
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
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;

/**
 * Reads {@code shaderpacks/<pack>/gallium.json} and resolves explicitly declared shader-pack
 * projection transforms through Iris's internal API. Internal-resolution scale and temporal
 * jitter are deliberately opt-in: neither custom-uniform names nor transform order are portable
 * across shader packs.
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
                                      boolean exactTemporalJitter) {
        public static final ProjectionTransform IDENTITY =
                new ProjectionTransform(1.0f, 1.0f, 0.0f, 0.0f, false);

        public boolean changesProjection() {
            return scaleX != 1.0f || scaleY != 1.0f || jitterX != 0.0f || jitterY != 0.0f;
        }

        public ProjectionTransform withoutTemporalJitter() {
            return new ProjectionTransform(scaleX, scaleY, 0.0f, 0.0f, false);
        }
    }

    private record TemporalJitter(float phase, float xMultiplier, float yMultiplier,
                                  int indexOffset, int period) {}

    private record ScaleResolution(float scale, boolean resolved) {}

    private record HintConfig(float scale, boolean scaleResolved, boolean floorScaleToPixels,
                              @Nullable TemporalJitter temporalJitter) {
        private static final HintConfig DEFAULT = new HintConfig(1.0f, false, false, null);
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

    @Nullable private static Object lastSeenPackRef;
    private static HintConfig cachedHint = HintConfig.DEFAULT;
    private static int cachedFrameCounter = Integer.MIN_VALUE;
    private static int cachedViewWidth = -1;
    private static int cachedViewHeight = -1;
    private static ProjectionTransform cachedProjection = ProjectionTransform.IDENTITY;

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
            Class<?> shaderPackClass = Class.forName("net.irisshaders.iris.shaderpack.ShaderPack");
            Class<?> packOptionsClass = Class.forName("net.irisshaders.iris.shaderpack.option.ShaderPackOptions");
            Class<?> optionValuesClass = Class.forName("net.irisshaders.iris.shaderpack.option.values.OptionValues");

            MethodHandles.Lookup lookup = MethodHandles.lookup();

            Method m = irisClass.getMethod("getCurrentPack");
            getCurrentPack = lookup.unreflect(m);

            m = shaderPackClass.getMethod("getShaderPackOptions");
            getPackOptions = lookup.unreflect(m);

            m = packOptionsClass.getMethod("getOptionValues");
            getOptionValues = lookup.unreflect(m);

            // getStringValueOrDefault is a default method that falls back to the pack's
            // default value when the user hasn't overridden the option.
            m = optionValuesClass.getMethod("getStringValueOrDefault", String.class);
            getStringValueOrDefault = lookup.unreflect(m);

            // Boolean and string shader-pack options are kept in separate Iris maps. Do not use
            // getBooleanValueOrDefault for an unknown option: Iris deliberately returns true in
            // that case. The OptionSet membership check keeps a misspelled requirement from
            // activating an exact projection replay.
            try {
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
                        "Iris boolean-option reflection unavailable; boolean gallium.json requirements ignored: {}",
                        t.toString());
            }

            shaderpacksDirectory = irisClass.getDeclaredField("shaderpacksDirectory");
            shaderpacksDirectory.setAccessible(true);

            currentPackName = irisClass.getDeclaredField("currentPackName");
            currentPackName.setAccessible(true);

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
        return currentHint().scale();
    }

    /**
     * Returns the exact declared projection transform for {@code frameCounter}, or a scale-only
     * transform with {@code exactTemporalJitter=false} when the temporal declaration is absent or
     * the Iris frame counter is unavailable.
     */
    public static ProjectionTransform getProjectionTransform(
            int frameCounter, int viewWidth, int viewHeight) {
        HintConfig hint = currentHint();
        if (frameCounter == cachedFrameCounter
                && viewWidth == cachedViewWidth
                && viewHeight == cachedViewHeight) {
            return cachedProjection;
        }
        cachedFrameCounter = frameCounter;
        cachedViewWidth = viewWidth;
        cachedViewHeight = viewHeight;
        cachedProjection = computeProjectionTransform(hint, frameCounter, viewWidth, viewHeight);
        return cachedProjection;
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
        try {
            JsonElement root = JsonParser.parseReader(reader);
            if (!root.isJsonObject()) return ProjectionTransform.IDENTITY;
            HintConfig hint = resolveHint(root.getAsJsonObject(), optionLookup);
            return computeProjectionTransform(hint, frameCounter, viewWidth, viewHeight);
        } catch (Throwable t) {
            Gallium.LOGGER.warn("Malformed shader pack hint: {}", t.toString());
            return ProjectionTransform.IDENTITY;
        }
    }

    private static HintConfig currentHint() {
        if (!REFLECTION_OK) return HintConfig.DEFAULT;
        Object pack = invokeOrNull(GET_CURRENT_PACK);
        if (pack instanceof Optional<?> opt) pack = opt.orElse(null);
        if (pack == null) {
            if (lastSeenPackRef != null || cachedHint != HintConfig.DEFAULT) {
                lastSeenPackRef = null;
                cachedHint = HintConfig.DEFAULT;
                invalidateProjectionCache();
            }
            return HintConfig.DEFAULT;
        }
        if (pack == lastSeenPackRef) return cachedHint;

        // Pack instance changed -> reload triggered. Re-read every declared projection field.
        lastSeenPackRef = pack;
        cachedHint = recomputeHint(pack);
        invalidateProjectionCache();
        return cachedHint;
    }

    private static HintConfig recomputeHint(Object pack) {
        try {
            String hint = readHintContent(pack);
            if (hint == null) return HintConfig.DEFAULT;
            Object packOptions = GET_PACK_OPTIONS.invoke(pack);
            if (packOptions == null) {
                Gallium.LOGGER.debug("Iris ShaderPack.getShaderPackOptions() returned null; gallium.json ignored");
                return HintConfig.DEFAULT;
            }
            Object optionValues = GET_OPTION_VALUES.invoke(packOptions);
            if (optionValues == null) {
                Gallium.LOGGER.debug("Iris ShaderPackOptions.getOptionValues() returned null; gallium.json ignored");
                return HintConfig.DEFAULT;
            }
            Function<String, String> lookup = name -> lookupOptionValue(optionValues, name);
            JsonElement root = JsonParser.parseReader(new StringReader(hint));
            if (!root.isJsonObject()) return HintConfig.DEFAULT;
            return resolveHint(root.getAsJsonObject(), lookup);
        } catch (Throwable t) {
            Gallium.LOGGER.debug("Failed to recompute shader pack projection hint: {}", t.toString());
            return HintConfig.DEFAULT;
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
        return new HintConfig(scaleResolution.scale(), scaleResolution.resolved(), floorScale, jitter);
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
    }

    /**
     * Reads {@code gallium.json} contents for the active pack. Handles both directory and zip
     * packs: for directories the file lives at {@code shaderpacks/<name>/gallium.json}; for zips
     * we open the entry through a {@link FileSystem} view and read its bytes inline so no temp
     * file is ever created. Returns {@code null} when the hint file is absent.
     */
    private static @Nullable String readHintContent(Object pack) {
        try {
            Path packsDir = (Path) SHADERPACKS_DIRECTORY_FIELD.get(null);
            String name = (String) CURRENT_PACK_NAME_FIELD.get(null);
            if (packsDir == null || name == null) return null;
            // Directory pack
            Path dirCandidate = packsDir.resolve(name).resolve(HINT_FILE_NAME);
            if (Files.exists(dirCandidate)) {
                return Files.readString(dirCandidate, StandardCharsets.UTF_8);
            }
            // Zip pack: read the entry directly without staging a temp file.
            Path zipCandidate = packsDir.resolve(name);
            if (Files.exists(zipCandidate) && name.endsWith(".zip")) {
                try (FileSystem fs = FileSystems.newFileSystem(zipCandidate, (ClassLoader) null)) {
                    Path inZip = fs.getPath(HINT_FILE_NAME);
                    if (!Files.exists(inZip)) return null;
                    return Files.readString(inZip, StandardCharsets.UTF_8);
                }
            }
            return null;
        } catch (Throwable t) {
            Gallium.LOGGER.debug("Failed to read gallium.json for active pack: {}", t.toString());
            return null;
        }
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
