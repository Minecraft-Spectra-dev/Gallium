package cn.spectra.gallium.glowoutline.shader;

import java.nio.*;
import org.joml.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class OutlineMotionTest {
    private static ByteBuffer vertices(float x) {
        var b=ByteBuffer.allocate(36).order(ByteOrder.nativeOrder());
        b.putFloat(x).putFloat(1).putFloat(-7);
        b.putFloat(x+1).putFloat(1).putFloat(-7);
        b.putFloat(x).putFloat(2).putFloat(-7);
        return b.flip();
    }
    @Test void cameraMotionDoesNotMakeAStaticMeshReactive() {
        var tracker=new OutlineMotion();var owner=new Object();
        var view=new Matrix4f().rotateYXZ(.3f,.2f,0);
        tracker.begin();tracker.observe(owner,vertices(2),12,0,3,new Matrix4f(view).scale(.99975f),view,0,0,0);
        assertTrue(tracker.changed(owner));tracker.finish();
        view.rotateY(.1f);
        tracker.begin();tracker.observe(owner,vertices(1.75f),12,0,3,new Matrix4f(view).scale(.99975f),view,.25,0,0);
        assertFalse(tracker.changed(owner));tracker.finish();
        tracker.begin();tracker.observe(owner,vertices(1.8f),12,0,3,new Matrix4f(view).scale(.99975f),view,.25,0,0);
        assertTrue(tracker.changed(owner));
    }
    @Test void missingDrawAndChangedLayerStillRejectHistory() {
        var tracker=new OutlineMotion();var owner=new Object();var identity=new Matrix4f();
        tracker.begin();
        for(int i=0;i<2;i++)tracker.observe(owner,vertices(0),12,0,3,identity,identity,0,0,0);
        tracker.finish();tracker.begin();tracker.observe(owner,vertices(0),12,0,3,identity,identity,0,0,0);
        assertTrue(tracker.changed(owner));tracker.finish();tracker.begin();
        tracker.observe(owner,vertices(0),12,0,3,new Matrix4f().scale(.9f),identity,0,0,0);
        assertTrue(tracker.changed(owner));
        tracker.clear();assertTrue(tracker.changed(owner));
    }
    @Test void reprojectsRotationTranslationAndDifferentDepths() {
        var projection=new Matrix4f().perspective(1.2f,16f/9,.05f,512);
        var old=new Matrix4f(projection).rotateYXZ(.4f,.1f,0);
        var current=new Matrix4f(projection).rotateYXZ(.5f,.15f,0);
        var reprojection=OutlineMotion.reproject(old,current,.02,.01,.04);
        for(float z:new float[]{-2,-7,-40}) {
            var world=new Vector4f(1,2,z,1);
            var expected=new Vector4f(world).add(.02f,.01f,.04f,0).mul(old);
            var clip=new Vector4f(world).mul(current);clip.div(clip.w);
            var actual=clip.mul(reprojection);actual.div(actual.w);expected.div(expected.w);
            assertEquals(expected.x,actual.x,0.00001f);
            assertEquals(expected.y,actual.y,0.00001f);
            assertEquals(expected.z,actual.z,0.00001f);
        }
    }
}
