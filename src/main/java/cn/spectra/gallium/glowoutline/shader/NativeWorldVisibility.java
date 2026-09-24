package cn.spectra.gallium.glowoutline.shader;

//#if MC==1_21_11 || MC==1_26_01
import cn.spectra.gallium.glowoutline.IrisCompat;
import cn.spectra.gallium.glowoutline.SuperResolutionCompat;
import cn.spectra.gallium.glowoutline.capture.*;
import com.mojang.blaze3d.pipeline.*;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.google.common.collect.ImmutableList;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.Model;
import net.minecraft.client.renderer.SubmitNodeStorage;
//#if MC>=1_26_00
import net.minecraft.client.resources.model.geometry.BakedQuad;
//#else
//$$ import net.minecraft.client.renderer.block.model.BakedQuad;
//#endif
import org.lwjgl.opengl.*;
import java.nio.ByteBuffer;
import java.util.*;

/** Owns a frame's original-source image and its deferred composite receipts. */
public final class NativeWorldVisibility {
    private static Frame current;
    private static boolean permitted,attempted;
    private static int image,width,height;
    private static long pendingBytes;
    private static OpenGlMaskOrdering.Stamp imageOwner;
    private static Stats last=new Stats(0,0,0,null);
    private static final Map<Class<?>,Boolean> nativeModels=new IdentityHashMap<>();
    public record Stats(int adopted,int staged,int draws,String declined){}
    private static final class Frame {
        final NativeSourceCoverage coverage;
        final NativePrimarySources sources;
        final RenderTarget output;
        final long epoch;
        final OpenGlMaskOrdering.Stamp ordering;
        final Map<String,NativeGlowInstances.Pipeline> pipelines=new HashMap<>();
        final IdentityHashMap<GlowCaptureState,Lease> leases=new IdentityHashMap<>();
        TextureTarget scene;
        long sceneGeneration;
        boolean prepared,closed;
        String declined;
        Frame(NativeSourceCoverage coverage,NativePrimarySources sources,RenderTarget output){
            this.coverage=coverage;this.sources=sources;this.output=output;
            epoch=SuperResolutionCompat.currentFrameEpoch();ordering=OpenGlMaskOrdering.observe();
        }
        boolean valid(){
            return !closed && current==this && ordering!=null && ordering.current()
                    && epoch==SuperResolutionCompat.currentFrameEpoch()
                    && output==SharedMaskFrame.currentMainTarget()
                    && output.width==width && output.height==height
                    && scene!=null && !scene.getDepthTexture().isClosed()
                    && sceneGeneration==GlowCaptureManager.getSceneDepthGeneration();
        }
        void close(){
            if(closed)return;closed=true;
            last=new Stats(leases.size(),sources.stagedCount(),sources.drawnCount(),declined);
            leases.clear();pipelines.clear();sources.close();coverage.close();
            if(current==this)current=null;
        }
    }
    static final class Lease implements MaskStorageImage {
        final Frame frame;
        final GlowCaptureState state;
        final int id;
        final NativeGlowInstances.Pipeline pipeline;
        Lease(Frame frame,GlowCaptureState state,int id,NativeGlowInstances.Pipeline pipeline){
            this.frame=frame;this.state=state;this.id=id;this.pipeline=pipeline;
        }
        public int id(){return id;}
        public NativeGlowInstances.Pipeline pipeline(){return pipeline;}
        public boolean valid(){return frame.valid() && frame.prepared && frame.leases.get(state)==this && image>0;}
        public Object group(){return frame;}
        public NativeVisibilityBindings.Image bind(){
            if(!valid())throw new IllegalStateException("Expired native visibility receipt");
            return new NativeVisibilityBindings.Image(0,image,GL15.GL_READ_ONLY,GL30.GL_RG32UI);
        }
    }
    static {
        GlowResources.register(()->{finishFrame();disposeImage();nativeModels.clear();});
    }
    private NativeWorldVisibility(){}
    public static Stats lastStats(){return last;}
    public static boolean suspendBeforeSources(){if(current!=null)return false;permitted=false;return true;}
    public static long reservedBytes(){return pendingBytes!=0?pendingBytes:(long)width*height*8;}
    public static void beginFrame(){
        finishFrame();attempted=false;last=new Stats(0,0,0,null);
        permitted=SharedMaskFrame.selected() && !IrisCompat.isShaderActive() && !IrisCompat.isActiveSrRuntime()
                && !SuperResolutionCompat.ownsLateReplayFrame();
        var output=SharedMaskFrame.currentMainTarget();
        if(!permitted || output==null || image!=0 && (output.width!=width || output.height!=height))disposeImage();
    }
    private static boolean supported(GlowCaptureState state){
        if(state==null || state.firstPerson || state.guiEntity!=null || state.config==null || state.superResolutionPrepared
                || state.config.params().size()>252)return false;
        var contract=BoundedGlowContracts.get(state.config.shader());
        return contract!=null && contract.alphaDepthOnly() && contract.atlasStorage() && contract.instanceUniforms();
    }
    /** Starts before the first original world submission, without work on non-glowing frames. */
    public static void beginSource(GlowCaptureState state){
        if(attempted || !permitted || state==null || state.firstPerson || state.guiEntity!=null
                || NativeSourceCoverage.active() || !RenderSystem.isOnRenderThread())return;
        attempted=true;
        if(!supported(state)){last=new Stats(0,0,0,"effect contract unavailable");return;}
        if(NativeGlowInstances.visibilityPlan(state.config.shader())==null){last=new Stats(0,0,0,"effect sampling module unavailable");return;}
        var output=SharedMaskFrame.currentMainTarget();if(output==null)return;
        var coverage=NativeSourceCoverage.beginFrame();if(coverage==null)return;
        var sources=NativePrimarySources.begin();
        if(sources==null){coverage.close();last=new Stats(0,0,0,"source GPU frame unavailable");return;}
        current=new Frame(coverage,sources,output);
    }
    private static boolean rejectSource(String reason){if(current!=null && current.declined==null)current.declined=reason;return false;}
    /** Foreign models/quad implementations must retain their original capture callbacks. */
    public static boolean sourceAllowed(Object source){
        if(current==null)return true;
        if(source instanceof SubmitNodeStorage.ModelSubmit<?> model){
            if(model.crumblingOverlay()!=null || model.model()==null || model.pose()==null || model.pose().getClass()!=PoseStack.Pose.class)return false;
            Object state=model.state();
            if(state!=null && state.getClass()!=Object.class && state.getClass()!=Float.class && state.getClass()!=Integer.class
                    && !Objects.equals(state.getClass().getProtectionDomain().getCodeSource(),Model.class.getProtectionDomain().getCodeSource()))return false;
            return nativeModels.computeIfAbsent(model.model().getClass(),type->{
                if(!type.getName().startsWith(Model.class.getPackageName()+".") || type.isAnonymousClass() || type.isSynthetic())return false;
                try{return Objects.equals(type.getProtectionDomain().getCodeSource(),Model.class.getProtectionDomain().getCodeSource());}
                catch(SecurityException denied){return false;}
            });
        }
        //#if MC==1_21_11
        //$$ if(source instanceof SubmitNodeStorage.ModelPartSubmit part){
        //$$     if(part.modelPart()==null || part.pose()==null || part.pose().getClass()!=PoseStack.Pose.class
        //$$             || part.crumblingOverlay()!=null || part.outlineColor()!=0
        //$$             || part.sprite()!=null && part.sprite().getClass()!=net.minecraft.client.renderer.texture.TextureAtlasSprite.class)return false;
        //$$     return nativeLeafPart(part.modelPart());
        //$$ }
        //#endif
        if(source instanceof SubmitNodeStorage.ItemSubmit item){
            if(item.quads()==null || item.pose()==null || item.pose().getClass()!=PoseStack.Pose.class)return false;
            Class<?> type=item.quads().getClass();String name=type.getName();
            boolean materialized=type==ArrayList.class || name.equals("java.util.ImmutableCollections$ListN")
                    || name.equals("java.util.ImmutableCollections$List12")
                    || (name.equals("com.google.common.collect.RegularImmutableList") || name.equals("com.google.common.collect.SingletonImmutableList"))
                        && Objects.equals(type.getProtectionDomain().getCodeSource(),ImmutableList.class.getProtectionDomain().getCodeSource());
            if(!materialized || item.quads().size()>4096)return rejectSource("unsupported quad collection "+name);
            for(var quad:item.quads())if(quad==null || quad.getClass()!=BakedQuad.class)return false;
            return true;
        }
        return false;
    }
    //#if MC==1_21_11
    //$$ private static boolean nativeLeafPart(net.minecraft.client.model.geom.ModelPart part){
    //$$     var access=(cn.spectra.gallium.glowoutline.mixin.accessor.NativePrimaryPartAccessor)(Object)part;
    //$$     var children=access.gallium$children();var type=children.getClass();String mapName=type.getName();
    //$$     if(type!=HashMap.class && type!=LinkedHashMap.class && type!=it.unimi.dsi.fastutil.objects.Object2ObjectArrayMap.class
    //$$             && !mapName.equals("java.util.ImmutableCollections$MapN") && type!=Collections.emptyMap().getClass()
    //$$             && type!=com.google.common.collect.ImmutableMap.of().getClass())return false;
    //$$     if(!children.isEmpty())return false;
    //$$     var cubes=access.gallium$cubes();String name=cubes.getClass().getName();
    //$$     if(cubes.getClass()!=ArrayList.class && !name.equals("java.util.ImmutableCollections$ListN") && !name.equals("java.util.ImmutableCollections$List12")
    //$$             && cubes.getClass()!=Collections.emptyList().getClass() && cubes.getClass()!=Collections.singletonList(0).getClass()
    //$$             && cubes.getClass()!=ImmutableList.of().getClass() && cubes.getClass()!=ImmutableList.of(1).getClass())return false;
    //$$     if(cubes.size()>4096)return false;
    //$$     for(var cube:cubes)if(cube==null || cube.getClass()!=net.minecraft.client.model.geom.ModelPart.Cube.class)return false;
    //$$     return true;
    //$$ }
    //#endif
    private static void abandon(Frame frame,String reason){frame.declined=frame.declined==null?reason:frame.declined+"; "+reason;frame.close();}
    private static boolean ensureImage(int w,int h){
        if(image!=0 && (imageOwner==null || !imageOwner.current()))return false;
        if(image!=0 && (width!=w || height!=h))disposeImage();
        if(image!=0)return true;
        if(w<=0 || h<=0 || w>GL11.glGetInteger(GL11.GL_MAX_TEXTURE_SIZE) || h>GL11.glGetInteger(GL11.GL_MAX_TEXTURE_SIZE))return false;
        int atlasW=1<<32-Integer.numberOfLeadingZeros(w-1),atlasH=1<<32-Integer.numberOfLeadingZeros(h-1);
        pendingBytes=(long)w*h*8;
        try{
            if(!GlowCaptureManager.maskAtlasFitsBudget(w,h,atlasW,atlasH))return false;
            var owner=OpenGlMaskOrdering.observe();if(owner==null)return false;
            int created=GL45.glCreateTextures(GL11.GL_TEXTURE_2D);
            try{GL45.glTextureStorage2D(created,1,GL30.GL_RG32UI,w,h);}
            catch(RuntimeException failure){GL11.glDeleteTextures(created);return false;}
            image=created;width=w;height=h;imageOwner=owner;return true;
        }finally{pendingBytes=0;}
    }
    private static void disposeImage(){
        if(image!=0 && (imageOwner==null || !imageOwner.current()))return;
        if(image!=0)GL11.glDeleteTextures(image);
        image=width=height=0;imageOwner=null;
    }
    private static boolean prepare(Frame frame){
        if(frame.prepared)return frame.valid();
        if(!frame.sources.readyFrame()){abandon(frame,"incomplete original geometry");return false;}
        var scene=GlowCaptureManager.getSceneDepthTarget();
        if(scene==null || scene.width!=frame.output.width || scene.height!=frame.output.height
                || scene.getDepthTextureView()==null || !ModernSparseGlow.visibilityReady(frame.output)){
            abandon(frame,"unavailable scene or composite scope");return false;
        }
        long bytes=0;String previous=null;int group=0;
        for(var state:GlowCaptureManager.getActiveStates()){
            if(state.guiEntity!=null || state.firstPerson){bytes+=4096L*16;previous=null;group=0;continue;}
            if(!supported(state)){abandon(frame,"unsupported effect contract");return false;}
            var plan=frame.pipelines.computeIfAbsent(state.config.shader(),NativeGlowInstances::visibilityPlan);
            var contract=BoundedGlowContracts.get(state.config.shader());
            var bounds=frame.sources.bounds(state);
            if(plan==null || bounds==null || contract.rectangle(bounds,frame.output.width,frame.output.height,state.itemWorldToUv.x,state.itemWorldToUv.y)==null){
                abandon(frame,"unverified composite pipeline");return false;
            }
            if(!state.config.shader().equals(previous) || group==16){bytes+=(long)plan.stride()*16;previous=state.config.shader();group=0;}
            group++;
        }
        if(bytes>2*1024*1024 || !ensureImage(frame.output.width,frame.output.height)){
            abandon(frame,"visibility allocation budget");return false;
        }
        GL42.glMemoryBarrier(GL42.GL_TEXTURE_UPDATE_BARRIER_BIT|GL42.GL_SHADER_IMAGE_ACCESS_BARRIER_BIT);
        GL44.glClearTexImage(image,0,GL30.GL_RG_INTEGER,GL11.GL_UNSIGNED_INT,(ByteBuffer)null);
        GL42.glMemoryBarrier(GL42.GL_TEXTURE_UPDATE_BARRIER_BIT|GL42.GL_SHADER_IMAGE_ACCESS_BARRIER_BIT);
        if(!frame.sources.render(scene,image)){abandon(frame,"source image unavailable");return false;}
        frame.scene=scene;frame.sceneGeneration=GlowCaptureManager.getSceneDepthGeneration();frame.prepared=true;
        return true;
    }
    /** Only the owned composite path may consume a payload without filling its borrowed texture. */
    static boolean replay(GlowCaptureState state,Minecraft minecraft,TextureTarget mask){
        var frame=current;
        if(frame==null || !supported(state) || minecraft!=Minecraft.getInstance() || mask==null
                || !GlowCaptureManager.sequentialFrameCurrent() || !GlowCaptureManager.sequentialPayloadReady(state)
                || !SharedMaskFrame.maskMatches(mask) || state.captureDispatcher==null
                || mask.width!=frame.output.width || mask.height!=frame.output.height)return false;
        if(!prepare(frame))return false;
        var bounds=frame.sources.bounds(state);int id=frame.sources.id(state);
        if(bounds==null || id<0 || id>=64 || frame.leases.containsKey(state))return false;
        if(!state.beginOrdinaryReplayAttempt(frame.epoch))return false;
        state.maskBounds.begin(frame.output.width,frame.output.height);state.maskBounds.include(bounds);
        //#if MC>=1_26_00
        state.captureDispatcher.clearSubmitNodes();
        //#else
        //$$ state.captureDispatcher.getSubmitNodeStorage().clear();
        //#endif
        state.captureStorageDrained(state.captureDispatcher);
        state.maskDepthPrepared=true;state.maskDepthSnapshotGeneration=frame.sceneGeneration;
        state.lastMaskScaleX=state.lastMaskScaleY=state.lastSceneScaleX=state.lastSceneScaleY=1;
        state.lastMaskOffsetX=state.lastMaskOffsetY=state.lastSceneOffsetX=state.lastSceneOffsetY=0;
        state.exactDepthAlignment=true;state.capturedThisFrame=true;state.maskPreparedThisFrame=true;
        frame.leases.put(state,new Lease(frame,state,id,frame.pipelines.get(state.config.shader())));
        return true;
    }
    static Lease lease(GlowCaptureState state){
        var frame=current;if(frame==null)return null;var lease=frame.leases.get(state);return lease!=null && lease.valid()?lease:null;
    }
    public static void finishFrame(){if(current!=null)current.close();}
}
//#else
//$$ public final class NativeWorldVisibility {}
//#endif
