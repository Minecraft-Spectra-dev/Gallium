package cn.spectra.gallium.glowoutline;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
