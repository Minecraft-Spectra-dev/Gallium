package cn.spectra.gallium.glowoutline.capture;

import cn.spectra.gallium.glowoutline.GlowOutlineConfig;
import net.minecraft.world.item.ItemDisplayContext;
import org.jspecify.annotations.Nullable;

/** A renderer helper is not a render domain: mods also use the hand helper for worn items. */
public enum ItemCaptureMode {
    NONE(null, false),
    FIRST_PERSON(GlowOutlineConfig.Toggle.FIRST_PERSON, true),
    PLAYER_EQUIPMENT(GlowOutlineConfig.Toggle.THIRD_PERSON, false),
    OTHER_EQUIPMENT(GlowOutlineConfig.Toggle.OTHER_ENTITIES, false);

    private final GlowOutlineConfig.@Nullable Toggle toggle;
    private final boolean firstPerson;

    ItemCaptureMode(GlowOutlineConfig.@Nullable Toggle toggle, boolean firstPerson) {
        this.toggle = toggle;
        this.firstPerson = firstPerson;
    }

    public GlowOutlineConfig.@Nullable Toggle toggle() { return toggle; }
    public boolean firstPerson() { return firstPerson; }

    public static ItemCaptureMode resolve(@Nullable ItemDisplayContext context,
                                          boolean hasEntity, boolean player, boolean preview) {
        if (!hasEntity || context == null) return NONE;
        if (preview) return player ? PLAYER_EQUIPMENT : OTHER_EQUIPMENT;
        return switch (context) {
            case FIRST_PERSON_LEFT_HAND, FIRST_PERSON_RIGHT_HAND -> FIRST_PERSON;
            case THIRD_PERSON_LEFT_HAND, THIRD_PERSON_RIGHT_HAND, NONE ->
                    player ? PLAYER_EQUIPMENT : OTHER_EQUIPMENT;
            default -> NONE;
        };
    }
}
