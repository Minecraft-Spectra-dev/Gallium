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
}
