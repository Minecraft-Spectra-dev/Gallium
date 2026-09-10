package cn.spectra.gallium.glowoutline.capture;

import org.joml.Matrix4f;
import org.joml.Vector4f;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PreviewGeometryTest {
    @Test void croppedProjectionPreservesScreenPositionsAndDepth() {
        var source = new Matrix4f().setOrtho(0, 1000, 800, 0, 1000, 21000);
        var region = new PreviewGeometry.Region(200, 300, 100, 200);
        var cropped = PreviewGeometry.crop(source, region, 1000, 800);
        var topLeft = cropped.transform(new Vector4f(200, 300, -11000, 1));
        var bottomRight = cropped.transform(new Vector4f(300, 500, -11000, 1));
        assertEquals(-1, topLeft.x, 1e-5);
        assertEquals(1, topLeft.y, 1e-5);
        assertEquals(1, bottomRight.x, 1e-5);
        assertEquals(-1, bottomRight.y, 1e-5);
        assertEquals(source.transform(new Vector4f(200, 300, -11000, 1)).z, topLeft.z, 1e-6);
    }

    @Test void clippingHandlesPartiallyOffscreenAndOverflowingRectangles() {
        assertEquals(new PreviewGeometry.Region(0, 0, 30, 40), PreviewGeometry.clip(-10, -20, 40, 60, 100, 100));
        assertEquals(new PreviewGeometry.Region(80, 90, 20, 10),
                PreviewGeometry.clip(80, 90, Integer.MAX_VALUE, Integer.MAX_VALUE, 100, 100));
        assertFalse(PreviewGeometry.clip(200, 200, 10, 10, 100, 100).valid());
        assertThrows(IllegalArgumentException.class, () -> PreviewGeometry.crop(new Matrix4f(),
                new PreviewGeometry.Region(0, 0, 0, 1), 100, 100));
    }

    @Test void depthDirectionComesFromTheActualOrthoProjection() {
        assertFalse(PreviewGeometry.reverseDepth(new Matrix4f().setOrtho(-1, 1, -1, 1, -1000, 1000)));
        assertTrue(PreviewGeometry.reverseDepth(new Matrix4f().setOrtho(-1, 1, -1, 1, 1000, -1000, true)));
        assertFalse(PreviewGeometry.reverseDepth(new Matrix4f().m22(Float.NaN)));
    }
}
