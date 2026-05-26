package cn.spectra.gallium.glowoutline.mixin.accessor;

import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.RenderBuffers;
//#if MC>=1_26_00
import net.minecraft.client.renderer.state.GameRenderState;
//#endif
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(GameRenderer.class)
public interface GameRendererAccessor {
    @Accessor("renderBuffers")
    RenderBuffers gallium$getRenderBuffers();

    //#if MC>=1_26_00
    @Accessor("gameRenderState")
    GameRenderState gallium$getGameRenderState();
    //#endif
}
