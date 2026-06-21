package cn.spectra.gallium.glowoutline.mixin;

//#if MC>=1_21_02 && MC<1_21_06
import cn.spectra.gallium.glowoutline.shader.UboRewriter;
import com.mojang.blaze3d.preprocessor.GlslPreprocessor;
import java.util.List;
import net.minecraft.client.renderer.ShaderManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Converts {@code layout(std140) uniform <Block> { ... };} UBO blocks
 * to individual {@code uniform} declarations in Gallium fragment shaders on
 * 1.21.5. Handles both {@code GlowUniforms} (world composite) and
 * {@code GalliumGuiGlow} (GUI composite, when 1.21.5 is configured to load
 * the same {@code _gui.fsh} as 1.21.6+ rather than reusing the world shader).
 *
 * <p>1.21.5's {@code UniformType} enum has no {@code UNIFORM_BUFFER}, so the
 * pipeline API cannot bind a UBO.  Without a bound buffer, the driver
 * rejects {@code glUniform*} calls on block-member locations.  Removing the
 * block wrapper and promoting the members to top-level uniforms makes the
 * shader match the pipeline's individual uniform declarations.
 *
 * <p>The actual rewrite logic lives in {@link UboRewriter} so the 1.21.4
 * direct-compile pipelines (which bypass {@code ShaderManager.loadShader}
 * and therefore this mixin) can apply the same transformation without
 * duplicating the regex and rewrite loop.
 *
 * <p>MC ≥1.21.6 supports UBOs natively ({@code UniformType.UNIFORM_BUFFER}),
 * so this mixin is stripped on those versions via
 * {@code STUB_MIXIN_CLASSES_FROM_1_21_06}.
 */
@Mixin(ShaderManager.class)
public class ShaderUboCompatMixin {

    @Redirect(
        method = "loadShader",
        at = @At(
            value = "INVOKE",
            target = "Lcom/mojang/blaze3d/preprocessor/GlslPreprocessor;process(Ljava/lang/String;)Ljava/util/List;"
        ),
        require = 1
    )
    private static List<String> convertUboToUniforms(GlslPreprocessor preprocessor, String source) {
        return preprocessor.process(UboRewriter.rewrite(source));
    }
}
//#else
//$$ // Stub: MC>=1_21_06 handles UBO natively; MC<1_21_02 (1.21.1) has no ShaderManager
//$$ // (the direct-compile ShaderInstance path runs UboRewriter itself instead).
//$$ // Stripped from mixins.json via STUB_MIXIN_CLASSES_FROM_1_21_06 / _PRE_1_21_02.
//$$ public abstract class ShaderUboCompatMixin {}
//#endif
