package cn.spectra.gallium.glowoutline;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure-logic tests for ItemCondition composites and ItemEffectRule matching.
 * Uses {@code And(empty)}/{@code Or(empty)} as always-true/always-false primitives
 * since real Items/Tag/Path conditions would require a Minecraft registry.
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
}
