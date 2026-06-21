package cn.spectra.gallium.glowoutline;

import net.minecraft.core.component.DataComponentType;
//#if MC>=1_21_09
import net.minecraft.IdentifierException;
import net.minecraft.resources.Identifier;
//#else
//$$ import net.minecraft.ResourceLocationException;
//$$ import net.minecraft.resources.ResourceLocation;
//#endif
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.ItemEnchantments;

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

    record Path(DataComponentType<?> component, CheckMode mode, String value, float min, float max) implements ItemCondition {
        public enum CheckMode {
            /** Returns whatever {@code ItemStack.has} reports, including prototype defaults — not necessarily an "explicit override". */
            EXISTS,
            NOT_EMPTY, CONTAINS, RANGE, EQUALS
        }

        @Override
        public boolean test(ItemStack stack) {
            return switch (mode) {
                case EXISTS -> stack.has(component);
                case NOT_EMPTY -> {
                    Object val = stack.get(component);
                    if (val == null) yield false;
                    if (val instanceof ItemEnchantments e) yield !e.isEmpty();
                    if (val instanceof java.util.Collection<?> c) yield !c.isEmpty();
                    if (val instanceof java.util.Map<?, ?> m) yield !m.isEmpty();
                    if (val instanceof CharSequence s) yield s.length() > 0;
                    yield true;
                }
                case CONTAINS -> {
                    Object val = stack.get(component);
                    if (val == null) yield false;
                    if (val instanceof ItemEnchantments enchants) {
                        //#if MC>=1_21_09
                        Identifier id;
                        try {
                            id = Identifier.parse(value);
                        } catch (IdentifierException e) {
                            yield false;
                        }
                        //#else
                        //$$ ResourceLocation id;
                        //$$ try {
                        //$$     id = ResourceLocation.parse(value);
                        //$$ } catch (ResourceLocationException e) {
                        //$$     yield false;
                        //$$ }
                        //#endif
                        boolean found = false;
                        for (var holder : enchants.keySet()) {
                            if (holder.is(id)) { found = true; break; }
                        }
                        yield found;
                    }
                    yield val.toString().contains(value);
                }
                case RANGE -> {
                    Object val = stack.get(component);
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
                    Object val = stack.get(component);
                    if (val == null) yield value.equals("null");
                    if (val instanceof Number n) {
                        // Number.toString format varies (1 vs 1.0 vs 1.0E0). Try numeric compare
                        // with relative epsilon so small fractions match correctly and large
                        // integers aren't false-negative from rounding error.
                        try {
                            float target = java.lang.Float.parseFloat(value);
                            float abs = Math.abs(n.floatValue() - target);
                            float rel = 1.0e-6f * Math.max(Math.abs(n.floatValue()), Math.abs(target));
                            float eps = Math.max(1.0e-6f, rel);
                            yield abs <= eps;
                        } catch (NumberFormatException ignored) {
                            yield val.toString().equals(value);
                        }
                    }
                    yield val.toString().equals(value);
                }
            };
        }
    }
}
