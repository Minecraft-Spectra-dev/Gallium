package cn.spectra.gallium.glowoutline.mixin;

import cn.spectra.gallium.glowoutline.ItemEffectConfig;
import cn.spectra.gallium.glowoutline.capture.GuiItemRenderStateAccessor;
import net.minecraft.client.renderer.state.gui.GuiItemRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

@Mixin(GuiItemRenderState.class)
public class GuiItemRenderStateMixin implements GuiItemRenderStateAccessor {

    @Unique
    private ItemEffectConfig gallium$effectConfig;

    @Override
    public ItemEffectConfig gallium$getEffectConfig() {
        return gallium$effectConfig;
    }

    @Override
    public void gallium$setEffectConfig(ItemEffectConfig config) {
        gallium$effectConfig = config;
    }
}
