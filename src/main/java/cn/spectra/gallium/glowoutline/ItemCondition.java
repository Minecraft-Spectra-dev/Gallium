package cn.spectra.gallium.glowoutline;

import net.minecraft.core.component.DataComponentType;
import net.minecraft.resources.Identifier;
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
                        var id = Identifier.parse(value);
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
                        yield f >= min && f <= max;
                    }
                    yield false;
                }
                case EQUALS -> {
                    Object val = stack.get(component);
                    if (val == null) yield value.equals("null");
                    yield val.toString().equals(value);
                }
            };
        }
    }
}
