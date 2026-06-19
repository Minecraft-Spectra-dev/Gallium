package cn.spectra.gallium.glowoutline.shader;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Shared GLSL source transformer that rewrites
 * {@code layout(std140) uniform <Block>} blocks (member list followed by a
 * closing brace and semicolon) into matching top-level {@code uniform}
 * declarations.
 *
 * <p>Used by the pre-1.21.6 paths where {@code UniformType.UNIFORM_BUFFER}
 * is unavailable (the pipeline API can't bind a UBO, so the driver rejects
 * {@code glUniform*} calls on block-member locations). Three call sites:
 * <ul>
 *   <li>{@code ShaderUboCompatMixin} — intercepts vanilla shader loads via
 *       {@code ShaderManager.loadShader}.</li>
 *   <li>{@code GlowPipeline} (1.21.4) — direct {@code CompiledShader.compile}
 *       path that bypasses {@code ShaderManager}.</li>
 *   <li>{@code GuiImmediateGlowPipeline} (1.21.4) — same direct path for the
 *       GUI glow shader.</li>
 * </ul>
 *
 * <h2>Brace handling</h2>
 *
 * Pack authors are not Gallium developers — their shaders may legitimately
 * contain nested braces inside a UBO block. The realistic cases are GLSL
 * block comments (delimited by slash-star and star-slash) that wrap members
 * and contain stray brace or semicolon characters, plus the rarer-but-valid
 * inline struct declaration with its own pair of braces. A naive regex like
 * {@code [^}]*} stops at the first inner closing brace — leaving the rest
 * of the block as a raw {@code uniform} declaration the driver still
 * rejects, exactly the bug this rewriter is meant to prevent.
 *
 * <p>The implementation finds the block <i>header</i> with a regex (cheap,
 * unique) but locates the matching closing brace by walking the source,
 * skipping line and block comments, and counting brace depth. Member
 * splitting in the body uses the same comment-aware walker so a semicolon
 * inside a comment doesn't fragment a declaration.
 *
 * <p>The block-name alternation in the header pattern is anchored so a
 * future block name typo doesn't silently pass through unconverted.
 */
public final class UboRewriter {

    /**
     * Matches the block header up to and including its opening brace.
     * Group 0 covers everything from {@code layout} through the brace; the
     * matcher's {@code end()} is the byte just past the brace, where the
     * member list begins.
     */
    private static final Pattern BLOCK_HEADER = Pattern.compile(
            "layout\\s*\\(\\s*std140\\s*\\)\\s*uniform\\s+(?:GlowUniforms|GalliumGuiGlow)\\s*\\{",
            Pattern.DOTALL
    );

    private UboRewriter() {}

    /**
     * Rewrites every matched UBO block in {@code source} into individual
     * {@code uniform} declarations. Returns {@code source} unchanged if no
     * block is found, or partially-rewritten if a block is unbalanced
     * (mid-source content past the unbalanced point is left verbatim so the
     * driver's compile error reflects the original source location).
     */
    public static String rewrite(String source) {
        Matcher m = BLOCK_HEADER.matcher(source);
        if (!m.find()) return source;

        StringBuilder out = new StringBuilder();
        int cursor = 0;
        do {
            int blockStart = m.start();
            int bodyStart = m.end();
            int bodyEnd = findMatchingClose(source, bodyStart);
            if (bodyEnd < 0) {
                // Unbalanced — bail and emit the rest verbatim.
                out.append(source, cursor, source.length());
                return out.toString();
            }
            out.append(source, cursor, blockStart);
            appendRewrittenBody(source, bodyStart, bodyEnd, out);
            cursor = bodyEnd + 1; // step past the closing brace
        } while (m.find(cursor));
        out.append(source, cursor, source.length());
        return out.toString();
    }

    /**
     * Returns the index of the closing brace that matches the implicit
     * opening brace just before {@code from}, or {@code -1} if unbalanced.
     * Skips line comments and block comments so brace characters inside
     * them don't perturb the depth count.
     */
    private static int findMatchingClose(String s, int from) {
        int depth = 1;
        int i = from;
        int n = s.length();
        while (i < n) {
            char c = s.charAt(i);
            if (c == '/' && i + 1 < n) {
                char next = s.charAt(i + 1);
                if (next == '/') {
                    int nl = s.indexOf('\n', i + 2);
                    i = (nl < 0) ? n : nl + 1;
                    continue;
                }
                if (next == '*') {
                    int end = s.indexOf("*/", i + 2);
                    i = (end < 0) ? n : end + 2;
                    continue;
                }
            }
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                if (--depth == 0) return i;
            }
            i++;
        }
        return -1;
    }

    /**
     * Splits the body in {@code [from, to)} on top-level semicolons (depth-0,
     * outside of comments) and emits each non-empty piece as a
     * {@code uniform <piece>} line terminated with a semicolon. Comments are
     * passed through verbatim within the piece so authoring metadata
     * (inline doc, default-value hints) survives the rewrite.
     */
    private static void appendRewrittenBody(String src, int from, int to, StringBuilder out) {
        int memberStart = from;
        int i = from;
        int depth = 0;
        while (i < to) {
            char c = src.charAt(i);
            if (c == '/' && i + 1 < to) {
                char next = src.charAt(i + 1);
                if (next == '/') {
                    int nl = src.indexOf('\n', i + 2);
                    i = (nl < 0 || nl >= to) ? to : nl + 1;
                    continue;
                }
                if (next == '*') {
                    int end = src.indexOf("*/", i + 2);
                    i = (end < 0 || end + 2 > to) ? to : end + 2;
                    continue;
                }
            }
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
            } else if (c == ';' && depth == 0) {
                emitMember(src, memberStart, i, out);
                memberStart = i + 1;
            }
            i++;
        }
        // Pack author may omit the trailing semicolon on the last member —
        // still emit it (with an explicit semicolon) rather than dropping
        // it silently. Pure-whitespace or comment-only trailing content
        // stays out of the output.
        emitMember(src, memberStart, to, out);
    }

    private static void emitMember(String src, int from, int to, StringBuilder out) {
        String piece = src.substring(from, to).trim();
        if (piece.isEmpty()) return;
        out.append("uniform ").append(piece).append(";\n");
    }
}
