package cn.spectra.gallium.glowoutline.capture;

import cn.spectra.gallium.glowoutline.GlowOutlineConfig;
import net.minecraft.world.item.ItemDisplayContext;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ItemCaptureModeTest {
    @Test void bodyEquipmentUsingTheHandHelperDoesNotBecomeFirstPerson() {
        for (var context : new ItemDisplayContext[]{ItemDisplayContext.NONE,
                ItemDisplayContext.THIRD_PERSON_LEFT_HAND, ItemDisplayContext.THIRD_PERSON_RIGHT_HAND}) {
            var player = ItemCaptureMode.resolve(context, true, true, false);
            assertEquals(GlowOutlineConfig.Toggle.THIRD_PERSON, player.toggle());
            assertFalse(player.firstPerson());
            assertEquals(ItemCaptureMode.OTHER_EQUIPMENT,
                    ItemCaptureMode.resolve(context, true, false, false));
        }
    }

    @Test void onlyFirstPersonHandContextsUseTheIsolatedHandDepth() {
        for (var context : ItemDisplayContext.values()) {
            boolean expected = context == ItemDisplayContext.FIRST_PERSON_LEFT_HAND
                    || context == ItemDisplayContext.FIRST_PERSON_RIGHT_HAND;
            assertEquals(expected, ItemCaptureMode.resolve(context, true, true, false).firstPerson(), context.name());
        }
    }

    @Test void previewOwnershipOverridesEvenAFirstPersonDisplayTransform() {
        for (var context : ItemDisplayContext.values()) {
            var mode = ItemCaptureMode.resolve(context, true, true, true);
            assertEquals(ItemCaptureMode.PLAYER_EQUIPMENT, mode);
            assertNotEquals(GlowOutlineConfig.Toggle.GUI, mode.toggle());
            assertFalse(mode.firstPerson());
        }
    }

    @Test void unrelatedItemContextsAndMissingOwnersAreNotCapturedByTheHandHelper() {
        for (var context : new ItemDisplayContext[]{ItemDisplayContext.GUI, ItemDisplayContext.GROUND,
                ItemDisplayContext.FIXED, ItemDisplayContext.HEAD}) {
            assertEquals(ItemCaptureMode.NONE, ItemCaptureMode.resolve(context, true, true, false));
        }
        assertEquals(ItemCaptureMode.NONE, ItemCaptureMode.resolve(null, true, true, false));
        assertEquals(ItemCaptureMode.NONE, ItemCaptureMode.resolve(ItemDisplayContext.NONE, false, false, false));
    }
}
