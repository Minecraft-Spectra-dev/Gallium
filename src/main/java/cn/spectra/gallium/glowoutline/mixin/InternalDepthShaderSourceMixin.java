package cn.spectra.gallium.glowoutline.mixin;

//#if MC==1_21_11 || MC==1_26_01
import cn.spectra.gallium.glowoutline.shader.DepthMinPoolPipeline;
import cn.spectra.gallium.glowoutline.shader.DepthResamplePipeline;
import com.mojang.blaze3d.shaders.ShaderType;
//#if MC>=1_21_11
import net.minecraft.resources.Identifier;
//#else
//$$ import net.minecraft.resources.ResourceLocation;
//#endif
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Both reload precompilation and ordinary cache-miss draws resolve sources through this cache.
 * They must find both private inline shaders even before the item-effect listener precompiles;
 * otherwise the device retains an invalid program that its computeIfAbsent cannot replace.
 */
@Mixin(targets = "net.minecraft.client.renderer.ShaderManager$CompilationCache")
public class InternalDepthShaderSourceMixin {
    @Inject(method = "getShaderSource", at = @At("HEAD"), cancellable = true)
    void galliumInternalDepthSource(
            //#if MC>=1_21_11
            Identifier id,
            //#else
            //$$ ResourceLocation id,
            //#endif
            ShaderType type, CallbackInfoReturnable<String> callback) {
        String source = DepthResamplePipeline.shaderSource(id, type);
        if (source == null) source = DepthMinPoolPipeline.shaderSource(id, type);
        if (source != null) callback.setReturnValue(source);
    }
}
//#else
//$$ public abstract class InternalDepthShaderSourceMixin {}
//#endif
