package cn.spectra.gallium.glowoutline.shader;

import java.util.regex.Pattern;

/** Rejects any shader whose raster position is not the inspected default transform. */
public final class VertexPositionProof {
    private static final Pattern COMMENTS = Pattern.compile("(?s)/\\*.*?\\*/|//[^\\r\\n]*");
    private static final Pattern POSITION = Pattern.compile("\\bgl_Position\\b");
    private static final Pattern ASSIGNMENT = Pattern.compile(
            "\\bgl_Position\\s*=\\s*ProjMat\\s*\\*\\s*ModelViewMat\\s*\\*\\s*vec4\\s*\\(\\s*Position\\s*,\\s*1(?:\\.0*)?\\s*\\)\\s*;");
    private static final Pattern UNSAFE_DEFINE = Pattern.compile(
            "(?m)^\\h*#\\h*(?:define|undef)\\h+(?:ProjMat|ModelViewMat|Position|vec4|main|gl_Position)\\b");

    private VertexPositionProof() {}

    /** Base-vertex addressing leaves attributes intact but changes these shader built-ins. */
    public static boolean supportsBaseVertex(String source) {
        if (!matchesUniformBlocks(source)) return false;
        String code = COMMENTS.matcher(source).replaceAll(" ");
        return !code.contains("gl_VertexID") && !code.contains("gl_BaseVertex");
    }

    /** Recognizes only the native, uninstanced matrix blocks used by the modern draw API. */
    public static boolean matchesUniformBlocks(String source) {
        if (source == null) return false;
        String code = COMMENTS.matcher(source).replaceAll(" ");
        String projection = "layout\\s*\\(\\s*std140\\s*\\)\\s*uniform\\s+Projection\\s*\\{\\s*mat4\\s+ProjMat\\s*;\\s*}\\s*;";
        String transforms = "layout\\s*\\(\\s*std140\\s*\\)\\s*uniform\\s+DynamicTransforms\\s*\\{"
                + "\\s*mat4\\s+ModelViewMat\\s*;\\s*vec4\\s+ColorModulator\\s*;"
                + "\\s*vec3\\s+ModelOffset\\s*;\\s*mat4\\s+TextureMat\\s*;"
                + "(?:\\s*float\\s+LineWidth\\s*;)?\\s*}\\s*;";
        var p = Pattern.compile(projection).matcher(code);
        var t = Pattern.compile(transforms).matcher(code);
        if (!p.find() || p.find() || !t.find() || t.find()) return false;
        // Retain all other declarations and directives so the ordinary certificate still
        // rejects shadowed names, macros, conditional position writes and altered viewports.
        code = code.replaceAll(projection, "uniform mat4 ProjMat;")
                .replaceAll(transforms, "uniform mat4 ModelViewMat;");
        return matches(code);
    }

    public static boolean matches(String source) {
        if (source == null) return false;
        String code = COMMENTS.matcher(source).replaceAll(" ");
        if (UNSAFE_DEFINE.matcher(code).find() || code.contains("gl_ViewportIndex")
                || code.contains("gl_Layer")) return false;
        // Include guards and numeric constants cannot shadow an input declaration's type.
        // Arbitrary identifier/function expansion cannot establish this certificate.
        for (String line : code.split("\\R")) {
            if (line.stripLeading().matches("#\\h*define\\h+.*")
                    && !line.strip().matches("#\\h*define\\h+[A-Za-z_][A-Za-z_0-9]*"
                    + "(?:\\h+[0-9eE+*/(). \\t-]+)?")) return false;
        }
        for (String declaration : new String[] {"mat4\\s+ProjMat", "mat4\\s+ModelViewMat", "vec3\\s+Position"}) {
            var declarations = Pattern.compile("\\b" + declaration + "\\b").matcher(code);
            if (!declarations.find() || declarations.find()) return false;
        }
        if (!Pattern.compile("\\buniform\\s+mat4\\s+ProjMat\\s*;").matcher(code).find()
                || !Pattern.compile("\\buniform\\s+mat4\\s+ModelViewMat\\s*;").matcher(code).find()
                || !Pattern.compile("\\bin\\s+vec3\\s+Position\\s*;").matcher(code).find()) return false;
        var uses = POSITION.matcher(code);
        if (!uses.find() || uses.find()) return false;
        var assignment = ASSIGNMENT.matcher(code);
        if (!assignment.find()) return false;
        String prefix = code.substring(0, assignment.start());
        if (!Pattern.compile("\\bvoid\\s+main\\s*\\(\\s*(?:void\\s*)?\\)\\s*\\{\\s*$")
                .matcher(prefix).find()) return false;
        int conditionalDepth = 0;
        for (String line : prefix.split("\\R")) {
            if (line.stripLeading().matches("#\\h*(if|ifdef|ifndef)\\b.*")) conditionalDepth++;
            if (line.stripLeading().matches("#\\h*endif\\b.*") && --conditionalDepth < 0) return false;
        }
        return conditionalDepth == 0;
    }
}
