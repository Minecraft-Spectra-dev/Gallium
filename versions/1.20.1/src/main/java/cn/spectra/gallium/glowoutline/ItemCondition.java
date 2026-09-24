package cn.spectra.gallium.glowoutline;


import net.minecraft.ResourceLocationException;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import cn.spectra.gallium.glowoutline.LegacyItemData.Enchantments;
import org.jspecify.annotations.Nullable;

import java.util.List;

public sealed interface ItemCondition permits
        ItemCondition.Items,
        ItemCondition.Tag,
        ItemCondition.Path,
        ItemCondition.And,
        ItemCondition.Or,
        ItemCondition.Not {

    boolean test(ItemStack stack);

    record Items(java.util.Set<net.minecraft.world.item.Item> items) implements ItemCondition {
        @Override
        public boolean test(ItemStack stack) {
            return items.contains(stack.getItem());
        }
    }

    record Tag(net.minecraft.tags.TagKey<net.minecraft.world.item.Item> tag) implements ItemCondition {
        @Override
        public boolean test(ItemStack stack) {
            return stack.is(tag);
        }
    }

    record And(List<ItemCondition> children) implements ItemCondition {
        @Override
        public boolean test(ItemStack stack) {
            for (int i = 0; i < children.size(); i++) {
                if (!children.get(i).test(stack)) return false;
            }
            return true;
        }
    }

    record Or(List<ItemCondition> children) implements ItemCondition {
        @Override
        public boolean test(ItemStack stack) {
            for (int i = 0; i < children.size(); i++) {
                if (children.get(i).test(stack)) return true;
            }
            return false;
        }
    }

    record Not(ItemCondition child) implements ItemCondition {
        @Override
        public boolean test(ItemStack stack) {
            return !child.test(stack);
        }
    }

    /**
     * Component predicate with the immutable string operands compiled when the resource rule is
     * parsed.  {@code containsId} is used only when the runtime component value is an
     * {@link Enchantments}; ordinary values retain their historical
     * {@code toString().contains(value)} behaviour.  {@code numericEqualsValue} is null when the
     * equals operand is not a float, preserving the old string-equality fallback for numeric
     * component values.
     */
    record Path(
            String component,
            CheckMode mode,
            String value,
            float min,
            float max,
            @Nullable ResourceLocation containsId,
            @Nullable Float numericEqualsValue
    ) implements ItemCondition {
        public enum CheckMode {
            /** Returns whatever {@code ItemStack.has} reports, including prototype defaults — not necessarily an "explicit override". */
            EXISTS,
            NOT_EMPTY, CONTAINS, RANGE, EQUALS
        }

        /**
         * Backwards-compatible constructor for callers that build a path directly. Compilation
         * still happens once here, rather than from {@link #test(ItemStack)} on every match.
         */
        public Path(String component, CheckMode mode, String value,
                    float min, float max) {
            this(component, mode, value, min, max,
                    compileContainsId(mode, value), compileNumericEquals(mode, value));
        }

        /** Explicit resource-parser entry point, documenting that operands are precompiled. */
        public static Path compile(String component, CheckMode mode, String value,
                                   float min, float max) {
            return new Path(component, mode, value, min, max);
        }

        private static @Nullable ResourceLocation compileContainsId(CheckMode mode, String value) {
            if (mode != CheckMode.CONTAINS) return null;
            try {
                return new ResourceLocation(value);
            } catch (ResourceLocationException ignored) {
                return null;
            }
        }

        private static @Nullable Float compileNumericEquals(CheckMode mode, String value) {
            if (mode != CheckMode.EQUALS) return null;
            try {
                return java.lang.Float.parseFloat(value);
            } catch (NumberFormatException ignored) {
                return null;
            }
        }

        /** Package-private pure helper used by focused compatibility tests. */
        boolean testContainsValue(@Nullable Object val) {
            if (val == null) return false;
            if (val instanceof Enchantments enchants) {
                if (containsId == null) return false;
                return enchants.contains(containsId);
            }
            return val.toString().contains(value);
        }

        /** Package-private pure helper used by focused compatibility tests. */
        boolean testEqualsValue(@Nullable Object val) {
            if (val == null) return value.equals("null");
            if (val instanceof Number n && numericEqualsValue != null) {
                float target = numericEqualsValue;
                // Keep the existing relative-epsilon formula exactly: resource packs may rely on
                // its behaviour for both small fractions and large integer-valued components.
                float abs = Math.abs(n.floatValue() - target);
                float rel = 1.0e-6f * Math.max(Math.abs(n.floatValue()), Math.abs(target));
                float eps = Math.max(1.0e-6f, rel);
                return abs <= eps;
            }
            // A numeric runtime value paired with a non-numeric target historically fell back to
            // its string form after Float.parseFloat threw. Non-numeric values used the same path.
            return val.toString().equals(value);
        }

        @Override
        public boolean test(ItemStack stack) {
            return switch (mode) {
                case EXISTS -> LegacyItemData.get(stack, component) != null;
                case NOT_EMPTY -> {
                    Object val = LegacyItemData.get(stack, component);
                    if (val == null) yield false;
                    if (val instanceof Enchantments e) yield !e.isEmpty();
                    if (val instanceof net.minecraft.nbt.CollectionTag<?> nbt) yield !nbt.isEmpty();
                    if (val instanceof net.minecraft.nbt.CompoundTag nbt) yield !nbt.isEmpty();
                    if (val instanceof java.util.Collection<?> c) yield !c.isEmpty();
                    if (val instanceof java.util.Map<?, ?> m) yield !m.isEmpty();
                    if (val instanceof CharSequence s) yield s.length() > 0;
                    yield true;
                }
                case CONTAINS -> {
                    Object val = LegacyItemData.get(stack, component);
                    yield testContainsValue(val);
                }
                case RANGE -> {
                    Object val = LegacyItemData.get(stack, component);
                    if (val == null) yield false;
                    if (val instanceof Number n) {
                        float f = n.floatValue();
                        // Reject non-finite values: NaN compares false against everything (would
                        // confuse "in range" semantics), and ±Infinity slipping through silently
                        // treats unbounded values as in-range, which is rarely the author's intent.
                        if (!java.lang.Float.isFinite(f)) yield false;
                        yield f >= min && f <= max;
                    }
                    yield false;
                }
                case EQUALS -> {
                    Object val = LegacyItemData.get(stack, component);
                    yield testEqualsValue(val);
                }
            };
        }
    }
}
