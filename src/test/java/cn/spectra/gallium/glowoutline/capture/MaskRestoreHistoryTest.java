package cn.spectra.gallium.glowoutline.capture;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.Random;
import org.joml.Matrix4f;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MaskRestoreHistoryTest {
    private static ProjectedMaskBounds box(int width, int height, int x, int y, int w, int h) {
        var data = ByteBuffer.allocate(24).order(ByteOrder.nativeOrder());
        data.putFloat(2f*x/width-1).putFloat(2f*y/height-1).putFloat(0);
        data.putFloat(2f*(x+w)/width-1).putFloat(2f*(y+h)/height-1).putFloat(0).flip();
        var bounds = new ProjectedMaskBounds(); bounds.begin(width,height);
        bounds.include(data,12,2,0,new Matrix4f(),new Matrix4f());
        return bounds;
    }

    @Test void partialRestoreProducesTheSameFullTexturesAsClearingAndCopyingEveryState() {
        int width=31,height=29,n=width*height;
        var random = new Random(229);
        var history = new MaskRestoreHistory();
        var target = new Object();var source = new Object();
        int[] color=new int[n],depth=new int[n],base=new int[n];
        for(int i=0;i<n;i++) base[i]=random.nextInt();
        int partial=0;
        for(int draw=0;draw<1000;draw++) {
            long epoch=draw/100;
            boolean far=(draw/37&1)==0;
            int[] expected=far?new int[n]:base;
            var region=history.begin(new MaskRestoreHistory.Key(target,1,2,width,height,
                    epoch,far?null:source,far?-1:3,epoch));
            if(region==null) { Arrays.fill(color,0);System.arraycopy(expected,0,depth,0,n); }
            else {
                partial++;
                for(int y=region.y();y<region.y()+region.height();y++)
                    for(int x=region.x();x<region.x()+region.width();x++) {
                        int index=y*width+x;color[index]=0;depth[index]=expected[index];
                    }
            }
            assertArrayEquals(new int[n],color,"color before draw "+draw);
            assertArrayEquals(expected,depth,"depth before draw "+draw);
            int x=random.nextInt(width+10)-5,y=random.nextInt(height+10)-5;
            int w=random.nextInt(8)+1,h=random.nextInt(8)+1;
            var bounds=box(width,height,x,y,w,h);
            for(int yy=Math.max(0,y);yy<Math.min(height,y+h);yy++)
                for(int xx=Math.max(0,x);xx<Math.min(width,x+w);xx++) {
                    int index=yy*width+xx;color[index]=random.nextInt();depth[index]=random.nextInt();
                }
            if(draw%17==0) bounds.invalidate(); // Unknown native shader: next state reinitializes fully.
            if(draw%43!=0) history.finish(bounds); // An interrupted replay also requires a full restore.
        }
        assertTrue(partial>800);
    }

    @Test void attachmentOrSourceChangesNeverReuseOldContents() {
        var history=new MaskRestoreHistory();var target=new Object();var source=new Object();
        var key=new MaskRestoreHistory.Key(target,1,2,100,100,3,source,4,5);
        assertNull(history.begin(key));history.finish(box(100,100,10,10,20,20));
        assertNotNull(history.begin(key));
        assertNull(history.begin(key)); // No completed draw since the preceding begin.
        history.finish(box(100,100,10,10,20,20));
        assertNull(history.begin(new MaskRestoreHistory.Key(target,1,2,100,100,3,source,4,6)));
        history.finish(box(100,100,10,10,20,20));
        assertNull(history.begin(new MaskRestoreHistory.Key(new Object(),1,2,100,100,3,source,4,6)));
        history.invalidate();assertNull(history.begin(key));
    }
}
