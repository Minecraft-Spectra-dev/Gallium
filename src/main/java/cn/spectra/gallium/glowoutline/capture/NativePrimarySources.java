package cn.spectra.gallium.glowoutline.capture;

//#if MC==1_21_11 || MC==1_26_01
import cn.spectra.gallium.glowoutline.shader.NativeMaskChannels;
import com.mojang.blaze3d.buffers.*;
import com.mojang.blaze3d.opengl.GlRenderPipeline;
import com.mojang.blaze3d.pipeline.*;
import com.mojang.blaze3d.systems.*;
import com.mojang.blaze3d.textures.*;
import com.mojang.blaze3d.vertex.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.rendertype.RenderType;
import org.joml.Matrix4fc;
import org.lwjgl.system.MemoryUtil;
import java.lang.reflect.*;
import java.nio.ByteOrder;
import java.util.*;

/** Collects original native geometry without retaining a second CPU copy of its vertices. */
public final class NativePrimarySources implements AutoCloseable {
    //#if MC>=1_26_00
    private static final VertexFormat ENTITY_FORMAT=DefaultVertexFormat.ENTITY;
    //#else
    //$$ private static final VertexFormat ENTITY_FORMAT=DefaultVertexFormat.NEW_ENTITY;
    //#endif

    private static final int MAX_SPANS=4096,MAX_MESHES=256;
    private static NativePrimarySources current;
    private static final List<State> statePool=new ArrayList<>(64);
    //#if MC>=1_26_00
    private static final Field COUNT=field(BufferBuilder.class,"vertices"),BACKEND=field(RenderPass.class,"backend");
    private static Field vertices,index,indexType,pipeline,uniforms,samplers;
    private static Method scissor;
    //#endif
    static {cn.spectra.gallium.glowoutline.shader.GlowResources.register(()->{finishFrame();statePool.clear();});}
    private final NativeVisibilityBatch batch;
    private final IdentityHashMap<GlowCaptureState,State> states=new IdentityHashMap<>();
    private final IdentityHashMap<BufferBuilder,Open> open=new IdentityHashMap<>();
    private final IdentityHashMap<BufferBuilder,List<Span>> spans=new IdentityHashMap<>();
    private final IdentityHashMap<MeshData,Mesh> meshes=new IdentityHashMap<>();
    private final IdentityHashMap<RenderPass,Attachments> attachments=new IdentityHashMap<>();
    private final int width,height;
    private Scope scope;
    private int spanCount;
    private long revision,proofRevision=-1,proofSourceRevision=-1,proofWorldRoles;
    private final List<GlowCaptureState> proofOwners=new ArrayList<>();
    private boolean failed,closed;
    private String failure;
    private static final class State {
        final int id;
        final ProjectedMaskBounds bounds=new ProjectedMaskBounds();
        final Set<Mesh> meshes=Collections.newSetFromMap(new IdentityHashMap<>());
        State(int id){this.id=id;}
        void reset(int width,int height){meshes.clear();bounds.begin(width,height);}
    }
    private record Open(GlowCaptureState owner,RenderType type,int first){}
    private record Span(GlowCaptureState owner,RenderType type,int first,int end){}
    private record Attachments(GpuTextureView color,GpuTextureView depth){}
    private static final class Mesh {
        final List<Span> spans;
        final int count;
        boolean transformed,staged,delivered;
        //#if MC==1_21_11
        //$$ GpuBuffer sortedIndices;
        //$$ VertexFormat.IndexType sortedType;
        //#endif
        Mesh(List<Span> spans,int count){this.spans=spans;this.count=count;}
    }
    private static Field field(Class<?> type,String name){
        try{var result=type.getDeclaredField(name);return result.trySetAccessible()?result:null;}
        catch(ReflectiveOperationException e){return null;}
    }
    private NativePrimarySources(NativeVisibilityBatch batch){
        this.batch=batch;var target=Minecraft.getInstance().getMainRenderTarget();width=target.width;height=target.height;current=this;
    }
    /** The caller owns the frame and must close it after all image consumers have finished. */
    public static NativePrimarySources begin(){
        if(current!=null || !RenderSystem.isOnRenderThread() || !NativeSourceCoverage.active()
                //#if MC>=1_26_00
                || COUNT==null || BACKEND==null
                //#endif
                )return null;
        var batch=NativeVisibilityBatch.begin();return batch==null?null:new NativePrimarySources(batch);
    }
    public static boolean active(){return current!=null && !current.closed;}
    public static NativePrimarySources current(){return current;}
    public void invalidate(String reason){revision++;failed=true;if(failure==null)failure=reason;}
    public String failure(){return failure;}
    private boolean usable(){return !failed && !closed && current==this;}
    private State state(GlowCaptureState owner){
        var found=states.get(owner);if(found!=null)return found;
        if(states.size()>=64){invalidate("owner capacity");return null;}
        int slot=states.size();
        if(slot==statePool.size())statePool.add(new State(slot));
        found=statePool.get(slot);found.reset(width,height);states.put(owner,found);revision++;return found;
    }
    public int reserveId(GlowCaptureState owner){if(!usable() || owner==null)return -1;var value=state(owner);return value==null?-1:value.id;}
    public int id(GlowCaptureState owner){var state=states.get(owner);return state==null?-1:state.id;}
    public int stateCount(){return states.size();}
    public int stagedCount(){return batch.stagedCount();}
    public int drawnCount(){return batch.drawnCount();}
    public int byteCount(){return batch.byteCount();}
    public int draws(GlowCaptureState owner){var state=states.get(owner);return state==null?0:state.meshes.size();}
    public static Scope enter(Object source,VertexConsumer consumer,RenderType type){
        var frame=current;if(frame==null || !frame.usable() || ModernMaskBounds.currentState()!=null)return null;
        if(frame.scope!=null){frame.invalidate("nested source rendering");return null;}
        var owner=NativeSourceCoverage.owner(source);if(owner==null || owner.firstPerson || owner.guiEntity!=null)return null;
        var result=new Scope(frame,owner);frame.scope=result;frame.revision++;if(consumer!=null)buffer(consumer,type);return result;
    }
    public static final class Scope implements AutoCloseable {
        private final NativePrimarySources frame;
        private final GlowCaptureState owner;
        private final Set<BufferBuilder> builders=Collections.newSetFromMap(new IdentityHashMap<>());
        private boolean closed;
        private Scope(NativePrimarySources frame,GlowCaptureState owner){this.frame=frame;this.owner=owner;}
        @Override public void close(){
            if(closed)return;closed=true;
            for(var builder:builders)frame.closeSpan(builder);
            if(frame.scope==this)frame.scope=null;else frame.invalidate("source scope order");
            frame.revision++;builders.clear();
        }
    }
    //#if MC>=1_26_00
    private int count(BufferBuilder builder){try{return COUNT.getInt(builder);}catch(IllegalAccessException e){invalidate("vertex count unavailable");return -1;}}
    //#else
    //$$ private int count(BufferBuilder builder){return ((cn.spectra.gallium.glowoutline.mixin.accessor.NativePrimaryBuilderAccessor)builder).gallium$vertexCount();}
    //#endif
    public static void buffer(VertexConsumer consumer,RenderType type){
        var frame=current;if(frame==null || frame.scope==null || !frame.usable())return;
        if(type==null){frame.invalidate("missing source render type");return;}
        if(!(consumer instanceof BufferBuilder builder)){frame.invalidate("unknown source consumer");return;}
        var previous=frame.open.get(builder);
        if(previous!=null && (previous.owner!=frame.scope.owner || previous.type!=type))frame.closeSpan(builder);
        if(!frame.open.containsKey(builder)){
            if(frame.open.size()>=MAX_MESHES || frame.scope.builders.size()>=MAX_MESHES && !frame.scope.builders.contains(builder)){frame.invalidate("open source capacity");return;}
            frame.open.put(builder,new Open(frame.scope.owner,type,frame.count(builder)));frame.scope.builders.add(builder);
        }
    }
    private void closeSpan(BufferBuilder builder){
        var value=open.remove(builder);if(value==null)return;int end=count(builder);
        if(value.first<0 || end<value.first){invalidate("source range rewound");return;}
        if(end==value.first)return;
        if(++spanCount>MAX_SPANS){invalidate("source span capacity");return;}
        if(NativeMaskChannels.canOmit(value.owner,value.type.pipeline()))return;
        spans.computeIfAbsent(builder,ignored->new ArrayList<>()).add(new Span(value.owner,value.type,value.first,end));revision++;
    }
    public static void building(BufferBuilder builder){var frame=current;if(frame!=null)frame.closeSpan(builder);}
    public static void built(BufferBuilder builder,MeshData data){
        var frame=current;if(frame==null)return;var ranges=frame.spans.remove(builder);
        if(ranges==null || ranges.isEmpty() || !frame.usable())return;
        if(data==null){frame.invalidate("missing built source mesh");return;}
        var draw=data.drawState();var format=draw.format();
        if(ByteOrder.nativeOrder()!=ByteOrder.LITTLE_ENDIAN || format!=ENTITY_FORMAT || format.getVertexSize()!=36
                || format.getOffset(VertexFormatElement.NORMAL)!=32 || VertexFormatElement.NORMAL.byteSize()!=3
                || draw.mode()!=VertexFormat.Mode.QUADS || data.indexBuffer()!=null || draw.vertexCount()%4!=0
                || data.vertexBuffer().remaining()!=(long)draw.vertexCount()*36 || frame.meshes.size()>=MAX_MESHES || frame.meshes.containsKey(data)){
            frame.invalidate("unsupported source mesh");return;
        }
        int previousEnd=0;
        for(var range:ranges){
            if(range.first<previousEnd || range.end>draw.vertexCount() || range.first%4!=0 || range.end%4!=0
                    || frame.state(range.owner)==null){frame.invalidate("overlapping or split source quad");return;}
            previousEnd=range.end;
        }
        var mesh=new Mesh(ranges,draw.vertexCount());
        for(var range:ranges)frame.states.get(range.owner).meshes.add(mesh);
        frame.meshes.put(data,mesh);frame.revision++;
    }
    public static void transformed(RenderType type,MeshData data,Matrix4fc modelView){
        var frame=current;if(frame==null || !frame.usable())return;var mesh=frame.meshes.get(data);if(mesh==null)return;
        // Calibration and shader compilation may open their own render passes.
        // Complete them before the original source pass is created.
        if(cn.spectra.gallium.glowoutline.shader.NativeVisibilityPipeline.compile(type.pipeline())==null){frame.invalidate("source pipeline unverified before pass");return;}
        var projection=ProjectionMatrixTracker.lookup(RenderSystem.getProjectionMatrixBuffer());
        if(mesh.transformed || projection==null){frame.invalidate("untracked or repeated source transform");return;}
        var bytes=data.vertexBuffer();int position=bytes.position(),limit=bytes.limit();
        try{
            for(var span:mesh.spans){
                if(span.type!=type){frame.invalidate("source render type changed");return;}
                if(!span.owner.capturedProjectionMatrix4fValid || !projection.equals(span.owner.capturedProjectionMatrix4f)){
                    frame.invalidate("source projection changed");return;
                }
                var part=bytes.duplicate().order(bytes.order());part.position(position+span.first*36).limit(position+span.end*36);
                frame.states.get(span.owner).bounds.includeTransient(part,36,span.end-span.first,0,modelView,projection);
            }
        }finally{bytes.limit(limit).position(position);}
        // Only verified raster-only source programs may receive padding tags.
        // Declined pipelines retain every original byte, including padding.
        long pointer=MemoryUtil.memAddress(data.vertexBuffer());
        for(int i=0;i<mesh.count;i++)MemoryUtil.memPutByte(pointer+(long)i*36+35,(byte)0);
        for(var span:mesh.spans){
            int id=frame.states.get(span.owner).id;
            for(int i=span.first;i<span.end;i++)MemoryUtil.memPutByte(pointer+(long)i*36+35,(byte)(id+1));
        }
        mesh.transformed=true;frame.revision++;
    }
    public static void attachments(RenderPass pass,GpuTextureView color,GpuTextureView depth,boolean clear,MeshData data){
        var frame=current;if(frame==null || !frame.usable() || !frame.meshes.containsKey(data))return;
        if(clear || pass==null || frame.attachments.size()>=MAX_MESHES){frame.invalidate("unsupported source pass");return;}
        frame.attachments.put(pass,new Attachments(color,depth));
    }
    //#if MC==1_21_11
    //$$ /** The uploaded native index buffer must contain every original quad exactly once. */
    //$$ public static void indexUploaded(RenderType type,MeshData data,java.nio.ByteBuffer bytes,GpuBuffer uploaded){
    //$$     var frame=current;if(frame==null || !frame.usable())return;var mesh=frame.meshes.get(data);if(mesh==null)return;
    //$$     if(!type.pipeline().getBlendFunction().isPresent() || !mesh.transformed || mesh.sortedIndices!=null
    //$$             || uploaded==null || uploaded.getClass()!=com.mojang.blaze3d.opengl.GlBuffer.class || uploaded.isClosed()
    //$$             || (uploaded.usage()&GpuBuffer.USAGE_INDEX)==0 || data.indexBuffer()==null || !bytes.equals(data.indexBuffer())
    //$$             || !QuadIndexPermutation.complete(bytes,data.drawState().indexType().bytes,mesh.count)){
    //$$         frame.invalidate("unverified sorted source quads");return;
    //$$     }
    //$$     mesh.sortedIndices=uploaded;mesh.sortedType=data.drawState().indexType();frame.revision++;
    //$$ }
    //#endif
    @SuppressWarnings("unchecked")
    public static void drawing(RenderPass pass,RenderType type,MeshData data,int base,int first,int count,int instances){
        var frame=current;if(frame==null || !frame.usable())return;var mesh=frame.meshes.get(data);if(mesh==null)return;
        var target=frame.attachments.remove(pass);
        if(target==null || !mesh.transformed || mesh.staged || base!=0 || first!=0 || count!=mesh.count/4*6 || instances!=1){frame.invalidate("unsupported source draw");return;}
        //#if MC>=1_26_00
        try{
            Object backend=BACKEND.get(pass);var cls=backend.getClass();
            if(!cls.getName().equals("com.mojang.blaze3d.opengl.GlRenderPass")){frame.invalidate("unknown source backend");return;}
            if(vertices==null){
                vertices=field(cls,"vertexBuffers");index=field(cls,"indexBuffer");indexType=field(cls,"indexType");pipeline=field(cls,"pipeline");
                uniforms=field(cls,"uniforms");samplers=field(cls,"samplers");scissor=cls.getDeclaredMethod("isScissorEnabled");scissor.setAccessible(true);
            }
            var sequential=RenderSystem.getSequentialBuffer(VertexFormat.Mode.QUADS);
            if(vertices==null || index==null || indexType==null || pipeline==null || uniforms==null || samplers==null
                    || (boolean)scissor.invoke(backend) || index.get(backend)!=sequential.getBuffer(count) || indexType.get(backend)!=sequential.type()
                    || pipeline.get(backend)!=RenderSystem.getDevice().precompilePipeline(type.pipeline())){frame.invalidate("unverified native draw bindings");return;}
            var textures=new HashMap<String,NativeVisibilityBatch.TextureBinding>();
            for(var entry:((Map<String,Object>)samplers.get(backend)).entrySet()){
                var binding=entry.getValue();var view=field(binding.getClass(),"view");var sampler=field(binding.getClass(),"sampler");
                if(view==null || sampler==null){frame.invalidate("unreadable source sampler");return;}
                textures.put(entry.getKey(),new NativeVisibilityBatch.TextureBinding((GpuTextureView)view.get(binding),(GpuSampler)sampler.get(binding)));
            }
            mesh.staged=frame.batch.stageTaggedEntityQuads(type.pipeline(),((GpuBuffer[])vertices.get(backend))[0],mesh.count,
                    target.color,target.depth,(Map<String,GpuBufferSlice>)uniforms.get(backend),textures);
            frame.revision++;
            if(!mesh.staged)frame.invalidate("source batch declined: "+frame.batch.rejectionReason());
        }catch(ReflectiveOperationException | ClassCastException e){frame.invalidate("native binding access failed");}
        //#else
        //$$ if(pass.getClass()!=com.mojang.blaze3d.opengl.GlRenderPass.class){frame.invalidate("unknown source backend");return;}
        //$$ var backend=(com.mojang.blaze3d.opengl.GlRenderPass)pass;
        //$$ var access=(cn.spectra.gallium.glowoutline.mixin.accessor.NativePrimaryPassAccessor)backend;
        //$$ var sequential=RenderSystem.getSequentialBuffer(VertexFormat.Mode.QUADS);
        //$$ boolean indexMatches=mesh.sortedIndices!=null
        //$$         ? access.gallium$index()==mesh.sortedIndices && access.gallium$indexType()==mesh.sortedType
        //$$         : access.gallium$index()==sequential.getBuffer(count) && access.gallium$indexType()==sequential.type();
        //$$ if(backend.isScissorEnabled() || !indexMatches
        //$$         || access.gallium$pipeline()!=RenderSystem.getDevice().precompilePipeline(type.pipeline())){frame.invalidate("unverified native draw bindings");return;}
        //$$ var textures=new HashMap<String,NativeVisibilityBatch.TextureBinding>();
        //$$ for(var entry:access.gallium$samplers().entrySet()){
        //$$     var binding=(cn.spectra.gallium.glowoutline.mixin.accessor.NativePrimarySamplerAccessor)entry.getValue();textures.put(entry.getKey(),new NativeVisibilityBatch.TextureBinding(binding.gallium$view(),binding.gallium$sampler()));
        //$$ }
        //$$ mesh.staged=frame.batch.stageTaggedEntityQuads(type.pipeline(),access.gallium$vertices()[0],mesh.count,target.color,target.depth,access.gallium$uniforms(),textures);
        //$$ frame.revision++;if(!mesh.staged)frame.invalidate("source batch declined: "+frame.batch.rejectionReason());
        //#endif
    }
    public static void delivered(MeshData data){var frame=current;if(frame!=null){var mesh=frame.meshes.get(data);if(mesh!=null && !mesh.delivered){mesh.delivered=true;frame.revision++;}}}
    public boolean ready(GlowCaptureState owner){
        if(!usable() || scope!=null || !open.isEmpty() || !spans.isEmpty() || !NativeSourceCoverage.complete(owner))return false;var state=states.get(owner);
        if(state==null || state.meshes.isEmpty() || !state.bounds.valid())return false;
        for(var mesh:state.meshes)if(!mesh.transformed || !mesh.staged || !mesh.delivered)return false;
        return true;
    }
    /** Borrowed until this source frame closes; consumers copy the numeric bounds. */
    public ProjectedMaskBounds bounds(GlowCaptureState owner){return ready(owner)?states.get(owner).bounds:null;}
    private static boolean world(GlowCaptureState owner){return !owner.firstPerson && owner.guiEntity==null;}
    private boolean sameOwners(Collection<GlowCaptureState> owners){
        if(owners.size()!=proofOwners.size())return false;int index=0;
        for(var owner:owners){
            if(proofOwners.get(index)!=owner || world(owner)!=((proofWorldRoles & 1L<<index)!=0))return false;
            index++;
        }
        return true;
    }
    /** Reuses proof only while source identities, staged work and the active owners are unchanged. */
    public boolean readyFrame(){
        if(!usable() || scope!=null || !open.isEmpty() || !spans.isEmpty())return false;
        var owners=GlowCaptureManager.getActiveStates();
        if(owners.isEmpty() || owners.size()>64)return false;
        long sourceRevision=NativeSourceCoverage.revision();
        if(proofRevision==revision && proofSourceRevision==sourceRevision && sameOwners(owners))return true;
        for(var owner:owners)if(world(owner) && !ready(owner))return false;
        proofOwners.clear();proofWorldRoles=0;int index=0;
        for(var owner:owners){proofOwners.add(owner);if(world(owner))proofWorldRoles|=1L<<index;index++;}
        proofRevision=revision;proofSourceRevision=sourceRevision;return true;
    }
    public boolean render(TextureTarget scene,int image){
        if(!readyFrame())return false;
        return batch.render(scene,image);
    }
    public boolean renderReference(TextureTarget scene,int image){return usable() && batch.renderReference(scene,image);}
    public static void finishFrame(){if(current!=null)current.close();}
    @Override public void close(){
        if(closed)return;closed=true;revision++;proofOwners.clear();batch.close();
        for(var state:states.values()){state.meshes.clear();state.bounds.invalidate();}open.clear();spans.clear();meshes.clear();attachments.clear();states.clear();scope=null;
        if(current==this)current=null;
    }
}
//#else
//$$ public final class NativePrimarySources {}
//#endif
