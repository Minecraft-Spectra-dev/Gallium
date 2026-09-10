package cn.spectra.gallium.glowoutline.capture;

import org.joml.Matrix4f;
import org.joml.Vector2f;
import org.joml.Vector4f;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ItemProjectionTest {
    private static Matrix4f perspective() {
        return new Matrix4f().perspective((float) Math.toRadians(70), 16.0f / 9.0f, 0.05f, 1000);
    }

    private static Vector2f scale(Matrix4f pose, Matrix4f view, Matrix4f projection) {
        Vector2f result = new Vector2f();
        ItemProjection.worldToUv(pose, view, projection, result);
        return result;
    }

    @Test
    void matchesActualProjectionOfViewPlaneOffsets() {
        Matrix4f pose = new Matrix4f().translation(3, 2, -12).rotateX(0.4f).scale(0.5f);
        Matrix4f view = new Matrix4f().rotationY(0.3f);
        Matrix4f projection = perspective();
        Vector2f result = scale(pose, view, projection);
        Vector4f origin = view.transform(pose.transform(new Vector4f(0, 0, 0, 1)));
        Vector4f right = projection.transform(new Vector4f(origin).add(1, 0, 0, 0));
        Vector4f up = projection.transform(new Vector4f(origin).add(0, 1, 0, 0));
        projection.transform(origin);
        assertEquals((right.x / right.w - origin.x / origin.w) * 0.5f, result.x, 0.000001f);
        assertEquals((up.y / up.w - origin.y / origin.w) * 0.5f, result.y, 0.000001f);
    }

    @Test
    void doublingViewDepthHalvesBothAxesWithoutAThresholdOrPixelFloor() {
        Vector2f atTwo = scale(new Matrix4f().translation(0, 0, -2), new Matrix4f(), perspective());
        for (int depth : new int[]{4, 8, 16, 32, 64, 128, 1024}) {
            Vector2f actual = scale(new Matrix4f().translation(0, 0, -depth), new Matrix4f(), perspective());
            assertEquals(atTwo.x * 2.0f / depth, actual.x, 0.0000001f);
            assertEquals(atTwo.y * 2.0f / depth, actual.y, 0.0000001f);
        }
    }

    @Test
    void lateralMovementDoesNotApplyRadialDistanceFalloff() {
        Vector2f center = scale(new Matrix4f().translation(0, 0, -8), new Matrix4f(), perspective());
        Vector2f side = scale(new Matrix4f().translation(10, 5, -8), new Matrix4f(), perspective());
        assertEquals(center, side);
    }

    @Test
    void cameraRotationIsAppliedBeforeCalculatingClipW() {
        Vector2f forward = scale(new Matrix4f().translation(0, 0, -8), new Matrix4f(), perspective());
        Vector2f rotated = scale(new Matrix4f().translation(8, 0, 0),
                new Matrix4f().rotationY((float) (Math.PI / 2)), perspective());
        assertEquals(forward.x, rotated.x, 0.000001f);
        assertEquals(forward.y, rotated.y, 0.000001f);
    }

    @Test
    void zoomAndAspectRatioFollowTheProjection() {
        Matrix4f projection = perspective();
        Matrix4f pose = new Matrix4f().translation(0, 0, -8);
        Vector2f base = scale(pose, new Matrix4f(), projection);
        projection.m00(projection.m00() * 2).m11(projection.m11() * 2);
        Vector2f zoom = scale(pose, new Matrix4f(), projection);
        assertEquals(base.x * 2, zoom.x);
        assertEquals(base.y * 2, zoom.y);
        projection.m00(projection.m00() / 2);
        Vector2f wider = scale(pose, new Matrix4f(), projection);
        assertEquals(base.x, wider.x);
        assertEquals(zoom.y, wider.y);
    }

    @Test
    void depthConventionAndTemporalOffsetDoNotChangeWorldThickness() {
        Matrix4f projection = perspective();
        Matrix4f pose = new Matrix4f().translation(1, 2, -8);
        Vector2f base = scale(pose, new Matrix4f(), projection);
        projection.m22(-projection.m22()).m32(-projection.m32()).m20(0.001f).m21(-0.002f);
        assertEquals(base, scale(pose, new Matrix4f(), projection));
    }

    @Test
    void orthographicProjectionKeepsConstantWorldThickness() {
        Matrix4f projection = new Matrix4f().ortho(-4, 4, -3, 3, 0.05f, 1000);
        assertEquals(scale(new Matrix4f().translation(0, 0, -2), new Matrix4f(), projection),
                scale(new Matrix4f().translation(0, 0, -32), new Matrix4f(), projection));
    }

    @Test
    void invalidOrBehindCameraInputsClearPreviousMetadata() {
        Vector2f result = new Vector2f(1, 1);
        ItemProjection.worldToUv(null, new Matrix4f(), perspective(), result);
        assertEquals(new Vector2f(), result);
        for (Matrix4f pose : new Matrix4f[]{new Matrix4f(), new Matrix4f().translation(0, 0, 2),
                new Matrix4f().m32(Float.NaN), new Matrix4f().m32(Float.NEGATIVE_INFINITY)}) {
            result.set(1, 1);
            ItemProjection.worldToUv(pose, new Matrix4f(), perspective(), result);
            assertEquals(new Vector2f(), result);
        }
        result.set(1, 1);
        ItemProjection.worldToUv(new Matrix4f().translation(0, 0, -2), new Matrix4f(),
                perspective().m11(Float.NaN), result);
        assertEquals(new Vector2f(), result);
    }
}
