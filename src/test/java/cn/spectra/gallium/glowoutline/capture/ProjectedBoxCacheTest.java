package cn.spectra.gallium.glowoutline.capture;
import org.junit.jupiter.api.Test;
import org.joml.Matrix4f;
import java.nio.*;
import java.util.Random;
import static org.junit.jupiter.api.Assertions.*;

class ProjectedBoxCacheTest {
    private static ByteBuffer vertices(float... values){
        var bytes=ByteBuffer.allocate(values.length*4).order(ByteOrder.nativeOrder());
        for(float value:values)bytes.putFloat(value);return bytes.flip();
    }
    private static void same(ProjectedMaskBounds expected,ProjectedMaskBounds actual){
        assertEquals(expected.valid(),actual.valid());assertEquals(expected.accepting(),actual.accepting());
        assertEquals(expected.minX(),actual.minX());assertEquals(expected.maxX(),actual.maxX());
        assertEquals(expected.minY(),actual.minY());assertEquals(expected.maxY(),actual.maxY());
    }
    @Test void transientInputsReactToEveryProjectionKeyChange(){
        var reused=new ProjectedMaskBounds();var random=new Random(77392);
        var model=new Matrix4f().translation(0,0,-12);
        var projection=new Matrix4f().perspective(1.1f,1.6f,.05f,1000);
        var data=vertices(-1,-1,-1,1,1,1,0,0,0);
        for(int frame=0;frame<240;frame++){
            if(frame%7==0)data.putFloat(24,random.nextFloat()-.5f);
            if(frame%13==0)data.putFloat(0,-1-random.nextFloat());
            if(frame%17==0)model.rotateY(.01f);
            if(frame%29==0)projection.setPerspective(1.1f+random.nextFloat()*.1f,1.6f,.05f,1000);
            int width=frame%31<20?1920:3840,height=frame%37<25?1080:2054;
            var fresh=new ProjectedMaskBounds();
            for(var target:new ProjectedMaskBounds[]{reused,fresh}){
                target.begin(width,height);target.includeTransient(data,12,3,0,model,projection);
            }
            same(fresh,reused);assertEquals(0,data.position());assertEquals(36,data.limit());
        }
    }
    @Test void equalBoxesDoNotReuseRefinedVertexResults(){
        var reused=new ProjectedMaskBounds();
        var model=new Matrix4f().translation(0,0,-.5f).rotateY((float)Math.PI/4);
        var projection=new Matrix4f().perspective(1.2f,1.5f,.05f,1000);
        var data=vertices(-1,0,-1,1,0,1,0,0,0);
        reused.begin(1920,1080);reused.includeTransient(data,12,3,0,model,projection);
        assertTrue(reused.valid());float originalRight=reused.maxX();
        data.putFloat(24,.25f).putFloat(32,.75f);
        var fresh=new ProjectedMaskBounds();
        for(var target:new ProjectedMaskBounds[]{reused,fresh}){
            target.begin(1920,1080);target.includeTransient(data,12,3,0,model,projection);
        }
        assertTrue(fresh.valid());assertTrue(fresh.maxX()>originalRight);same(fresh,reused);
        data.putFloat(24,.1f).putFloat(32,.95f);
        reused.begin(1920,1080);reused.includeTransient(data,12,3,0,model,projection);
        assertFalse(reused.valid());
    }
    @Test void cachedBoxKeepsCurrentLayerUnionAndRefinement(){
        var reused=new ProjectedMaskBounds();var model=new Matrix4f().translation(0,0,-3).rotateY(.6f);
        var projection=new Matrix4f().perspective(1.2f,1.5f,.05f,1000);
        var data=vertices(-.4f,-.4f,-.4f,.4f,.4f,.4f);
        reused.begin(1920,1080);reused.includeTransient(data,12,2,0,model,projection);
        var existing=new ProjectedMaskBounds();existing.begin(1920,1080);
        existing.includeTransient(vertices(-4,-2,-4,4,2,-4),12,2,0,new Matrix4f(),projection);
        var fresh=new ProjectedMaskBounds();
        for(var target:new ProjectedMaskBounds[]{reused,fresh}){
            target.begin(1920,1080);target.include(existing);
            target.includeTransient(data,12,2,0,model,projection);
        }
        same(fresh,reused);
    }
    @Test void cachedProjectionStillScansFiniteVerticesAndAcceptsNewByteOrder(){
        var reused=new ProjectedMaskBounds();var model=new Matrix4f().translation(0,0,-4);var projection=new Matrix4f();
        var original=vertices(-.1f,-.1f,0,.1f,.1f,0,0,0,0);
        reused.begin(640,480);reused.includeTransient(original,12,3,0,model,projection);
        assertTrue(reused.valid());
        original.putFloat(28,Float.NaN);
        reused.begin(640,480);reused.includeTransient(original,12,3,0,model,projection);
        assertFalse(reused.valid());
        var opposite=ByteOrder.nativeOrder()==ByteOrder.LITTLE_ENDIAN?ByteOrder.BIG_ENDIAN:ByteOrder.LITTLE_ENDIAN;
        var bytes=ByteBuffer.allocateDirect(36).order(opposite);
        for(float f:new float[]{-.1f,-.1f,0,.1f,.1f,0,0,0,0})bytes.putFloat(f);
        bytes.flip();var fresh=new ProjectedMaskBounds();
        for(var target:new ProjectedMaskBounds[]{reused,fresh}){
            target.begin(640,480);target.includeTransient(bytes.asReadOnlyBuffer().order(opposite),12,3,0,model,projection);
        }
        same(fresh,reused);assertTrue(reused.valid());
    }
}
