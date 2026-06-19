package cn.spectra.gallium.glowoutline.shader;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link UboRewriter}. Covers the simple case the original mixin
 * regex handled, plus the nested-brace and comment cases that the previous
 * {@code [^}]*} pattern would have mangled. Pack authors are not Gallium
 * developers — their shaders may legitimately contain block comments with
 * stray braces or {@code ;} characters, and the rewriter must survive all
 * of them.
 */
class UboRewriterTest {

    @Test
    void noBlock_returnsSourceUnchanged() {
        String src = "#version 150\nuniform float foo;\nvoid main() {}";
        assertEquals(src, UboRewriter.rewrite(src));
    }

    @Test
    void simpleGlowUniformsBlock_rewritten() {
        String src = "#version 150\n"
                + "layout(std140) uniform GlowUniforms {\n"
                + "    float Intensity;\n"
                + "    vec2 ScreenSize;\n"
                + "};\n"
                + "void main() {}";
        String out = UboRewriter.rewrite(src);
        assertTrue(out.contains("uniform float Intensity;"), out);
        assertTrue(out.contains("uniform vec2 ScreenSize;"), out);
        // The original UBO declaration must be gone.
        assertTrue(!out.contains("layout(std140)"), out);
        assertTrue(!out.contains("GlowUniforms"), out);
    }

    @Test
    void galliumGuiGlowBlock_rewritten() {
        String src = "layout(std140) uniform GalliumGuiGlow { vec4 InnerColor; };";
        String out = UboRewriter.rewrite(src);
        assertTrue(out.contains("uniform vec4 InnerColor;"), out);
    }

    @Test
    void unrelatedBlockName_passesThrough() {
        // Block names other than GlowUniforms / GalliumGuiGlow MUST be left alone —
        // this is a positive: a typo in the rewriter would otherwise convert random
        // pack UBOs that happen to share the std140 layout, breaking the pack.
        String src = "layout(std140) uniform SomePackBlock { float foo; };";
        assertEquals(src, UboRewriter.rewrite(src));
    }

    @Test
    void blockCommentInsideBody_withStrayClosingBrace_doesNotEndBlockEarly() {
        // The classic [^}]*} regex bug: a `}` inside a comment ends the block
        // prematurely, leaving the rest of the members raw. With brace-balanced
        // scanning + comment skipping, the body is fully captured — both members
        // and the comment-with-braces are preserved.
        String src = "layout(std140) uniform GlowUniforms {\n"
                + "    /* todo: see also { related stuff } */\n"
                + "    float Intensity;\n"
                + "    vec2 ScreenSize;\n"
                + "};\n";
        String out = UboRewriter.rewrite(src);
        // Both members surfaced as uniforms (the first carries its leading
        // comment as part of the member text, which is valid GLSL).
        assertTrue(out.contains("float Intensity;"), out);
        assertTrue(out.contains("uniform vec2 ScreenSize;"), out);
        assertTrue(out.contains("/* todo: see also { related stuff } */"), out);
        assertTrue(!out.contains("layout(std140)"), "block header should be gone: " + out);
        assertTrue(!out.contains("GlowUniforms"), "block name should be gone: " + out);
    }

    @Test
    void blockCommentWithSemicolon_doesNotFragmentMember() {
        // A `;` inside a block comment must NOT split a single member into
        // two `uniform` declarations.
        String src = "layout(std140) uniform GlowUniforms {\n"
                + "    /* default: 1.0; max: 8.0 */\n"
                + "    float Intensity;\n"
                + "};\n";
        String out = UboRewriter.rewrite(src);
        // Exactly one occurrence of "float Intensity;" — the comment-with-`;`
        // didn't fragment the member into multiple `uniform` lines.
        int firstIntensity = out.indexOf("float Intensity;");
        assertTrue(firstIntensity >= 0, "expected member: " + out);
        assertEquals(-1, out.indexOf("float Intensity;", firstIntensity + 1),
                "member should appear exactly once: " + out);
        // Comment text is preserved as part of the member's leading whitespace.
        assertTrue(out.contains("/* default: 1.0; max: 8.0 */"), out);
        // No spurious `uniform`s synthesised from comment fragments — the comment
        // contents (`default`, `max`) must not appear as standalone uniforms.
        assertTrue(!out.contains("uniform default"), out);
        assertTrue(!out.contains("uniform max"), out);
        assertTrue(!out.contains("uniform 1.0"), out);
    }

    @Test
    void lineCommentWithStrayBrace_doesNotEndBlockEarly() {
        String src = "layout(std140) uniform GlowUniforms {\n"
                + "    // TODO: } experiment\n"
                + "    float Intensity;\n"
                + "};\n";
        String out = UboRewriter.rewrite(src);
        assertTrue(out.contains("float Intensity;"), out);
        assertTrue(!out.contains("GlowUniforms"), out);
    }

    @Test
    void multipleBlocks_bothRewritten() {
        String src = "layout(std140) uniform GlowUniforms { float A; };\n"
                + "void inert() {}\n"
                + "layout(std140) uniform GalliumGuiGlow { vec2 B; };\n";
        String out = UboRewriter.rewrite(src);
        assertTrue(out.contains("uniform float A;"), out);
        assertTrue(out.contains("uniform vec2 B;"), out);
        assertTrue(!out.contains("GlowUniforms"), out);
        assertTrue(!out.contains("GalliumGuiGlow"), out);
    }

    @Test
    void unbalancedBlock_emitsRestVerbatim_doesNotInfinitelyLoop() {
        // Pack authors may ship malformed shaders — the rewriter must not loop or
        // throw. Best behaviour: leave the unbalanced source verbatim so the GLSL
        // compile error surfaces the original location.
        String src = "layout(std140) uniform GlowUniforms { float A;\n"
                + "// missing closing brace\n";
        String out = UboRewriter.rewrite(src);
        // Source past the unbalanced point is preserved.
        assertTrue(out.contains("missing closing brace"), out);
    }

    @Test
    void memberWithoutTrailingSemicolon_stillEmittedWithSemicolon() {
        // Tolerate slightly malformed blocks — emit a properly-terminated `uniform`
        // line rather than dropping the last member silently.
        String src = "layout(std140) uniform GlowUniforms {\n"
                + "    float Intensity\n"  // no trailing `;`
                + "};\n";
        String out = UboRewriter.rewrite(src);
        assertTrue(out.contains("uniform float Intensity;"), out);
    }
}
