package cn.spectra.gallium.glowoutline.capture;

import org.joml.Matrix4fc;
import org.joml.Vector2f;
import org.jspecify.annotations.Nullable;

/** Converts a view-plane world length to final-output UV length at an item's origin. */
final class ItemProjection {
    private ItemProjection() {}

    static void worldToUv(@Nullable Matrix4fc pose, @Nullable Matrix4fc modelView,
                          @Nullable Matrix4fc projection, Vector2f result) {
        result.zero();
        if (pose == null || modelView == null || projection == null) return;
        double x = pose.m30(), y = pose.m31(), z = pose.m32(), w = pose.m33();
        double viewX = modelView.m00() * x + modelView.m10() * y + modelView.m20() * z + modelView.m30() * w;
        double viewY = modelView.m01() * x + modelView.m11() * y + modelView.m21() * z + modelView.m31() * w;
        double viewZ = modelView.m02() * x + modelView.m12() * y + modelView.m22() * z + modelView.m32() * w;
        double viewW = modelView.m03() * x + modelView.m13() * y + modelView.m23() * z + modelView.m33() * w;
        double clipW = projection.m03() * viewX + projection.m13() * viewY
                + projection.m23() * viewZ + projection.m33() * viewW;
        if (!(clipW > 0.0) || !Double.isFinite(clipW)) return;
        // Camera projection maps NDC [-1, 1] to UV [0, 1]. Use clip W, not radial
        // distance: moving sideways at the same view depth must not shrink an item.
        // This also handles reverse Z, FOV/zoom, aspect ratio, and orthographic views
        // without assuming a near plane or the shader pack's internal resolution.
        float scaleX = (float) (Math.abs(projection.m00()) / (2.0 * clipW));
        float scaleY = (float) (Math.abs(projection.m11()) / (2.0 * clipW));
        if (scaleX > 0.0f && scaleY > 0.0f && Float.isFinite(scaleX) && Float.isFinite(scaleY)) {
            result.set(scaleX, scaleY);
        }
    }
}
