package cn.spectra.gallium.glowoutline.sr;

import cn.spectra.gallium.Gallium;
import cn.spectra.gallium.glowoutline.sr.definition.SrDefinition;
import cn.spectra.gallium.glowoutline.sr.definition.SrDefinitionLoader;
import cn.spectra.gallium.glowoutline.sr.runtime.ReflectiveSrRuntimeAccess;
import cn.spectra.gallium.glowoutline.sr.runtime.ShaderPackProjectionResolver;
import cn.spectra.gallium.glowoutline.sr.runtime.SrProjectionResolver;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Loads a shader pack's version-neutral Super Resolution definition and resolves its active
 * profile against live SR/Iris values.  All references to either mod stay reflective so this
 * class remains loadable in every Gallium build and when either optional mod is absent.
 */
public final class SrShaderPackResolver {

    private static final Pattern SR_MACRO = Pattern.compile("\\bSR_[A-Z][A-Z0-9_]*\\b");
    private static final Pattern MACRO_DIRECTIVE = Pattern.compile(
            "^\\s*#\\s*(define|undef)\\s+([A-Za-z_][A-Za-z0-9_]*)\\b");
    private static final Pattern CONDITIONAL_DIRECTIVE = Pattern.compile(
            "^\\s*#\\s*(if|ifdef|ifndef|elif|else|endif)\\b");
    private static final Pattern PREPROCESSOR_DIRECTIVE = Pattern.compile("^\\s*#");
    private static final String SR_INSTALLED = "SR_INSTALLED";

    private SrShaderPackResolver() {}

    public static Path normalizeShadersPath(Path path) {
        return path == null ? null : path.toAbsolutePath().normalize();
    }

    public static Optional<Path> packRootForShaders(Path shadersPath) {
        Path normalized = normalizeShadersPath(shadersPath);
        return normalized == null ? Optional.empty() : Optional.ofNullable(normalized.getParent());
    }

    public static Optional<Session> load(Path packRoot, Object irisPack) {
        Optional<Map<String, String>> environment =
                ReflectivePreprocessor.standardEnvironmentMacros(
                        SrShaderPackResolver.class.getClassLoader());
        if (environment.isEmpty()) {
            // Without the exact Iris environment, C-preprocessor semantics would silently turn
            // unknown identifiers into zero and could select the wrong profile branch.
            return loadInternal(packRoot, irisPack, null, false);
        }
        return load(packRoot, irisPack, environment.get());
    }

    /** Loads using the exact macro list Iris passed to this ShaderPack constructor. */
    public static Optional<Session> load(
            Path packRoot, Object irisPack, Map<String, String> environmentDefines) {
        return loadInternal(packRoot, irisPack,
                environmentDefines == null ? Map.of() : Map.copyOf(environmentDefines), true);
    }

    private static Optional<Session> loadInternal(
            Path packRoot, Object irisPack,
            Map<String, String> environmentDefines, boolean environmentComplete) {
        if (packRoot == null || irisPack == null) return Optional.empty();

        SrDefinitionLoader loader = new SrDefinitionLoader();
        Optional<ReflectivePreprocessor> preprocessor = environmentComplete
                ? ReflectivePreprocessor.create(environmentDefines, irisPack)
                : Optional.empty();
        SrDefinitionLoader.LoadResult result = preprocessor.isPresent()
                ? loader.load(packRoot, preprocessor.get()::process)
                : loader.load(packRoot);

        if (!result.loaded()) {
            if (result.status() != SrDefinitionLoader.LoadStatus.NOT_FOUND) {
                Gallium.LOGGER.warn("SR shader-pack definition {} was not usable: {} ({})",
                        result.fileName(), result.status(), result.message());
            }
            return Optional.empty();
        }
        return Optional.of(new Session(result.definition(), result.fileName(), irisPack));
    }

    public static final class Session {
        private final SrDefinition definition;
        private final String fileName;
        private final Object irisPack;
        private final SrProjectionResolver resolver = new SrProjectionResolver(
                ReflectiveSrRuntimeAccess.getInstance());
        private final ShaderPackProjectionResolver packProjectionResolver =
                new ShaderPackProjectionResolver(ReflectiveSrRuntimeAccess.getInstance());

        private Session(SrDefinition definition, String fileName, Object irisPack) {
            this.definition = definition;
            this.fileName = fileName;
            this.irisPack = irisPack;
        }

        public SrDefinition definition() {
            return definition;
        }

        public String fileName() {
            return fileName;
        }

        public Optional<SrProjectionResolver.ProjectionResolution> resolve() {
            String profileKey = currentProfileKey(irisPack);
            return definition.profile(profileKey)
                    .map(profile -> resolver.resolve(profile, definition.format()))
                    .filter(SrProjectionResolver.ProjectionResolution::active);
        }

        public Optional<ShaderPackProjectionResolver.ProjectionResolution> resolvePackProjection(
                int screenWidth, int screenHeight) {
            String profileKey = currentProfileKey(irisPack);
            return definition.profile(profileKey).flatMap(profile ->
                    packProjectionResolver.resolve(
                            profile, irisPack, screenWidth, screenHeight));
        }
    }

    /** Copies Iris StringPair values without linking Gallium's universal reader to Iris classes. */
    public static Optional<Map<String, String>> tryCopyEnvironmentDefines(Iterable<?> definitions) {
        if (definitions == null) return Optional.empty();
        Map<String, String> result = new LinkedHashMap<>();
        for (Object pair : definitions) {
            if (pair == null) continue;
            try {
                Object key = pair.getClass().getMethod("key").invoke(pair);
                Object value = pair.getClass().getMethod("value").invoke(pair);
                if (key != null) {
                    result.put(key.toString(), value == null ? "" : value.toString());
                }
            } catch (Throwable t) {
                Gallium.LOGGER.warn("Could not copy an Iris shader environment define: {}",
                        t.toString());
                return Optional.empty();
            }
        }
        return result.isEmpty() ? Optional.empty() : Optional.of(Map.copyOf(result));
    }

    /** Copies Iris's effective boolean/string option values into C-preprocessor macro form. */
    static Optional<Map<String, String>> tryCopyShaderPackOptionMacros(Object irisPack) {
        if (irisPack == null) return Optional.empty();
        try {
            Object options = irisPack.getClass().getMethod("getShaderPackOptions")
                    .invoke(irisPack);
            if (options == null) return Optional.empty();

            Method getOptionSet = options.getClass().getMethod("getOptionSet");
            Method getOptionValues = options.getClass().getMethod("getOptionValues");
            Object optionSet = getOptionSet.invoke(options);
            Object optionValues = getOptionValues.invoke(options);
            if (optionSet == null || optionValues == null) return Optional.empty();

            Method getBooleanOptions = getOptionSet.getReturnType()
                    .getMethod("getBooleanOptions");
            Method getStringOptions = getOptionSet.getReturnType()
                    .getMethod("getStringOptions");
            Method getBooleanValue = getOptionValues.getReturnType()
                    .getMethod("getBooleanValueOrDefault", String.class);
            Method getStringValue = getOptionValues.getReturnType()
                    .getMethod("getStringValueOrDefault", String.class);

            Object booleanOptions = getBooleanOptions.invoke(optionSet);
            Object stringOptions = getStringOptions.invoke(optionSet);
            if (!(booleanOptions instanceof Map<?, ?> booleans)
                    || !(stringOptions instanceof Map<?, ?> strings)) {
                return Optional.empty();
            }

            Map<String, String> result = new LinkedHashMap<>();
            for (Object key : booleans.keySet()) {
                if (key == null) continue;
                String name = key.toString();
                if (Boolean.TRUE.equals(getBooleanValue.invoke(optionValues, name))) {
                    // Mirror Iris PropertiesPreprocessor exactly: a true boolean is a bare
                    // macro, while false is absent. This keeps installed-vs-absent SR branches
                    // identical even for packs that depend on textual macro expansion.
                    result.put(name, "");
                }
            }
            for (Object key : strings.keySet()) {
                if (key == null) continue;
                String name = key.toString();
                Object value = getStringValue.invoke(optionValues, name);
                if (value == null) return Optional.empty();
                result.put(name, value.toString());
            }
            return Optional.of(Map.copyOf(result));
        } catch (Throwable t) {
            Gallium.LOGGER.debug(
                    "Could not copy Iris shader-pack options for SR preprocessing: {}",
                    t.toString());
            return Optional.empty();
        }
    }

    private static String currentProfileKey(Object pack) {
        try {
            Class<?> iris = Class.forName("net.irisshaders.iris.Iris", false,
                    SrShaderPackResolver.class.getClassLoader());
            Object dimension = iris.getMethod("getCurrentDimension").invoke(null);
            return profileKeyForDimension(pack, dimension);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** Cross-Iris profile lookup: older 1.8 builds expose only a private dimensionMap field. */
    static String profileKeyForDimension(Object pack, Object dimension) {
        if (pack == null || dimension == null) return null;
        Object value = null;
        try {
            value = pack.getClass().getMethod("getDimensionMap").invoke(pack);
        } catch (Throwable ignored) {
            Class<?> type = pack.getClass();
            while (type != null && value == null) {
                try {
                    Field field = type.getDeclaredField("dimensionMap");
                    field.setAccessible(true);
                    value = field.get(pack);
                    break;
                } catch (NoSuchFieldException e) {
                    type = type.getSuperclass();
                } catch (Throwable reflectionFailure) {
                    return null;
                }
            }
        }
        if (!(value instanceof Map<?, ?> map)) return null;
        Object profile = map.get(dimension);
        if (profile == null) return null;
        String key = profile.toString();
        return key.startsWith("world") ? key.substring("world".length()) : key;
    }

    /**
     * Rejects only genuinely unavailable SR runtime macros. SR_INSTALLED has an exact known
     * absent value in this path, while file-local SR_* helpers follow define/undef order.
     */
    static void requireNoUnavailableSrMacros(
            String source, Set<String> availableSrMacros) {
        Set<String> available = availableSrMacros == null
                ? Set.of() : availableSrMacros;
        Set<String> local = new LinkedHashSet<>();
        String sanitized = stripCommentsAndQuotedText(source);
        for (String line : sanitized.split("\\R", -1)) {
            Matcher directive = MACRO_DIRECTIVE.matcher(line);
            String operation = null;
            String declared = null;
            if (directive.find()) {
                operation = directive.group(1);
                String name = directive.group(2);
                if (name.startsWith("SR_")) {
                    declared = name;
                    if ("define".equals(operation)) local.add(declared);
                }
            }

            Matcher macro = SR_MACRO.matcher(line);
            while (macro.find()) {
                String name = macro.group();
                if (name.equals(declared)
                        || name.equals(SR_INSTALLED)
                        || available.contains(name)
                        || local.contains(name)) {
                    continue;
                }
                throw new IllegalArgumentException(
                        "SR macro " + name
                                + " requires an installed Super Resolution runtime");
            }
            if ("undef".equals(operation)) local.remove(declared);
        }
    }

    /**
     * Adds inert identifier lines around conditional branches and local SR directives. Iris's
     * own preprocessor then tells us exactly which markers survived, including nested
     * {@code if/elif/else} and the pack's effective options. The markers are removed before JSON
     * parsing, so branch selection is still performed by Iris rather than reimplemented here.
     */
    private static MacroValidationProbe macroValidationProbe(String source) {
        String markerPrefix = "GALLIUM_SR_ACTIVE_BRANCH_";
        while (source.contains(markerPrefix)) markerPrefix += "X";

        String[] originalLines = source.split("\\R", -1);
        String[] sanitizedLines = stripCommentsAndQuotedText(source).split("\\R", -1);
        StringBuilder instrumented = new StringBuilder(source.length() + 256);
        List<ConditionalProbe> stack = new ArrayList<>();
        List<MacroProbeEvent> events = new ArrayList<>();
        int nextMarker = 0;

        for (int index = 0; index < originalLines.length; index++) {
            String firstSanitized = index < sanitizedLines.length
                    ? sanitizedLines[index] : originalLines[index];
            int logicalEnd = PREPROCESSOR_DIRECTIVE.matcher(firstSanitized).find()
                    ? logicalDirectiveEnd(originalLines, index) : index;
            String sanitized = joinedLines(sanitizedLines, index, logicalEnd);
            Matcher conditional = CONDITIONAL_DIRECTIVE.matcher(sanitized);
            if (conditional.find()) {
                String kind = conditional.group(1);
                if ("if".equals(kind) || "ifdef".equals(kind) || "ifndef".equals(kind)) {
                    Integer parentBranchMarker = stack.isEmpty()
                            ? null : stack.get(stack.size() - 1).currentBranchMarker();
                    appendProbeLines(instrumented, originalLines, index, logicalEnd);
                    ConditionalProbe group = new ConditionalProbe(parentBranchMarker);
                    int branchMarker = nextMarker++;
                    group.addBranchMarker(branchMarker);
                    appendProbeMarker(instrumented, markerPrefix, branchMarker);
                    events.add(MacroProbeEvent.condition(
                            sanitized, group, 0));
                    stack.add(group);
                    index = logicalEnd;
                    continue;
                }
                if ("elif".equals(kind) && !stack.isEmpty()) {
                    appendProbeLines(instrumented, originalLines, index, logicalEnd);
                    ConditionalProbe group = stack.get(stack.size() - 1);
                    int branchIndex = group.branchMarkers.size();
                    int branchMarker = nextMarker++;
                    group.addBranchMarker(branchMarker);
                    appendProbeMarker(instrumented, markerPrefix, branchMarker);
                    events.add(MacroProbeEvent.condition(
                            sanitized, group, branchIndex));
                    index = logicalEnd;
                    continue;
                }
                if ("else".equals(kind) && !stack.isEmpty()) {
                    appendProbeLines(instrumented, originalLines, index, logicalEnd);
                    ConditionalProbe group = stack.get(stack.size() - 1);
                    int branchMarker = nextMarker++;
                    group.addBranchMarker(branchMarker);
                    appendProbeMarker(instrumented, markerPrefix, branchMarker);
                    index = logicalEnd;
                    continue;
                }
                appendProbeLines(instrumented, originalLines, index, logicalEnd);
                if ("endif".equals(kind) && !stack.isEmpty()) {
                    stack.remove(stack.size() - 1);
                }
                index = logicalEnd;
                continue;
            }

            Matcher macroDirective = MACRO_DIRECTIVE.matcher(sanitized);
            if (macroDirective.find()) {
                appendProbeLines(instrumented, originalLines, index, logicalEnd);
                int activeMarker = nextMarker++;
                appendProbeMarker(instrumented, markerPrefix, activeMarker);
                String declaredName = macroDirective.group(2);
                events.add(MacroProbeEvent.macroDirective(
                        sanitized, macroDirective.group(1),
                        declaredName.startsWith("SR_") ? declaredName : null,
                        activeMarker));
                index = logicalEnd;
                continue;
            }
            appendProbeLines(instrumented, originalLines, index, logicalEnd);
            index = logicalEnd;
        }
        return new MacroValidationProbe(
                instrumented.toString(), markerPrefix, List.copyOf(events));
    }

    private static void appendProbeMarker(
            StringBuilder target, String markerPrefix, int marker) {
        target.append(markerPrefix).append(marker).append('\n');
    }

    private static void appendProbeLines(
            StringBuilder target, String[] lines, int start, int end) {
        for (int index = start; index <= end; index++) {
            target.append(lines[index]).append('\n');
        }
    }

    private static int logicalDirectiveEnd(String[] lines, int start) {
        int end = start;
        while (end + 1 < lines.length && hasLineContinuation(lines[end])) end++;
        return end;
    }

    private static boolean hasLineContinuation(String line) {
        return line != null && line.stripTrailing().endsWith("\\");
    }

    private static String joinedLines(String[] lines, int start, int end) {
        StringBuilder result = new StringBuilder();
        for (int index = start; index <= end && index < lines.length; index++) {
            if (result.length() > 0) result.append('\n');
            result.append(lines[index]);
        }
        return result.toString();
    }

    private static final class ConditionalProbe {
        private final Integer parentBranchMarker;
        private final List<Integer> branchMarkers = new ArrayList<>();
        private int currentBranchMarker = -1;

        private ConditionalProbe(Integer parentBranchMarker) {
            this.parentBranchMarker = parentBranchMarker;
        }

        private void addBranchMarker(int marker) {
            branchMarkers.add(marker);
            currentBranchMarker = marker;
        }

        private int currentBranchMarker() {
            return currentBranchMarker;
        }
    }

    private record MacroProbeEvent(
            String sourceLine, String operation, String localName, int activeMarker,
            ConditionalProbe conditional, int branchIndex) {
        private static MacroProbeEvent condition(
                String sourceLine, ConditionalProbe conditional, int branchIndex) {
            return new MacroProbeEvent(
                    sourceLine, "condition", null, -1, conditional, branchIndex);
        }

        private static MacroProbeEvent macroDirective(
                String sourceLine, String operation, String localName, int activeMarker) {
            return new MacroProbeEvent(
                    sourceLine, operation, localName, activeMarker, null, -1);
        }
    }

    private record MacroValidationProbe(
            String source, String markerPrefix, List<MacroProbeEvent> events) {
        private String validateAndStrip(
                String processed, Set<String> availableSrMacros) {
            Set<Integer> activeMarkers = activeMarkers(processed);
            Set<String> localMacros = new LinkedHashSet<>();
            Set<String> available = availableSrMacros == null
                    ? Set.of() : availableSrMacros;

            for (MacroProbeEvent event : events) {
                if (event.conditional() != null) {
                    if (conditionWasEvaluated(event, activeMarkers)) {
                        requireNoUnavailableSrMacros(
                                event.sourceLine(), union(available, localMacros));
                    }
                    continue;
                }
                if (!activeMarkers.contains(event.activeMarker())) continue;
                requireNoUnavailableSrMacros(
                        event.sourceLine(), union(available, localMacros));
                if (event.localName() == null) continue;
                if (event.localName() != null) {
                    if ("define".equals(event.operation())) {
                        localMacros.add(event.localName());
                    } else {
                        localMacros.remove(event.localName());
                    }
                }
            }

            String stripped = stripMarkers(processed);
            // Unknown identifiers in the selected JSON body survive JCPP expansion. This final
            // pass catches those references while ignoring strings and comments.
            requireNoUnavailableSrMacros(stripped, available);
            return stripped;
        }

        private boolean conditionWasEvaluated(
                MacroProbeEvent event, Set<Integer> activeMarkers) {
            ConditionalProbe group = event.conditional();
            if (group.parentBranchMarker != null
                    && !activeMarkers.contains(group.parentBranchMarker)) {
                return false;
            }
            for (int index = 0; index < event.branchIndex(); index++) {
                if (activeMarkers.contains(group.branchMarkers.get(index))) return false;
            }
            return true;
        }

        private Set<Integer> activeMarkers(String processed) {
            Set<Integer> result = new LinkedHashSet<>();
            Matcher matcher = Pattern.compile(
                    "\\b" + Pattern.quote(markerPrefix) + "(\\d+)\\b")
                    .matcher(processed);
            while (matcher.find()) result.add(Integer.parseInt(matcher.group(1)));
            return result;
        }

        private String stripMarkers(String processed) {
            StringBuilder result = new StringBuilder(processed.length());
            for (String line : processed.split("\\R", -1)) {
                String trimmed = line.trim();
                if (trimmed.startsWith(markerPrefix)
                        && trimmed.substring(markerPrefix.length()).matches("\\d+")) {
                    continue;
                }
                result.append(line).append('\n');
            }
            return result.toString();
        }

        private static Set<String> union(Set<String> available, Set<String> local) {
            if (local.isEmpty()) return available;
            Set<String> result = new LinkedHashSet<>(available);
            result.addAll(local);
            return result;
        }
    }

    /** Macro-looking uniform names inside JSON strings/comments are data, not expressions. */
    private static String stripCommentsAndQuotedText(String source) {
        if (source == null || source.isEmpty()) return "";
        StringBuilder result = new StringBuilder(source.length());
        int cursor = 0;
        while (cursor < source.length()) {
            char value = source.charAt(cursor);
            if (value == '/' && cursor + 1 < source.length()
                    && source.charAt(cursor + 1) == '/') {
                result.append("  ");
                cursor += 2;
                while (cursor < source.length() && source.charAt(cursor) != '\n') {
                    result.append(' ');
                    cursor++;
                }
                continue;
            }
            if (value == '/' && cursor + 1 < source.length()
                    && source.charAt(cursor + 1) == '*') {
                result.append("  ");
                cursor += 2;
                while (cursor < source.length()) {
                    if (cursor + 1 < source.length() && source.charAt(cursor) == '*'
                            && source.charAt(cursor + 1) == '/') {
                        result.append("  ");
                        cursor += 2;
                        break;
                    }
                    result.append(source.charAt(cursor) == '\n' ? '\n' : ' ');
                    cursor++;
                }
                continue;
            }
            if (value == '"' || value == '\'') {
                char quote = value;
                result.append(' ');
                cursor++;
                boolean escaped = false;
                while (cursor < source.length()) {
                    char quoted = source.charAt(cursor++);
                    result.append(quoted == '\n' ? '\n' : ' ');
                    if (escaped) {
                        escaped = false;
                    } else if (quoted == '\\') {
                        escaped = true;
                    } else if (quoted == quote) {
                        break;
                    }
                }
                continue;
            }
            result.append(value);
            cursor++;
        }
        return result.toString();
    }

    /**
     * Uses SR's own bundled preprocessor when SR is installed.  With no SR jar at all, Iris's
     * stable properties preprocessor supplies the exact active shader options and constructor
     * environment. No SR macro is fabricated while the mod is absent: doing so would change
     * {@code #ifdef SR_INSTALLED} semantics. A definition that references any unavailable
     * {@code SR_*} macro fails closed instead of letting the C preprocessor turn it into zero.
     */
    private static final class ReflectivePreprocessor {
        private static final String PREPROCESSOR =
                "io.homo.superresolution.common.minecraft.handler.shadercompat.JsonMacroPreprocessor";
        private static final String BUILTINS =
                "io.homo.superresolution.common.minecraft.handler.shadercompat.SRCompatBuiltinMacros";
        private static final String SR_API =
                "io.homo.superresolution.api.SuperResolutionAPI";
        private static final String IRIS_PROPERTIES_PREPROCESSOR =
                "net.irisshaders.iris.shaderpack.preprocessor.PropertiesPreprocessor";
        private static final String IRIS_SHADER_PACK_OPTIONS =
                "net.irisshaders.iris.shaderpack.option.ShaderPackOptions";
        private static final String IRIS_STRING_PAIR =
                "net.irisshaders.iris.helpers.StringPair";

        private final Processor processor;

        private ReflectivePreprocessor(Processor processor) {
            this.processor = processor;
        }

        static Optional<ReflectivePreprocessor> create(
                Map<String, String> environmentDefines, Object irisPack) {
            ClassLoader loader = SrShaderPackResolver.class.getClassLoader();
            if (isSuperResolutionInstalled(loader)) {
                return createSuperResolutionPreprocessor(
                        environmentDefines, irisPack, loader);
            }
            return createIrisPreprocessor(environmentDefines, irisPack, loader);
        }

        private static Optional<ReflectivePreprocessor> createSuperResolutionPreprocessor(
                Map<String, String> environmentDefines, Object irisPack, ClassLoader loader) {
            try {
                Class<?> type = Class.forName(PREPROCESSOR, false, loader);
                Object delegate = type.getConstructor().newInstance();
                Method addMacro = type.getMethod("addMacro", String.class, String.class);

                for (Map.Entry<String, String> macro : environmentDefines.entrySet()) {
                    addMacro.invoke(delegate, macro.getKey(), macro.getValue());
                }

                // SR's own loader receives only Iris environment defines. Gallium additionally
                // supplies the effective pack options so installing SR cannot change which JSON
                // macro branch the universal reader selects.
                Optional<Map<String, String>> optionMacros =
                        tryCopyShaderPackOptionMacros(irisPack);
                if (optionMacros.isEmpty()) return Optional.empty();
                for (Map.Entry<String, String> macro : optionMacros.get().entrySet()) {
                    addMacro.invoke(delegate, macro.getKey(), macro.getValue());
                }

                try {
                    Class<?> builtins = Class.forName(BUILTINS, false, loader);
                    Method addMacros = builtins.getMethod("addMacros", type);
                    addMacros.invoke(null, delegate);
                } catch (ClassNotFoundException ignored) {
                    // Schema-v1-era SR did not expose the shared built-in registrar. Macros were
                    // not part of v1's supported contract, so the captured Iris environment is
                    // still the closest equivalent to the official loader.
                }
                Method process = type.getMethod("process", String.class);
                return Optional.of(new ReflectivePreprocessor((ignoredPath, source) -> {
                    Object value = process.invoke(delegate, source);
                    if (!(value instanceof String text)) {
                        throw new IllegalStateException("SR preprocessor returned a non-string result");
                    }
                    return text;
                }));
            } catch (Throwable t) {
                Gallium.LOGGER.debug("SR JSON macro preprocessor unavailable: {}", t.toString());
                return Optional.empty();
            }
        }

        private static Optional<ReflectivePreprocessor> createIrisPreprocessor(
                Map<String, String> environmentDefines, Object irisPack, ClassLoader loader) {
            if (irisPack == null) return Optional.empty();
            try {
                Class<?> properties = Class.forName(
                        IRIS_PROPERTIES_PREPROCESSOR, false, loader);
                Class<?> optionsType = Class.forName(IRIS_SHADER_PACK_OPTIONS, false, loader);
                Class<?> pairType = Class.forName(IRIS_STRING_PAIR, false, loader);

                Method getOptions = irisPack.getClass().getMethod("getShaderPackOptions");
                Object options = getOptions.invoke(irisPack);
                if (options == null || !optionsType.isInstance(options)) return Optional.empty();
                Optional<Set<String>> optionNames = availableOptionNames(options);
                if (optionNames.isEmpty()) return Optional.empty();

                Map<String, String> defines = new LinkedHashMap<>(environmentDefines);
                Set<String> availableSrMacros = new LinkedHashSet<>();
                defines.keySet().stream().filter(name -> name.startsWith("SR_"))
                        .forEach(availableSrMacros::add);
                optionNames.get().stream().filter(name -> name.startsWith("SR_"))
                        .forEach(availableSrMacros::add);

                java.lang.reflect.Constructor<?> pairConstructor =
                        pairType.getConstructor(String.class, String.class);
                List<Object> pairs = new ArrayList<>(defines.size());
                for (Map.Entry<String, String> define : defines.entrySet()) {
                    pairs.add(pairConstructor.newInstance(define.getKey(), define.getValue()));
                }

                Method preprocess = properties.getMethod(
                        "preprocessSource", String.class, optionsType, Iterable.class);
                return Optional.of(new ReflectivePreprocessor((ignoredPath, source) -> {
                    MacroValidationProbe probe = macroValidationProbe(source);
                    Object value = preprocess.invoke(null, probe.source(), options, pairs);
                    if (!(value instanceof String text)) {
                        throw new IllegalStateException(
                                "Iris properties preprocessor returned a non-string result");
                    }
                    return probe.validateAndStrip(text, availableSrMacros);
                }));
            } catch (Throwable t) {
                Gallium.LOGGER.debug(
                        "Iris JSON macro preprocessor fallback unavailable: {}", t.toString());
                return Optional.empty();
            }
        }

        private static Optional<Set<String>> availableOptionNames(Object options) {
            try {
                Object optionSet = options.getClass().getMethod("getOptionSet").invoke(options);
                if (optionSet == null) return Optional.empty();
                Set<String> result = new LinkedHashSet<>();
                for (String getter : List.of("getBooleanOptions", "getStringOptions")) {
                    Object value = optionSet.getClass().getMethod(getter).invoke(optionSet);
                    if (!(value instanceof Map<?, ?> map)) return Optional.empty();
                    for (Object key : map.keySet()) {
                        if (key != null) result.add(key.toString());
                    }
                }
                return Optional.of(Set.copyOf(result));
            } catch (Throwable ignored) {
                return Optional.empty();
            }
        }

        private static boolean isSuperResolutionInstalled(ClassLoader loader) {
            try {
                Class<?> fabricLoader = Class.forName(
                        "net.fabricmc.loader.api.FabricLoader", false, loader);
                Object instance = fabricLoader.getMethod("getInstance").invoke(null);
                Object result = fabricLoader.getMethod("isModLoaded", String.class)
                        .invoke(instance, "super_resolution");
                if (result instanceof Boolean installed) return installed;
            } catch (Throwable ignored) {
                // Unit tests and nonstandard launchers may not expose Fabric's singleton.
            }
            return classPresent(PREPROCESSOR, loader) || classPresent(SR_API, loader);
        }

        private static boolean classPresent(String name, ClassLoader loader) {
            try {
                Class.forName(name, false, loader);
                return true;
            } catch (Throwable ignored) {
                return false;
            }
        }

        String process(Path ignoredPath, String source) throws Exception {
            return processor.process(ignoredPath, source);
        }

        private static Optional<Map<String, String>> standardEnvironmentMacros(
                ClassLoader loader) {
            try {
                Class<?> standardMacros = Class.forName(
                        "net.irisshaders.iris.gl.shader.StandardMacros", false, loader);
                Object pairs = standardMacros.getMethod("createStandardEnvironmentDefines")
                        .invoke(null);
                if (!(pairs instanceof Iterable<?> iterable)) return Optional.empty();
                return tryCopyEnvironmentDefines(iterable);
            } catch (Throwable t) {
                return Optional.empty();
            }
        }

        @FunctionalInterface
        private interface Processor {
            String process(Path path, String source) throws Exception;
        }
    }
}
