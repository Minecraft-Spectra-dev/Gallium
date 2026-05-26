package cn.spectra.gallium.glowoutline.mixin.accessor;

import net.minecraft.client.renderer.GameRenderer;
//#if MC>=1_26_00
import net.minecraft.client.renderer.state.GameRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(GameRenderer.class)
public interface GameRendererAccessor {
    @Accessor("gameRenderState")
    GameRenderState gallium$getGameRenderState();
}
//#else
//$$ // GameRenderState doesn't exist on 1.21.x — see GlowCaptureManager's
//$$ // FeatureRenderDispatcher constructor branches; the 1.21.x ctor doesn't
//$$ // take a GameRenderState. Stub kept so the mixins.json reference is
//$$ // valid; common.gradle's strip pass keeps it out of the runtime config.
//$$ public interface GameRendererAccessor {}
//#endif
