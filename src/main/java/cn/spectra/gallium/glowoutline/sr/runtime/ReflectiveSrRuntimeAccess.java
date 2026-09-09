package cn.spectra.gallium.glowoutline.sr.runtime;

import cn.spectra.gallium.glowoutline.sr.definition.SrDefinition.SourceKind;
import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * Cached, fail-closed reflection bridge for supported Super Resolution generations.
 *
 * <p>SR 0.8.2 and the schema-based releases share the public size API and the no-argument
 * {@code AlgorithmManager.getJitterOffset()} entry point. Schema releases additionally expose an
 * Iris pipeline context that can evaluate the uniform/variable references stored in their JSON
 * definition. Every optional capability is discovered independently so one renamed internal does
 * not disable the stable size API.</p>
 */
public final class ReflectiveSrRuntimeAccess implements SrRuntimeAccess {

    private static final String SR_API = "io.homo.superresolution.api.SuperResolutionAPI";
    private static final String ALGORITHM_MANAGER =
            "io.homo.superresolution.common.upscale.AlgorithmManager";
    private static final String SR_CONFIG =
            "io.homo.superresolution.common.config.SuperResolutionConfig";
    private static final String RENDER_HANDLER_MANAGER =
            "io.homo.superresolution.common.minecraft.handler.RenderHandlerManager";
    private static final String SHADER_COMPAT_HANDLER =
            "io.homo.superresolution.common.minecraft.handler.shadercompat.ShaderCompatHandler";
    private static final String IRIS_API = "io.homo.irisapi.IrisAPI";
    private static final String IRIS_PIPELINE_CONTEXT =
            "io.homo.superresolution.shadercompat.IrisShaderPipelineContext";

    private static final ReflectiveSrRuntimeAccess INSTANCE =
            new ReflectiveSrRuntimeAccess(ReflectiveSrRuntimeAccess.class.getClassLoader());

    private final ClassLoader classLoader;

    private boolean sizeLookupAttempted;
    private Method getRenderWidth;
    private Method getRenderHeight;
    private Method getScreenWidth;
    private Method getScreenHeight;

    private boolean activationLookupAttempted;
    private Method isEnableUpscale;

    private boolean jitterLookupAttempted;
    private Method getJitterOffset;
    private Method getJitterSequenceLength;
    private Method getCurrentAlgorithmDescription;
    private Class<?> cachedDescriptionClass;
    private Method getAlgorithmCodeName;
    private Field algorithmCodeNameField;

    private boolean shaderLookupAttempted;
    private Method getIrisRenderingPipeline;
    private Constructor<?> pipelineContextConstructor;
    private Method getCustomUniformValue;
    private Method getCustomVariableValue;
    private Object cachedPipeline;
    private Object cachedPipelineContext;

    // Direct Iris fallback. This keeps shader-pack-owned runtime values available even when the
    // SR mod (and its IrisShaderPipelineContext helper) is not installed.
    private Method getIrisPipelineManager;
    private Method getPipelineNullable;
    private Method getPipelineCustomUniforms;
    private Field customUniformVariablesField;
    private Field customUniformOrderField;
    private Constructor<?> functionReturnConstructor;
    private Method cachedUniformGetName;
    private Method cachedUniformWriteTo;
    private Method cachedUniformGetType;
    private Field functionReturnIntField;
    private Field functionReturnFloatField;
    private Field functionReturnObjectField;
    private Object irisIntType;
    private Object irisFloatType;

    public static ReflectiveSrRuntimeAccess getInstance() {
        return INSTANCE;
    }

    /** Public for isolated class-loader tests and alternate launch environments. */
    public ReflectiveSrRuntimeAccess(ClassLoader classLoader) {
        this.classLoader = classLoader == null
                ? ReflectiveSrRuntimeAccess.class.getClassLoader() : classLoader;
    }

    @Override
    public Optional<Boolean> upscaleActive() {
        ensureActivationLookup();
        if (isEnableUpscale != null) {
            try {
                Object value = isEnableUpscale.invoke(null);
                if (value instanceof Boolean active) return Optional.of(active);
            } catch (Throwable ignored) {
                // Fall through to the dimension-based capability fallback below.
            }
        }
        return extents()
                .filter(value -> value.renderWidth() > 0 && value.renderHeight() > 0
                        && value.screenWidth() > 0 && value.screenHeight() > 0)
                .map(value -> value.renderWidth() < value.screenWidth()
                        || value.renderHeight() < value.screenHeight());
    }

    @Override
    public Optional<Extents> extents() {
        ensureSizeLookup();
        if (getRenderWidth != null && getRenderHeight != null
                && getScreenWidth != null && getScreenHeight != null) {
            try {
                Extents api = new Extents(
                        invokeInt(getRenderWidth),
                        invokeInt(getRenderHeight),
                        invokeInt(getScreenWidth),
                        invokeInt(getScreenHeight));
                if (validExtents(api)) return Optional.of(api);
            } catch (Throwable ignored) {
                // Fall through to the manager/API capability fallback below.
            }
        }
        return Optional.empty();
    }

    @Override
    public Optional<NumericValue> modJitter() {
        ensureJitterLookup();
        if (getJitterOffset == null) return Optional.empty();
        try {
            return numericValue(getJitterOffset.invoke(null));
        } catch (Throwable ignored) {
            return Optional.empty();
        }
    }

    @Override
    public Optional<NumericValue> shaderFacingModJitter() {
        ensureJitterLookup();
        if (getJitterOffset == null || getCurrentAlgorithmDescription == null) {
            return Optional.empty();
        }
        try {
            Object raw = getJitterOffset.invoke(null);
            Class<?> handler = Class.forName(SHADER_COMPAT_HANDLER, false, classLoader);
            Object config = handler.getMethod("getShaderCompatData").invoke(null);
            if (config == null) return Optional.empty();
            Object processor = config.getClass().getMethod("getProcessor").invoke(config);
            if (processor == null) return Optional.empty();

            Class<?> api = Class.forName(SR_API, false, classLoader);
            Object algorithm = api.getMethod("getCurrentAlgorithm").invoke(null);
            Object description = getCurrentAlgorithmDescription.invoke(null);
            if (algorithm == null || description == null) return Optional.empty();

            for (Method method : processor.getClass().getMethods()) {
                if (method.getName().equals("adaptJitterForShaderpack")
                        && method.getParameterCount() == 4) {
                    return numericValue(method.invoke(
                            processor, raw, algorithm, config, description));
                }
            }
        } catch (Throwable ignored) {
            // SrProjectionResolver retains a known-algorithm, schema-specific fallback.
        }
        return Optional.empty();
    }

    @Override
    public OptionalInt modJitterSequenceLength() {
        ensureJitterLookup();
        if (getJitterSequenceLength == null) return OptionalInt.empty();
        try {
            return OptionalInt.of(invokeInt(getJitterSequenceLength));
        } catch (Throwable ignored) {
            return OptionalInt.empty();
        }
    }

    @Override
    public Optional<String> algorithmCode() {
        ensureJitterLookup();
        String code = currentAlgorithmCode();
        return code.isBlank() ? Optional.empty() : Optional.of(code);
    }

    @Override
    public Optional<NumericValue> shaderValue(SourceKind kind, String reference) {
        if ((kind != SourceKind.UNIFORM && kind != SourceKind.VARIABLE)
                || reference == null || reference.isBlank()) {
            return Optional.empty();
        }
        ensureShaderLookup();
        Method resolver = kind == SourceKind.UNIFORM
                ? getCustomUniformValue : getCustomVariableValue;
        if (getIrisRenderingPipeline != null && pipelineContextConstructor != null
                && resolver != null) {
            try {
                Object pipeline = getIrisRenderingPipeline.invoke(null);
                if (pipeline != null) {
                    Object context = contextFor(pipeline);
                    if (context != null) {
                        Optional<NumericValue> value =
                                numericValue(resolver.invoke(context, reference));
                        if (value.isPresent()) return value;
                    }
                }
            } catch (Throwable ignored) {
                // Fall through to the direct Iris implementation below.
            }
        }
        return directIrisShaderValue(kind, reference);
    }

    private synchronized void ensureSizeLookup() {
        if (sizeLookupAttempted) return;
        sizeLookupAttempted = true;
        try {
            Class<?> api = Class.forName(SR_API, false, classLoader);
            getRenderWidth = api.getMethod("getRenderWidth");
            getRenderHeight = api.getMethod("getRenderHeight");
            getScreenWidth = api.getMethod("getScreenWidth");
            getScreenHeight = api.getMethod("getScreenHeight");
        } catch (Throwable ignored) {
            try {
                Class<?> manager = Class.forName(RENDER_HANDLER_MANAGER, false, classLoader);
                getRenderWidth = manager.getMethod("getRenderWidth");
                getRenderHeight = manager.getMethod("getRenderHeight");
                getScreenWidth = manager.getMethod("getScreenWidth");
                getScreenHeight = manager.getMethod("getScreenHeight");
            } catch (Throwable ignoredAgain) {
                getRenderWidth = null;
                getRenderHeight = null;
                getScreenWidth = null;
                getScreenHeight = null;
            }
        }
    }

    private synchronized void ensureActivationLookup() {
        if (activationLookupAttempted) return;
        activationLookupAttempted = true;
        try {
            Class<?> config = Class.forName(SR_CONFIG, false, classLoader);
            isEnableUpscale = config.getMethod("isEnableUpscale");
        } catch (Throwable ignored) {
            isEnableUpscale = null;
        }
    }

    private synchronized void ensureJitterLookup() {
        if (jitterLookupAttempted) return;
        jitterLookupAttempted = true;
        try {
            Class<?> manager = Class.forName(ALGORITHM_MANAGER, false, classLoader);
            getJitterOffset = manager.getMethod("getJitterOffset");
            // Added by the schema-based implementation. Legacy 0.8.2 still resolves the
            // current offset, but intentionally reports an unknown sequence length.
            getJitterSequenceLength = findNoArgMethod(manager, "getJitterSequenceLength");
            if (getJitterSequenceLength == null) {
                getJitterSequenceLength = findNoArgMethod(
                        manager, "getConfiguredJitterSequenceLength");
            }
            try {
                Class<?> api = Class.forName(SR_API, false, classLoader);
                getCurrentAlgorithmDescription = api.getMethod("getCurrentAlgorithmDescription");
            } catch (Throwable ignored) {
                getCurrentAlgorithmDescription = null;
            }
        } catch (Throwable ignored) {
            getJitterOffset = null;
            getJitterSequenceLength = null;
            getCurrentAlgorithmDescription = null;
        }
    }

    private String currentAlgorithmCode() {
        if (getCurrentAlgorithmDescription == null) return "";
        try {
            Object description = getCurrentAlgorithmDescription.invoke(null);
            if (description == null) return "";
            if (description.getClass() != cachedDescriptionClass) {
                cachedDescriptionClass = description.getClass();
                getAlgorithmCodeName = findNoArgMethod(cachedDescriptionClass, "getCodeName");
                algorithmCodeNameField = null;
                if (getAlgorithmCodeName == null) {
                    try {
                        algorithmCodeNameField = cachedDescriptionClass.getField("codeName");
                    } catch (ReflectiveOperationException ignored) {
                        algorithmCodeNameField = null;
                    }
                }
            }
            Object code = getAlgorithmCodeName != null
                    ? getAlgorithmCodeName.invoke(description)
                    : algorithmCodeNameField != null ? algorithmCodeNameField.get(description) : null;
            return code == null ? "" : code.toString().toLowerCase(java.util.Locale.ROOT);
        } catch (Throwable ignored) {
            return "";
        }
    }

    private synchronized void ensureShaderLookup() {
        if (shaderLookupAttempted) return;
        shaderLookupAttempted = true;
        try {
            Class<?> irisApi = Class.forName(IRIS_API, false, classLoader);
            Class<?> context = Class.forName(IRIS_PIPELINE_CONTEXT, false, classLoader);
            getIrisRenderingPipeline = irisApi.getMethod("getIrisRenderingPipeline");
            for (Constructor<?> constructor : context.getConstructors()) {
                if (constructor.getParameterCount() == 1) {
                    pipelineContextConstructor = constructor;
                    break;
                }
            }
            if (pipelineContextConstructor != null) {
                getCustomUniformValue = context.getMethod("getCustomUniformValue", String.class);
                getCustomVariableValue = context.getMethod("getCustomVariableValue", String.class);
            }
        } catch (Throwable ignored) {
            getIrisRenderingPipeline = null;
            pipelineContextConstructor = null;
            getCustomUniformValue = null;
            getCustomVariableValue = null;
        }
        ensureDirectIrisShaderLookup();
    }

    private void ensureDirectIrisShaderLookup() {
        try {
            Class<?> iris = Class.forName("net.irisshaders.iris.Iris", false, classLoader);
            Class<?> manager = Class.forName(
                    "net.irisshaders.iris.pipeline.PipelineManager", false, classLoader);
            Class<?> pipeline = Class.forName(
                    "net.irisshaders.iris.pipeline.IrisRenderingPipeline", false, classLoader);
            Class<?> customUniforms = Class.forName(
                    "net.irisshaders.iris.uniforms.custom.CustomUniforms", false, classLoader);
            Class<?> cachedUniform = Class.forName(
                    "net.irisshaders.iris.uniforms.custom.cached.CachedUniform", false, classLoader);
            Class<?> functionReturn = Class.forName(
                    "kroppeb.stareval.function.FunctionReturn", false, classLoader);
            Class<?> type = Class.forName("kroppeb.stareval.function.Type", false, classLoader);

            getIrisPipelineManager = iris.getMethod("getPipelineManager");
            getPipelineNullable = manager.getMethod("getPipelineNullable");
            getPipelineCustomUniforms = pipeline.getMethod("getCustomUniforms");
            customUniformVariablesField = customUniforms.getDeclaredField("variables");
            customUniformOrderField = customUniforms.getDeclaredField("uniformOrder");
            customUniformVariablesField.setAccessible(true);
            customUniformOrderField.setAccessible(true);
            functionReturnConstructor = functionReturn.getConstructor();
            cachedUniformGetName = cachedUniform.getMethod("getName");
            cachedUniformWriteTo = cachedUniform.getMethod("writeTo", functionReturn);
            cachedUniformGetType = cachedUniform.getMethod("getType");
            functionReturnIntField = functionReturn.getField("intReturn");
            functionReturnFloatField = functionReturn.getField("floatReturn");
            functionReturnObjectField = functionReturn.getField("objectReturn");
            irisIntType = type.getField("Int").get(null);
            irisFloatType = type.getField("Float").get(null);
        } catch (Throwable ignored) {
            getIrisPipelineManager = null;
            getPipelineNullable = null;
            getPipelineCustomUniforms = null;
            customUniformVariablesField = null;
            customUniformOrderField = null;
            functionReturnConstructor = null;
            cachedUniformGetName = null;
            cachedUniformWriteTo = null;
            cachedUniformGetType = null;
            functionReturnIntField = null;
            functionReturnFloatField = null;
            functionReturnObjectField = null;
            irisIntType = null;
            irisFloatType = null;
        }
    }

    private Optional<NumericValue> directIrisShaderValue(SourceKind kind, String reference) {
        if (getIrisPipelineManager == null || getPipelineNullable == null
                || getPipelineCustomUniforms == null) {
            return Optional.empty();
        }
        try {
            Object manager = getIrisPipelineManager.invoke(null);
            if (manager == null) return Optional.empty();
            Object pipeline = getPipelineNullable.invoke(manager);
            if (pipeline == null) return Optional.empty();
            Object customUniforms = getPipelineCustomUniforms.invoke(pipeline);
            if (customUniforms == null) return Optional.empty();

            Iterable<?> candidates;
            if (kind == SourceKind.UNIFORM) {
                Object values = customUniformOrderField.get(customUniforms);
                if (!(values instanceof Iterable<?> iterable)) return Optional.empty();
                candidates = iterable;
            } else {
                Object values = customUniformVariablesField.get(customUniforms);
                if (!(values instanceof java.util.Map<?, ?> map)) return Optional.empty();
                candidates = map.values();
            }

            for (Object uniform : candidates) {
                if (uniform == null
                        || !reference.equals(String.valueOf(cachedUniformGetName.invoke(uniform)))) {
                    continue;
                }
                Object result = functionReturnConstructor.newInstance();
                cachedUniformWriteTo.invoke(uniform, result);
                Object uniformType = cachedUniformGetType.invoke(uniform);
                if (uniformType == irisIntType) {
                    return Optional.of(NumericValue.scalar(
                            ((Number) functionReturnIntField.get(result)).doubleValue()));
                }
                if (uniformType == irisFloatType) {
                    return Optional.of(NumericValue.scalar(
                            ((Number) functionReturnFloatField.get(result)).doubleValue()));
                }
                return numericValue(functionReturnObjectField.get(result));
            }
        } catch (Throwable ignored) {
            return Optional.empty();
        }
        return Optional.empty();
    }

    private synchronized Object contextFor(Object pipeline) {
        if (pipeline == cachedPipeline && cachedPipelineContext != null) {
            return cachedPipelineContext;
        }
        try {
            Object context = pipelineContextConstructor.newInstance(pipeline);
            cachedPipeline = pipeline;
            cachedPipelineContext = context;
            return context;
        } catch (Throwable ignored) {
            cachedPipeline = null;
            cachedPipelineContext = null;
            return null;
        }
    }

    private static boolean validExtents(Extents value) {
        return value != null
                && value.renderWidth() > 0 && value.renderHeight() > 0
                && value.screenWidth() > 0 && value.screenHeight() > 0;
    }

    private static Method findNoArgMethod(Class<?> owner, String name) {
        try {
            return owner.getMethod(name);
        } catch (ReflectiveOperationException ignored) {
            return null;
        }
    }

    private static int invokeInt(Method method) throws ReflectiveOperationException {
        Object value = method.invoke(null);
        if (!(value instanceof Number number)) throw new IllegalStateException("not numeric");
        return number.intValue();
    }

    static Optional<NumericValue> numericValue(Object value) {
        if (value instanceof Number number) {
            return Optional.of(NumericValue.scalar(number.doubleValue()));
        }
        if (value == null) return Optional.empty();

        if (value instanceof List<?> list) {
            List<Double> components = numbers(list);
            return components.isEmpty() ? Optional.empty()
                    : Optional.of(new NumericValue(components));
        }
        Class<?> type = value.getClass();
        if (type.isArray()) {
            List<Double> components = new ArrayList<>();
            int length = Array.getLength(value);
            for (int i = 0; i < length; i++) {
                Object component = Array.get(value, i);
                if (!(component instanceof Number number)) return Optional.empty();
                components.add(number.doubleValue());
            }
            return components.isEmpty() ? Optional.empty()
                    : Optional.of(new NumericValue(components));
        }

        List<Double> components = new ArrayList<>(4);
        for (String component : List.of("x", "y", "z", "w")) {
            Number number = component(value, component);
            if (number == null) break;
            components.add(number.doubleValue());
        }
        return components.isEmpty() ? Optional.empty()
                : Optional.of(new NumericValue(components));
    }

    private static List<Double> numbers(List<?> values) {
        List<Double> result = new ArrayList<>(values.size());
        for (Object value : values) {
            if (!(value instanceof Number number)) return List.of();
            result.add(number.doubleValue());
        }
        return result;
    }

    private static Number component(Object value, String name) {
        try {
            Method getter = value.getClass().getMethod(name);
            Object component = getter.invoke(value);
            return component instanceof Number number ? number : null;
        } catch (Throwable ignored) {
            try {
                Field field = value.getClass().getField(name);
                Object component = field.get(value);
                return component instanceof Number number ? number : null;
            } catch (Throwable ignoredAgain) {
                return null;
            }
        }
    }
}
