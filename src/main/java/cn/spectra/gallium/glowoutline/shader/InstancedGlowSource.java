package cn.spectra.gallium.glowoutline.shader;

import java.util.regex.Pattern;

/** Adds instance selection around the existing final vertex entry point. */
public final class InstancedGlowSource {
    private static final Pattern VERSION = Pattern.compile("(?m)^\\h*#\\h*version[^\\r\\n]*(?:\\r?\\n|$)");
    private static final Pattern MAIN = Pattern.compile("\\bvoid\\s+(main)\\s*\\(\\s*\\)\\s*\\{");
    private InstancedGlowSource() {}

    public static String adapt(String source, boolean vertex) {
        if (!source.contains("// gallium:foreground-occlusion-wrapper")
                || source.contains("gl_InstanceID") || source.contains("gl_ViewportIndex")
                || source.contains("gl_Layer") || source.contains("gl_PrimitiveID")
                || source.contains("gl_FragCoord")) return null;
        var version = VERSION.matcher(source);
        if (!version.find()) return null;
        int insertion = version.end();
        if (!vertex) return source.substring(0, insertion) + "\n#define GALLIUM_HAS_MASK_INSTANCES 1\n" + source.substring(insertion);
        var main = MAIN.matcher(source);
        int start = -1, end = -1;
        while (main.find()) { start = main.start(1); end = main.end(1); }
        if (start < insertion || source.substring(start).contains("#define main")) return null;
        source = source.substring(0, start) + "gallium_InternalInstanceMain" + source.substring(end);
        return source.substring(0, insertion) + "\n#extension GL_ARB_shader_viewport_layer_array : require\n"
                + source.substring(insertion) + """

                flat out int gallium_InternalInstance;
                void main() {
                    gallium_InternalInstanceMain();
                    gallium_InternalInstance = gl_InstanceID;
                    gl_ViewportIndex = gl_InstanceID;
                }
                """;
    }
}
