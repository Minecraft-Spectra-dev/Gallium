package cn.spectra.gallium.glowoutline.capture;

import cn.spectra.gallium.glowoutline.ItemEffectConfig;
import org.jspecify.annotations.Nullable;

public interface GuiItemRenderStateAccessor {
    @Nullable
    ItemEffectConfig gallium$getEffectConfig();
    void gallium$setEffectConfig(@Nullable ItemEffectConfig config);
}
