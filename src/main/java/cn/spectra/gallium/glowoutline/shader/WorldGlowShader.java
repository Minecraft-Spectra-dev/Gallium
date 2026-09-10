package cn.spectra.gallium.glowoutline.shader;

import java.util.regex.Pattern;

/** Internal final-output clipping. Resource-pack files and their effect code stay unchanged. */
public final class WorldGlowShader {
    public static final String FOREGROUND_SAMPLER = "gallium_InternalForegroundDepth";
    private static final String MARKER = "// gallium:foreground-occlusion-wrapper\n";
    private static final String PREFIX = "internal/world_glow/";
    private static final Pattern VERSION = Pattern.compile("(?m)^\\h*#\\h*version\\h+[^\\r\\n]*(?:\\r?\\n|$)");

    private WorldGlowShader() {}

    public static String path(String name) {
        return PREFIX + name;
    }

    public static String originalPath(String namespace, String path) {
        return "gallium".equals(namespace) && path.startsWith(PREFIX) && path.length() > PREFIX.length()
                ? "core/" + path.substring(PREFIX.length()) : null;
    }

    public static String wrap(String source, boolean vertex) {
        if (source.contains(MARKER)) return source;
        if (source.startsWith("\uFEFF")) source = source.substring(1);
        var version = VERSION.matcher(withoutComments(source));
        int insertion = version.find() ? version.end() : 0;
        String header = source.substring(0, insertion);
        if (!header.isEmpty() && !header.endsWith("\n")) header += "\n";
        String body = source.substring(insertion);
        String adapter = vertex ? """
                noperspective out vec2 gallium_InternalScreenUv;
                void main() {
                    gallium_InternalPackMain();
                    gallium_InternalScreenUv = gl_Position.xy / gl_Position.w * 0.5 + 0.5;
                }
                """ : """
                uniform sampler2D gallium_InternalForegroundDepth;
                noperspective in vec2 gallium_InternalScreenUv;
                void main() {
                    ivec2 size = textureSize(gallium_InternalForegroundDepth, 0);
                    ivec2 pixel = clamp(ivec2(floor(gallium_InternalScreenUv * vec2(size))),
                            ivec2(0), size - ivec2(1));
                    // This depth belongs to a later foreground layer. Any written value
                    // covers the world, even when its raw Z is farther than the world item.
                    if (texelFetch(gallium_InternalForegroundDepth, pixel, 0).r < 1.0) discard;
                    gallium_InternalPackMain();
                }
                """;
        return header + MARKER + "#define main gallium_InternalPackMain\n" + body
                + "\n#undef main\n" + adapter;
    }

    /** Preserve source offsets/newlines while ignoring fake directives inside comments. */
    private static String withoutComments(String source) {
        StringBuilder result = new StringBuilder(source);
        boolean block = false, line = false;
        for (int i = 0; i < source.length(); i++) {
            char c = source.charAt(i);
            if (line && (c == '\n' || c == '\r')) line = false;
            if (!block && !line && c == '/' && i + 1 < source.length()) {
                char next = source.charAt(i + 1);
                if (next == '/' || next == '*') {
                    line = next == '/';
                    block = next == '*';
                    result.setCharAt(i++, ' ');
                    result.setCharAt(i, ' ');
                    continue;
                }
            }
            if (block && c == '*' && i + 1 < source.length() && source.charAt(i + 1) == '/') {
                result.setCharAt(i++, ' ');
                result.setCharAt(i, ' ');
                block = false;
            } else if ((block || line) && c != '\n' && c != '\r') {
                result.setCharAt(i, ' ');
            }
        }
        return result.toString();
    }
}
