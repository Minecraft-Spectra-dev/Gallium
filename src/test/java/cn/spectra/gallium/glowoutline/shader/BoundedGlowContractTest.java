package cn.spectra.gallium.glowoutline.shader;

import cn.spectra.gallium.glowoutline.capture.ProjectedMaskBounds;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import org.joml.Matrix4f;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BoundedGlowContractTest {
    private static BoundedGlowContract verifiedBounds() {
        return new BoundedGlowContract(1f / 64f, 6, 2, .5f);
    }


    @Test void expandsTheNativeMaskByTheSameWorldRadiusOrFallbackAndClipsToOutput() {
        var bounds = new ProjectedMaskBounds();
        var bytes = ByteBuffer.allocate(12).order(ByteOrder.nativeOrder()).putFloat(0).putFloat(0).putFloat(0).flip();
        bounds.begin(1920,1080); bounds.include(bytes,12,1,0,new Matrix4f(),new Matrix4f());
        var contract = verifiedBounds();
        var fallback = contract.rectangle(bounds,1920,1080,0,0);
        assertTrue(fallback.x() <= bounds.minX()-6 && fallback.y() <= bounds.minY()-6);
        var projected = contract.rectangle(bounds,1920,1080,1,1);
        assertTrue(projected.x() <= bounds.minX()-30 && projected.y() <= bounds.minY()-16.875);
        var huge = contract.rectangle(bounds,1920,1080,Float.MAX_VALUE,Float.MAX_VALUE);
        assertEquals(new BoundedGlowContract.Rectangle(0,0,1920,1080),huge);
        bounds.invalidate(); assertNull(contract.rectangle(bounds,1920,1080,1,1));
    }



    private static ProjectedMaskBounds box(int width, int height) {
        var bytes = ByteBuffer.allocate(24).order(ByteOrder.nativeOrder());
        bytes.putFloat(8f / width * 2 - 1).putFloat(4f / height * 2 - 1).putFloat(0);
        bytes.putFloat(14f / width * 2 - 1).putFloat(11f / height * 2 - 1).putFloat(0).flip();
        var bounds = new ProjectedMaskBounds(); bounds.begin(width, height);
        bounds.include(bytes, 12, 2, 0, new Matrix4f(), new Matrix4f());
        assertTrue(bounds.valid()); return bounds;
    }

    @Test void mappedBoundsCoverIndependentBilinearSamplingAndAllOutlineDirections() {
        int width = 57, height = 43, visibleSamples = 0;
        var bounds = box(width, height); var contract = verifiedBounds();
        for (float[] scale : new float[][]{{1,1},{.5f,.5f},{.37f,.75f},{.02f,.03f}})
            for (float[] offset : new float[][]{{0,0},{.1f,.05f},{-.4f/width,.3f/height}})
                for (boolean tieUp : new boolean[]{false,true}) {
                    var rectangle = contract.mappedRectangle(bounds,width,height,0,0,
                            scale[0],scale[1],offset[0],offset[1]);
                    assertNotNull(rectangle);
                    for (int y=0;y<height;y++) for (int x=0;x<width;x++) {
                        boolean canSample=false;
                        for (int direction=0;direction<8;direction++) {
                            float angle=direction*.785398f;
                            float u=(x+.5f)/width+(float)Math.cos(angle)*6/width;
                            float v=(y+.5f)/height+(float)Math.sin(angle)*6/height;
                            canSample |= reconstructed(u,v,width,height,scale,offset,tieUp)>0;
                        }
                        if (canSample) {
                            visibleSamples++;
                            assertTrue(x>=rectangle.x() && x<rectangle.x()+rectangle.width()
                                    && y>=rectangle.y() && y<rectangle.y()+rectangle.height(),
                                    "Sample support escaped "+rectangle+" at "+x+","+y);
                        }
                    }
                }
        assertTrue(visibleSamples>100);
    }

    // Direct scalar evaluation of the shader's four taps, independent of the rectangle math.
    private static float reconstructed(float u,float v,int width,int height,float[] scale,float[] offset,boolean tieUp) {
        if(u<0 || u>=1 || v<0 || v>=1)return 0;
        int aw=Math.min(width,Math.max(1,rounded(width*scale[0],tieUp)));
        int ah=Math.min(height,Math.max(1,rounded(height*scale[1],tieUp)));
        int ox=Math.max(0,Math.min(width-aw,rounded(offset[0]*width,tieUp)));
        int oy=Math.max(0,Math.min(height-ah,rounded(offset[1]*height,tieUp)));
        float tx=u*aw+offset[0]*width-.5f,ty=v*ah+offset[1]*height-.5f;
        if(tx<=ox-1 || ty<=oy-1 || tx>=ox+aw || ty>=oy+ah)return 0;
        int bx=(int)Math.floor(tx),by=(int)Math.floor(ty);
        float fx=tx-bx,fy=ty-by,total=0;
        for(int dy=0;dy<2;dy++)for(int dx=0;dx<2;dx++){
            int x=bx+dx,y=by+dy;
            if(x>=ox && x<ox+aw && y>=oy && y<oy+ah && x>=8 && x<14 && y>=4 && y<11)
                total+=(dx==0?1-fx:fx)*(dy==0?1-fy:fy);
        }
        return total;
    }

    private static int rounded(float value,boolean tieUp) {
        double floor=Math.floor(value);
        return (int)(value-floor==.5 ? floor+(tieUp?1:0) : Math.floor(value+.5));
    }

    @Test void mappedBoundsRejectUnprovenSamplerCoordinates() {
        var bounds=box(57,43);var contract=verifiedBounds();
        for(float bad:new float[]{0,-1,1.1f,Float.NaN,Float.POSITIVE_INFINITY})
            assertNull(contract.mappedRectangle(bounds,57,43,0,0,bad,.5f,0,0));
        for(float bad:new float[]{-1.1f,1.1f,Float.NaN,Float.NEGATIVE_INFINITY})
            assertNull(contract.mappedRectangle(bounds,57,43,0,0,.5f,.5f,bad,0));
        assertNull(contract.mappedRectangle(bounds,57,43,Float.NaN,0,.5f,.5f,0,0));
        assertNull(contract.mappedRectangle(bounds,0,43,0,0,.5f,.5f,0,0));
        bounds.invalidate();assertNull(contract.mappedRectangle(bounds,57,43,0,0,.5f,.5f,0,0));
    }

    @Test void mappedNativeGridContainsExistingSupportAndHugeRadiiClipSafely() {
        var bounds=box(57,43);var contract=verifiedBounds();
        var nativeRect=contract.rectangle(bounds,57,43,0,0);
        var mapped=contract.mappedRectangle(bounds,57,43,0,0,1,1,0,0);
        assertTrue(mapped.x()<=nativeRect.x() && mapped.y()<=nativeRect.y());
        assertTrue(mapped.x()+mapped.width()>=nativeRect.x()+nativeRect.width());
        assertTrue(mapped.y()+mapped.height()>=nativeRect.y()+nativeRect.height());
        assertEquals(new BoundedGlowContract.Rectangle(0,0,57,43),
                contract.mappedRectangle(bounds,57,43,Float.MAX_VALUE,Float.MAX_VALUE,Float.MIN_NORMAL,.5f,0,0));
    }
}
