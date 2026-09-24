package cn.spectra.gallium.glowoutline.shader;

//#if MC==1_21_11
//$$ import cn.spectra.gallium.glowoutline.capture.*;
//$$ import com.mojang.blaze3d.pipeline.*;
//$$ import com.mojang.blaze3d.opengl.GlTexture;
//$$ import com.mojang.blaze3d.systems.RenderSystem;
//$$ import com.mojang.blaze3d.vertex.*;
//$$ import com.mojang.blaze3d.textures.TextureFormat;
//$$ import org.joml.Matrix4f;
//$$ import org.joml.Matrix4fc;
//$$ import org.lwjgl.opengl.*;
//$$ import java.util.*;
//$$ 
//$$ /** Observes native display-space SR masks without changing their rasterization or main bounds. */
//$$ public final class OutlineSrCapture {
//$$  private static final Map<GlowCaptureState,ProjectedMaskBounds> bounds=new IdentityHashMap<>();
//$$  private static final Map<Integer,Boolean> programs=new HashMap<>();
//$$  private static final Matrix4f model=new Matrix4f(),projection=new Matrix4f();
//$$  private static GlowCaptureState owner;private static TextureTarget mask,storage;private static MeshData pending;
//$$  private static final List<NativeMaskAtlas.Entry> entries=new ArrayList<>();
//$$  static List<NativeMaskAtlas.Entry> entries(){return List.copyOf(entries);}
//$$  private static MaskAtlasLayout layout;private static int slot;private static boolean invalid;
//$$  public static final class Scope implements AutoCloseable {
//$$   private final boolean active;private Scope(boolean active){this.active=active;}
//$$   public void close(){if(active)finish();}
//$$  }
//$$  private static final Scope ACTIVE=new Scope(true),INACTIVE=new Scope(false);
//$$  static void beginFrame(){entries.clear();bounds.clear();owner=null;pending=null;layout=null;slot=0;invalid=false;}
//$$  static ProjectedMaskBounds bounds(GlowCaptureState state){return bounds.get(state);}
//$$  static NativeMaskAtlas.Entry latest(GlowCaptureState state){
//$$   if(!valid() || !OutlineTemporalStabilizer.capturingSr() || entries.isEmpty())return null;
//$$   var entry=entries.getLast();return entry.state()==state?entry:null;
//$$  }
//$$  static boolean valid(){return !invalid && owner==null;}
//$$  static long storageBytes(int w,int h){
//$$   if(w<=0 || h<=0 || w>(1<<29) || h>(1<<29))return Long.MAX_VALUE;
//$$   return (long)powerOfTwo(w)*powerOfTwo(h)*8;
//$$  }
//$$  static long reservedBytes(){return storage==null?0:(long)storage.width*storage.height*8;}
//$$  public static Scope begin(GlowCaptureState state,TextureTarget target){
//$$   if(!OutlineTemporalStabilizer.capturingSr())return INACTIVE;
//$$   var output=net.minecraft.client.Minecraft.getInstance().getMainRenderTarget();
//$$   if(owner!=null || target==null || target.width!=output.width || target.height!=output.height){invalid=true;return INACTIVE;}
//$$   owner=state;mask=target;pending=null;var b=new ProjectedMaskBounds();b.begin(target.width,target.height);bounds.put(state,b);return ACTIVE;
//$$  }
//$$  public static void transformed(MeshData mesh,Matrix4fc matrix){if(owner==null)return;if(pending!=null){invalid=true;return;}pending=mesh;model.set(matrix);}
//$$  public static void drawn(MeshData mesh,VertexFormat format){
//$$   if(owner==null)return;
//$$   try {
//$$    var b=bounds.get(owner);var d=mesh.drawState();
//$$    int program=GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
//$$    if(programs.size()>=128 && !programs.containsKey(program))programs.clear();
//$$    if(pending!=mesh || d.format()!=format || !format.contains(VertexFormatElement.POSITION)
//$$     || !programs.computeIfAbsent(program,OutlineSrCapture::nativePosition)
//$$     || RenderSystem.outputColorTextureOverride!=mask.getColorTextureView()
//$$     || RenderSystem.outputDepthTextureOverride!=mask.getDepthTextureView()
//$$     || ProjectionMatrixTracker.lookupInto(RenderSystem.getProjectionMatrixBuffer(),projection)==null){invalid=true;b.invalidate();return;}
//$$    try(var stack=org.lwjgl.system.MemoryStack.stackPush()){
//$$     var viewport=stack.mallocInt(4);GL11.glGetIntegerv(GL11.GL_VIEWPORT,viewport);
//$$     if(viewport.get(0)!=0 || viewport.get(1)!=0 || viewport.get(2)!=mask.width || viewport.get(3)!=mask.height){invalid=true;b.invalidate();return;}
//$$    }
//$$    b.includeTransient(mesh.vertexBuffer(),format.getVertexSize(),d.vertexCount(),format.getOffset(VertexFormatElement.POSITION),model,projection);
//$$    OutlineTemporalStabilizer.mesh(owner,mesh,model,projection,program);
//$$   } catch(RuntimeException|LinkageError unavailable){invalid=true;bounds.get(owner).invalidate();}
//$$   finally{pending=null;}
//$$  }
//$$  private static boolean nativePosition(int program){
//$$   if(GL20.glGetProgrami(program,GL20.GL_ATTACHED_SHADERS)!=2)return false;
//$$   try(var stack=org.lwjgl.system.MemoryStack.stackPush()){
//$$    var shaders=stack.mallocInt(2);GL20.glGetAttachedShaders(program,null,shaders);boolean vertex=false,fragment=false;
//$$    for(int i=0;i<2;i++){int s=shaders.get(i);String text=GL20.glGetShaderSource(s);int type=GL20.glGetShaderi(s,GL20.GL_SHADER_TYPE);if(type==GL20.GL_VERTEX_SHADER)vertex=VertexPositionProof.matchesUniformBlocks(text);else if(type==GL20.GL_FRAGMENT_SHADER)fragment=NativeShaderSideEffects.hasOnlyRasterOutputs(text);}
//$$    return vertex && fragment;
//$$   }
//$$  }
//$$  private static void finish(){
//$$   try(var saved=new OutlineTemporalGlState()) {
//$$    var b=bounds.get(owner);if(pending!=null || !owner.capturedThisFrame || b==null || !b.valid()){invalid=true;return;}
//$$    if(invalid)return;
//$$    if(owner.lastMaskScaleX!=1 || owner.lastMaskScaleY!=1 || owner.lastMaskOffsetX!=0 || owner.lastMaskOffsetY!=0 || owner.maskDepthSnapshotGeneration>=0){invalid=true;return;}
//$$    if(!(mask.getColorTexture() instanceof GlTexture source) || source.getFormat()!=TextureFormat.RGBA8){invalid=true;return;}
//$$    int w=powerOfTwo(mask.width),h=powerOfTwo(mask.height);
//$$    if(storage==null || storage.width!=w || storage.height!=h){if(storage!=null)storage.destroyBuffers();storage=null;storage=new TextureTarget("GlowSrHistoryMasks",w,h,true);}
//$$    if(!(mask.getDepthTexture() instanceof GlTexture sourceDepth) || !(storage.getDepthTexture() instanceof GlTexture storedDepth)
//$$      || GL45.glGetTextureLevelParameteri(sourceDepth.glId(),0,GL11.GL_TEXTURE_INTERNAL_FORMAT)!=GL45.glGetTextureLevelParameteri(storedDepth.glId(),0,GL11.GL_TEXTURE_INTERNAL_FORMAT)){invalid=true;return;}
//$$    if(layout==null)layout=new MaskAtlasLayout(w,h);
//$$    int x=(int)Math.max(0,Math.min(mask.width,b.minX())),y=(int)Math.max(0,Math.min(mask.height,b.minY()));
//$$    int bw=(int)Math.max(0,Math.min(mask.width,b.maxX()))-x,bh=(int)Math.max(0,Math.min(mask.height,b.maxY()))-y;
//$$    var tile=layout.place(bw,bh);if(tile==null || slot>=128){invalid=true;return;}
//$$    if(bw>0 && bh>0)GL43.glCopyImageSubData(source.glId(),GL11.GL_TEXTURE_2D,0,x,y,0,((GlTexture)storage.getColorTexture()).glId(),GL11.GL_TEXTURE_2D,0,tile.x(),tile.y(),0,bw,bh,1);
//$$    if(bw>0 && bh>0)GL43.glCopyImageSubData(sourceDepth.glId(),GL11.GL_TEXTURE_2D,0,x,y,0,storedDepth.glId(),GL11.GL_TEXTURE_2D,0,tile.x(),tile.y(),0,bw,bh,1);
//$$    var output=net.minecraft.client.Minecraft.getInstance().getMainRenderTarget();
//$$    var entry=new NativeMaskAtlas.Entry(owner,output,mask,storage,null,owner.captureEpoch,x,y,bw,bh,tile.x()-x,tile.y()-y,slot++,null);entries.add(entry);OutlineTemporalStabilizer.stored(entry);
//$$   }catch(RuntimeException|LinkageError unavailable){invalid=true;}
//$$   finally{owner=null;mask=null;pending=null;}
//$$  }
//$$  private static int powerOfTwo(int value){return value<=1?1:Integer.highestOneBit(value-1)<<1;}
//$$  static void release(){if(storage!=null)storage.destroyBuffers();storage=null;beginFrame();}
//$$  static void dispose(){release();programs.clear();}
//$$ }
//#else
public final class OutlineSrCapture { private OutlineSrCapture() {} }
//#endif
