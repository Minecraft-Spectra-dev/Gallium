package cn.spectra.gallium.glowoutline.mixin;

//#if MC>=1_21_05
import cn.spectra.gallium.glowoutline.shader.WorldGlowShader;
import com.mojang.blaze3d.shaders.ShaderType;
//#if MC>=1_21_11
import net.minecraft.resources.Identifier;
//#else
//$$ import net.minecraft.resources.ResourceLocation;
//#endif
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Private world aliases keep original resource shaders reusable by GUI/other pipelines. */
@Mixin(targets = "net.minecraft.client.renderer.ShaderManager$CompilationCache")
public abstract class WorldGlowShaderSourceMixin {
    @Shadow public abstract String getShaderSource(
            //#if MC>=1_21_11
            Identifier id,
            //#else
            //$$ ResourceLocation id,
            //#endif
            ShaderType type);

    @Inject(method = "getShaderSource", at = @At("HEAD"), cancellable = true)
    private void galliumForegroundClip(
            //#if MC>=1_21_11
            Identifier id,
            //#else
            //$$ ResourceLocation id,
            //#endif
            ShaderType type, CallbackInfoReturnable<String> callback) {
        String originalPath = WorldGlowShader.originalPath(id.getNamespace(), id.getPath());
        if (originalPath == null) return;
        String source = getShaderSource(id.withPath(originalPath), type);
        callback.setReturnValue(source == null ? null : WorldGlowShader.wrapKnown(source, type == ShaderType.VERTEX, originalPath));
    }
}
//#else
//$$ public abstract class WorldGlowShaderSourceMixin {}
//#endif
