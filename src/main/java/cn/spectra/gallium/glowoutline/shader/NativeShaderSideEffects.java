package cn.spectra.gallium.glowoutline.shader;

import java.util.Locale;
import java.util.regex.Pattern;

/** Conservative rejection of storage writes beyond the native color/depth outputs. */
public final class NativeShaderSideEffects {
    private static final Pattern COMMENTS = Pattern.compile("(?s)/\\*.*?\\*/|//[^\\r\\n]*");
    private NativeShaderSideEffects() {}
    public static boolean hasOnlyRasterOutputs(String source) {
        if (source == null) return false;
        String code = COMMENTS.matcher(source).replaceAll(" ").toLowerCase(Locale.ROOT);
        return !code.contains("buffer") && !code.contains("image") && !code.contains("atomic")
                && !code.contains("barrier") && !code.contains("interlock") && !code.contains("pointer")
                && !code.contains("##") && !code.contains("\\");
    }
}
