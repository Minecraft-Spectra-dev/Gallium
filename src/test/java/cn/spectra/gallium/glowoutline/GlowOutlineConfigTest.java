package cn.spectra.gallium.glowoutline;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GlowOutlineConfigTest {

    @AfterEach
    void resetDefaults() {
        for (GlowOutlineConfig.Toggle t : GlowOutlineConfig.Toggle.values()) {
            t.set(true);
        }
    }

    @Test
    void allTogglesDefaultTrue() {
        for (GlowOutlineConfig.Toggle t : GlowOutlineConfig.Toggle.values()) {
            assertTrue(t.get(), "Toggle " + t.name() + " should default true");
        }
    }

    @Test
    void setAndGet_independent() {
        GlowOutlineConfig.Toggle.FIRST_PERSON.set(false);
        assertFalse(GlowOutlineConfig.Toggle.FIRST_PERSON.get());
        assertTrue(GlowOutlineConfig.Toggle.THIRD_PERSON.get());
    }

    @Test
    void enabled_isGlobalGroup() {
        assertEquals(GlowOutlineConfig.Group.GLOBAL, GlowOutlineConfig.Toggle.ENABLED.group());
    }

    @Test
    void renderTargetToggles_inRenderTargetGroup() {
        for (GlowOutlineConfig.Toggle t : GlowOutlineConfig.Toggle.values()) {
            if (t == GlowOutlineConfig.Toggle.ENABLED) continue;
            assertEquals(GlowOutlineConfig.Group.RENDER_TARGET, t.group(),
                    "Toggle " + t.name() + " should be in RENDER_TARGET group");
        }
    }

    @Test
    void jsonKeys_unique() {
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (GlowOutlineConfig.Toggle t : GlowOutlineConfig.Toggle.values()) {
            assertNotNull(t.jsonKey());
            assertTrue(seen.add(t.jsonKey()), "Duplicate jsonKey: " + t.jsonKey());
        }
    }

    @Test
    void sodiumIds_unique() {
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (GlowOutlineConfig.Toggle t : GlowOutlineConfig.Toggle.values()) {
            assertNotNull(t.sodiumId());
            assertTrue(seen.add(t.sodiumId()), "Duplicate sodiumId: " + t.sodiumId());
        }
    }

    @Test
    void staticAccessors_alignWithEnum() {
        GlowOutlineConfig.Toggle.ENABLED.set(false);
        assertFalse(GlowOutlineConfig.isEnabled());
        GlowOutlineConfig.setEnabled(true);
        assertTrue(GlowOutlineConfig.Toggle.ENABLED.get());

        GlowOutlineConfig.setGui(false);
        assertFalse(GlowOutlineConfig.Toggle.GUI.get());
    }
}
