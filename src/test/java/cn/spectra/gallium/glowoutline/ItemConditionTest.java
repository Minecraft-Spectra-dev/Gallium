package cn.spectra.gallium.glowoutline;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure-logic tests for ItemCondition composites and ItemEffectRule matching.
 * Uses {@code And(empty)}/{@code Or(empty)} as always-true/always-false primitives
 * since real Items/Tag/Path conditions would require a Minecraft registry.
 * <p>
 * The composite primitives never read the stack, so {@code null} is an inert
 * placeholder here. Constructing a real {@code ItemStack} would drag in vanilla's
 * Bootstrap (registry init) which is out of scope for pure-logic tests, and
 * mocking the (final) class fails on the 1.21.x branch where ItemStack's
 * {@code <clinit>} chains into Item.{@code <clinit>} and the registry. If
 * {@code test()} ever evolves to dereference the stack unconditionally, every
 * test below will NPE — refactor or bootstrap at that point.
 */
class ItemConditionTest {

    private static final ItemCondition ALWAYS_TRUE = new ItemCondition.And(List.of());
    private static final ItemCondition ALWAYS_FALSE = new ItemCondition.Or(List.of());

    @Test
    void andEmpty_matches() {
        assertTrue(ALWAYS_TRUE.test(null));
    }

    @Test
    void orEmpty_doesNotMatch() {
        assertFalse(ALWAYS_FALSE.test(null));
    }

    @Test
    void and_allTrue_matches() {
        ItemCondition c = new ItemCondition.And(List.of(ALWAYS_TRUE, ALWAYS_TRUE, ALWAYS_TRUE));
        assertTrue(c.test(null));
    }

    @Test
    void and_oneFalse_doesNotMatch() {
        ItemCondition c = new ItemCondition.And(List.of(ALWAYS_TRUE, ALWAYS_FALSE, ALWAYS_TRUE));
        assertFalse(c.test(null));
    }

    @Test
    void or_oneTrue_matches() {
        ItemCondition c = new ItemCondition.Or(List.of(ALWAYS_FALSE, ALWAYS_TRUE, ALWAYS_FALSE));
        assertTrue(c.test(null));
    }

    @Test
    void or_allFalse_doesNotMatch() {
        ItemCondition c = new ItemCondition.Or(List.of(ALWAYS_FALSE, ALWAYS_FALSE));
        assertFalse(c.test(null));
    }

    @Test
    void not_invertsTrue() {
        assertFalse(new ItemCondition.Not(ALWAYS_TRUE).test(null));
    }

    @Test
    void not_invertsFalse() {
        assertTrue(new ItemCondition.Not(ALWAYS_FALSE).test(null));
    }

    @Test
    void nested_notOfAnd_works() {
        ItemCondition andTrue = new ItemCondition.And(List.of(ALWAYS_TRUE, ALWAYS_TRUE));
        ItemCondition notAnd = new ItemCondition.Not(andTrue);
        assertFalse(notAnd.test(null));
    }

    @Test
    void containsIdentifier_isPrecompiledAndInvalidInputKeepsStringFallback() {
        ItemCondition.Path valid = ItemCondition.Path.compile(
                null, ItemCondition.Path.CheckMode.CONTAINS,
                "minecraft:sharpness", 0.0f, 0.0f);
        assertNotNull(valid.containsId());

        ItemCondition.Path invalid = ItemCondition.Path.compile(
                null, ItemCondition.Path.CheckMode.CONTAINS,
                "not an identifier", 0.0f, 0.0f);
        assertNull(invalid.containsId());
        // Invalid identifiers are false only for ItemEnchantments. Other component types retain
        // the historical string-contains behaviour.
        assertTrue(invalid.testContainsValue("prefix not an identifier suffix"));
        assertFalse(invalid.testContainsValue("different text"));
    }

    @Test
    void numericEqualsOperand_isPrecompiled() {
        ItemCondition.Path numeric = ItemCondition.Path.compile(
                null, ItemCondition.Path.CheckMode.EQUALS, "1.25", 0.0f, 0.0f);
        assertEquals(1.25f, numeric.numericEqualsValue().floatValue());
        assertTrue(numeric.testEqualsValue(1.2500005f));
        assertFalse(numeric.testEqualsValue(1.251f));
    }

    @Test
    void nonNumericEquals_keepsNumericStringFallback() {
        ItemCondition.Path condition = ItemCondition.Path.compile(
                null, ItemCondition.Path.CheckMode.EQUALS, "1", 0.0f, 0.0f);
        // Numeric operand parses, so 1.0 and 1 compare numerically.
        assertTrue(condition.testEqualsValue(1.0f));

        ItemCondition.Path nonNumeric = ItemCondition.Path.compile(
                null, ItemCondition.Path.CheckMode.EQUALS, "not-a-number", 0.0f, 0.0f);
        assertNull(nonNumeric.numericEqualsValue());
        assertTrue(nonNumeric.testEqualsValue(new Number() {
            @Override public int intValue() { return 0; }
            @Override public long longValue() { return 0; }
            @Override public float floatValue() { return 0; }
            @Override public double doubleValue() { return 0; }
            @Override public String toString() { return "not-a-number"; }
        }));
        assertFalse(nonNumeric.testEqualsValue(1));
    }

    @Test
    void equalsNull_keepsLiteralNullSemantics() {
        ItemCondition.Path matches = ItemCondition.Path.compile(
                null, ItemCondition.Path.CheckMode.EQUALS, "null", 0.0f, 0.0f);
        ItemCondition.Path misses = ItemCondition.Path.compile(
                null, ItemCondition.Path.CheckMode.EQUALS, "NULL", 0.0f, 0.0f);
        assertTrue(matches.testEqualsValue(null));
        assertFalse(misses.testEqualsValue(null));
    }
}
