package cn.spectra.gallium.glowoutline.sr.definition;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Version-neutral, immutable subset of Super Resolution's shader-pack definition.
 *
 * <p>The model deliberately describes declarations rather than resolved runtime values. In
 * particular, {@link ValueSource} can point at an Iris uniform/variable, and region sentinels are
 * retained until a runtime resolver knows the render and screen extents.</p>
 */
public record SrDefinition(Format format, int schemaVersion, Map<String, Profile> profiles) {

    public SrDefinition {
        if (format == null) throw new IllegalArgumentException("format");
        profiles = immutableMap(profiles);
    }

    /** Selects an exact world profile first and then the protocol's {@code *} fallback. */
    public Optional<Profile> profile(String worldKey) {
        Profile exact = worldKey == null ? null : profiles.get(worldKey);
        return Optional.ofNullable(exact != null ? exact : profiles.get("*"));
    }

    public enum Format {
        LEGACY,
        V1,
        V2,
        V3
    }

    public record Profile(boolean enabled, Upscale upscale, Jitter jitter) {
        public Profile {
            upscale = upscale == null ? Upscale.DISABLED : upscale;
            jitter = jitter == null ? Jitter.DISABLED : jitter;
        }
    }

    public record Upscale(boolean enabled,
                          Trigger trigger,
                          String internalFormat,
                          Map<String, InputTexture> inputs,
                          Map<String, OutputTexture> outputs) {
        public static final Upscale DISABLED =
                new Upscale(false, Trigger.NONE, "", Map.of(), Map.of());

        public Upscale {
            trigger = trigger == null ? Trigger.NONE : trigger;
            internalFormat = internalFormat == null ? "" : internalFormat;
            inputs = immutableMap(inputs);
            outputs = immutableMap(outputs);
        }
    }

    public record Trigger(TriggerOrder order, String passName) {
        public static final Trigger NONE = new Trigger(TriggerOrder.NONE, "");

        public Trigger {
            order = order == null ? TriggerOrder.NONE : order;
            passName = passName == null ? "" : passName;
        }
    }

    public enum TriggerOrder {
        NONE,
        BEFORE,
        AFTER
    }

    public record InputTexture(boolean enabled, String sourceName, Region region) {
        public InputTexture {
            sourceName = sourceName == null ? "" : sourceName;
            region = region == null ? Region.FULL_RENDER : region;
        }
    }

    public record OutputTexture(boolean enabled, List<String> targetNames, Region region) {
        public OutputTexture {
            targetNames = targetNames == null ? List.of() : List.copyOf(targetNames);
            region = region == null ? Region.FULL_RENDER : region;
        }
    }

    public record Region(RegionValue x, RegionValue y,
                         RegionValue width, RegionValue height) {
        public static final Region FULL_RENDER = new Region(
                RegionValue.literal(0), RegionValue.literal(0),
                RegionValue.renderSize(), RegionValue.renderSize());
        public static final Region FULL_SCREEN = new Region(
                RegionValue.literal(0), RegionValue.literal(0),
                RegionValue.screenSize(), RegionValue.screenSize());

        public Region {
            if (x == null || y == null || width == null || height == null) {
                throw new IllegalArgumentException("region values must not be null");
            }
            if (x.kind() != RegionValueKind.LITERAL || x.literal() < 0
                    || y.kind() != RegionValueKind.LITERAL || y.literal() < 0) {
                throw new IllegalArgumentException("region x/y must be non-negative literals");
            }
            if (width.kind() == RegionValueKind.LITERAL && width.literal() <= 0
                    || height.kind() == RegionValueKind.LITERAL && height.literal() <= 0) {
                throw new IllegalArgumentException(
                        "region width/height must be positive or use -1/-2");
            }
        }
    }

    /** Preserves SR's {@code -1 = render size}, {@code -2 = screen size} region protocol. */
    public record RegionValue(RegionValueKind kind, int literal) {
        public RegionValue {
            if (kind == null) throw new IllegalArgumentException("kind");
        }

        public static RegionValue fromProtocolValue(int value) {
            return switch (value) {
                case -1 -> renderSize();
                case -2 -> screenSize();
                default -> {
                    if (value < -2) {
                        throw new IllegalArgumentException("unsupported region sentinel: " + value);
                    }
                    yield literal(value);
                }
            };
        }

        public static RegionValue literal(int value) {
            return new RegionValue(RegionValueKind.LITERAL, value);
        }

        public static RegionValue renderSize() {
            return new RegionValue(RegionValueKind.RENDER_SIZE, 0);
        }

        public static RegionValue screenSize() {
            return new RegionValue(RegionValueKind.SCREEN_SIZE, 0);
        }
    }

    public enum RegionValueKind {
        LITERAL,
        RENDER_SIZE,
        SCREEN_SIZE
    }

    public record Jitter(boolean enabled,
                         JitterOwner owner,
                         ValueSource offset,
                         ValueSource sequenceLength) {
        public static final Jitter DISABLED = new Jitter(
                false, JitterOwner.UNKNOWN, ValueSource.unresolved(), ValueSource.unresolved());

        public Jitter {
            owner = owner == null ? JitterOwner.UNKNOWN : owner;
            offset = offset == null ? ValueSource.unresolved() : offset;
            sequenceLength = sequenceLength == null
                    ? ValueSource.unresolved() : sequenceLength;
        }

        /** Whether the definition itself names every value needed for a runtime exact replay. */
        public boolean hasCompleteDeclaration() {
            return enabled && owner != JitterOwner.UNKNOWN
                    && offset.kind() != SourceKind.UNRESOLVED
                    && sequenceLength.kind() != SourceKind.UNRESOLVED;
        }
    }

    public enum JitterOwner {
        MOD,
        SHADERPACK,
        UNKNOWN
    }

    /**
     * Typed source from SR's {@code const/variable/uniform} protocol.
     * Constant scalars use a one-element component list; references use {@link #reference}.
     */
    public record ValueSource(SourceKind kind,
                              ValueType type,
                              String reference,
                              List<Double> constants) {
        public ValueSource {
            kind = kind == null ? SourceKind.UNRESOLVED : kind;
            type = type == null ? ValueType.UNKNOWN : type;
            reference = reference == null ? "" : reference;
            constants = constants == null ? List.of() : List.copyOf(constants);
        }

        public static ValueSource unresolved() {
            return new ValueSource(SourceKind.UNRESOLVED, ValueType.UNKNOWN, "", List.of());
        }

        public static ValueSource reference(SourceKind kind, ValueType type, String name) {
            if (kind != SourceKind.UNIFORM && kind != SourceKind.VARIABLE) {
                throw new IllegalArgumentException("reference source must be uniform or variable");
            }
            if (name == null || name.isBlank()) throw new IllegalArgumentException("name");
            return new ValueSource(kind, type, name, List.of());
        }

        public static ValueSource constant(ValueType type, List<Double> components) {
            if (components == null || components.isEmpty()) {
                throw new IllegalArgumentException("constant components");
            }
            return new ValueSource(SourceKind.CONST, type, "", components);
        }
    }

    public enum SourceKind {
        CONST,
        UNIFORM,
        VARIABLE,
        UNRESOLVED
    }

    public enum ValueType {
        FLOAT(1),
        INT(1),
        UINT(1),
        VECTOR2F(2),
        VECTOR3F(3),
        VECTOR4F(4),
        UNKNOWN(0);

        private final int componentCount;

        ValueType(int componentCount) {
            this.componentCount = componentCount;
        }

        public int componentCount() {
            return componentCount;
        }
    }

    private static <K, V> Map<K, V> immutableMap(Map<K, V> input) {
        if (input == null || input.isEmpty()) return Map.of();
        return Map.copyOf(new LinkedHashMap<>(input));
    }
}
