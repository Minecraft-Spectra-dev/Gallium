package cn.spectra.gallium.glowoutline.shader;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import org.joml.Matrix4f;
import org.joml.Vector4f;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class OutlineDepthBoundsTest {
    @Test void boundsFloatProjectionAndTriangleInteriorDespiteCancellation() {
        var projection = new Matrix4f().perspective((float) Math.toRadians(90), 16f / 9, .05f, 1024);
        for (float origin : new float[]{0, 1024, 100_000}) {
            var view = new Matrix4f().translation(-origin, 0, origin);
            var combined = new Matrix4f(projection).mul(view);
            var vertices = ByteBuffer.allocate(36).order(ByteOrder.nativeOrder());
            float[][] positions = {{origin - 2, -1, -origin - 12},
                    {origin + 3, -1, -origin - 20}, {origin, 4, -origin - 16}};
            for (var position : positions) for (float value : position) vertices.putFloat(value);
            vertices.flip();
            float lower = OutlineDepthBounds.minimum(vertices, 12, 3, 0, view, projection);
            assertTrue(Float.isFinite(lower));
            for (int i = 0; i <= 16; i++) for (int j = 0; j <= 16 - i; j++) {
                float a = i / 16f, b = j / 16f, c = 1 - a - b;
                var point = new Vector4f(
                        a * positions[0][0] + b * positions[1][0] + c * positions[2][0],
                        a * positions[0][1] + b * positions[1][1] + c * positions[2][1],
                        a * positions[0][2] + b * positions[1][2] + c * positions[2][2], 1);
                var separate = new Vector4f(point).mul(view).mul(projection);
                var multiplied = new Vector4f(point).mul(combined);
                assertTrue(lower <= .5f + .5f * separate.z / separate.w);
                assertTrue(lower <= .5f + .5f * multiplied.z / multiplied.w);
            }
        }
    }

    @Test void refusesInvalidRangesAndAnEyePlaneCrossing() {
        var vertices = ByteBuffer.allocate(12).order(ByteOrder.nativeOrder());
        vertices.putFloat(0).putFloat(0).putFloat(1).flip();
        var projection = new Matrix4f().perspective(1, 1, .05f, 128);
        assertTrue(Float.isNaN(OutlineDepthBounds.minimum(vertices, 12, 1, 0, new Matrix4f(), projection)));
        assertTrue(Float.isNaN(OutlineDepthBounds.minimum(vertices, 12, 2, 0, new Matrix4f(), projection)));
        assertTrue(Float.isNaN(OutlineDepthBounds.minimum(vertices, 12, 1, 1, new Matrix4f(), projection)));
        assertTrue(Float.isNaN(OutlineDepthBounds.minimum(vertices, 12, 0, 0, new Matrix4f(), projection)));
    }
}
