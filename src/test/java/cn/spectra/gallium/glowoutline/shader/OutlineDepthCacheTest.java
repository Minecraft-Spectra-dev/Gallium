package cn.spectra.gallium.glowoutline.shader;
import java.nio.*;
import org.joml.Matrix4f;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class OutlineDepthCacheTest {
    private static ByteBuffer vertices(float depth) {
        var data=ByteBuffer.allocate(36).order(ByteOrder.nativeOrder());
        data.putFloat(0).putFloat(0).putFloat(depth).putFloat(1).putFloat(0).putFloat(depth-1).putFloat(0).putFloat(1).putFloat(depth-2);
        return data.flip();
    }
    private static void check(OutlineDepthCache cache,Object owner,ByteBuffer data,Matrix4f model,Matrix4f projection) {
        assertEquals(Float.floatToRawIntBits(OutlineDepthBounds.minimum(data,12,3,0,model,projection)),
                Float.floatToRawIntBits(cache.minimum(owner,data,12,3,0,model,projection)));
    }
    @Test void stationaryReuseAndEveryMutableInputKeepTheExactBound() {
        var cache=new OutlineDepthCache();var owner=new Object();var data=vertices(-5);var model=new Matrix4f();var projection=new Matrix4f().perspective(1,1,.05f,256);
        for(int frame=0;frame<12;frame++) {
            if(frame==3)data.putFloat(8,-2);
            if(frame==5)model.translate(0,0,-10);
            if(frame==7)projection.perspective(.8f,1,.1f,512);
            if(frame==9)data.putFloat(8,Float.NaN);
            if(frame==10)data.putFloat(8,-5);
            cache.begin();check(cache,owner,data,model,projection);cache.finish();
        }
    }
    @Test void drawOrderOwnerReuseAndMissingDrawNeverUseAnotherMeshBound() {
        var cache=new OutlineDepthCache();var a=new Object();var b=new Object();var model=new Matrix4f();var projection=new Matrix4f().perspective(1,1,.05f,256);
        for(int frame=0;frame<4;frame++) {
            cache.begin();check(cache,a,vertices(frame%2==0?-5:-20),model,projection);
            if(frame!=2)check(cache,a,vertices(frame%2==0?-20:-5),model,projection);
            check(cache,b,vertices(-50),model,projection);cache.finish();
        }
        cache.clear();cache.begin();check(cache,a,vertices(-1),model,projection);
    }
    @Test void aFrameWithoutFinishCannotPairUpdatedInputsWithAnOldBound() {
        var cache=new OutlineDepthCache();var owner=new Object();var model=new Matrix4f();var projection=new Matrix4f().perspective(1,1,.05f,256);
        cache.begin();check(cache,owner,vertices(-5),model,projection);cache.finish();
        cache.begin();check(cache,owner,vertices(-1),model,projection);
        cache.begin();check(cache,owner,vertices(-1),model,projection);cache.finish();
        cache.begin();check(cache,owner,vertices(-5),model,projection);
    }
    @Test void cameraMotionAndReturnToRestAlwaysMatchCurrentProjection() {
        var cache=new OutlineDepthCache();var owner=new Object();var projection=new Matrix4f().perspective(1,1,.05f,256);
        for(int frame=0;frame<12;frame++) {
            float angle=frame<3?0:frame<9?frame*.03f:.27f;var view=new Matrix4f().rotateY(angle);
            cache.begin();cache.setView(view,projection,frame<9?frame:9,0,0);
            check(cache,owner,vertices(-5),view,projection);cache.finish();
        }
        cache.clear();cache.begin();cache.setView(null,projection,0,0,0);check(cache,owner,vertices(-2),new Matrix4f(),projection);
    }
    @Test void bufferPositionStrideAndOffsetAreHonored() {
        var cache=new OutlineDepthCache();var owner=new Object();var model=new Matrix4f();var projection=new Matrix4f().perspective(1,1,.05f,256);
        cache.begin();check(cache,owner,vertices(-5),model,projection);cache.finish();cache.begin();
        var padded=ByteBuffer.allocate(80).order(ByteOrder.nativeOrder());padded.position(8);
        for(int i=0;i<3;i++)padded.putInt(0x12345678).putFloat(i==1?1:0).putFloat(i==2?1:0).putFloat(-5-i).putLong(0);
        padded.flip();padded.position(8);
        assertEquals(OutlineDepthBounds.minimum(padded,24,3,4,model,projection),cache.minimum(owner,padded,24,3,4,model,projection));
    }
    @Test void capacityAndInvalidInputFallBackWithoutKeepingStaleEntries() {
        var cache=new OutlineDepthCache();var owner=new Object();var model=new Matrix4f();var projection=new Matrix4f().perspective(1,1,.05f,256);
        cache.begin();for(int i=0;i<520;i++)check(cache,owner,vertices(-5-i),model,projection);cache.finish();cache.begin();check(cache,owner,vertices(-1),model,projection);
        assertTrue(Float.isNaN(cache.minimum(owner,vertices(-5),12,4,0,model,projection)));
        assertTrue(Float.isNaN(cache.minimum(owner,vertices(-5),12,3,1,model,projection)));
    }
}
