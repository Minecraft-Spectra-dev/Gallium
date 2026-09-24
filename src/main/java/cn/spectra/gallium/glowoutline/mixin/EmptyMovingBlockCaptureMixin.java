package cn.spectra.gallium.glowoutline.mixin;

//#if MC==1_26_01
import cn.spectra.gallium.glowoutline.capture.ModernMaskBounds;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.SubmitNodeCollection;
import net.minecraft.client.renderer.block.BlockStateModelSet;
import net.minecraft.client.renderer.feature.BlockFeatureRenderer;
import net.minecraft.client.renderer.state.OptionsRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** An empty capture list needs none of the per-call block renderer and lighting allocations. */
@Mixin(BlockFeatureRenderer.class)
public abstract class EmptyMovingBlockCaptureMixin {
    @Inject(method = "renderMovingBlockSubmits", at = @At("HEAD"), cancellable = true)
    private void galliumSkipEmptyMovingBlocks(SubmitNodeCollection submits,
            MultiBufferSource.BufferSource buffers, BlockStateModelSet models,
            OptionsRenderState options, boolean translucent, CallbackInfo callback) {
        if (submits.getMovingBlockSubmits().isEmpty() && ModernMaskBounds.currentState() != null) {
            callback.cancel();
        }
    }
}
//#else
//$$ public abstract class EmptyMovingBlockCaptureMixin {}
//#endif
