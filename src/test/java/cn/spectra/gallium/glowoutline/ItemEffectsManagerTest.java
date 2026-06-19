package cn.spectra.gallium.glowoutline;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;

class ItemEffectsManagerTest {

    private static JsonElement parse(String json) {
        return JsonParser.parseString(json);
    }

    @Test
    void parseParam_number_returnsFloat() {
        ShaderParam p = ItemEffectsManager.parseParam("intensity", parse("1.5"));
        ShaderParam.Float f = assertInstanceOf(ShaderParam.Float.class, p);
        assertEquals("intensity", f.name());
        assertEquals(1.5f, f.value());
    }

    @Test
    void parseParam_integerLiteral_returnsFloat() {
        ShaderParam p = ItemEffectsManager.parseParam("count", parse("3"));
        ShaderParam.Float f = assertInstanceOf(ShaderParam.Float.class, p);
        assertEquals(3.0f, f.value());
    }

    @Test
    void parseParam_array2_returnsVec2() {
        ShaderParam p = ItemEffectsManager.parseParam("size", parse("[1.0, 2.0]"));
        ShaderParam.Vec2 v = assertInstanceOf(ShaderParam.Vec2.class, p);
        assertEquals(1.0f, v.x());
        assertEquals(2.0f, v.y());
    }

    @Test
    void parseParam_array3_returnsVec3() {
        ShaderParam p = ItemEffectsManager.parseParam("color", parse("[1.0, 0.5, 0.25]"));
        ShaderParam.Vec3 v = assertInstanceOf(ShaderParam.Vec3.class, p);
        assertEquals(1.0f, v.x());
        assertEquals(0.5f, v.y());
        assertEquals(0.25f, v.z());
    }

    @Test
    void parseParam_array4_returnsVec4() {
        ShaderParam p = ItemEffectsManager.parseParam("rgba", parse("[1.0, 0.5, 0.25, 0.1]"));
        ShaderParam.Vec4 v = assertInstanceOf(ShaderParam.Vec4.class, p);
        assertEquals(1.0f, v.x());
        assertEquals(0.5f, v.y());
        assertEquals(0.25f, v.z());
        assertEquals(0.1f, v.w());
    }

    @Test
    void parseParam_array1_returnsNull() {
        assertNull(ItemEffectsManager.parseParam("x", parse("[1.0]")));
    }

    @Test
    void parseParam_array5_returnsNull() {
        assertNull(ItemEffectsManager.parseParam("x", parse("[1, 2, 3, 4, 5]")));
    }

    @Test
    void parseParam_string_returnsNull() {
        // Hex color support has been removed; strings are no longer accepted.
        assertNull(ItemEffectsManager.parseParam("color", parse("\"#ff0000\"")));
    }

    @Test
    void parseParam_boolean_returnsNull() {
        assertNull(ItemEffectsManager.parseParam("flag", parse("true")));
    }

    @Test
    void parseParam_object_returnsNull() {
        assertNull(ItemEffectsManager.parseParam("obj", parse("{\"x\": 1}")));
    }

    @Test
    void parseMode_missing_defaultsToAllOf() {
        JsonObject obj = parse("{}").getAsJsonObject();
        assertEquals(MatchMode.ALL_OF, ItemEffectsManager.parseMode(obj, 0));
    }

    @Test
    void parseMode_allOf() {
        JsonObject obj = parse("{\"mode\": \"all_of\"}").getAsJsonObject();
        assertEquals(MatchMode.ALL_OF, ItemEffectsManager.parseMode(obj, 0));
    }

    @Test
    void parseMode_anyOf() {
        JsonObject obj = parse("{\"mode\": \"any_of\"}").getAsJsonObject();
        assertEquals(MatchMode.ANY_OF, ItemEffectsManager.parseMode(obj, 0));
    }

    @Test
    void parseMode_noneOf() {
        JsonObject obj = parse("{\"mode\": \"none_of\"}").getAsJsonObject();
        assertEquals(MatchMode.NONE_OF, ItemEffectsManager.parseMode(obj, 0));
    }

    @Test
    void parseMode_unknown_fallsBackToAllOf() {
        JsonObject obj = parse("{\"mode\": \"foo\"}").getAsJsonObject();
        assertEquals(MatchMode.ALL_OF, ItemEffectsManager.parseMode(obj, 0));
    }

    // --- parseConditionNode early-exit paths ---
    // These exercise the structural validation that runs before any registry lookup, so
    // they don't need vanilla bootstrap. Anything that touches BuiltInRegistries (the
    // path:components.<id> branch with a real component) is covered by integration runs.

    @Test
    void parseConditionNode_nullElement_returnsNullAndDoesNotThrow() {
        assertNull(ItemEffectsManager.parseConditionNode(null, 0, "root"));
    }

    @Test
    void parseConditionNode_nonObjectElement_returnsNull() {
        assertNull(ItemEffectsManager.parseConditionNode(parse("[1, 2, 3]"), 0, "root"));
        assertNull(ItemEffectsManager.parseConditionNode(parse("\"plain string\""), 0, "root"));
        assertNull(ItemEffectsManager.parseConditionNode(parse("42"), 0, "root"));
    }

    @Test
    void parseConditionNode_missingPath_returnsNull() {
        // Predicate object with no recognizable shape — no and/or/not, and no path.
        assertNull(ItemEffectsManager.parseConditionNode(parse("{\"unknown\": true}"), 0, "predicates[0]"));
    }

    @Test
    void parseConditionNode_pathWithoutComponentsPrefix_returnsNull() {
        // path must start with "components." — anything else is rejected before registry lookup.
        assertNull(ItemEffectsManager.parseConditionNode(parse("{\"path\": \"foo.bar\"}"), 0, "predicates[0]"));
        assertNull(ItemEffectsManager.parseConditionNode(parse("{\"path\": \"\"}"), 0, "predicates[0]"));
    }

    @Test
    void parseConditionNode_andOfEmpty_returnsNull() {
        // An empty {"and": []} collapses to null (no child conditions to satisfy).
        assertNull(ItemEffectsManager.parseConditionNode(parse("{\"and\": []}"), 0, "predicates[0]"));
    }

    @Test
    void parseConditionNode_orOfEmpty_returnsNull() {
        assertNull(ItemEffectsManager.parseConditionNode(parse("{\"or\": []}"), 0, "predicates[0]"));
    }

    @Test
    void parseConditionNode_notWithInvalidChild_returnsNull() {
        assertNull(ItemEffectsManager.parseConditionNode(parse("{\"not\": {}}"), 0, "predicates[0]"));
    }

    @Test
    void parseConditionNode_andDropsInvalidChildren() {
        // and with one invalid child + one missing-path child — both get dropped, list ends
        // empty, the and itself collapses to null. Path arg is just for the warn message.
        assertNull(ItemEffectsManager.parseConditionNode(
                parse("{\"and\": [{}, {\"path\": \"not_components.x\"}]}"), 0, "predicates[0]"));
    }

    @Test
    void parseConditionNode_malformedAndChildIsCaughtNotPropagated() {
        // and child that is not an object (number) — handled by parseConditionNode's
        // early-exit; should not throw out of the parent and corrupt the rule.
        assertNull(ItemEffectsManager.parseConditionNode(
                parse("{\"and\": [42, \"str\"]}"), 0, "predicates[0]"));
    }
}
