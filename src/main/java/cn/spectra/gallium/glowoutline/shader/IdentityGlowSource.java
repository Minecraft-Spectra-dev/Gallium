package cn.spectra.gallium.glowoutline.shader;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.regex.Pattern;

/** Specializes the explicit instance aliases while retaining their std140 members. */
public final class IdentityGlowSource {
    private static final Pattern COMMENTS = Pattern.compile("(?s)/\\*.*?\\*/|//[^\\r\\n]*");
    private static final Pattern DIRECTIVE = Pattern.compile("(?m)^\\h*#\\h*(?:define|undef)\\h+(ShaderAlign|ShaderOffset)\\b[^\\r\\n]*");
    private static final Pattern ALIAS = Pattern.compile("(?m)^\\h*#\\h*define\\h+(ShaderAlign|ShaderOffset)\\h+"
            + "(gallium_InternalValues\\[gallium_InternalInstance\\]\\.\\1)\\h*$");
    private IdentityGlowSource() {}

    public static String specialize(String source) {
        if (source == null) return null;
        var code = source.toCharArray();
        var comments = COMMENTS.matcher(source);
        while (comments.find()) for (int i = comments.start(); i < comments.end(); i++)
            if (code[i] != '\r' && code[i] != '\n') code[i] = ' ';
        String visible = new String(code);
        if (visible.contains("\\") || visible.contains("##")) return null;
        var directives = DIRECTIVE.matcher(visible);
        int alignmentCount = 0, offsetCount = 0;
        while (directives.find()) {
            if (directives.group(1).equals("ShaderAlign")) alignmentCount++;
            else offsetCount++;
        }
        if (alignmentCount != 1 || offsetCount != 1) return null;
        var aliases = ALIAS.matcher(visible);
        var result = new StringBuilder(source.length());
        int end = 0, found = 0;
        while (aliases.find()) {
            result.append(source, end, aliases.start(2));
            result.append(aliases.group(1).equals("ShaderAlign") ? "vec4(1.0)" : "vec4(0.0)");
            end = aliases.end(2); found++;
        }
        if (found != 2) return null;
        return result.append(source, end, source.length()).toString();
    }

    /** Offsets come from the linked original program, so parameter layouts may differ. */
    public static boolean matchesUniforms(ByteBuffer data, int alignmentOffset, int offsetOffset) {
        if (data == null || alignmentOffset < 0 || offsetOffset < 0
                || (long) alignmentOffset + 16 > data.remaining() || (long) offsetOffset + 16 > data.remaining()) return false;
        boolean reverse = data.order() != ByteOrder.nativeOrder();
        int start = data.position();
        for (int i = 0; i < 4; i++) {
            int alignment = data.getInt(start + alignmentOffset + i * 4);
            int offset = data.getInt(start + offsetOffset + i * 4);
            if (reverse) { alignment = Integer.reverseBytes(alignment); offset = Integer.reverseBytes(offset); }
            if (alignment != Float.floatToRawIntBits(1.0f) || offset != 0) return false;
        }
        return true;
    }
}
