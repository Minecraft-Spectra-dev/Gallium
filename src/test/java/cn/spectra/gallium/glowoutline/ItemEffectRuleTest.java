package cn.spectra.gallium.glowoutline;

import net.minecraft.world.item.ItemStack;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Composite/match-mode logic tests. Stacks aren't real {@link ItemStack}s — vanilla's
 * static init pulls in the registry bootstrap, which we don't want to require for a
 * pure-logic test. The condition primitives used here ({@code And(empty)} /
 * {@code Or(empty)}) deliberately do not inspect the stack, so a {@code null} works
 * as an inert placeholder. If a future change to {@link ItemEffectRule#matches} ever
 * dereferences the stack unconditionally, every test in this class will NPE — that's
 * the signal to either bootstrap the registry in {@code @BeforeAll} or refactor the
 * test stand-ins.
 */
class ItemEffectRuleTest {

    private static final ItemCondition T = new ItemCondition.And(List.of());
    private static final ItemCondition F = new ItemCondition.Or(List.of());
    private static final ItemEffectConfig EFFECT = new ItemEffectConfig("any", List.of());

    @Test
    void emptyConditions_matchAlways() {
        ItemEffectRule rule = new ItemEffectRule(MatchMode.ALL_OF, List.of(), EFFECT);
        assertTrue(rule.matches(null));
        ItemEffectRule rule2 = new ItemEffectRule(MatchMode.ANY_OF, List.of(), EFFECT);
        assertTrue(rule2.matches(null));
        ItemEffectRule rule3 = new ItemEffectRule(MatchMode.NONE_OF, List.of(), EFFECT);
        assertTrue(rule3.matches(null));
    }

    @Test
    void allOf_allTrue_matches() {
        ItemEffectRule rule = new ItemEffectRule(MatchMode.ALL_OF, List.of(T, T, T), EFFECT);
        assertTrue(rule.matches(null));
    }

    @Test
    void allOf_oneFalse_fails() {
        ItemEffectRule rule = new ItemEffectRule(MatchMode.ALL_OF, List.of(T, F, T), EFFECT);
        assertFalse(rule.matches(null));
    }

    @Test
    void anyOf_oneTrue_matches() {
        ItemEffectRule rule = new ItemEffectRule(MatchMode.ANY_OF, List.of(F, T, F), EFFECT);
        assertTrue(rule.matches(null));
    }

    @Test
    void anyOf_allFalse_fails() {
        ItemEffectRule rule = new ItemEffectRule(MatchMode.ANY_OF, List.of(F, F), EFFECT);
        assertFalse(rule.matches(null));
    }

    @Test
    void noneOf_allFalse_matches() {
        ItemEffectRule rule = new ItemEffectRule(MatchMode.NONE_OF, List.of(F, F), EFFECT);
        assertTrue(rule.matches(null));
    }

    @Test
    void noneOf_oneTrue_fails() {
        ItemEffectRule rule = new ItemEffectRule(MatchMode.NONE_OF, List.of(F, T, F), EFFECT);
        assertFalse(rule.matches(null));
    }
}
