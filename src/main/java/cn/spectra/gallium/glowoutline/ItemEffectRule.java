package cn.spectra.gallium.glowoutline;

import net.minecraft.world.item.ItemStack;
import org.jspecify.annotations.Nullable;
import java.util.List;

public record ItemEffectRule(
        MatchMode mode,
        List<ItemCondition> conditions,
        @Nullable ItemEffectConfig effect
) {

    public boolean matches(ItemStack stack) {
        if (conditions.isEmpty()) return true;

        return switch (mode) {
            case ALL_OF -> {
                for (int i = 0; i < conditions.size(); i++) {
                    if (!conditions.get(i).test(stack)) yield false;
                }
                yield true;
            }
            case ANY_OF -> {
                for (int i = 0; i < conditions.size(); i++) {
                    if (conditions.get(i).test(stack)) yield true;
                }
                yield false;
            }
            case NONE_OF -> {
                for (int i = 0; i < conditions.size(); i++) {
                    if (conditions.get(i).test(stack)) yield false;
                }
                yield true;
            }
        };
    }
}
