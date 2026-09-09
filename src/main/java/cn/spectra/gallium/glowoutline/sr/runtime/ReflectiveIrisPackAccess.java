package cn.spectra.gallium.glowoutline.sr.runtime;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/** Reads Iris's already-preprocessed per-dimension texture-size directives without linking Iris. */
final class ReflectiveIrisPackAccess {

    private static final Pattern FSR_AFFINE_PROJECTION = Pattern.compile(
            "(?s)\\A\\s*void\\s+FsrScaleVS\\s*\\(\\s*inout\\s+vec4\\s+position"
                    + "\\s*,\\s*(?:in\\s+)?vec2\\s+jitter\\s*\\)\\s*\\{\\s*"
                    + "position\\.xy\\s*/=\\s*position\\.w\\s*;\\s*"
                    + "position\\.xy\\s*=\\s*position\\.xy\\s*\\*\\s*fsrRenderScale"
                    + "\\s*\\+\\s*fsrRenderScale\\s*-\\s*1(?:\\.0)?\\s*;"
                    + "\\s*position\\.xy\\s*\\+=\\s*jitter\\s*;"
                    + "\\s*position\\.xy\\s*\\*=\\s*position\\.w\\s*;"
                    + "\\s*\\}\\s*\\z");
    private static final Pattern FSR_AFFINE_CALL = Pattern.compile(
            "FsrScaleVS\\s*\\(\\s*gl_Position\\s*,");
    private static final Pattern FSR_FUNCTION_START = Pattern.compile(
            "\\bvoid\\s+FsrScaleVS\\s*\\([^)]*\\)\\s*\\{");
    private static final String[] CAPTURE_PROGRAMS = {
            "Entities", "EntitiesTrans", "EntitiesGlowing",
            "Item", "Block", "BlockTrans", "ArmorGlint", "Hand", "HandWater"
    };
    private static final String[] GLOBAL_TEMPORAL_PROGRAMS = {
            "Entities", "EntitiesTrans", "EntitiesGlowing",
            "Item", "Block", "BlockTrans", "ArmorGlint", "Hand", "HandWater",
            // These programs produce the world/foreground scene depth sampled by the composite.
            // Exact replay is global only when their actual jitter agrees with captured masks.
            "Terrain", "TerrainSolid", "TerrainCutout", "Water",
            "DhTerrain", "DhWater", "DhGeneric"
    };

    private Object cachedProofProgramSet;
    private ProjectionAnalysis cachedProjectionAnalysis = ProjectionAnalysis.rejected();

    Optional<int[]> textureExtent(
            Object irisPack, String textureSource, int screenWidth, int screenHeight) {
        int index = colorTargetIndex(textureSource);
        if (irisPack == null || index < 0 || screenWidth <= 0 || screenHeight <= 0) {
            return Optional.empty();
        }
        try {
            Object programSet = currentProgramSet(irisPack);
            if (programSet == null) return Optional.empty();
            Object directives = programSet.getClass().getMethod("getPackDirectives")
                    .invoke(programSet);
            if (directives == null) return Optional.empty();
            Object extent = directives.getClass().getMethod(
                    "getTextureScaleOverride", int.class, int.class, int.class)
                    .invoke(directives, index, screenWidth, screenHeight);
            return integerVector2(extent);
        } catch (Throwable ignored) {
            return Optional.empty();
        }
    }

    /** Proves the restricted affine projection form before a target extent may drive replay. */
    boolean hasFsrAffineProjection(Object irisPack) {
        return projectionAnalysis(irisPack).affine();
    }

    /** Returns the call-site consensus only after every capture program proves the same affine form. */
    FsrTemporalJitterAnalyzer.Analysis fsrTemporalJitter(Object irisPack) {
        return projectionAnalysis(irisPack).temporalJitter();
    }

    private ProjectionAnalysis projectionAnalysis(Object irisPack) {
        if (irisPack == null) return ProjectionAnalysis.rejected();
        try {
            Object programSet = currentProgramSet(irisPack);
            if (programSet == null) return ProjectionAnalysis.rejected();
            if (programSet == cachedProofProgramSet) return cachedProjectionAnalysis;
            ClassLoader loader = ReflectiveIrisPackAccess.class.getClassLoader();
            Class<?> programId = Class.forName(
                    "net.irisshaders.iris.shaderpack.loading.ProgramId", false, loader);
            // If Iris changes the directive ABI, assume Weather may write depth and require it;
            // losing exact replay is safer than treating rain depth as aligned without proof.
            boolean rainDepth = rainDepthEnabled(programSet).orElse(true);
            ProjectionAnalysis proof = analyzeProjectionPrograms(
                    programSet, programId, rainDepth);
            cachedProofProgramSet = programSet;
            cachedProjectionAnalysis = proof;
            return cachedProjectionAnalysis;
        } catch (Throwable ignored) {
            return ProjectionAnalysis.rejected();
        }
    }

    /** Mirrors Iris's ProgramFallbackResolver and requires every capture domain to resolve. */
    static boolean allCaptureProgramsHaveFsrAffineProjection(
            Object programSet, Class<?> programId) {
        return analyzeCapturePrograms(programSet, programId).affine();
    }

    /** Resolves, proves, and analyzes every effective source used by Gallium capture domains. */
    static ProjectionAnalysis analyzeCapturePrograms(Object programSet, Class<?> programId) {
        return analyzePrograms(programSet, programId, CAPTURE_PROGRAMS);
    }

    /** Scale is capture-local; exact jitter additionally requires every scene-depth producer. */
    static ProjectionAnalysis analyzeProjectionPrograms(Object programSet, Class<?> programId) {
        return analyzeProjectionPrograms(programSet, programId, false);
    }

    static ProjectionAnalysis analyzeProjectionPrograms(
            Object programSet, Class<?> programId, boolean rainDepth) {
        ProjectionAnalysis capture = analyzeCapturePrograms(programSet, programId);
        if (!capture.affine()) return capture;
        String[] temporalPrograms = GLOBAL_TEMPORAL_PROGRAMS;
        if (rainDepth) {
            temporalPrograms = java.util.Arrays.copyOf(
                    GLOBAL_TEMPORAL_PROGRAMS, GLOBAL_TEMPORAL_PROGRAMS.length + 1);
            temporalPrograms[temporalPrograms.length - 1] = "Weather";
        }
        ProjectionAnalysis global = analyzePrograms(
                programSet, programId, temporalPrograms);
        return new ProjectionAnalysis(
                true, global.affine() ? global.temporalJitter() : noTemporalJitter());
    }

    private static ProjectionAnalysis analyzePrograms(
            Object programSet, Class<?> programId, String[] programNames) {
        if (programSet == null || programId == null || !programId.isEnum()) {
            return ProjectionAnalysis.rejected();
        }
        try {
            Method get = programSet.getClass().getMethod("get", programId);
            Method getFallback = programId.getMethod("getFallback");
            Set<Object> analyzedSources = Collections.newSetFromMap(new IdentityHashMap<>());
            List<String> vertexSources = new ArrayList<>();
            for (String name : programNames) {
                @SuppressWarnings({"unchecked", "rawtypes"})
                Object id = Enum.valueOf(
                        (Class<? extends Enum>) programId.asSubclass(Enum.class), name);
                Optional<?> source = resolveEffectiveProgramSource(
                        programSet, get, getFallback, id);
                if (source.isEmpty()) return ProjectionAnalysis.rejected();
                Object vertexOptional = source.get().getClass()
                        .getMethod("getVertexSource").invoke(source.get());
                if (!(vertexOptional instanceof Optional<?> vertex) || vertex.isEmpty()
                        || !isFsrAffineProjectionSource(vertex.get().toString())) {
                    return ProjectionAnalysis.rejected();
                }
                if (analyzedSources.add(source.get())) {
                    vertexSources.add(vertex.get().toString());
                }
            }
            return new ProjectionAnalysis(
                    true, FsrTemporalJitterAnalyzer.consensusSources(vertexSources));
        } catch (Throwable ignored) {
            return ProjectionAnalysis.rejected();
        }
    }

    record ProjectionAnalysis(
            boolean affine, FsrTemporalJitterAnalyzer.Analysis temporalJitter) {
        ProjectionAnalysis {
            temporalJitter = temporalJitter == null
                    ? new FsrTemporalJitterAnalyzer.Analysis(
                    FsrTemporalJitterAnalyzer.Kind.NONE, "", 0)
                    : temporalJitter;
        }

        private static ProjectionAnalysis rejected() {
            return new ProjectionAnalysis(false, noTemporalJitter());
        }
    }

    private static FsrTemporalJitterAnalyzer.Analysis noTemporalJitter() {
        return new FsrTemporalJitterAnalyzer.Analysis(
                FsrTemporalJitterAnalyzer.Kind.NONE, "", 0);
    }

    /** Resolves one ProgramId through its complete fallback chain, failing closed on cycles. */
    static Optional<?> resolveEffectiveProgramSource(
            Object programSet, Method get, Method getFallback, Object start)
            throws ReflectiveOperationException {
        if (programSet == null || get == null || getFallback == null || start == null) {
            return Optional.empty();
        }
        Set<Object> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        Object current = start;
        while (current != null && visited.add(current)) {
            Object sourceValue = get.invoke(programSet, current);
            if (!(sourceValue instanceof Optional<?> source)) return Optional.empty();
            if (source.isPresent()) return source;

            Object fallbackValue = getFallback.invoke(current);
            if (!(fallbackValue instanceof Optional<?> fallback)) return Optional.empty();
            current = fallback.orElse(null);
        }
        return Optional.empty();
    }

    static boolean isFsrAffineProjectionSource(String source) {
        if (source == null) return false;
        String code = source
                .replaceAll("(?s)/\\*.*?\\*/", "")
                .replaceAll("(?m)//.*$", "");
        String function = extractFsrScaleFunction(code);
        if (function == null) return false;
        // The signature and complete body are anchored. This deliberately rejects helpers,
        // conditionals, shadowing locals, and any fifth statement even when they do not write
        // position directly: only the four protocol statements establish the affine proof.
        return FSR_AFFINE_PROJECTION.matcher(function).matches()
                && FSR_AFFINE_CALL.matcher(code).find();
    }

    private static String extractFsrScaleFunction(String code) {
        java.util.regex.Matcher start = FSR_FUNCTION_START.matcher(code);
        if (!start.find()) return null;
        int openingBrace = start.end() - 1;
        int depth = 0;
        for (int cursor = openingBrace; cursor < code.length(); cursor++) {
            char value = code.charAt(cursor);
            if (value == '{') {
                depth++;
            } else if (value == '}' && --depth == 0) {
                return code.substring(start.start(), cursor + 1);
            }
        }
        return null;
    }

    private static Object currentProgramSet(Object irisPack) throws ReflectiveOperationException {
        ClassLoader loader = ReflectiveIrisPackAccess.class.getClassLoader();
        Class<?> iris = Class.forName("net.irisshaders.iris.Iris", false, loader);
        Object dimension = iris.getMethod("getCurrentDimension").invoke(null);
        if (dimension == null) return null;
        for (Method method : irisPack.getClass().getMethods()) {
            if (method.getName().equals("getProgramSet") && method.getParameterCount() == 1) {
                return method.invoke(irisPack, dimension);
            }
        }
        return null;
    }

    private static Optional<Boolean> rainDepthEnabled(Object programSet) {
        if (programSet == null) return Optional.empty();
        try {
            Object directives = programSet.getClass().getMethod("getPackDirectives")
                    .invoke(programSet);
            if (directives == null) return Optional.empty();
            Object value = directives.getClass().getMethod("rainDepth").invoke(directives);
            return value instanceof Boolean enabled
                    ? Optional.of(enabled) : Optional.empty();
        } catch (Throwable ignored) {
            return Optional.empty();
        }
    }

    static int colorTargetIndex(String source) {
        if (source == null) return -1;
        String value = source.toLowerCase(java.util.Locale.ROOT);
        for (String prefix : new String[]{"colortex", "alttex", "autotex"}) {
            if (!value.startsWith(prefix)) continue;
            try {
                int index = Integer.parseInt(value.substring(prefix.length()));
                return index >= 0 && index <= 31 ? index : -1;
            } catch (NumberFormatException ignored) {
                return -1;
            }
        }
        return -1;
    }

    private static Optional<int[]> integerVector2(Object value) {
        if (value == null) return Optional.empty();
        try {
            Number x = component(value, "x");
            Number y = component(value, "y");
            if (x == null || y == null) return Optional.empty();
            int width = x.intValue(), height = y.intValue();
            return width > 0 && height > 0
                    ? Optional.of(new int[]{width, height}) : Optional.empty();
        } catch (Throwable ignored) {
            return Optional.empty();
        }
    }

    private static Number component(Object value, String name) {
        try {
            Object component = value.getClass().getMethod(name).invoke(value);
            return component instanceof Number number ? number : null;
        } catch (Throwable ignored) {
            try {
                Object component = value.getClass().getField(name).get(value);
                return component instanceof Number number ? number : null;
            } catch (Throwable ignoredAgain) {
                return null;
            }
        }
    }
}
