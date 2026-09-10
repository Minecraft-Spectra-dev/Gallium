package cn.spectra.gallium.glowoutline.capture;

import com.mojang.blaze3d.vertex.PoseStack;
import java.lang.reflect.Field;
import org.joml.Matrix4f;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ItemDistanceTest {
    @Test
    void cameraRelativeDistanceIgnoresLocalModelRotationAndScale() {
        Matrix4f pose = new Matrix4f().translation(3, 4, -12)
                .rotateY(0.8f).scale(0.5f, 2.0f, 0.2f);
        assertEquals(13.0f, GlowCaptureManager.itemDistance(pose, false));
        assertEquals(13.0f, GlowCaptureManager.itemDistance(
                new Matrix4f().rotationY(1.2f).mul(pose), false), 0.00001f);
    }

    @Test
    void firstPersonAndInvalidMetadataPreserveTheOriginalEffect() {
        assertEquals(0.0f, GlowCaptureManager.itemDistance(new Matrix4f().translation(0, 0, -100), true));
        assertEquals(0.0f, GlowCaptureManager.itemDistance(new Matrix4f().m30(Float.NaN), false));
        assertEquals(0.0f, GlowCaptureManager.itemDistance(new Matrix4f().m32(Float.POSITIVE_INFINITY), false));
        assertEquals(0.0f, GlowCaptureManager.itemDistance(null, false));
    }

    @Test
    void pooledStateDoesNotRetainThePreviousItemsDistance() {
        GlowCaptureState state = new GlowCaptureState();
        state.itemDistance = 90.0f;
        state.itemWorldToUv.set(1, 1);
        state.resetFrame();
        assertEquals(0.0f, state.itemDistance);
        assertEquals(0.0f, state.itemWorldToUv.lengthSquared());
        state.itemDistance = 90.0f;
        state.itemWorldToUv.set(1, 1);
        state.beginCaptureLifecycle(12, true);
        assertEquals(0.0f, state.itemDistance);
        assertEquals(0.0f, state.itemWorldToUv.lengthSquared());
    }

    @Test
    void skippedNestedCaptureCannotChangeItsParentsDistance() throws Exception {
        Field current = GlowCaptureManager.class.getDeclaredField("currentCapture");
        current.setAccessible(true);
        Object previous = current.get(null);
        GlowCaptureState parent = new GlowCaptureState();
        parent.capturedProjectionMatrix4f.setPerspective((float) Math.toRadians(70), 1, 0.05f, 1000);
        parent.capturedProjectionMatrix4fValid = true;
        parent.capturedModelViewMatrix = new Matrix4f();
        parent.capturedModelViewMatrixValid = true;
        PoseStack pose = new PoseStack();
        pose.translate(0, 0, -64);
        GlowCaptureManager.beginItemCaptureScope();
        try {
            current.set(null, parent);
            GlowCaptureManager.markItemCaptureScopeStarted();
            GlowCaptureManager.captureItemView(pose);
            assertEquals(64.0f, parent.itemDistance);
            float parentScale = parent.itemWorldToUv.x;
            org.junit.jupiter.api.Assertions.assertTrue(parentScale > 0);
            GlowCaptureManager.beginItemCaptureScope();
            try {
                pose.translate(0, 0, -64);
                GlowCaptureManager.captureItemView(pose);
                assertEquals(64.0f, parent.itemDistance);
                assertEquals(parentScale, parent.itemWorldToUv.x);
            } finally {
                GlowCaptureManager.endItemCapture();
            }
            parent.firstPerson = true;
            GlowCaptureManager.captureItemView(pose);
            assertEquals(0.0f, parent.itemDistance);
            assertEquals(0.0f, parent.itemWorldToUv.lengthSquared());
        } finally {
            GlowCaptureManager.endItemCapture();
            current.set(null, previous);
        }
    }
}
