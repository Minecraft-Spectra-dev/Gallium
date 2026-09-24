package cn.spectra.gallium.glowoutline.capture;

//#if MC==1_21_11 || MC==1_26_01
import org.lwjgl.opengl.*;
import org.lwjgl.system.MemoryStack;
/** Tight CPU readback with exact restoration of the caller's pixel-pack layout and PBO. */
public final class NativePixelPackScope implements AutoCloseable {
    private static final int[] KEYS={GL11.GL_PACK_ALIGNMENT,GL11.GL_PACK_ROW_LENGTH,GL11.GL_PACK_SKIP_ROWS,GL11.GL_PACK_SKIP_PIXELS,GL12.GL_PACK_IMAGE_HEIGHT,GL12.GL_PACK_SKIP_IMAGES,GL11.GL_PACK_SWAP_BYTES,GL11.GL_PACK_LSB_FIRST};
    private final int[] values=new int[KEYS.length];
    private final int buffer=GL11.glGetInteger(GL21.GL_PIXEL_PACK_BUFFER_BINDING);
    public NativePixelPackScope(){
        for(int i=0;i<KEYS.length;i++){values[i]=GL11.glGetInteger(KEYS[i]);GL11.glPixelStorei(KEYS[i],i==0?1:0);}
        GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER,0);
    }
    public void close(){GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER,buffer);for(int i=0;i<KEYS.length;i++)GL11.glPixelStorei(KEYS[i],values[i]);}
}

//#else
//$$ public final class NativePixelPackScope {}
//#endif
