package cn.spectra.gallium.glowoutline.capture;

//#if MC==1_21_11 || MC==1_26_01
import org.lwjgl.opengl.*;

/** Exact restoration of the indexed bindings touched by the temporary primary-image pass. */
public final class NativeVisibilityBindings {
    public static final class Image implements cn.spectra.gallium.glowoutline.shader.MaskStorageImage.Binding {
        final int unit,name,level,layered,layer,access,format;
        public Image(int unit,int texture,int mode,int storage){
            this.unit=unit;name=GL30.glGetIntegeri(GL42.GL_IMAGE_BINDING_NAME,unit);level=GL30.glGetIntegeri(GL42.GL_IMAGE_BINDING_LEVEL,unit);
            layered=GL30.glGetIntegeri(GL42.GL_IMAGE_BINDING_LAYERED,unit);layer=GL30.glGetIntegeri(GL42.GL_IMAGE_BINDING_LAYER,unit);
            access=GL30.glGetIntegeri(GL42.GL_IMAGE_BINDING_ACCESS,unit);format=GL30.glGetIntegeri(GL42.GL_IMAGE_BINDING_FORMAT,unit);
            GL42.glBindImageTexture(unit,texture,0,false,0,mode,storage);
        }
        public void close(){GL42.glBindImageTexture(unit,name,level,layered!=0,layer,access,format);}
    }
    static final class Storage implements AutoCloseable {
        final int unit,name,generic;
        final long start,size;
        Storage(int unit,int buffer){
            this.unit=unit;generic=GL11.glGetInteger(GL43.GL_SHADER_STORAGE_BUFFER_BINDING);
            name=GL30.glGetIntegeri(GL43.GL_SHADER_STORAGE_BUFFER_BINDING,unit);
            start=GL32.glGetInteger64i(GL43.GL_SHADER_STORAGE_BUFFER_START,unit);size=GL32.glGetInteger64i(GL43.GL_SHADER_STORAGE_BUFFER_SIZE,unit);
            GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER,unit,buffer);
        }
        public void close(){
            if(name!=0 && size>0)GL30.glBindBufferRange(GL43.GL_SHADER_STORAGE_BUFFER,unit,name,start,size);
            else GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER,unit,name);
            GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER,generic);
        }
    }
}

//#else
//$$ final class NativeVisibilityBindings {}
//#endif
