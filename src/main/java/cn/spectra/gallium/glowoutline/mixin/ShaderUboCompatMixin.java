package cn.spectra.gallium.glowoutline.mixin;

//#if MC<1_21_06
import com.mojang.blaze3d.preprocessor.GlslPreprocessor;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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
 * <p>MC ≥1.21.6 supports UBOs natively ({@code UniformType.UNIFORM_BUFFER}),
 * so this mixin is stripped on those versions via
 * {@code STUB_MIXIN_CLASSES_FROM_1_21_06}.
 */
@Mixin(ShaderManager.class)
public class ShaderUboCompatMixin {

    // Matches both world ("GlowUniforms") and GUI ("GalliumGuiGlow") UBO blocks.
    // Group 1 is the block body (members), used to emit individual uniforms.
    // The alternation is anchored so a future block name typo doesn't silently
    // pass through unconverted and produce the same "all-zero uniforms" symptom
    // that motivated this mixin.
    private static final Pattern UBO_PATTERN = Pattern.compile(
        "layout\\s*\\(\\s*std140\\s*\\)\\s*uniform\\s+(?:GlowUniforms|GalliumGuiGlow)\\s*\\{([^}]*)\\}",
        Pattern.DOTALL
    );

    @Redirect(
        method = "loadShader",
        at = @At(
            value = "INVOKE",
            target = "Lcom/mojang/blaze3d/preprocessor/GlslPreprocessor;process(Ljava/lang/String;)Ljava/util/List;"
        ),
        require = 1
    )
    private static List<String> convertUboToUniforms(GlslPreprocessor preprocessor, String source) {
        // findAll: a shader could in principle contain both blocks (none currently
        // do, but the loop is cheap and future-proof). replaceFirst handled only
        // the first occurrence; we want to convert every match.
        Matcher m = UBO_PATTERN.matcher(source);
        if (!m.find()) {
            return preprocessor.process(source);
        }

        StringBuilder transformed = new StringBuilder();
        int lastEnd = 0;
        do {
            transformed.append(source, lastEnd, m.start());
            for (String line : m.group(1).split(";")) {
                line = line.trim();
                if (!line.isEmpty()) {
                    transformed.append("uniform ").append(line).append(";\n");
                }
            }
            lastEnd = m.end();
        } while (m.find());
        transformed.append(source, lastEnd, source.length());

        return preprocessor.process(transformed.toString());
    }
}
//#else
//$$ // Stub: MC>=1_21_06 handles UBO natively.
//$$ public abstract class ShaderUboCompatMixin {}
//#endif
