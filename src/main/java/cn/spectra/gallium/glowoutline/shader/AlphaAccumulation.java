package cn.spectra.gallium.glowoutline.shader;

/** Conservative saturation proof for repeated nonnegative additions to an UNORM8 attachment. */
final class AlphaAccumulation {
    private AlphaAccumulation() {}
    static boolean saturatesUnorm8(float alpha, int draws) {
        if (!Float.isFinite(alpha) || alpha < 0 || alpha > 1 || draws <= 0) return false;
        return alpha == 1 || (alpha - 1.0 / 255.0) * draws > 1.0 + 1.0 / 65536.0;
    }
}
