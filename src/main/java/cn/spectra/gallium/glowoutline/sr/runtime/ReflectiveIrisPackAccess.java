package cn.spectra.gallium.glowoutline.sr.runtime;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Optional;
import java.util.Set;

/** Reads Iris's already-preprocessed per-dimension texture-size directives without linking Iris. */
final class ReflectiveIrisPackAccess {

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

    private Object cachedProgramSet;
    private Optional<InlineViewportProjectionAnalyzer.Analysis> cachedProjection = Optional.empty();

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

    Optional<InlineViewportProjectionAnalyzer.Analysis> viewportProjection(Object irisPack) {
        if (irisPack == null) return Optional.empty();
        try {
            Object programs = currentProgramSet(irisPack);
            if (programs == null) return Optional.empty();
            if (programs == cachedProgramSet) return cachedProjection;
            Class<?> programId = Class.forName("net.irisshaders.iris.shaderpack.loading.ProgramId",
                    false, ReflectiveIrisPackAccess.class.getClassLoader());
            Optional<InlineViewportProjectionAnalyzer.Analysis> proof = analyzeProjectionPrograms(
                    programs, programId, rainDepthEnabled(programs).orElse(true));
            cachedProgramSet = programs;
            cachedProjection = proof;
            return proof;
        } catch (Throwable ignored) {
            return Optional.empty();
        }
    }

    static Optional<InlineViewportProjectionAnalyzer.Analysis> analyzeProjectionPrograms(
            Object programs, Class<?> programId, boolean rainDepth) {
        Optional<InlineViewportProjectionAnalyzer.Analysis> capture =
                analyzePrograms(programs, programId, CAPTURE_PROGRAMS);
        if (capture.isEmpty()) return Optional.empty();
        String[] globalPrograms = GLOBAL_TEMPORAL_PROGRAMS;
        if (rainDepth) {
            globalPrograms = java.util.Arrays.copyOf(globalPrograms, globalPrograms.length + 1);
            globalPrograms[globalPrograms.length - 1] = "Weather";
        }
        Optional<InlineViewportProjectionAnalyzer.Analysis> global =
                analyzePrograms(programs, programId, globalPrograms);
        var result = capture.get();
        return Optional.of(global.isPresent() && result.sameViewport(global.get())
                && result.sameTemporalTransform(global.get()) ? result : result.withoutTemporalJitter());
    }

    private static Optional<InlineViewportProjectionAnalyzer.Analysis> analyzePrograms(
            Object programs, Class<?> programId, String[] names) {
        if (programs == null || programId == null || !programId.isEnum()) return Optional.empty();
        try {
            Method get = programs.getClass().getMethod("get", programId);
            Method getFallback = programId.getMethod("getFallback");
            Set<Object> visited = Collections.newSetFromMap(new IdentityHashMap<>());
            InlineViewportProjectionAnalyzer.Analysis agreed = null;
            for (String name : names) {
                @SuppressWarnings({"unchecked", "rawtypes"})
                Object id = Enum.valueOf((Class<? extends Enum>) programId.asSubclass(Enum.class), name);
                Optional<?> resolved = resolveEffectiveProgramSource(programs, get, getFallback, id);
                if (resolved.isEmpty()) return Optional.empty();
                Object source = resolved.get();
                if (!visited.add(source)) continue;
                // A later programmable stage could replace the vertex projection entirely.
                for (String stage : new String[]{"getGeometrySource", "getTessControlSource", "getTessEvalSource"}) {
                    Object value = source.getClass().getMethod(stage).invoke(source);
                    if (!(value instanceof Optional<?> optional) || optional.isPresent()) return Optional.empty();
                }
                Object vertex = source.getClass().getMethod("getVertexSource").invoke(source);
                Object fragment = source.getClass().getMethod("getFragmentSource").invoke(source);
                if (!(vertex instanceof Optional<?> vs) || vs.isEmpty()
                        || !(fragment instanceof Optional<?> fs) || fs.isEmpty()) return Optional.empty();
                Optional<InlineViewportProjectionAnalyzer.Analysis> analysis =
                        InlineViewportProjectionAnalyzer.analyze(vs.get().toString(), fs.get().toString());
                if (analysis.isEmpty()) return Optional.empty();
                if (agreed == null) agreed = analysis.get();
                else if (!agreed.sameViewport(analysis.get())) return Optional.empty();
                else if (!agreed.sameTemporalTransform(analysis.get())) agreed = agreed.withoutTemporalJitter();
            }
            return Optional.ofNullable(agreed);
        } catch (Throwable ignored) {
            return Optional.empty();
        }
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
