package cn.spectra.gallium.glowoutline.sr.runtime;

import cn.spectra.gallium.glowoutline.sr.definition.SrDefinition.Jitter;
import cn.spectra.gallium.glowoutline.sr.definition.SrDefinition.JitterOwner;
import cn.spectra.gallium.glowoutline.sr.definition.SrDefinition.Format;
import cn.spectra.gallium.glowoutline.sr.definition.SrDefinition.InputTexture;
import cn.spectra.gallium.glowoutline.sr.definition.SrDefinition.Profile;
import cn.spectra.gallium.glowoutline.sr.definition.SrDefinition.Region;
import cn.spectra.gallium.glowoutline.sr.definition.SrDefinition.RegionValue;
import cn.spectra.gallium.glowoutline.sr.definition.SrDefinition.RegionValueKind;
import cn.spectra.gallium.glowoutline.sr.definition.SrDefinition.SourceKind;
import cn.spectra.gallium.glowoutline.sr.definition.SrDefinition.ValueSource;
import cn.spectra.gallium.glowoutline.sr.definition.SrDefinition.ValueType;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;

/** Resolves definition metadata against live SR/Iris values without choosing an NDC convention. */
public final class SrProjectionResolver {

    private final SrRuntimeAccess runtime;

    public SrProjectionResolver(SrRuntimeAccess runtime) {
        if (runtime == null) throw new IllegalArgumentException("runtime");
        this.runtime = runtime;
    }

    public ProjectionResolution resolve(Profile profile) {
        return resolve(profile, Format.V3);
    }

    public ProjectionResolution resolve(Profile profile, Format format) {
        Optional<SrRuntimeAccess.Extents> runtimeExtents = runtime.extents();
        SrRuntimeAccess.Extents extents = runtimeExtents.orElse(
                new SrRuntimeAccess.Extents(0, 0, 0, 0));
        boolean definitionActive = profile != null
                && profile.enabled() && profile.upscale().enabled();
        Optional<Boolean> runtimeActive = runtime.upscaleActive();
        boolean activationResolved = !definitionActive || runtimeActive.isPresent();
        // A definition describes what SR may do; it is not proof that SR dispatched this frame.
        // Unknown activation therefore fails closed instead of applying stale projection data.
        boolean active = definitionActive && runtimeActive.orElse(false);

        Scale scale = active
                ? resolveScale(runtimeExtents, selectInputRegion(profile))
                : Scale.FALLBACK;
        JitterResolution jitter = active
                ? resolveJitter(profile.jitter(), format) : JitterResolution.unresolved(
                JitterOwner.UNKNOWN);

        return new ProjectionResolution(
                extents.renderWidth(), extents.renderHeight(),
                extents.screenWidth(), extents.screenHeight(),
                scale.x(), scale.y(),
                scale.originX(), scale.originY(),
                jitter.x(), jitter.y(), jitter.sequenceLength(),
                scale.resolved(), jitter.offsetResolved(), jitter.sequenceResolved(),
                jitter.owner(), active, activationResolved, jitter.conventionResolved());
    }

    private static Region selectInputRegion(Profile profile) {
        InputTexture color = profile.upscale().inputs().get("color");
        if (color != null && color.enabled()) return color.region();
        InputTexture depth = profile.upscale().inputs().get("depth");
        if (depth != null && depth.enabled()) return depth.region();
        return null;
    }

    private static Scale resolveScale(Optional<SrRuntimeAccess.Extents> extents, Region region) {
        if (extents.isEmpty()) return Scale.FALLBACK;
        SrRuntimeAccess.Extents value = extents.get();
        if (value.renderWidth() <= 0 || value.renderHeight() <= 0
                || value.screenWidth() <= 0 || value.screenHeight() <= 0
                || value.renderWidth() > value.screenWidth()
                || value.renderHeight() > value.screenHeight()) {
            return Scale.FALLBACK;
        }
        // A missing enabled color/depth declaration cannot identify which source viewport SR
        // actually consumes. Keep the useful live render/screen ratio, but mark it non-exact so
        // callers retain their conservative alignment fallback.
        boolean regionDeclared = region != null;
        Region selected = regionDeclared ? region : Region.FULL_RENDER;
        OptionalInt originPixelsX = resolveOrigin(selected.x());
        OptionalInt originPixelsY = resolveOrigin(selected.y());
        OptionalInt width = resolveExtent(
                selected.width(), value.renderWidth(), value.screenWidth());
        OptionalInt height = resolveExtent(
                selected.height(), value.renderHeight(), value.screenHeight());
        if (originPixelsX.isEmpty() || originPixelsY.isEmpty()
                || width.isEmpty() || height.isEmpty()) {
            return Scale.FALLBACK;
        }
        int x = originPixelsX.getAsInt(), y = originPixelsY.getAsInt();
        int w = width.getAsInt(), h = height.getAsInt();
        if (x < 0 || y < 0 || w <= 0 || h <= 0
                || x > value.screenWidth() - w || y > value.screenHeight() - h) {
            return Scale.FALLBACK;
        }
        float scaleX = (float) w / (float) value.screenWidth();
        float scaleY = (float) h / (float) value.screenHeight();
        float originX = (float) x / (float) value.screenWidth();
        float originY = (float) y / (float) value.screenHeight();
        if (!Float.isFinite(scaleX) || !Float.isFinite(scaleY)
                || !Float.isFinite(originX) || !Float.isFinite(originY)
                || scaleX <= 0.0f || scaleY <= 0.0f) {
            return Scale.FALLBACK;
        }
        return new Scale(scaleX, scaleY, originX, originY, regionDeclared);
    }

    private static OptionalInt resolveOrigin(RegionValue value) {
        if (value == null || value.kind() != RegionValueKind.LITERAL) {
            return OptionalInt.empty();
        }
        return OptionalInt.of(value.literal());
    }

    private static OptionalInt resolveExtent(
            RegionValue value, int renderExtent, int screenExtent) {
        if (value == null) return OptionalInt.empty();
        return switch (value.kind()) {
            case LITERAL -> OptionalInt.of(value.literal());
            case RENDER_SIZE -> OptionalInt.of(renderExtent);
            case SCREEN_SIZE -> OptionalInt.of(screenExtent);
        };
    }

    private JitterResolution resolveJitter(Jitter jitter, Format format) {
        if (jitter == null || !jitter.enabled()) return JitterResolution.disabledZero();
        return switch (jitter.owner()) {
            case MOD -> resolveModJitter(format);
            case SHADERPACK -> resolveShaderpackJitter(jitter, format);
            case UNKNOWN -> JitterResolution.unresolved(JitterOwner.UNKNOWN);
        };
    }

    private JitterResolution resolveModJitter(Format format) {
        Optional<SrRuntimeAccess.NumericValue> offset = Optional.empty();
        boolean conventionResolved = false;

        // Ask SR's active schema processor for the value registered through its standard uniform
        // holder. Iris CustomUniforms is deliberately not used here: a pack may define an
        // unrelated custom uniform with the same name.
        if (format == Format.V2 || format == Format.V3) {
            Optional<SrRuntimeAccess.NumericValue> shaderFacing = runtime.shaderFacingModJitter();
            if (shaderFacing.flatMap(SrProjectionResolver::finiteVector2).isPresent()) {
                offset = shaderFacing;
                conventionResolved = true;
            }
        }

        if (offset.isEmpty()) {
            Optional<SrRuntimeAccess.NumericValue> raw = runtime.modJitter();
            Optional<String> code = runtime.algorithmCode();
            if ((format == Format.V2 || format == Format.V3) && raw.isPresent()
                    && code.isPresent()) {
                Optional<SrRuntimeAccess.NumericValue> adapted = adaptSchemaJitter(
                        raw.get(), format, code.get());
                if (adapted.isPresent()) {
                    offset = adapted;
                    conventionResolved = true;
                } else {
                    offset = raw;
                }
            } else {
                offset = raw;
            }
        }

        OptionalInt sequence = runtime.modJitterSequenceLength();
        float[] vector = offset.flatMap(SrProjectionResolver::finiteVector2)
                .orElse(null);
        boolean vectorResolved = vector != null;
        int sequenceLength = sequence.isPresent() && sequence.getAsInt() >= 0
                ? sequence.getAsInt() : 0;
        boolean sequenceResolved = sequence.isPresent() && sequence.getAsInt() >= 0;
        return new JitterResolution(
                vectorResolved ? vector[0] : 0.0f,
                vectorResolved ? vector[1] : 0.0f,
                sequenceLength,
                vectorResolved,
                sequenceResolved,
                JitterOwner.MOD,
                conventionResolved);
    }

    private JitterResolution resolveShaderpackJitter(Jitter jitter, Format format) {
        Optional<SrRuntimeAccess.NumericValue> offset = resolveValue(
                jitter.offset(), ValueType.VECTOR2F);
        Optional<SrRuntimeAccess.NumericValue> sequence = resolveSequenceValue(
                jitter.sequenceLength());

        float[] vector = offset.flatMap(SrProjectionResolver::finiteVector2)
                .orElse(null);
        OptionalInt sequenceLength = sequence.flatMap(SrProjectionResolver::nonNegativeInt)
                .map(OptionalInt::of).orElseGet(OptionalInt::empty);
        boolean vectorResolved = vector != null;
        return new JitterResolution(
                vectorResolved ? vector[0] : 0.0f,
                vectorResolved ? vector[1] : 0.0f,
                sequenceLength.orElse(0),
                vectorResolved,
                sequenceLength.isPresent(),
                JitterOwner.SHADERPACK,
                // Every schema version defines a shaderpack-owned offset as the pack-facing
                // sub-pixel value. Unlike mod-owned jitter it needs no algorithm-specific Y
                // adaptation: the same value is both applied by the pack and supplied to SR.
                format == Format.V1 || format == Format.V2 || format == Format.V3);
    }

    private Optional<SrRuntimeAccess.NumericValue> resolveValue(
            ValueSource source, ValueType expectedType) {
        if (source == null || source.type() != expectedType) return Optional.empty();
        return switch (source.kind()) {
            case CONST -> source.constants().size() == expectedType.componentCount()
                    ? Optional.of(new SrRuntimeAccess.NumericValue(source.constants()))
                    : Optional.empty();
            case UNIFORM, VARIABLE -> runtime.shaderValue(source.kind(), source.reference());
            case UNRESOLVED -> Optional.empty();
        };
    }

    private Optional<SrRuntimeAccess.NumericValue> resolveSequenceValue(ValueSource source) {
        if (source == null || (source.type() != ValueType.INT
                && source.type() != ValueType.UINT)) {
            return Optional.empty();
        }
        return switch (source.kind()) {
            case CONST -> source.constants().size() == 1
                    ? Optional.of(new SrRuntimeAccess.NumericValue(source.constants()))
                    : Optional.empty();
            case UNIFORM, VARIABLE -> runtime.shaderValue(source.kind(), source.reference());
            case UNRESOLVED -> Optional.empty();
        };
    }

    private static final Set<String> KNOWN_SCHEMA_ALGORITHMS = Set.of(
            "none", "fsr1", "fsr2", "fsr", "dlss", "xess",
            "sgsr1", "sgsr2", "anime4k", "fsr4_d3d12");

    /** Returns empty for custom/unknown algorithms so their Y convention is never guessed. */
    static Optional<SrRuntimeAccess.NumericValue> adaptSchemaJitter(
            SrRuntimeAccess.NumericValue raw, Format format, String algorithmCode) {
        if (raw == null || raw.size() != 2 || algorithmCode == null
                || (format != Format.V2 && format != Format.V3)) {
            return Optional.empty();
        }
        String code = algorithmCode.toLowerCase(java.util.Locale.ROOT);
        boolean dlssRr = code.equals("dlssrr");
        if (!KNOWN_SCHEMA_ALGORITHMS.contains(code) && !(format == Format.V3 && dlssRr)) {
            return Optional.empty();
        }
        boolean flipY = code.equals("fsr") || code.equals("dlss")
                || code.equals("xess") || code.equals("fsr4_d3d12")
                || (format == Format.V3 && dlssRr);
        if (!flipY) return Optional.of(raw);
        return Optional.of(SrRuntimeAccess.NumericValue.vector(
                raw.component(0), -raw.component(1)));
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

    private static Optional<Integer> nonNegativeInt(SrRuntimeAccess.NumericValue value) {
        if (value == null || value.size() != 1) return Optional.empty();
        double component = value.component(0);
        if (!Double.isFinite(component) || component < 0.0
                || component > Integer.MAX_VALUE || component != Math.rint(component)) {
            return Optional.empty();
        }
        return Optional.of((int) component);
    }

    private record Scale(float x, float y, float originX, float originY, boolean resolved) {
        private static final Scale FALLBACK = new Scale(1.0f, 1.0f, 0.0f, 0.0f, false);
    }

    private record JitterResolution(float x, float y, int sequenceLength,
                                    boolean offsetResolved, boolean sequenceResolved,
                                    JitterOwner owner, boolean conventionResolved) {
        private static JitterResolution disabledZero() {
            // The SR definition proves only that SR does not add jitter; it cannot prove that the
            // shader pack has no independent temporal projection transform.
            return new JitterResolution(
                    0.0f, 0.0f, 0, true, true, JitterOwner.UNKNOWN, false);
        }

        private static JitterResolution unresolved(JitterOwner owner) {
            return new JitterResolution(0.0f, 0.0f, 0, false, false, owner, false);
        }
    }

    /**
     * Resolved raw SR state. Jitter remains in pixel space because shader packs choose how that
     * value is converted to clip/NDC coordinates; the schema does not encode that convention.
     */
    public record ProjectionResolution(
            int renderWidth, int renderHeight,
            int screenWidth, int screenHeight,
            float scaleX, float scaleY,
            float originX, float originY,
            float jitterPixelsX, float jitterPixelsY,
            int jitterSequenceLength,
            boolean scaleResolved,
            boolean jitterResolved,
            boolean jitterSequenceLengthResolved,
            JitterOwner jitterOwner,
            boolean active,
            boolean activationResolved,
            boolean jitterConventionResolved) {

        public ProjectionResolution {
            jitterOwner = jitterOwner == null ? JitterOwner.UNKNOWN : jitterOwner;
        }

        public boolean hasExactJitterDeclaration() {
            return jitterResolved && jitterSequenceLengthResolved;
        }

        /** True only when Gallium can reproduce the current pack-facing temporal transform. */
        public boolean exactJitterTransform() {
            return active && activationResolved && jitterResolved && jitterConventionResolved;
        }

        /** Alias spelling used by projection consumers: exact includes origin and active size. */
        public boolean exactScale() {
            return active && activationResolved && scaleResolved;
        }
    }
}
