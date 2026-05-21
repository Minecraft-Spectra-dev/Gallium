package cn.spectra.gallium.glowoutline.mixin.accessor;

import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.RenderBuffers;
import net.minecraft.client.renderer.state.GameRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(GameRenderer.class)
public interface GameRendererAccessor {
    @Accessor("renderBuffers")
    RenderBuffers gallium$getRenderBuffers();

    @Accessor("gameRenderState")
    GameRenderState gallium$getGameRenderState();
}
