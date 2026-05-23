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
            case ALL_OF -> conditions.stream().allMatch(c -> c.test(stack));
            case ANY_OF -> conditions.stream().anyMatch(c -> c.test(stack));
            case NONE_OF -> conditions.stream().noneMatch(c -> c.test(stack));
        };
    }
}
