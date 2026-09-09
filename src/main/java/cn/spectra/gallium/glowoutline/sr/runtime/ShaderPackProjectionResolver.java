package cn.spectra.gallium.glowoutline.sr.runtime;

import cn.spectra.gallium.glowoutline.sr.definition.SrDefinition.SourceKind;
import cn.spectra.gallium.glowoutline.sr.definition.SrDefinition.InputTexture;
import cn.spectra.gallium.glowoutline.sr.definition.SrDefinition.Profile;
import cn.spectra.gallium.glowoutline.sr.definition.SrDefinition.Region;
import cn.spectra.gallium.glowoutline.sr.definition.SrDefinition.RegionValueKind;
import java.util.List;
import java.util.Optional;

/**
 * Registry of runtime-declared shader-pack projection conventions that do not require a
 * {@code gallium.json}. Each adapter must validate a coherent set of live values before it may
 * return a transform; a single suggestive uniform name is never sufficient.
 */
public final class ShaderPackProjectionResolver {

    private final SrRuntimeAccess runtime;
    private final List<Adapter> adapters;
    private final TextureExtentAccess textureExtentAccess;
    private final java.util.function.Predicate<Object> affineProjectionProof;
    private final java.util.function.Function<Object, FsrTemporalJitterAnalyzer.Analysis>
            temporalJitterProof;

    public ShaderPackProjectionResolver(SrRuntimeAccess runtime) {
        this(runtime, List.of(ShaderPackProjectionResolver::resolveFsrViewport),
                new ReflectiveIrisPackAccess());
    }

    ShaderPackProjectionResolver(SrRuntimeAccess runtime, List<Adapter> adapters) {
        this(runtime, adapters, new ReflectiveIrisPackAccess());
    }

    ShaderPackProjectionResolver(
            SrRuntimeAccess runtime, List<Adapter> adapters,
            TextureExtentAccess textureExtentAccess) {
        this(runtime, adapters, textureExtentAccess, ignored -> true,
                ignored -> noTemporalJitterProof());
    }

    private ShaderPackProjectionResolver(
            SrRuntimeAccess runtime, List<Adapter> adapters,
            ReflectiveIrisPackAccess irisPackAccess) {
        this(runtime, adapters, irisPackAccess::textureExtent,
                irisPackAccess::hasFsrAffineProjection,
                irisPackAccess::fsrTemporalJitter);
    }

    ShaderPackProjectionResolver(
            SrRuntimeAccess runtime, List<Adapter> adapters,
            TextureExtentAccess textureExtentAccess,
            java.util.function.Predicate<Object> affineProjectionProof) {
        this(runtime, adapters, textureExtentAccess, affineProjectionProof,
                ignored -> noTemporalJitterProof());
    }

    ShaderPackProjectionResolver(
            SrRuntimeAccess runtime, List<Adapter> adapters,
            TextureExtentAccess textureExtentAccess,
            java.util.function.Predicate<Object> affineProjectionProof,
            java.util.function.Function<Object, FsrTemporalJitterAnalyzer.Analysis>
                    temporalJitterProof) {
        if (runtime == null) throw new IllegalArgumentException("runtime");
        if (textureExtentAccess == null) throw new IllegalArgumentException("textureExtentAccess");
        if (affineProjectionProof == null) throw new IllegalArgumentException("affineProjectionProof");
        if (temporalJitterProof == null) throw new IllegalArgumentException("temporalJitterProof");
        this.runtime = runtime;
        this.adapters = adapters == null ? List.of() : List.copyOf(adapters);
        this.textureExtentAccess = textureExtentAccess;
        this.affineProjectionProof = affineProjectionProof;
        this.temporalJitterProof = temporalJitterProof;
    }

    public Optional<ProjectionResolution> resolve(int screenWidth, int screenHeight) {
        if (screenWidth <= 0 || screenHeight <= 0) return Optional.empty();
        for (Adapter adapter : adapters) {
            Optional<ProjectionResolution> value = adapter.resolve(
                    runtime, screenWidth, screenHeight);
            if (value.isPresent()) return value;
        }
        return Optional.empty();
    }

    /** Resolves the semantic render-size input named by the selected universal SR profile. */
    public Optional<ProjectionResolution> resolve(
            Profile profile, Object irisPack, int screenWidth, int screenHeight) {
        if (profile == null || !profile.enabled() || !profile.upscale().enabled()
                || screenWidth <= 0 || screenHeight <= 0) {
            return Optional.empty();
        }
        Optional<ProjectionResolution> runtimeValue = resolve(screenWidth, screenHeight);
        if (runtimeValue.isEmpty()) return Optional.empty();
        if (!affineProjectionProof.test(irisPack)) return Optional.empty();
        for (String inputName : List.of("motion_vectors", "color", "depth")) {
            InputTexture input = profile.upscale().inputs().get(inputName);
            if (input == null || !input.enabled() || !isFullRenderRegion(input.region())) continue;
            Optional<int[]> extent = textureExtentAccess.textureExtent(
                    irisPack, input.sourceName(), screenWidth, screenHeight);
            if (extent.isEmpty()) continue;
            int[] value = extent.get();
            if (value[0] > screenWidth || value[1] > screenHeight) continue;
            if (value[0] == screenWidth && value[1] == screenHeight) continue;
            float scaleX = value[0] / (float) screenWidth;
            float scaleY = value[1] / (float) screenHeight;
            if (!Float.isFinite(scaleX) || !Float.isFinite(scaleY)
                    || scaleX <= 0.0f || scaleX > 1.0f
                    || scaleY <= 0.0f || scaleY > 1.0f) {
                continue;
            }

            // Both independent sources are required: the SR profile provides semantic meaning,
            // PackDirectives provides exact integer rounding, and the live uniforms prove that
            // this target size is also the geometry projection viewport rather than an effect
            // buffer that merely happens to be half-resolution.
            int runtimeWidth = Math.round(runtimeValue.get().scaleX() * screenWidth);
            int runtimeHeight = Math.round(runtimeValue.get().scaleY() * screenHeight);
            if (runtimeWidth != value[0] || runtimeHeight != value[1]) {
                continue;
            }
            TemporalJitter temporal = resolveTemporalJitter(irisPack);
            return Optional.of(new ProjectionResolution(
                    scaleX, scaleY, 0.0f, 0.0f,
                    temporal.x(), temporal.y(), temporal.exact(),
                    "sr-input:" + inputName + ":" + input.sourceName()));
        }
        return Optional.empty();
    }

    /** Reads only a call-site-proven shader-facing NDC offset; a name alone is never trusted. */
    private TemporalJitter resolveTemporalJitter(Object irisPack) {
        FsrTemporalJitterAnalyzer.Analysis analysis;
        try {
            analysis = temporalJitterProof.apply(irisPack);
        } catch (Throwable ignored) {
            return TemporalJitter.CONSERVATIVE;
        }
        if (analysis == null) return TemporalJitter.CONSERVATIVE;
        if (analysis.kind() == FsrTemporalJitterAnalyzer.Kind.ZERO) {
            return new TemporalJitter(0.0f, 0.0f, true);
        }
        if (analysis.kind() != FsrTemporalJitterAnalyzer.Kind.UNIFORM) {
            return TemporalJitter.CONSERVATIVE;
        }
        Optional<float[]> value = runtime.shaderValue(
                        SourceKind.UNIFORM, analysis.uniformName())
                .flatMap(ShaderPackProjectionResolver::finiteVector2);
        if (value.isEmpty()) return TemporalJitter.CONSERVATIVE;
        return new TemporalJitter(value.get()[0], value.get()[1], true);
    }

    private static FsrTemporalJitterAnalyzer.Analysis noTemporalJitterProof() {
        return new FsrTemporalJitterAnalyzer.Analysis(
                FsrTemporalJitterAnalyzer.Kind.NONE, "", 0);
    }

    private static boolean isFullRenderRegion(Region region) {
        return region != null
                && region.x().kind() == RegionValueKind.LITERAL && region.x().literal() == 0
                && region.y().kind() == RegionValueKind.LITERAL && region.y().literal() == 0
                && region.width().kind() == RegionValueKind.RENDER_SIZE
                && region.height().kind() == RegionValueKind.RENDER_SIZE;
    }

    /**
     * iterationRP-style FSR paths expose both the integer viewport and its normalized scale.
     * Requiring both values to agree within one output pixel prevents unrelated half-resolution
     * effect buffers from being mistaken for the geometry projection viewport.
     */
    private static Optional<ProjectionResolution> resolveFsrViewport(
            SrRuntimeAccess runtime, int screenWidth, int screenHeight) {
        Optional<SrRuntimeAccess.NumericValue> scaleValue = runtime.shaderValue(
                SourceKind.UNIFORM, "fsrRenderScale");
        Optional<SrRuntimeAccess.NumericValue> extentValue = runtime.shaderValue(
                SourceKind.UNIFORM, "fsrScreenSize");
        if (scaleValue.isEmpty() || extentValue.isEmpty()) return Optional.empty();

        Optional<float[]> declaredScale = finiteVector2(scaleValue.get());
        Optional<int[]> renderExtent = positiveIntegerVector2(extentValue.get());
        if (declaredScale.isEmpty() || renderExtent.isEmpty()) return Optional.empty();

        float[] scale = declaredScale.get();
        int[] extent = renderExtent.get();
        if (extent[0] > screenWidth || extent[1] > screenHeight
                || (extent[0] == screenWidth && extent[1] == screenHeight)
                || scale[0] <= 0.0f || scale[0] > 1.0f
                || scale[1] <= 0.0f || scale[1] > 1.0f) {
            return Optional.empty();
        }
        float exactX = extent[0] / (float) screenWidth;
        float exactY = extent[1] / (float) screenHeight;
        if (Math.round(scale[0] * screenWidth) != extent[0]
                || Math.round(scale[1] * screenHeight) != extent[1]) {
            return Optional.empty();
        }
        return Optional.of(new ProjectionResolution(
                exactX, exactY, 0.0f, 0.0f,
                0.0f, 0.0f, false,
                "iris:fsrRenderScale+fsrScreenSize"));
    }

    private static Optional<float[]> finiteVector2(SrRuntimeAccess.NumericValue value) {
        if (value == null || value.size() != 2) return Optional.empty();
        double x = value.component(0), y = value.component(1);
        if (!Double.isFinite(x) || !Double.isFinite(y)
                || x < -Float.MAX_VALUE || x > Float.MAX_VALUE
                || y < -Float.MAX_VALUE || y > Float.MAX_VALUE) {
            return Optional.empty();
        }
        return Optional.of(new float[]{(float) x, (float) y});
    }

    private static Optional<int[]> positiveIntegerVector2(SrRuntimeAccess.NumericValue value) {
        if (value == null || value.size() != 2) return Optional.empty();
        double x = value.component(0), y = value.component(1);
        if (!Double.isFinite(x) || !Double.isFinite(y)
                || x <= 0.0 || y <= 0.0
                || x > Integer.MAX_VALUE || y > Integer.MAX_VALUE
                || Math.abs(x - Math.rint(x)) > 1.0e-3
                || Math.abs(y - Math.rint(y)) > 1.0e-3) {
            return Optional.empty();
        }
        return Optional.of(new int[]{(int) Math.rint(x), (int) Math.rint(y)});
    }

    @FunctionalInterface
    interface Adapter {
        Optional<ProjectionResolution> resolve(
                SrRuntimeAccess runtime, int screenWidth, int screenHeight);
    }

    @FunctionalInterface
    interface TextureExtentAccess {
        Optional<int[]> textureExtent(
                Object irisPack, String source, int screenWidth, int screenHeight);
    }

    private record TemporalJitter(float x, float y, boolean exact) {
        private static final TemporalJitter CONSERVATIVE =
                new TemporalJitter(0.0f, 0.0f, false);
    }

    public record ProjectionResolution(
            float scaleX, float scaleY,
            float originX, float originY,
            float jitterX, float jitterY,
            boolean exactTemporalJitter,
            String source) {
        public ProjectionResolution {
            source = source == null ? "" : source;
        }
    }
}
