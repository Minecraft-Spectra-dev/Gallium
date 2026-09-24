package cn.spectra.gallium.glowoutline.capture;

//#if MC==1_21_11 || MC==1_26_01
import cn.spectra.gallium.glowoutline.shader.*;
import com.mojang.blaze3d.buffers.*;
import com.mojang.blaze3d.opengl.*;
import com.mojang.blaze3d.pipeline.*;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.*;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.Minecraft;
import org.lwjgl.opengl.*;
import java.lang.reflect.Field;
import java.util.*;

/** Owns copies of tagged primary batches until their visibility is rendered or abandoned. */
public final class NativeVisibilityBatch implements AutoCloseable {
    private static final int CAPACITY=4*1024*1024,UNIFORM_CAPACITY=256*1024,MAX_BATCHES=64;
    //#if MC>=1_26_00
    private static final Field HANDLE=handleField();
    //#endif
    private static GpuBuffer arena,uniformArena;
    private static OpenGlMaskOrdering.Stamp arenaOwner;
    private static volatile NativeVisibilityBatch current;
    private final OpenGlMaskOrdering.Stamp ordering;
    private final List<Source> sources=new ArrayList<>();
    private final Set<GpuTexture> sampled=Collections.newSetFromMap(new IdentityHashMap<>());
    private volatile boolean inputsCurrent=true;
    private GpuTexture sourceColor,sourceDepth;
    private int width,height,cursor,uniformCursor,draws,renderedImage;
    private GpuTexture renderedDepth;
    private boolean rendered,closed,failed;
    private String rejectionReason;
    public record TextureBinding(GpuTextureView view,GpuSampler sampler){}
    private record Source(RenderPipeline source,Object program,RenderPipeline pipeline,int base,int count,Map<String,GpuBufferSlice> uniforms,Map<String,TextureBinding> textures){}
    static {GlowResources.register(()->{if(current!=null)current.close();if(arena!=null && arenaOwner!=null && arenaOwner.current()){arena.close();arena=null;if(uniformArena!=null){uniformArena.close();uniformArena=null;}arenaOwner=null;}});}
    private NativeVisibilityBatch(OpenGlMaskOrdering.Stamp ordering){this.ordering=ordering;current=this;}
    //#if MC>=1_26_00
    private static Field handleField(){try{var f=GlBuffer.class.getDeclaredField("handle");return f.trySetAccessible()?f:null;}catch(ReflectiveOperationException e){return null;}}
    private static int handle(GpuBuffer b){try{return HANDLE.getInt(b);}catch(ReflectiveOperationException e){throw new IllegalStateException(e);}}
    //#else
    //$$ private static int handle(GpuBuffer b){return ((cn.spectra.gallium.glowoutline.mixin.accessor.NativePrimaryBufferAccessor)b).gallium$handle();}
    //#endif
    public static NativeVisibilityBatch begin(){
        if(current!=null
                //#if MC>=1_26_00
                || HANDLE==null
                //#endif
                || arena!=null && (arenaOwner==null || !arenaOwner.current()) || !NativeVisibilityPipeline.supported())return null;
        var stamp=OpenGlMaskOrdering.observe();return stamp==null?null:new NativeVisibilityBatch(stamp);
    }
    private boolean reject(String reason){failed=true;if(rejectionReason==null)rejectionReason=reason;return false;}
    public String rejectionReason(){return rejectionReason;}
    private boolean available(){return !closed && !failed && current==this && ordering.current();}
    public int stagedCount(){return sources.size();}
    public int drawnCount(){return draws;}
    public int byteCount(){return cursor;}
    public int uniformByteCount(){return uniformCursor;}
    private static boolean view(GpuTextureView view){return view!=null && !view.isClosed() && !view.texture().isClosed() && view.texture().getClass()==GlTexture.class && view.baseMipLevel()==0 && view.mipLevels()==1 && view.texture().getDepthOrLayers()==1;}
    private static boolean bindings(Map<String,GpuBufferSlice> uniforms,Map<String,TextureBinding> textures){
        if(uniforms==null || textures==null || textures.size()>32 || !uniforms.containsKey("Projection") || !uniforms.containsKey("DynamicTransforms"))return false;
        for(var entry:uniforms.entrySet()){
            var u=entry.getValue();
            if(entry.getKey()==null || u==null || u.buffer()==null || u.buffer().getClass()!=GlBuffer.class || u.buffer().isClosed()
                    || (u.buffer().usage()&GpuBuffer.USAGE_UNIFORM)==0 || u.offset()<0 || u.length()<=0
                    || u.offset()>u.buffer().size() || u.length()>u.buffer().size()-u.offset())return false;
        }
        for(var entry:textures.entrySet()){var t=entry.getValue();if(entry.getKey()==null || t==null || t.view==null || t.view.isClosed() || t.view.texture().isClosed() || !(t.view.texture() instanceof GlTexture) || t.sampler==null || t.sampler.getClass()!=GlSampler.class || ((GlSampler)t.sampler).isClosed())return false;}
        return true;
    }
    /** Retains values from this draw even if a later draw overwrites or closes its buffers. */
    private Map<String,GpuBufferSlice> snapshotUniforms(Map<String,GpuBufferSlice> uniforms){
        int alignment=RenderSystem.getDevice().getUniformOffsetAlignment();
        if(alignment<=0 || uniforms.size()>32)return null;
        long end=uniformCursor;
        for(var u:uniforms.values()){
            if(u.offset()%alignment!=0 || u.length()>UNIFORM_CAPACITY)return null;
            long size=GL45.glGetNamedBufferParameteri64(handle(u.buffer()),GL15.GL_BUFFER_SIZE);
            if(u.offset()>size || u.length()>size-u.offset())return null;
            end=((end+alignment-1)/alignment)*alignment+u.length();
            if(end>UNIFORM_CAPACITY)return null;
        }
        if(uniformArena==null)uniformArena=RenderSystem.getDevice().createBuffer(
                ()->"Native primary visibility uniforms",GpuBuffer.USAGE_UNIFORM|GpuBuffer.USAGE_COPY_DST,UNIFORM_CAPACITY);
        if(uniformArena.isClosed())return null;
        var copies=new HashMap<String,GpuBufferSlice>(uniforms.size());
        int destination=handle(uniformArena);
        for(var entry:uniforms.entrySet()){
            var u=entry.getValue();int source=handle(u.buffer());
            if(source==destination)return null;
            uniformCursor=(int)(((long)uniformCursor+alignment-1)/alignment*alignment);
            GL45.glCopyNamedBufferSubData(source,destination,u.offset(),uniformCursor,u.length());
            copies.put(entry.getKey(),uniformArena.slice(uniformCursor,u.length()));
            uniformCursor+=(int)u.length();
        }
        return Map.copyOf(copies);
    }
    /** Vertex padding byte35 contains0 for unowned vertices or stateId+1 for IDs0..63. */
    public boolean stageTaggedEntityQuads(RenderPipeline source,GpuBuffer vertices,int count,GpuTextureView color,GpuTextureView depth,
                                         Map<String,GpuBufferSlice> uniforms,Map<String,TextureBinding> textures){
        if(!available() || source==null || !inputsCurrent || rendered || count<=0 || count%4!=0 || sources.size()>=MAX_BATCHES || !view(color) || !view(depth)
                || vertices==null || vertices.getClass()!=GlBuffer.class || vertices.isClosed() || (vertices.usage()&GpuBuffer.USAGE_VERTEX)==0
                || !bindings(uniforms,textures))return reject("invalid source inputs or closed frame");
        //#if MC==1_21_11
        //$$ if(source.getBlendFunction().isPresent()){
        //$$     var texture=textures.get("Sampler0");
        //$$     if(texture==null || texture.view().texture().getFormat()!=TextureFormat.RGBA8
        //$$             || GL11.glGetInteger(GL20.GL_BLEND_EQUATION_ALPHA)!=GL14.GL_FUNC_ADD)return reject("unverified blended alpha inputs");
        //$$ }
        //#endif
        long length=(long)count*36;if(length>CAPACITY || (long)cursor+length>CAPACITY || vertices.size()<length)return reject("vertex arena capacity");
        var main=Minecraft.getInstance().getMainRenderTarget();
        if(color.texture()!=main.getColorTexture() || depth.texture()!=main.getDepthTexture())return reject("source is not main target");
        if(sourceColor!=null && (sourceColor!=color.texture() || sourceDepth!=depth.texture() || width!=main.width || height!=main.height))return reject("main target changed");
        if(GL45.glGetTextureLevelParameteri(((GlTexture)color.texture()).glId(),0,GL11.GL_TEXTURE_INTERNAL_FORMAT)!=GL11.GL_RGBA8)return reject("unsupported color format");
        var pipeline=NativeVisibilityPipeline.compile(source);if(pipeline==null)return reject("source pipeline unverified");
        var program=NativeVisibilityPipeline.sourceProgram(source,pipeline);
        if(program==null || !NativeVisibilityPipeline.matchesInputs(source,pipeline,uniforms,textures.keySet()))return reject("linked shader input coverage");
        int src=handle(vertices);if(GL45.glGetNamedBufferParameteri64(src,GL15.GL_BUFFER_SIZE)<length)return reject("short source vertex buffer");
        if(arena==null){arena=RenderSystem.getDevice().createBuffer(()->"Native primary visibility vertices",GpuBuffer.USAGE_VERTEX|GpuBuffer.USAGE_COPY_DST,CAPACITY);arenaOwner=ordering;}
        int dst=handle(arena);if(src==dst || GL45.glGetNamedBufferParameteri64(dst,GL15.GL_BUFFER_SIZE)!=CAPACITY)return reject("invalid vertex arena");
        var retained=snapshotUniforms(uniforms);if(retained==null)return reject("uniform snapshot capacity or alignment");
        GL45.glCopyNamedBufferSubData(src,dst,0,cursor,length);
        sources.add(new Source(source,program,pipeline,cursor/36,count/4*6,retained,Map.copyOf(textures)));
        for(var binding:textures.values())sampled.add(binding.view.texture());
        sourceColor=color.texture();sourceDepth=depth.texture();width=main.width;height=main.height;cursor+=(int)length;return true;
    }
    /** Call before consuming capture payloads; failure leaves the visibility target untouched. */
    public boolean ready(TextureTarget scene,int image){
        if(!available() || !inputsCurrent || sources.isEmpty() || arena==null || arena.isClosed() || uniformArena==null || uniformArena.isClosed() || !view(scene==null?null:scene.getColorTextureView())
                || !view(scene.getDepthTextureView()) || scene.width!=width || scene.height!=height || image<=0 || !GL11.glIsTexture(image))return false;
        var main=Minecraft.getInstance().getMainRenderTarget();
        if(sourceColor.isClosed() || sourceDepth.isClosed() || main.getColorTexture()!=sourceColor || main.getDepthTexture()!=sourceDepth || main.width!=width || main.height!=height)return false;
        if(GL45.glGetTextureLevelParameteri(image,0,GL11.GL_TEXTURE_INTERNAL_FORMAT)!=GL30.GL_RG32UI
                || GL45.glGetTextureLevelParameteri(image,0,GL11.GL_TEXTURE_WIDTH)!=width || GL45.glGetTextureLevelParameteri(image,0,GL11.GL_TEXTURE_HEIGHT)!=height)return false;
        for(var s:sources){
            if(!bindings(s.uniforms,s.textures))return false;
            //#if MC==1_21_11
            //$$ if(s.source.getBlendFunction().isPresent() && GL11.glGetInteger(GL20.GL_BLEND_EQUATION_ALPHA)!=GL14.GL_FUNC_ADD)return false;
            //#endif
            var compiled=RenderSystem.getDevice().precompilePipeline(s.source);
            if(!(compiled instanceof GlRenderPipeline gl) || !gl.isValid() || gl.program()!=s.program)return false;
        }
        return true;
    }
    public boolean render(TextureTarget scene,int image){
        if(rendered)return available() && image==renderedImage && scene!=null && scene.getDepthTexture()==renderedDepth;
        if(!ready(scene,image))return false;
        draw(scene,image,true);rendered=true;renderedImage=image;renderedDepth=scene.getDepthTexture();return true;
    }
    /** Independent caller-owned image for a reference comparison. */
    public boolean renderReference(TextureTarget scene,int image){if(!ready(scene,image))return false;draw(scene,image,false);return true;}
    private void draw(TextureTarget scene,int image,boolean countDraws){
        GL42.glMemoryBarrier(GL42.GL_BUFFER_UPDATE_BARRIER_BIT|GL42.GL_UNIFORM_BARRIER_BIT|GL42.GL_VERTEX_ATTRIB_ARRAY_BARRIER_BIT|GL43.GL_SHADER_STORAGE_BARRIER_BIT|GL42.GL_SHADER_IMAGE_ACCESS_BARRIER_BIT);
        var indices=RenderSystem.getSequentialBuffer(VertexFormat.Mode.QUADS);
        try(var storage=new NativeVisibilityBindings.Storage(4,handle(arena));var target=new NativeVisibilityBindings.Image(1,image,GL15.GL_READ_WRITE,GL30.GL_RG32UI)){
            for(var source:sources){
                var index=indices.getBuffer(source.count);
                try(var pass=RenderSystem.getDevice().createCommandEncoder().createRenderPass(()->"Native primary visibility",scene.getColorTextureView(),OptionalInt.empty(),scene.getDepthTextureView(),OptionalDouble.empty())){
                    pass.setPipeline(source.pipeline);
                    for(var u:source.uniforms.entrySet())pass.setUniform(u.getKey(),u.getValue());
                    for(var t:source.textures.entrySet())pass.bindTexture(t.getKey(),t.getValue().view,t.getValue().sampler);
                    pass.setVertexBuffer(0,arena);pass.setIndexBuffer(index,indices.type());pass.drawIndexed(source.base,0,source.count,1);if(countDraws)draws++;
                }
            }
        }catch(RuntimeException | Error failure){failed=true;throw failure;}
        GL42.glMemoryBarrier(GL42.GL_SHADER_IMAGE_ACCESS_BARRIER_BIT|GL42.GL_TEXTURE_UPDATE_BARRIER_BIT);
    }
    public static boolean observingInputs(){var batch=current;return batch!=null && batch.inputsCurrent;}
    /** Notify before native or external writes; a completed image remains an independent result. */
    public static void textureWritten(GpuTexture texture){
        var batch=current;
        if(batch!=null && batch.inputsCurrent && texture!=null && (!RenderSystem.isOnRenderThread() || batch.sampled.contains(texture)))batch.inputsCurrent=false;
    }
    /** Foreign rendering without a per-texture write proof must abandon pending source reuse. */
    public static void invalidateInputs(){var batch=current;if(batch!=null)batch.inputsCurrent=false;}
    public static void finishFrame(){if(current!=null)current.close();}
    @Override public void close(){if(closed)return;closed=true;sources.clear();sampled.clear();sourceColor=sourceDepth=renderedDepth=null;if(current==this)current=null;}
}

//#else
//$$ public final class NativeVisibilityBatch {}
//#endif
