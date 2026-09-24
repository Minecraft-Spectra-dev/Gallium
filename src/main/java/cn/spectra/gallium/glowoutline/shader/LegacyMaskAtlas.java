package cn.spectra.gallium.glowoutline.shader;
//#if MC<1_21_05
//$$ import cn.spectra.gallium.glowoutline.IrisCompat;
//$$ import cn.spectra.gallium.glowoutline.capture.*;
//$$ import com.mojang.blaze3d.pipeline.*;
//$$ import net.minecraft.client.Minecraft;
//$$ import org.lwjgl.opengl.*;
//$$ import org.lwjgl.system.MemoryStack;
//$$ import java.util.ArrayList;
//$$ /** Independent storage receipts preserve ordinary replay and original blend order. */
//$$ final class LegacyMaskAtlas implements AutoCloseable {
//$$  private static TextureTarget storage,fallback;
//$$  private final RenderTarget output;private final GlowComposite.LateCompositeFrame composite;
//$$  private final long epoch;private final OpenGlMaskOrdering.Stamp ordering=OpenGlMaskOrdering.observe();
//$$  private final ArrayList<Entry> entries=new ArrayList<>();
//$$  private TextureTarget page;private MaskAtlasLayout layout;private boolean unavailable;
//$$  record Entry(GlowCaptureState state,RenderTarget output,TextureTarget atlas,TextureTarget baseDepth,long epoch,int x,int y,int width,int height,int offsetX,int offsetY){}
//$$  static {GlowResources.register(LegacyMaskAtlas::dispose);}
//$$  LegacyMaskAtlas(RenderTarget output,GlowComposite.LateCompositeFrame composite,long epoch){this.output=output;this.composite=composite;this.epoch=epoch;}
//$$  static void beginFrame(boolean enabled,int width,int height){
//$$   if(storage!=null && (!enabled || storage.width!=power(width) || storage.height!=power(height) || !GlowCaptureManager.maskAtlasFitsBudget(width,height,storage.width,storage.height)))dispose();
//$$  }
//$$  private static int power(int n){return n<=0 || n>(1<<29)?0:n==1?1:Integer.highestOneBit(n-1)<<1;}
//$$  private static int edge(float value,int max){return (int)Math.max(0,Math.min(max,value));}
//$$  private static TextureTarget target(int w,int h){
    //#if MC>=1_21_02
    //$$ return new TextureTarget(w,h,true);
    //#else
    //$$ return new TextureTarget(w,h,true,Minecraft.ON_OSX);
    //#endif
//$$  }
//$$  boolean supports(GlowCaptureState s){
//$$   return !unavailable && ordering!=null && ordering.current() && !IrisCompat.isShaderActive() && !IrisCompat.isActiveSrRuntime()
//$$    && s.guiEntity==null && !s.superResolutionPrepared && s.maskBounds.valid() && s.lastMaskScaleX==1 && s.lastMaskScaleY==1
//$$    && s.lastMaskOffsetX==0 && s.lastMaskOffsetY==0 && LegacyGlowInstances.parameters(s.config)
//$$    && BoundedGlowContracts.get(s.config.shader())!=null && BoundedGlowContracts.automatic(s.config.shader()) && LegacyGlowInstances.supported();
//$$  }
//$$  private static int format(int texture){return GL45.glGetTextureLevelParameteri(texture,0,GL11.GL_TEXTURE_INTERNAL_FORMAT);}
//$$  private boolean ensure(TextureTarget mask){
//$$   if(page!=null)return page==storage && storage.getColorTextureId()>0 && fallback.getColorTextureId()>0;
//$$   int w=power(output.width),h=power(output.height),limit=GL11.glGetInteger(GL11.GL_MAX_TEXTURE_SIZE);
//$$   if(w<=0 || h<=0 || w>limit || h>limit || !GlowCaptureManager.maskAtlasFitsBudget(output.width,output.height,w,h)){unavailable=true;return false;}
//$$   try(var allocation=new LegacyFramebufferBinding()){
//$$   if(storage!=null && (storage.width!=w || storage.height!=h)){storage.destroyBuffers();storage=null;}
//$$   if(fallback!=null && (fallback.width!=output.width || fallback.height!=output.height)){fallback.destroyBuffers();fallback=null;}
//$$   if(storage==null)storage=target(w,h);if(fallback==null)fallback=target(output.width,output.height);
//$$   if(format(mask.getColorTextureId())!=GL11.GL_RGBA8 || format(storage.getColorTextureId())!=GL11.GL_RGBA8
//$$     || format(fallback.getColorTextureId())!=GL11.GL_RGBA8 || format(mask.getDepthTextureId())!=format(storage.getDepthTextureId())
//$$     || format(mask.getDepthTextureId())!=format(fallback.getDepthTextureId())){unavailable=true;return false;}
//$$   }
//$$   page=storage;layout=new MaskAtlasLayout(w,h);return true;
//$$  }
//$$  Object copy(GlowCaptureState s,TextureTarget mask){
//$$   if(!supports(s) || entries.size()>=256 || !ensure(mask))return null;
//$$   var base=s.firstPerson?null:GlowCaptureManager.getSceneDepthTarget();
//$$   if(!s.firstPerson && (base==null || base.width!=output.width || base.height!=output.height || format(base.getDepthTextureId())!=format(mask.getDepthTextureId())))return null;
//$$   int x=edge(s.maskBounds.minX(),output.width),y=edge(s.maskBounds.minY(),output.height);
//$$   int w=Math.max(0,edge(s.maskBounds.maxX(),output.width)-x),h=Math.max(0,edge(s.maskBounds.maxY(),output.height)-y);
//$$   var tile=layout.place(w,h);if(tile==null)return null;
//$$   if(w!=0 && h!=0){copy(mask.getColorTextureId(),page.getColorTextureId(),x,y,tile.x(),tile.y(),w,h);copy(mask.getDepthTextureId(),page.getDepthTextureId(),x,y,tile.x(),tile.y(),w,h);}
//$$   var e=new Entry(s,output,page,base,epoch,x,y,w,h,tile.x()-x,tile.y()-y);entries.add(e);return e;
//$$  }
//$$  private static void copy(int source,int dest,int sx,int sy,int dx,int dy,int w,int h){GL43.glCopyImageSubData(source,GL11.GL_TEXTURE_2D,0,sx,sy,0,dest,GL11.GL_TEXTURE_2D,0,dx,dy,0,w,h,1);}
//$$  private TextureTarget materialize(Entry e){
//$$   try(var bindings=new LegacyFramebufferBinding()){
//$$   com.mojang.blaze3d.platform.GlStateManager._glBindFramebuffer(GL30.GL_FRAMEBUFFER,0);
//$$   GL44.glClearTexImage(fallback.getColorTextureId(),0,GL11.GL_RGBA,GL11.GL_UNSIGNED_BYTE,(java.nio.ByteBuffer)null);
//$$   try(var stack=MemoryStack.stackPush()){GL44.glClearTexImage(fallback.getDepthTextureId(),0,GL11.GL_DEPTH_COMPONENT,GL11.GL_FLOAT,stack.floats(1));}
//$$   if(e.baseDepth()!=null)copy(e.baseDepth().getDepthTextureId(),fallback.getDepthTextureId(),0,0,0,0,output.width,output.height);
//$$   if(e.width()!=0 && e.height()!=0){
//$$    copy(e.atlas().getColorTextureId(),fallback.getColorTextureId(),e.x()+e.offsetX(),e.y()+e.offsetY(),e.x(),e.y(),e.width(),e.height());
//$$    copy(e.atlas().getDepthTextureId(),fallback.getDepthTextureId(),e.x()+e.offsetX(),e.y()+e.offsetY(),e.x(),e.y(),e.width(),e.height());
//$$   }
//$$   return fallback;
//$$   }
//$$  }
//$$  boolean flush(){
//$$   if(entries.isEmpty())return true;
//$$   try(var instances=new LegacyGlowInstances()){
//$$    for(int i=0;i<entries.size();){
//$$     var e=entries.get(i);
//$$     if(ordering==null || !ordering.current() || !composite.valid() || !e.state().hasOrdinaryMaskStored(epoch,e))return false;
//$$     int count=composite.compositeLegacyInstances(instances,entries,i);
//$$     if(count>0){for(int j=0;j<count;j++){var consumed=entries.get(i+j);consumed.state().forgetOrdinaryMaskStorage(consumed);}i+=count;continue;}
//$$     if(!composite.compositeOrdinaryState(e.state(),materialize(e)))return false;
//$$     e.state().forgetOrdinaryMaskStorage(e);i++;
//$$    }
//$$   }
//$$   entries.clear();if(layout!=null)layout.reset();return true;
//$$  }
//$$  public void close(){for(var e:entries)e.state().forgetOrdinaryMaskStorage(e);entries.clear();}
//$$  private static void dispose(){if(storage!=null)storage.destroyBuffers();if(fallback!=null)fallback.destroyBuffers();storage=fallback=null;}
//$$ }
//#else
final class LegacyMaskAtlas { private LegacyMaskAtlas() {} }
//#endif
