package cn.spectra.gallium.glowoutline.sr.runtime;

import cn.spectra.gallium.glowoutline.sr.definition.SrDefinition.SourceKind;
import cn.spectra.gallium.glowoutline.sr.definition.SrDefinition.InputTexture;
import cn.spectra.gallium.glowoutline.sr.definition.SrDefinition.Profile;
import cn.spectra.gallium.glowoutline.sr.definition.SrDefinition.Region;
import cn.spectra.gallium.glowoutline.sr.definition.SrDefinition.RegionValueKind;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

/** Resolves source-proven native viewport semantics against live Iris values and SR inputs. */
public final class ShaderPackProjectionResolver {
    private final SrRuntimeAccess runtime;
    private final TextureExtentAccess textureExtentAccess;
    private final Function<Object, Optional<InlineViewportProjectionAnalyzer.Analysis>> viewportProof;

    public ShaderPackProjectionResolver(SrRuntimeAccess runtime) {
        this(runtime, new ReflectiveIrisPackAccess());
    }

    private ShaderPackProjectionResolver(SrRuntimeAccess runtime, ReflectiveIrisPackAccess iris) {
        this(runtime, iris::textureExtent, iris::viewportProjection);
    }

    ShaderPackProjectionResolver(SrRuntimeAccess runtime, TextureExtentAccess textureExtentAccess,
            Function<Object, Optional<InlineViewportProjectionAnalyzer.Analysis>> viewportProof) {
        if (runtime == null) throw new IllegalArgumentException("runtime");
        if (textureExtentAccess == null) throw new IllegalArgumentException("textureExtentAccess");
        if (viewportProof == null) throw new IllegalArgumentException("viewportProof");
        this.runtime = runtime;
        this.textureExtentAccess = textureExtentAccess;
        this.viewportProof = viewportProof;
    }

    /** External SR owns its live projection; this resolver supplies only the native pack path. */
    public Optional<ProjectionResolution> resolve(
            Profile profile, Object irisPack, int screenWidth, int screenHeight) {
        if (profile == null || !profile.enabled() || !profile.upscale().enabled()
                || screenWidth <= 0 || screenHeight <= 0
                || runtime.upscaleActive().orElse(false)) return Optional.empty();
        Optional<InlineViewportProjectionAnalyzer.Analysis> proof = viewportProof.apply(irisPack);
        if (proof.isEmpty()) return Optional.empty();
        var declaration = proof.get();
        Optional<float[]> scale = runtime.shaderValue(SourceKind.UNIFORM, declaration.scaleUniform())
                .flatMap(ShaderPackProjectionResolver::finiteVector2);
        Optional<int[]> extent = runtime.shaderValue(SourceKind.UNIFORM, declaration.extentUniform())
                .flatMap(ShaderPackProjectionResolver::positiveIntegerVector2);
        if (scale.isEmpty() || extent.isEmpty()) return Optional.empty();
        float[] declaredScale = scale.get();
        int width = extent.get()[0], height = extent.get()[1];
        if (width > screenWidth || height > screenHeight
                || declaredScale[0] <= 0 || declaredScale[0] > 1
                || declaredScale[1] <= 0 || declaredScale[1] > 1
                || Math.round(declaredScale[0] * screenWidth) != width
                || Math.round(declaredScale[1] * screenHeight) != height) return Optional.empty();
        boolean hasMatchingInput = false;
        for (String inputName : List.of("motion_vectors", "color", "depth")) {
            InputTexture input = profile.upscale().inputs().get(inputName);
            if (input == null || !input.enabled() || !isFullRenderRegion(input.region())) continue;
            Optional<int[]> physical = textureExtentAccess.textureExtent(
                    irisPack, input.sourceName(), screenWidth, screenHeight);
            if (physical.isEmpty()) continue;
            int[] size = physical.get();
            if ((size[0] == screenWidth && size[1] == screenHeight)
                    || (size[0] == width && size[1] == height)) {
                hasMatchingInput = true;
                break;
            }
        }
        if (!hasMatchingInput) return Optional.empty();
        float scaleX = width / (float) screenWidth, scaleY = height / (float) screenHeight;
        float jitterX = 0, jitterY = 0;
        boolean exactJitter = declaration.exactJitter();
        if (exactJitter && !declaration.jitterUniform().isEmpty()) {
            Optional<float[]> jitter = runtime.shaderValue(SourceKind.UNIFORM, declaration.jitterUniform())
                    .flatMap(ShaderPackProjectionResolver::finiteVector2);
            if (jitter.isEmpty()) exactJitter = false;
            else {
                jitterX = jitter.get()[0] * (declaration.jitterBeforeScale() ? declaredScale[0] : 1.0f);
                jitterY = jitter.get()[1] * (declaration.jitterBeforeScale() ? declaredScale[1] : 1.0f);
            }
        }
        return Optional.of(new ProjectionResolution(scaleX, scaleY, 0, 0,
                jitterX, jitterY, exactJitter,
                "iris:viewport:" + declaration.scaleUniform() + "+" + declaration.extentUniform()));
    }


    private static boolean isFullRenderRegion(Region region) {
        return region != null
                && region.x().kind() == RegionValueKind.LITERAL && region.x().literal() == 0
                && region.y().kind() == RegionValueKind.LITERAL && region.y().literal() == 0
                && region.width().kind() == RegionValueKind.RENDER_SIZE
                && region.height().kind() == RegionValueKind.RENDER_SIZE;
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
    interface TextureExtentAccess {
        Optional<int[]> textureExtent(
                Object irisPack, String source, int screenWidth, int screenHeight);
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
