package cn.spectra.gallium.glowoutline.sr.definition;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Registry and strict parsers for legacy and versioned SR shader-pack definitions. */
public final class SrDefinitionParserRegistry {

    private static final Pattern VERSIONED_FILE_NAME =
            Pattern.compile("superresolution\\.v([1-9][0-9]*)\\.json");

    private final Map<Integer, VersionedParser> versionedParsers = new LinkedHashMap<>();

    public SrDefinitionParserRegistry() {}

    public static SrDefinitionParserRegistry builtIn() {
        SrDefinitionParserRegistry registry = new SrDefinitionParserRegistry();
        registry.register(1, root -> parseVersioned(root, 1, SrDefinition.Format.V1));
        registry.register(2, root -> parseVersioned(root, 2, SrDefinition.Format.V2));
        registry.register(3, root -> parseVersioned(root, 3, SrDefinition.Format.V3));
        return registry;
    }

    public SrDefinitionParserRegistry register(int schemaVersion, VersionedParser parser) {
        if (schemaVersion <= 0) throw new IllegalArgumentException("schemaVersion");
        if (parser == null) throw new IllegalArgumentException("parser");
        if (versionedParsers.putIfAbsent(schemaVersion, parser) != null) {
            throw new IllegalArgumentException("schema already registered: " + schemaVersion);
        }
        return this;
    }

    /**
     * Parses one already-selected file. A missing schema is accepted only for the historical
     * unversioned {@code superresolution.json} shape containing {@code sr}.
     */
    public ParseResult parse(String fileName, String source) {
        final JsonElement parsed;
        try {
            parsed = JsonParser.parseString(source);
        } catch (Throwable t) {
            return ParseResult.failure(ParseStatus.MALFORMED_JSON, message(t));
        }
        if (!parsed.isJsonObject()) {
            return ParseResult.failure(ParseStatus.INVALID_DEFINITION,
                    "definition root must be an object");
        }

        JsonObject root = parsed.getAsJsonObject();
        JsonElement schemaNode = root.get("schema_version");
        if (schemaNode != null) {
            Integer schema = strictInteger(schemaNode);
            if (schema == null || schema <= 0) {
                return ParseResult.failure(ParseStatus.INVALID_SCHEMA,
                        "schema_version must be a positive integer");
            }
            Matcher fileVersion = VERSIONED_FILE_NAME.matcher(fileName == null ? "" : fileName);
            if (fileVersion.matches()
                    && Integer.parseInt(fileVersion.group(1)) != schema) {
                return ParseResult.failure(ParseStatus.INVALID_SCHEMA,
                        "file name schema does not match schema_version " + schema);
            }
            VersionedParser parser = versionedParsers.get(schema);
            if (parser == null) {
                return ParseResult.failure(ParseStatus.UNSUPPORTED_SCHEMA,
                        "unsupported schema_version: " + schema);
            }
            try {
                return ParseResult.loaded(parser.parse(root));
            } catch (DefinitionException e) {
                return ParseResult.failure(ParseStatus.INVALID_DEFINITION, e.getMessage());
            } catch (Throwable t) {
                return ParseResult.failure(ParseStatus.INVALID_DEFINITION, message(t));
            }
        }

        if (!"superresolution.json".equals(fileName)) {
            return ParseResult.failure(ParseStatus.MISSING_SCHEMA,
                    "versioned SR definition is missing schema_version");
        }
        if (!root.has("sr")) {
            return ParseResult.failure(ParseStatus.MISSING_SCHEMA,
                    "unversioned definition is neither legacy SR nor versioned SR");
        }
        try {
            return ParseResult.loaded(parseLegacy(root));
        } catch (DefinitionException e) {
            return ParseResult.failure(ParseStatus.INVALID_DEFINITION, e.getMessage());
        } catch (Throwable t) {
            return ParseResult.failure(ParseStatus.INVALID_DEFINITION, message(t));
        }
    }

    @FunctionalInterface
    public interface VersionedParser {
        SrDefinition parse(JsonObject root) throws DefinitionException;
    }

    public enum ParseStatus {
        LOADED,
        MALFORMED_JSON,
        MISSING_SCHEMA,
        INVALID_SCHEMA,
        UNSUPPORTED_SCHEMA,
        INVALID_DEFINITION
    }

    public record ParseResult(ParseStatus status, SrDefinition definition, String message) {
        public ParseResult {
            if (status == null) throw new IllegalArgumentException("status");
            message = message == null ? "" : message;
        }

        public static ParseResult loaded(SrDefinition definition) {
            return new ParseResult(ParseStatus.LOADED, definition, "");
        }

        public static ParseResult failure(ParseStatus status, String message) {
            if (status == ParseStatus.LOADED) throw new IllegalArgumentException("loaded status");
            return new ParseResult(status, null, message);
        }

        public boolean loaded() {
            return status == ParseStatus.LOADED && definition != null;
        }
    }

    private static SrDefinition parseVersioned(
            JsonObject root, int schemaVersion, SrDefinition.Format format) throws DefinitionException {
        JsonObject profilesNode = requiredObject(root, "profiles", "root");
        Map<String, SrDefinition.Profile> profiles = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> entry : profilesNode.entrySet()) {
            String context = "profiles." + entry.getKey();
            JsonObject profileNode = asObject(entry.getValue(), context);
            boolean enabled = optionalBoolean(profileNode, "enabled", true, context);
            SrDefinition.Upscale upscale = parseVersionedUpscale(
                    optionalObject(profileNode, "upscale", context), context + ".upscale");
            SrDefinition.Jitter jitter = parseVersionedJitter(
                    optionalObject(profileNode, "jitter", context), context + ".jitter");
            profiles.put(entry.getKey(), new SrDefinition.Profile(enabled, upscale, jitter));
        }
        return new SrDefinition(format, schemaVersion, profiles);
    }

    private static SrDefinition.Upscale parseVersionedUpscale(
            JsonObject node, String context) throws DefinitionException {
        if (node == null) return SrDefinition.Upscale.DISABLED;
        boolean enabled = optionalBoolean(node, "enabled", false, context);
        SrDefinition.Trigger trigger = parseTrigger(optionalObject(node, "trigger", context),
                context + ".trigger");
        String internalFormat = optionalString(node, "internal_format", "", context);
        Map<String, SrDefinition.InputTexture> inputs = parseInputs(
                optionalObject(node, "inputs", context), context + ".inputs", true);
        Map<String, SrDefinition.OutputTexture> outputs = parseOutputs(
                optionalObject(node, "outputs", context), context + ".outputs", true);
        return new SrDefinition.Upscale(
                enabled, trigger, internalFormat, inputs, outputs);
    }

    private static SrDefinition.Trigger parseTrigger(
            JsonObject node, String context) throws DefinitionException {
        if (node == null) return SrDefinition.Trigger.NONE;
        String type = requiredString(node, "type", context).toLowerCase(Locale.ROOT);
        SrDefinition.TriggerOrder order = switch (type) {
            case "before" -> SrDefinition.TriggerOrder.BEFORE;
            case "after" -> SrDefinition.TriggerOrder.AFTER;
            default -> throw error(context + ".type must be before or after");
        };
        String pass = requiredString(node, "pass", context);
        if (pass.isBlank()) throw error(context + ".pass must not be blank");
        return new SrDefinition.Trigger(order, pass);
    }

    private static SrDefinition.Jitter parseVersionedJitter(
            JsonObject node, String context) throws DefinitionException {
        if (node == null) return SrDefinition.Jitter.DISABLED;
        boolean enabled = optionalBoolean(node, "enabled", false, context);
        String ownerText = optionalString(node, "source", "mod", context)
                .toLowerCase(Locale.ROOT);
        SrDefinition.JitterOwner owner = switch (ownerText) {
            case "mod" -> SrDefinition.JitterOwner.MOD;
            case "shaderpack" -> SrDefinition.JitterOwner.SHADERPACK;
            default -> throw error(context + ".source must be mod or shaderpack");
        };

        JsonObject sourceConfig = optionalObject(node, "source_config", context);
        SrDefinition.ValueSource offset = SrDefinition.ValueSource.unresolved();
        SrDefinition.ValueSource sequence = SrDefinition.ValueSource.unresolved();
        if (sourceConfig != null) {
            offset = parseValueSource(requiredObject(
                    sourceConfig, "jitter_offset", context + ".source_config"),
                    context + ".source_config.jitter_offset");
            sequence = parseValueSource(requiredObject(
                    sourceConfig, "jitter_sequence_length", context + ".source_config"),
                    context + ".source_config.jitter_sequence_length");
            if (offset.type() != SrDefinition.ValueType.VECTOR2F) {
                throw error(context + ".source_config.jitter_offset must be vector2f");
            }
            if (sequence.type() != SrDefinition.ValueType.INT
                    && sequence.type() != SrDefinition.ValueType.UINT) {
                throw error(context + ".source_config.jitter_sequence_length must be int or uint");
            }
        } else if (enabled && owner == SrDefinition.JitterOwner.SHADERPACK) {
            throw error(context + ".source_config is required for shaderpack-owned jitter");
        }
        return new SrDefinition.Jitter(enabled, owner, offset, sequence);
    }

    private static SrDefinition.ValueSource parseValueSource(
            JsonObject node, String context) throws DefinitionException {
        String source = requiredString(node, "source", context).toLowerCase(Locale.ROOT);
        SrDefinition.ValueType type = parseValueType(requiredString(node, "type", context), context);
        JsonElement value = node.get("value");
        if (value == null || value.isJsonNull()) throw error(context + ".value is required");

        return switch (source) {
            case "uniform" -> SrDefinition.ValueSource.reference(
                    SrDefinition.SourceKind.UNIFORM, type, primitiveString(value, context + ".value"));
            case "variable" -> SrDefinition.ValueSource.reference(
                    SrDefinition.SourceKind.VARIABLE, type, primitiveString(value, context + ".value"));
            case "const" -> SrDefinition.ValueSource.constant(type, constantComponents(value, type, context));
            default -> throw error(context + ".source must be const, uniform, or variable");
        };
    }

    private static SrDefinition.ValueType parseValueType(String value, String context)
            throws DefinitionException {
        try {
            return SrDefinition.ValueType.valueOf(value.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw error(context + ".type is unsupported: " + value);
        }
    }

    private static List<Double> constantComponents(
            JsonElement value, SrDefinition.ValueType type, String context) throws DefinitionException {
        if (type == SrDefinition.ValueType.UNKNOWN) throw error(context + ".type is unknown");
        List<Double> components = new ArrayList<>();
        if (type.componentCount() == 1) {
            components.add(finiteNumber(value, context + ".value"));
        } else {
            if (!value.isJsonArray()) {
                throw error(context + ".value must be an array of " + type.componentCount());
            }
            JsonArray array = value.getAsJsonArray();
            if (array.size() != type.componentCount()) {
                throw error(context + ".value must contain " + type.componentCount() + " components");
            }
            for (int i = 0; i < array.size(); i++) {
                components.add(finiteNumber(array.get(i), context + ".value[" + i + "]"));
            }
        }
        return components;
    }

    private static SrDefinition parseLegacy(JsonObject root) throws DefinitionException {
        JsonObject sr = requiredObject(root, "sr", "root");
        boolean globalEnabled = requiredBoolean(sr, "enabled", "sr");
        JsonObject worlds = requiredObject(sr, "worlds", "sr");

        JsonObject jitterRoot = optionalObject(root, "sr_jitter", "root");
        boolean globalJitterEnabled = jitterRoot != null
                && optionalBoolean(jitterRoot, "enabled", false, "sr_jitter");
        JsonObject jitterWorlds = jitterRoot == null
                ? null : optionalObject(jitterRoot, "worlds", "sr_jitter");

        Set<String> profileKeys = new LinkedHashSet<>(worlds.keySet());
        if (jitterWorlds != null) profileKeys.addAll(jitterWorlds.keySet());

        Map<String, SrDefinition.Profile> profiles = new LinkedHashMap<>();
        for (String key : profileKeys) {
            JsonObject world = worlds.has(key)
                    ? asObject(worlds.get(key), "sr.worlds." + key) : null;
            boolean worldEnabled = world != null
                    && requiredBoolean(world, "enabled", "sr.worlds." + key);
            SrDefinition.Upscale upscale = parseLegacyUpscale(
                    world == null ? null : optionalObject(
                            world, "upscale_config", "sr.worlds." + key),
                    "sr.worlds." + key + ".upscale_config",
                    globalEnabled && worldEnabled);

            JsonObject jitterWorld = selectLegacyJitterWorld(jitterWorlds, key);
            boolean jitterEnabled = globalEnabled && globalJitterEnabled && jitterWorld != null
                    && requiredBoolean(jitterWorld, "enabled", "sr_jitter.worlds." + key);
            SrDefinition.Jitter jitter = jitterEnabled
                    ? new SrDefinition.Jitter(true, SrDefinition.JitterOwner.MOD,
                    SrDefinition.ValueSource.unresolved(), SrDefinition.ValueSource.unresolved())
                    : SrDefinition.Jitter.DISABLED;
            profiles.put(key, new SrDefinition.Profile(
                    globalEnabled && worldEnabled, upscale, jitter));
        }
        return new SrDefinition(SrDefinition.Format.LEGACY, 0, profiles);
    }

    private static JsonObject selectLegacyJitterWorld(JsonObject jitterWorlds, String key)
            throws DefinitionException {
        if (jitterWorlds == null) return null;
        JsonElement exact = jitterWorlds.get(key);
        if (exact != null) return asObject(exact, "sr_jitter.worlds." + key);
        JsonElement fallback = jitterWorlds.get("*");
        return fallback == null ? null : asObject(fallback, "sr_jitter.worlds.*");
    }

    private static SrDefinition.Upscale parseLegacyUpscale(
            JsonObject node, String context, boolean enabled) throws DefinitionException {
        if (node == null) return SrDefinition.Upscale.DISABLED;
        String pass = requiredString(node, "before_upscale_shader_name", context);
        if (pass.isBlank()) throw error(context + ".before_upscale_shader_name must not be blank");
        String format = optionalString(node, "sr_internal_texture_format", "", context);
        Map<String, SrDefinition.InputTexture> inputs = parseInputs(
                optionalObject(node, "input_textures", context),
                context + ".input_textures", false);
        Map<String, SrDefinition.OutputTexture> outputs = parseOutputs(
                optionalObject(node, "output_textures", context),
                context + ".output_textures", false);
        return new SrDefinition.Upscale(enabled,
                new SrDefinition.Trigger(SrDefinition.TriggerOrder.AFTER, pass),
                format, inputs, outputs);
    }

    private static Map<String, SrDefinition.InputTexture> parseInputs(
            JsonObject node, String context, boolean regionOptional) throws DefinitionException {
        if (node == null) return Map.of();
        Map<String, SrDefinition.InputTexture> result = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> entry : node.entrySet()) {
            String child = context + "." + entry.getKey();
            JsonObject texture = asObject(entry.getValue(), child);
            boolean enabled = requiredBoolean(texture, "enabled", child);
            String source = optionalString(texture, "src", "", child);
            if (enabled && source.isBlank()) throw error(child + ".src is required when enabled");
            SrDefinition.Region region = parseRegion(
                    texture.get("region"), child + ".region", regionOptional);
            result.put(entry.getKey(), new SrDefinition.InputTexture(enabled, source, region));
        }
        return result;
    }

    private static Map<String, SrDefinition.OutputTexture> parseOutputs(
            JsonObject node, String context, boolean regionOptional) throws DefinitionException {
        if (node == null) return Map.of();
        Map<String, SrDefinition.OutputTexture> result = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> entry : node.entrySet()) {
            String child = context + "." + entry.getKey();
            JsonObject texture = asObject(entry.getValue(), child);
            boolean enabled = requiredBoolean(texture, "enabled", child);
            List<String> targets = stringList(texture.get("target"), child + ".target");
            if (enabled && targets.isEmpty()) {
                throw error(child + ".target is required when enabled");
            }
            SrDefinition.Region region = parseRegion(
                    texture.get("region"), child + ".region", regionOptional);
            result.put(entry.getKey(), new SrDefinition.OutputTexture(enabled, targets, region));
        }
        return result;
    }

    private static SrDefinition.Region parseRegion(
            JsonElement node, String context, boolean optional)
            throws DefinitionException {
        if (node == null || node.isJsonNull()) {
            if (optional) return null;
            throw error(context + " must be a four-integer array");
        }
        if (!node.isJsonArray() || node.getAsJsonArray().size() != 4) {
            throw error(context + " must be a four-integer array");
        }
        JsonArray values = node.getAsJsonArray();
        try {
            return new SrDefinition.Region(
                    SrDefinition.RegionValue.fromProtocolValue(requiredInteger(values.get(0), context + "[0]")),
                    SrDefinition.RegionValue.fromProtocolValue(requiredInteger(values.get(1), context + "[1]")),
                    SrDefinition.RegionValue.fromProtocolValue(requiredInteger(values.get(2), context + "[2]")),
                    SrDefinition.RegionValue.fromProtocolValue(requiredInteger(values.get(3), context + "[3]")));
        } catch (IllegalArgumentException e) {
            throw error(context + ": " + e.getMessage());
        }
    }

    private static List<String> stringList(JsonElement node, String context) throws DefinitionException {
        if (node == null || node.isJsonNull()) return List.of();
        if (!node.isJsonArray()) throw error(context + " must be an array");
        List<String> result = new ArrayList<>();
        for (JsonElement value : node.getAsJsonArray()) {
            String item = primitiveString(value, context);
            if (item.isBlank()) throw error(context + " contains a blank target");
            result.add(item);
        }
        return result;
    }

    private static JsonObject requiredObject(JsonObject parent, String key, String context)
            throws DefinitionException {
        JsonObject result = optionalObject(parent, key, context);
        if (result == null) throw error(context + "." + key + " is required");
        return result;
    }

    private static JsonObject optionalObject(JsonObject parent, String key, String context)
            throws DefinitionException {
        JsonElement value = parent.get(key);
        if (value == null || value.isJsonNull()) return null;
        if (!value.isJsonObject()) throw error(context + "." + key + " must be an object");
        return value.getAsJsonObject();
    }

    private static JsonObject asObject(JsonElement value, String context) throws DefinitionException {
        if (value == null || !value.isJsonObject()) throw error(context + " must be an object");
        return value.getAsJsonObject();
    }

    private static boolean requiredBoolean(JsonObject parent, String key, String context)
            throws DefinitionException {
        JsonElement value = parent.get(key);
        if (value == null) throw error(context + "." + key + " is required");
        return booleanValue(value, context + "." + key);
    }

    private static boolean optionalBoolean(
            JsonObject parent, String key, boolean fallback, String context) throws DefinitionException {
        JsonElement value = parent.get(key);
        return value == null ? fallback : booleanValue(value, context + "." + key);
    }

    private static boolean booleanValue(JsonElement value, String context) throws DefinitionException {
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isBoolean()) {
            throw error(context + " must be a boolean");
        }
        return value.getAsBoolean();
    }

    private static String requiredString(JsonObject parent, String key, String context)
            throws DefinitionException {
        JsonElement value = parent.get(key);
        if (value == null) throw error(context + "." + key + " is required");
        return primitiveString(value, context + "." + key);
    }

    private static String optionalString(
            JsonObject parent, String key, String fallback, String context) throws DefinitionException {
        JsonElement value = parent.get(key);
        return value == null ? fallback : primitiveString(value, context + "." + key);
    }

    private static String primitiveString(JsonElement value, String context) throws DefinitionException {
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
            throw error(context + " must be a string");
        }
        return value.getAsString();
    }

    private static int requiredInteger(JsonElement value, String context) throws DefinitionException {
        Integer result = strictInteger(value);
        if (result == null) throw error(context + " must be an integer");
        return result;
    }

    private static Integer strictInteger(JsonElement value) {
        if (value == null || !value.isJsonPrimitive()) return null;
        JsonPrimitive primitive = value.getAsJsonPrimitive();
        if (!primitive.isNumber()) return null;
        String text = primitive.getAsString();
        if (!text.matches("-?(0|[1-9][0-9]*)")) return null;
        try {
            return Integer.valueOf(text);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static double finiteNumber(JsonElement value, String context) throws DefinitionException {
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
            throw error(context + " must be numeric");
        }
        double result = value.getAsDouble();
        if (!Double.isFinite(result)) throw error(context + " must be finite");
        return result;
    }

    private static String message(Throwable throwable) {
        String message = throwable.getMessage();
        return message == null || message.isBlank()
                ? throwable.getClass().getSimpleName() : message;
    }

    private static DefinitionException error(String message) {
        return new DefinitionException(message);
    }

    public static final class DefinitionException extends Exception {
        public DefinitionException(String message) {
            super(message);
        }
    }
}
