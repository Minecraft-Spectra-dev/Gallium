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
 * Reads {@code shaderpacks/<pack>/gallium.json} and resolves the named shader pack option through
 * Iris's internal API to obtain the current internal-resolution scale.
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
 * hint and option lookup must re-run. Per-frame cost on the steady state path is one identity
 * check + one cached float read.
 */
public final class ShaderPackHint {

    private static final float MIN_SCALE = 0.1f;
    private static final float MAX_SCALE = 1.0f;
    private static final String HINT_FILE_NAME = "gallium.json";
    private static final String SCALE_KEY = "internal_resolution_scale";

    // Resolved at static init; null when Iris is missing or the layout has shifted under us.
    @Nullable private static final MethodHandle GET_CURRENT_PACK;
    @Nullable private static final MethodHandle GET_PACK_OPTIONS;
    @Nullable private static final MethodHandle GET_OPTION_VALUES;
    @Nullable private static final MethodHandle GET_STRING_VALUE_OR_DEFAULT;
    @Nullable private static final Field SHADERPACKS_DIRECTORY_FIELD;
    @Nullable private static final Field CURRENT_PACK_NAME_FIELD;
    private static final boolean REFLECTION_OK;

    @Nullable private static Object lastSeenPackRef;
    private static float cachedScale = 1.0f;

    static {
        MethodHandle getCurrentPack = null;
        MethodHandle getPackOptions = null;
        MethodHandle getOptionValues = null;
        MethodHandle getStringValueOrDefault = null;
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
        if (!REFLECTION_OK) return 1.0f;
        Object pack = invokeOrNull(GET_CURRENT_PACK);
        if (pack instanceof Optional<?> opt) {
            pack = opt.orElse(null);
        }
        if (pack == null) {
            lastSeenPackRef = null;
            cachedScale = 1.0f;
            return 1.0f;
        }
        if (pack == lastSeenPackRef) return cachedScale;
        // Pack instance changed → reload triggered. Re-read hint file and re-resolve option.
        lastSeenPackRef = pack;
        cachedScale = recomputeScale(pack);
        return cachedScale;
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
            JsonObject json = root.getAsJsonObject();
            JsonElement scaleNode = json.get(SCALE_KEY);
            if (scaleNode == null || !scaleNode.isJsonObject()) return 1.0f;
            JsonObject scaleObj = scaleNode.getAsJsonObject();
            JsonElement optionEl = scaleObj.get("option");
            if (optionEl == null || !optionEl.isJsonPrimitive()) return 1.0f;
            String optionName = optionEl.getAsString();
            String optionValue = optionLookup.apply(optionName);
            if (optionValue == null) return 1.0f;

            // Optional values map: shader packs that expose resolution as an enum step (e.g.
            // iterationRP's FSR2_SCALE = 0..4) use this to translate each step to a float.
            JsonElement valuesEl = scaleObj.get("values");
            float scale;
            if (valuesEl != null && valuesEl.isJsonObject()) {
                JsonElement mapped = valuesEl.getAsJsonObject().get(optionValue);
                if (mapped == null || !mapped.isJsonPrimitive()) return 1.0f;
                scale = mapped.getAsFloat();
            } else {
                scale = Float.parseFloat(optionValue);
            }
            return clamp(scale);
        } catch (Throwable t) {
            Gallium.LOGGER.warn("Malformed shader pack hint: {}", t.toString());
            return 1.0f;
        }
    }

    private static float recomputeScale(Object pack) {
        try {
            String hint = readHintContent(pack);
            if (hint == null) return 1.0f;
            Object packOptions = GET_PACK_OPTIONS.invoke(pack);
            if (packOptions == null) {
                Gallium.LOGGER.debug("Iris ShaderPack.getShaderPackOptions() returned null; gallium.json ignored");
                return 1.0f;
            }
            Object optionValues = GET_OPTION_VALUES.invoke(packOptions);
            if (optionValues == null) {
                Gallium.LOGGER.debug("Iris ShaderPackOptions.getOptionValues() returned null; gallium.json ignored");
                return 1.0f;
            }
            Function<String, String> lookup = name -> {
                try {
                    Object result = GET_STRING_VALUE_OR_DEFAULT.invoke(optionValues, name);
                    return result == null ? null : result.toString();
                } catch (Throwable t) {
                    return null;
                }
            };
            return resolveFromHintReader(new StringReader(hint), lookup);
        } catch (Throwable t) {
            Gallium.LOGGER.debug("Failed to recompute shader pack scale: {}", t.toString());
            return 1.0f;
        }
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
