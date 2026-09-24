package cn.spectra.gallium.glowoutline.mixin;
import com.mojang.blaze3d.shaders.BlendMode;
import net.minecraft.client.renderer.ShaderInstance;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
/** 1.20.1's shader-owned blend state predates the pass-owned blending used by Gallium. */
@Mixin(ShaderInstance.class)
public class LegacyShaderBlendMixin {
    @WrapOperation(method = "apply", at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/shaders/BlendMode;apply()V"))
    private void galliumPreservePassBlend(BlendMode blend, Operation<Void> original) {
        // Both generated world/preview and GUI programs use this private synthesized prefix.
        if (((ShaderInstance)(Object)this).getName().startsWith("gallium_glow_")) {
            // Keep vanilla's shader-blend cache intact as well as the physical pass state.
            // Gui.renderVignette sets multiplicative blending before applying its shader.
            // Invalidating this cache makes that shader disable blending and paint the
            // vignette's black texture opaquely over the entire world.
            return;
        }
        original.call(blend);
    }
}
