package cn.spectra.gallium.glowoutline.sr.runtime;

import cn.spectra.gallium.glowoutline.sr.definition.SrDefinition.SourceKind;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * Runtime values that are intentionally absent from a shader pack's Super Resolution definition.
 *
 * <p>The interface contains no SR or Iris classes. Production code can therefore load when either
 * mod is absent, while tests and future compatibility adapters can inject values without setting up
 * a Minecraft client.</p>
 */
public interface SrRuntimeAccess {

    /**
     * Whether SR is currently applying upscaling. Empty means the installed generation does not
     * expose a trustworthy activation signal.
     */
    Optional<Boolean> upscaleActive();

    /** Current integer render and display extents reported by SR. */
    Optional<Extents> extents();

    /** Current mod-owned jitter in SR's pixel-space convention. */
    Optional<NumericValue> modJitter();

    /** Current mod-owned jitter after SR's active schema processor adapts it for shader packs. */
    default Optional<NumericValue> shaderFacingModJitter() {
        return Optional.empty();
    }

    /** Current mod-owned jitter sequence length, when the installed SR generation exposes it. */
    OptionalInt modJitterSequenceLength();

    /** Stable code name of the active SR algorithm, used only for schema-specific adaptation. */
    default Optional<String> algorithmCode() {
        return Optional.empty();
    }

    /** Resolves a shader-pack custom uniform or variable by name. */
    Optional<NumericValue> shaderValue(SourceKind kind, String reference);

    record Extents(int renderWidth, int renderHeight, int screenWidth, int screenHeight) {
    }

    /**
     * A scalar or vector numeric value without a hard dependency on JOML or Iris's expression
     * implementation. Components retain their declaration order (x, y, z, w).
     */
    record NumericValue(List<Double> components) {
        public NumericValue {
            components = components == null ? List.of() : List.copyOf(components);
        }

        public static NumericValue scalar(double value) {
            return new NumericValue(List.of(value));
        }

        public static NumericValue vector(double... values) {
            if (values == null || values.length == 0) return new NumericValue(List.of());
            Double[] boxed = new Double[values.length];
            for (int i = 0; i < values.length; i++) boxed[i] = values[i];
            return new NumericValue(List.of(boxed));
        }

        public int size() {
            return components.size();
        }

        public double component(int index) {
            return components.get(index);
        }
    }
}
