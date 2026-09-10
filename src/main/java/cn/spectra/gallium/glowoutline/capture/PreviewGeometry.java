package cn.spectra.gallium.glowoutline.capture;

import org.joml.Matrix4f;
import org.joml.Matrix4fc;

/** Coordinate/depth rules for a GUI entity's own framebuffer, independent of the world camera. */
public final class PreviewGeometry {
    private PreviewGeometry() {}

    public record Region(int x, int y, int width, int height) {
        public boolean valid() { return width > 0 && height > 0; }
    }

    public static Region clip(int x, int y, int width, int height, int screenWidth, int screenHeight) {
        int left = Math.max(0, x), bottom = Math.max(0, y);
        int right = (int) Math.min(screenWidth, (long) x + Math.max(0, width));
        int top = (int) Math.min(screenHeight, (long) y + Math.max(0, height));
        return new Region(left, bottom, Math.max(0, right - left), Math.max(0, top - bottom));
    }

    /** Crop an existing projection; never replace its Z mapping or the caller's model transform. */
    public static Matrix4f crop(Matrix4fc projection, Region region, int screenWidth, int screenHeight) {
        if (!region.valid()) throw new IllegalArgumentException("Empty preview region");
        return new Matrix4f().scaling((float) screenWidth / region.width(),
                        (float) screenHeight / region.height(), 1.0f)
                .m30((float) (screenWidth - 2.0 * region.x() - region.width()) / region.width())
                .m31((float) (screenHeight - 2.0 * region.y() - region.height()) / region.height())
                .mul(projection);
    }

    /** GUI entity projections are orthographic; Iris's forward-Z conversion changes this sign. */
    public static boolean reverseDepth(Matrix4fc projection) {
        return projection != null && Float.isFinite(projection.m22()) && projection.m22() > 0.0f;
    }
}
