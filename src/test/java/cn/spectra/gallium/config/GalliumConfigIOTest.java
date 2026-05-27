package cn.spectra.gallium.config;

import cn.spectra.gallium.glowoutline.GlowOutlineConfig;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Round-trip tests for the on-disk config schema. We deliberately avoid {@link GalliumConfigIO#load}
 * / {@link GalliumConfigIO#save} (those resolve via {@code FabricLoader} which is not available in
 * the unit-test classpath) and exercise the serializer/deserializer directly through the
 * package-private hooks.
 */
class GalliumConfigIOTest {

    @AfterEach
    void resetDefaults() {
        for (GlowOutlineConfig.Toggle t : GlowOutlineConfig.Toggle.values()) {
            t.set(true);
        }
    }

    @Test
    void roundTrip_preservesAllToggles() {
        // Flip a mix of GLOBAL and RENDER_TARGET toggles.
        GlowOutlineConfig.Toggle.ENABLED.set(false);
        GlowOutlineConfig.Toggle.FIRST_PERSON.set(false);
        GlowOutlineConfig.Toggle.GUI.set(false);
        // ARMOR stays true to verify mixed state survives.

        JsonObject serialized = GalliumConfigIO.doSerializeRuntime();

        // Reset everything and re-apply — the loaded values should match what we serialized.
        for (GlowOutlineConfig.Toggle t : GlowOutlineConfig.Toggle.values()) t.set(true);
        GalliumConfigIO.doApplyToRuntime(serialized);

        assertFalse(GlowOutlineConfig.Toggle.ENABLED.get());
        assertFalse(GlowOutlineConfig.Toggle.FIRST_PERSON.get());
        assertFalse(GlowOutlineConfig.Toggle.GUI.get());
        assertTrue(GlowOutlineConfig.Toggle.ARMOR.get());
        assertTrue(GlowOutlineConfig.Toggle.THIRD_PERSON.get());
    }

    @Test
    void serialize_writesCurrentVersionField() {
        JsonObject root = GalliumConfigIO.doSerializeRuntime();
        assertTrue(root.has("config_version"));
        assertEquals(1, root.get("config_version").getAsInt());
    }

    @Test
    void serialize_groupsRenderTargetsUnderRenderTargetsKey() {
        JsonObject root = GalliumConfigIO.doSerializeRuntime();
        assertTrue(root.has("render_targets"));
        JsonObject targets = root.getAsJsonObject("render_targets");
        // GLOBAL toggles (enabled) at root, not under render_targets.
        assertTrue(root.has("enabled"));
        assertFalse(targets.has("enabled"));
        // RENDER_TARGET toggles (gui, first_person...) under render_targets, not at root.
        assertTrue(targets.has("first_person"));
        assertFalse(root.has("first_person"));
    }

    @Test
    void apply_malformedToggleValue_keepsDefaultAndContinues() {
        // A string where a boolean is expected used to abort the entire toggle loop. Now each
        // toggle is independent — first_person stays at its default (true), and gui still
        // applies even though it appears after the bad entry in iteration order.
        for (GlowOutlineConfig.Toggle t : GlowOutlineConfig.Toggle.values()) t.set(true);
        JsonObject root = JsonParser.parseString(
                "{ \"config_version\": 1, \"enabled\": false, \"render_targets\": {"
                        + "\"first_person\": \"not a bool\","
                        + "\"third_person\": false,"
                        + "\"gui\": false } }").getAsJsonObject();
        GalliumConfigIO.doApplyToRuntime(root);

        assertFalse(GlowOutlineConfig.Toggle.ENABLED.get(), "valid global toggle still applied");
        assertTrue(GlowOutlineConfig.Toggle.FIRST_PERSON.get(), "malformed entry kept default");
        assertFalse(GlowOutlineConfig.Toggle.THIRD_PERSON.get(), "later valid entry still applied");
        assertFalse(GlowOutlineConfig.Toggle.GUI.get(), "later valid entry still applied");
    }

    @Test
    void apply_missingRenderTargetsObject_keepsDefaultsForThatGroup() {
        for (GlowOutlineConfig.Toggle t : GlowOutlineConfig.Toggle.values()) t.set(true);
        // No render_targets block at all — loader must not NPE; render-target toggles stay default.
        JsonObject root = JsonParser.parseString(
                "{ \"config_version\": 1, \"enabled\": false }").getAsJsonObject();
        GalliumConfigIO.doApplyToRuntime(root);

        assertFalse(GlowOutlineConfig.Toggle.ENABLED.get());
        for (GlowOutlineConfig.Toggle t : GlowOutlineConfig.Toggle.values()) {
            if (t == GlowOutlineConfig.Toggle.ENABLED) continue;
            assertTrue(t.get(), "render-target toggle " + t.name() + " should keep default when block missing");
        }
    }

    @Test
    void apply_futureVersion_stillReadsKnownFields() {
        // A v999 config is treated as "best effort": known fields apply, unknowns are dropped.
        // We only assert the known-field side here; the warn log is observed manually.
        for (GlowOutlineConfig.Toggle t : GlowOutlineConfig.Toggle.values()) t.set(true);
        JsonObject root = JsonParser.parseString(
                "{ \"config_version\": 999, \"enabled\": false, \"render_targets\": {"
                        + "\"first_person\": false } }").getAsJsonObject();
        GalliumConfigIO.doApplyToRuntime(root);

        assertFalse(GlowOutlineConfig.Toggle.ENABLED.get());
        assertFalse(GlowOutlineConfig.Toggle.FIRST_PERSON.get());
    }
}
