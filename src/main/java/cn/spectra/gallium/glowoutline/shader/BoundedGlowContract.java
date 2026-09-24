package cn.spectra.gallium.glowoutline.shader;

import cn.spectra.gallium.glowoutline.capture.ProjectedMaskBounds;

/** Internal finite-support and blend properties established by engine verification. */
public record BoundedGlowContract(float worldRadius, float fallbackPixels, float paddingPixels,
                                  float constantAlpha, boolean atlasStorage, boolean instanceUniforms, boolean alphaDepthOnly) {
    public record Rectangle(int x, int y, int width, int height) {}

    public BoundedGlowContract(float worldRadius, float fallbackPixels, float paddingPixels, float constantAlpha) {
        this(worldRadius, fallbackPixels, paddingPixels, constantAlpha, false, false, false);
    }

    public BoundedGlowContract(float worldRadius, float fallbackPixels, float paddingPixels, float constantAlpha, boolean atlasStorage) {
        this(worldRadius, fallbackPixels, paddingPixels, constantAlpha, atlasStorage, false, false);
    }

    /** Current fast path is only used for one-to-one, unshifted physical mask coordinates. */
    public Rectangle rectangle(ProjectedMaskBounds bounds, int width, int height, float worldX, float worldY) {
        if (!bounds.valid() || width <= 0 || height <= 0 || !Float.isFinite(worldX) || !Float.isFinite(worldY)) return null;
        double radiusX = fallbackPixels, radiusY = fallbackPixels;
        if (worldX > 0 && worldY > 0) {
            radiusX = (double) worldX * worldRadius * width;
            radiusY = (double) worldY * worldRadius * height;
        }
        // Extra two output pixels cover float rounding of radii and quad interpolation.
        radiusX += paddingPixels + 2; radiusY += paddingPixels + 2;
        int x0 = edge(Math.floor(bounds.minX() - radiusX), width);
        int x1 = edge(Math.ceil(bounds.maxX() + radiusX), width);
        int y0 = edge(Math.floor(bounds.minY() - radiusY), height);
        int y1 = edge(Math.ceil(bounds.maxY() + radiusY), height);
        return new Rectangle(x0, y0, Math.max(0, x1 - x0), Math.max(0, y1 - y0));
    }

    /**
     * Verified original shader only: map physical mask texels through its active-grid
     * sampling transform before expanding by the outline radius in output pixels.
     * Mask and output textures must have the same physical dimensions.
     */
    public Rectangle mappedRectangle(ProjectedMaskBounds bounds, int width, int height,
                                     float worldX, float worldY, float scaleX, float scaleY,
                                     float offsetX, float offsetY) {
        if (!bounds.valid() || width <= 0 || height <= 0
                || !Float.isFinite(worldX) || !Float.isFinite(worldY)
                || !validScale(scaleX) || !validScale(scaleY)
                || !Float.isFinite(offsetX) || !Float.isFinite(offsetY)
                || Math.abs(offsetX) > 1 || Math.abs(offsetY) > 1) return null;
        double radiusX = fallbackPixels, radiusY = fallbackPixels;
        if (worldX > 0 && worldY > 0) {
            radiusX = (double) worldX * worldRadius * width;
            radiusY = (double) worldY * worldRadius * height;
        }
        radiusX += paddingPixels + 2; radiusY += paddingPixels + 2;
        int x0 = edge(Math.floor(mappedEdge(bounds.minX(), width, scaleX, offsetX, false) - radiusX), width);
        int x1 = edge(Math.ceil(mappedEdge(bounds.maxX(), width, scaleX, offsetX, true) + radiusX), width);
        int y0 = edge(Math.floor(mappedEdge(bounds.minY(), height, scaleY, offsetY, false) - radiusY), height);
        int y1 = edge(Math.ceil(mappedEdge(bounds.maxY(), height, scaleY, offsetY, true) + radiusY), height);
        return new Rectangle(x0, y0, Math.max(0, x1 - x0), Math.max(0, y1 - y0));
    }

    private static boolean validScale(float scale) {
        return Float.isFinite(scale) && scale > 0 && scale <= 1;
    }

    private static double mappedEdge(float bound, int size, float scale, float offset, boolean high) {
        // GLSL round can choose either integer at a tie. Enclose both neighbouring
        // active sizes, and include a physical texel for bilinear reconstruction.
        float scaled = size * scale;
        double lower = Math.max(1, Math.min(size, Math.floor(scaled)));
        double upper = Math.max(1, Math.min(size, Math.ceil(scaled)));
        double numerator = (bound + (high ? 1.0 : -1.0) - (double) offset * size) * size;
        return high ? Math.max(numerator / lower, numerator / upper)
                : Math.min(numerator / lower, numerator / upper);
    }

    private static int edge(double value, int size) { return (int) Math.max(0, Math.min(size, value)); }
}
