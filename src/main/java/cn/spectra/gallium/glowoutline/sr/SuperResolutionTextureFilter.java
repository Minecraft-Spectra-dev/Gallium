package cn.spectra.gallium.glowoutline.sr;

/** Repairs SR 0.9.1's mipmapped MAG filter without changing its MIN filter or mip levels. */
public final class SuperResolutionTextureFilter {
    private SuperResolutionTextureFilter() {}

    public static int legalParameter(int parameter, int value) {
        if (parameter != 0x2800) return value; // GL_TEXTURE_MAG_FILTER
        return switch (value) {
            case 0x2700, 0x2702 -> 0x2600; // NEAREST_MIPMAP_* -> NEAREST
            case 0x2701, 0x2703 -> 0x2601; // LINEAR_MIPMAP_* -> LINEAR
            default -> value;
        };
    }
}
