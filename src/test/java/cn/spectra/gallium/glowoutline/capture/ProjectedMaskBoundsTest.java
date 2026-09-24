package cn.spectra.gallium.glowoutline.capture;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Random;
import org.joml.Matrix4f;
import org.joml.Vector4f;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ProjectedMaskBoundsTest {
    private static ByteBuffer vertices(float... values) {
        var buffer = ByteBuffer.allocate(values.length * 4).order(ByteOrder.nativeOrder());
        for (float value : values) buffer.putFloat(value);
        return buffer.flip();
    }

    @Test void coversTheFullMeshAndUnionsMultipleLayers() {
        var bounds = new ProjectedMaskBounds();
        bounds.begin(640, 480);
        bounds.include(vertices(-.5f,-.5f,0, .5f,.5f,0),12,2,0,new Matrix4f(),new Matrix4f());
        assertTrue(bounds.valid());
        assertTrue(bounds.minX() < 160 && bounds.maxX() > 480);
        assertTrue(bounds.minY() < 120 && bounds.maxY() > 360);
        bounds.include(vertices(.8f,.8f,0),12,1,0,new Matrix4f(),new Matrix4f());
        assertTrue(bounds.minX() < 160 && bounds.maxX() > 576);
    }

    @Test void rejectsNearPlaneCrossingNonFiniteDataAndBadLayouts() {
        var bounds = new ProjectedMaskBounds();
        var perspective = new Matrix4f().perspective(1.2f, 1.5f, .05f, 1000);
        bounds.begin(1920,1080);
        bounds.include(vertices(0,0,-1, 0,0,1),12,2,0,new Matrix4f(),perspective);
        assertFalse(bounds.valid());
        assertEquals(0, bounds.maxX());
        bounds.begin(1920,1080);
        bounds.include(vertices(Float.NaN,0,-1),12,1,0,new Matrix4f(),perspective);
        assertFalse(bounds.valid());
        bounds.begin(1920,1080);
        bounds.include(vertices(0,0,-1),8,1,0,new Matrix4f(),perspective);
        assertFalse(bounds.valid());
    }

    @Test void containsFloatProjectionResultsAcrossMeshTransforms() {
        var random = new Random(43193);
        var bounds = new ProjectedMaskBounds();
        var projection = new Matrix4f().perspective(1.6f, 1.87f, .05f, 1000);
        for (int scene=0;scene<200;scene++) {
            var model = new Matrix4f().translation(random.nextFloat()*8-4, random.nextFloat()*6-3,
                    -5-random.nextFloat()*100).rotateXYZ(random.nextFloat(),random.nextFloat(),random.nextFloat());
            var data = ByteBuffer.allocate(64*20).order(ByteOrder.nativeOrder());
            for (int i=0;i<64;i++) data.putInt(17).putFloat(random.nextFloat()*2-1)
                    .putFloat(random.nextFloat()*2-1).putFloat(random.nextFloat()*2-1).putInt(23);
            data.flip(); bounds.begin(3840,2054);
            bounds.include(data,20,64,4,model,projection);
            assertTrue(bounds.valid());
            for (int i=0;i<64;i++) {
                int offset=i*20+4;
                var p=new Vector4f(data.getFloat(offset),data.getFloat(offset+4),data.getFloat(offset+8),1);
                model.transform(p);projection.transform(p);
                float x=(p.x/p.w*.5f+.5f)*3840, y=(p.y/p.w*.5f+.5f)*2054;
                assertTrue(x>=bounds.minX() && x<=bounds.maxX() && y>=bounds.minY() && y<=bounds.maxY());
            }
        }
    }

    @Test void refinesABoxThatCrossesTheCameraWhenTheActualMeshDoesNot() {
        var bounds = new ProjectedMaskBounds();
        bounds.begin(1920,1080);
        var model = new Matrix4f().translation(0,0,-.5f).rotateY((float)Math.PI/4);
        var projection = new Matrix4f().perspective(1.2f, 1.5f, .05f, 1000);
        bounds.include(vertices(-1,0,-1, 1,0,1),12,2,0,model,projection);
        assertTrue(bounds.valid());
        assertTrue(bounds.minY() < 540 && bounds.maxY() > 540);
        assertTrue(bounds.maxY() - bounds.minY() < 10);
    }

    @Test void directSlicesAndReadOnlyViewsMatchHeapForBothProjectionPaths() {
        var random = new Random(838101);
        var projection = new Matrix4f().perspective(1.2f, 1.5f, .05f, 1000);
        var opposite = ByteOrder.nativeOrder() == ByteOrder.LITTLE_ENDIAN
                ? ByteOrder.BIG_ENDIAN : ByteOrder.LITTLE_ENDIAN;
        for (int scene = 0; scene < 100; scene++) {
            var model = new Matrix4f().translation(0, 0, scene % 2 == 0 ? -20 : -.6f)
                    .rotateXYZ(random.nextFloat(), random.nextFloat(), random.nextFloat());
            var heap = ByteBuffer.allocate(32 * 20 + 32).order(ByteOrder.nativeOrder());
            heap.position(12);
            for (int i = 0; i < 32; i++) heap.putInt(19).putFloat(random.nextFloat() * 2 - 1)
                    .putFloat(random.nextFloat() * 2 - 1).putFloat(random.nextFloat() * 2 - 1).putInt(29);
            heap.flip().position(12);
            var expected = new ProjectedMaskBounds();
            expected.begin(3840, 2054);
            expected.include(heap, 20, 32, 4, model, projection);
            for (var order : new ByteOrder[]{ByteOrder.nativeOrder(), opposite}) {
                var direct = ByteBuffer.allocateDirect(heap.capacity() + 16).order(order);
                direct.position(16);
                var slice = direct.slice().order(order);
                slice.position(12);
                for (int i = 12; i < heap.limit(); i += 4) slice.putFloat(heap.getFloat(i));
                slice.flip().position(12);
                var readOnly = slice.asReadOnlyBuffer().order(order);
                for (var view : new ByteBuffer[]{slice, readOnly}) {
                    var actual = new ProjectedMaskBounds();
                    actual.begin(3840, 2054);
                    actual.include(view, 20, 32, 4, model, projection);
                    assertEquals(expected.valid(), actual.valid());
                    assertEquals(expected.accepting(), actual.accepting());
                    assertEquals(expected.minX(), actual.minX());
                    assertEquals(expected.maxX(), actual.maxX());
                    assertEquals(expected.minY(), actual.minY());
                    assertEquals(expected.maxY(), actual.maxY());
                    assertEquals(12, view.position());
                    assertEquals(heap.limit(), view.limit());
                }
            }
        }
    }

    @Test void directInputMustPassFullRangeAndFiniteValueChecks() {
        var data = ByteBuffer.allocateDirect(64).order(ByteOrder.nativeOrder());
        data.putFloat(0).putFloat(0).putFloat(-1).flip();
        var bounds = new ProjectedMaskBounds();
        for (int count : new int[]{2, Integer.MAX_VALUE}) {
            bounds.begin(1920, 1080);
            bounds.include(data, 12, count, 0, new Matrix4f(), new Matrix4f());
            assertFalse(bounds.accepting());
        }
        bounds.begin(1920, 1080);
        data.putFloat(4, Float.POSITIVE_INFINITY);
        bounds.include(data.asReadOnlyBuffer().order(ByteOrder.nativeOrder()), 12, 1, 0,
                new Matrix4f(), new Matrix4f());
        assertFalse(bounds.valid());
    }

    @Test void repeatedMeshesKeepLayerUnionsAndReactToEveryInputChange() {
        var reused = new ProjectedMaskBounds();
        var model = new Matrix4f().translation(0, 0, -4);
        var projection = new Matrix4f().perspective(1.2f, 1.5f, .05f, 1000);
        var data = ByteBuffer.allocateDirect(256 * 20 + 16).order(ByteOrder.nativeOrder());
        var random = new Random(81296);
        data.position(8);
        for (int i = 0; i < 256; i++) data.putInt(19).putFloat(random.nextFloat() * 2 - 1)
                .putFloat(random.nextFloat() * 2 - 1).putFloat(random.nextFloat() * 2 - 1).putInt(23);
        data.flip().position(8);
        for (int frame = 0; frame < 100; frame++) {
            if (frame % 9 == 0) data.putFloat(12, random.nextFloat());
            if (frame % 11 == 0) model.rotateY(.02f);
            if (frame % 23 == 0) projection.perspective(1.2f, 1.5f, .05f, 1000);
            int width = frame % 13 < 9 ? 1920 : 3840;
            int stride = frame % 29 < 20 ? 20 : 24;
            int count = stride == 24 ? 200 : frame % 17 < 12 ? 256 : 255;
            int positionOffset = frame % 19 < 14 ? 4 : 8;
            var input = frame % 7 == 0 ? data.asReadOnlyBuffer().order(ByteOrder.BIG_ENDIAN) : data;
            var fresh = new ProjectedMaskBounds();
            for (var bounds : new ProjectedMaskBounds[]{reused, fresh}) {
                bounds.begin(width, 1080);
                bounds.include(input, stride, count, positionOffset, model, projection);
                if (frame % 3 == 0) bounds.include(vertices(2, 0, -2), 12, 1, 0, model, projection);
            }
            assertEquals(fresh.valid(), reused.valid());
            assertEquals(fresh.accepting(), reused.accepting());
            assertEquals(fresh.minX(), reused.minX()); assertEquals(fresh.minY(), reused.minY());
            assertEquals(fresh.maxX(), reused.maxX()); assertEquals(fresh.maxY(), reused.maxY());
            assertEquals(8, data.position());
        }
        reused.begin(1920, 1080);
        data.putFloat(12, Float.NaN);
        reused.include(data, 20, 256, 4, model, projection);
        assertFalse(reused.valid());
    }
    @Test void projectedUnionsCoverIndependentTransformsWithoutRetainingTheSource() {
        var projection = new Matrix4f().perspective(1.2f, 1.5f, .05f, 1000);
        var left = new ProjectedMaskBounds();
        var right = new ProjectedMaskBounds();
        left.begin(1920, 1080); right.begin(1920, 1080);
        left.include(vertices(-1, -1, 0, 1, 1, 0), 12, 2, 0,
                new Matrix4f().translation(-2, 0, -8), projection);
        right.include(vertices(-1, -1, 0, 1, 1, 0), 12, 2, 0,
                new Matrix4f().translation(2, 1, -6), projection);
        var result = new ProjectedMaskBounds(); result.begin(1920, 1080);
        result.include(left); result.include(right);
        assertTrue(result.valid());
        assertTrue(result.minX() <= left.minX() && result.maxX() >= right.maxX());
        assertTrue(result.minY() <= Math.min(left.minY(), right.minY()));
        assertTrue(result.maxY() >= Math.max(left.maxY(), right.maxY()));
        float x0=result.minX(), y0=result.minY(), x1=result.maxX(), y1=result.maxY();
        left.begin(1, 1); right.invalidate();
        assertTrue(result.valid());
        assertEquals(x0,result.minX()); assertEquals(y0,result.minY());
        assertEquals(x1,result.maxX()); assertEquals(y1,result.maxY());
    }

    @Test void projectedUnionsRejectWrongGridsAndPropagateFailureButIgnoreEmptyLayers() {
        var result=new ProjectedMaskBounds();var other=new ProjectedMaskBounds();
        result.begin(640,480);other.begin(640,480);
        result.include(vertices(0,0,0),12,1,0,new Matrix4f(),new Matrix4f());
        float x0=result.minX(),x1=result.maxX();result.include(other);
        assertTrue(result.valid());assertEquals(x0,result.minX());assertEquals(x1,result.maxX());
        other.begin(641,480);result.include(other);assertFalse(result.accepting());
        result.begin(640,480);other.begin(640,479);result.include(other);assertFalse(result.accepting());
        result.begin(640,480);other.begin(640,480);other.invalidate();result.include(other);assertFalse(result.accepting());
        result.begin(640,480);result.include((ProjectedMaskBounds)null);assertFalse(result.accepting());
    }

    @Test void projectedUnionDoesNotPolluteTheCachedFirstMeshOnLaterFrames() {
        var result=new ProjectedMaskBounds();var extra=new ProjectedMaskBounds();
        var data=vertices(0,0,0);var identity=new Matrix4f();
        result.begin(640,480);result.include(data,12,1,0,identity,identity);
        float x0=result.minX(),x1=result.maxX();
        extra.begin(640,480);extra.include(vertices(.8f,.8f,0),12,1,0,identity,identity);
        result.begin(640,480);result.include(extra);result.include(data,12,1,0,identity,identity);
        assertTrue(result.minX()<=x0 && result.maxX()>=extra.maxX());
        result.begin(640,480);result.include(data,12,1,0,identity,identity);
        assertTrue(result.valid());assertEquals(x0,result.minX());assertEquals(x1,result.maxX());
        assertEquals(0,data.position());
    }

}
