package cn.spectra.gallium.glowoutline.shader;

import cn.spectra.gallium.glowoutline.capture.*;
import cn.spectra.gallium.glowoutline.sr.runtime.ReflectiveSrRuntimeAccess;
import cn.spectra.gallium.glowoutline.sr.definition.SrDefinition.SourceKind;

import com.mojang.blaze3d.pipeline.RenderTarget;

import com.mojang.blaze3d.vertex.BufferBuilder.RenderedBuffer;
import com.mojang.blaze3d.vertex.VertexFormatElement;
import net.minecraft.client.Minecraft;
import org.lwjgl.opengl.*;
import org.lwjgl.system.MemoryStack;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import java.util.*;

/** Stabilizes verified world outlines across shader jitter. Reprojects camera motion; changed meshes and newly occluding depth reject history. */
public final class OutlineTemporalStabilizer {
 private static int width,height,before,after,fbo,vao,resolve,resolveCpu,resolveGpu,combine,index;
 private static final int[] colors=new int[2],depths=new int[2];
 private static boolean enabled,disabled,originalCaptured;
 private static long reserved;
 private static float jitterX,jitterY,previousJitterX,previousJitterY;
 private static int jitterLifetime;
 private static boolean jitterSeen;
 static { GlowResources.register(OutlineTemporalStabilizer::dispose); }
 private static boolean valid,seen,copied;
 private static long camera,lastNanos;
 private static final OutlineMotion motion=new OutlineMotion();
 private static final Matrix4f previousViewProjection=new Matrix4f();
 private static double previousX,previousY,previousZ;

 private static final Set<GlowCaptureState> geometry=Collections.newSetFromMap(new IdentityHashMap<>());
 private static final OutlineDepthCache depthCache=new OutlineDepthCache();
 private static final IdentityHashMap<GlowCaptureState,Float> nearestDepth=new IdentityHashMap<>();
 private static final Map<Integer,Boolean> depthPrograms=new HashMap<>();
 private static final IdentityHashMap<GlowCaptureState,Previous> previous=new IdentityHashMap<>();
 private record Rect(int x,int y,int w,int h){}
 private record Previous(Rect rect,int slot,boolean hand,int config){}
 private record Owner(Rect rect,float near){}
 private static final String VERTEX="""
 #version 330 core
 void main(){vec2 p=vec2((gl_VertexID&1)<<2,(gl_VertexID&2)<<1);gl_Position=vec4(p-1.0,0.0,1.0);}
 """;
 private static final String RESOLVE="""
 #version 330 core
 #extension GL_ARB_shading_language_packing : require
 uniform sampler2D Before,After,History,OldDepth,Scene;
 uniform ivec2 Size;
 uniform vec2 SceneScale,SceneOffset;
 uniform bool Reset,Moving;
 uniform mat4 Reprojection;
 uniform int RejectCount;
 uniform ivec4 RejectRects[128];
 uniform int ObjectCount;
 uniform ivec4 ObjectRects[128];
 uniform float ObjectNear[128];
 #ifdef GALLIUM_GPU_FOOTPRINTS
 struct Footprint { uvec4 bounds; uvec4 state; vec4 mapping; vec4 support; ivec4 prior; };
 layout(std140) uniform CurrentFootprints { Footprint currentFootprints[128]; };
 layout(std140) uniform OldFootprints { Footprint oldFootprints[128]; };
 bool containsFootprint(ivec2 p,uvec4 b){ivec4 r=ivec4(b);return all(greaterThanEqual(p,r.xy))&&all(lessThan(p,r.xy+r.zw));}
 #endif
 layout(location=0) out vec4 Color;
 layout(location=1) out float Depth;
 vec3 contribution(ivec2 p){p=clamp(p,ivec2(0),Size-1);return max(texelFetch(After,p,0).rgb-texelFetch(Before,p,0).rgb,vec3(0.0));}
 float encodedSpan(float lo,float hi,bool reject){
  float span=max(hi-lo,0.0);uint bits=packHalf2x16(vec2(span,0.0))&0xffffu;
  float rounded=unpackHalf2x16(bits).x;if(rounded<span)rounded=unpackHalf2x16(bits+1u).x;
  return reject?-rounded:rounded;
 }
 vec4 cubic(float f){return vec4(-0.5*f+f*f-0.5*f*f*f,1.0-2.5*f*f+1.5*f*f*f,0.5*f+2.0*f*f-1.5*f*f*f,-0.5*f*f+0.5*f*f*f);}
 void main(){
  ivec2 p=ivec2(gl_FragCoord.xy);vec3 now=contribution(p);
  ivec2 physical=textureSize(Scene,0),active=ivec2(round(vec2(physical)*SceneScale));
  ivec2 q=clamp(ivec2(floor((vec2(p)+0.5)/vec2(Size)*vec2(active)+SceneOffset*vec2(physical))),ivec2(0),active-1);
  float z=texelFetch(Scene,q,0).r,zlo=z,zhi=z;ivec2 planeQ=q;
  for(int y=-1;y<=1;y++)for(int x=-1;x<=1;x++){ivec2 t=clamp(q+ivec2(x,y),ivec2(0),active-1);float n=texelFetch(Scene,t,0).r;if(n<zlo){zlo=n;planeQ=t;}zhi=max(zhi,n);}
  bool resetHistory=Reset;
  #ifdef GALLIUM_GPU_FOOTPRINTS
  resetHistory=resetHistory || currentFootprints[0].state.z+uint(RejectCount)>128u;
  #endif
  if(resetHistory){Color=vec4(now,encodedSpan(zlo,zhi,true));Depth=-zlo;return;}
  // At a silhouette the scene sample may be background. Anchor motion to the
  // nearest contributing object instead of projecting the outline at sky depth.
  float owner=1.0;bool owned=false;
  for(int i=0;i<ObjectCount;i++){ivec4 r=ObjectRects[i];if(all(greaterThanEqual(p,r.xy))&&all(lessThan(p,r.xy+r.zw))){owned=true;owner=min(owner,ObjectNear[i]);}}
  vec2 oldPosition=vec2(p);vec2 projectedRange=vec2(zlo,zhi);
  bool reject=Reset || zlo<0.5;
  if(Moving){
   vec2 ndc=(vec2(p)+0.5)/vec2(Size)*2.0-1.0;
   vec4 clip=Reprojection*vec4(ndc,max(zlo,owner)*2.0-1.0,1.0);
   oldPosition=(clip.xy/clip.w*0.5+0.5)*vec2(Size)-0.5;
   vec4 nearClip=Reprojection*vec4(ndc,zlo*2.0-1.0,1.0);
   vec4 farClip=Reprojection*vec4(ndc,zhi*2.0-1.0,1.0);
   projectedRange=vec2(nearClip.z/nearClip.w,farClip.z/farClip.w)*0.5+0.5;
   projectedRange=vec2(min(projectedRange.x,projectedRange.y),max(projectedRange.x,projectedRange.y));
   reject=reject || clip.w<=0.0 || any(lessThan(oldPosition,vec2(0.0))) || any(greaterThan(oldPosition,vec2(Size-1)));
  }
  ivec2 oldPixel=clamp(ivec2(round(oldPosition)),ivec2(0),Size-1);
  float low=uintBitsToFloat(max(floatBitsToUint(projectedRange.x),4u)-4u);
  float high=uintBitsToFloat(min(floatBitsToUint(projectedRange.y)+4u,0x3f800000u));
  vec4 history=vec4(0.0);float historyWeight=0.0;
  ivec2 base=ivec2(floor(oldPosition));vec2 fraction=fract(oldPosition);
  // Repeated bilinear resampling diffuses a thin outline during camera motion.
  // Cubic reconstruction preserves its energy; visibility rejects invalid taps.
  vec4 wx=cubic(fraction.x),wy=cubic(fraction.y);
  for(int y=-1;y<=2;y++)for(int x=-1;x<=2;x++){
   ivec2 t=clamp(base+ivec2(x,y),ivec2(0),Size-1);vec4 sampleColor=texelFetch(History,t,0);
   float oldNear=abs(texelFetch(OldDepth,t,0).r);
   bool accepted=(floatBitsToUint(sampleColor.a)&0x80000000u)==0u && oldNear<=high && oldNear+abs(sampleColor.a)>=low;
   float weight=wx[x+1]*wy[y+1];
   if(accepted){history.rgb+=sampleColor.rgb*weight;historyWeight+=weight;}
  }
  if(historyWeight>0.0)history.rgb=max(history.rgb/historyWeight,vec3(0.0));
  for(int i=0;i<RejectCount;i++){ivec4 r=RejectRects[i];
  #ifdef GALLIUM_GPU_FOOTPRINTS
   if(r.z<0){if(containsFootprint(oldPixel,oldFootprints[r.x].bounds))reject=true;continue;}
  #endif
   if((all(greaterThanEqual(p,r.xy))&&all(lessThan(p,r.xy+r.zw))) || (Moving && all(greaterThanEqual(oldPixel,r.xy))&&all(lessThan(oldPixel,r.xy+r.zw))))reject=true;
  }
  #ifdef GALLIUM_GPU_FOOTPRINTS
  for(int i=0;i<ObjectCount;i++){
   if((Moving || currentFootprints[i].state.x==0u) && currentFootprints[i].prior.y==0)continue;
   if(containsFootprint(p,currentFootprints[i].bounds))reject=true;
   int old=currentFootprints[i].prior.x;
   if(old>=0 && containsFootprint(oldPixel,oldFootprints[old].bounds))reject=true;
  }
  #endif
  float l=texelFetch(Scene,clamp(planeQ+ivec2(-1,0),ivec2(0),active-1),0).r;
  float r=texelFetch(Scene,clamp(planeQ+ivec2(1,0),ivec2(0),active-1),0).r;
  float d=texelFetch(Scene,clamp(planeQ+ivec2(0,-1),ivec2(0),active-1),0).r;
  float u=texelFetch(Scene,clamp(planeQ+ivec2(0,1),ivec2(0),active-1),0).r;
  // Use the surface that supplied the nearest depth, which can differ from the
  // center surface. Continue only a consistent slope, never a depth discontinuity.
  float gx=(zlo-l)*(r-zlo)>0.0?min(abs(zlo-l),abs(r-zlo)):0.0;
  float gy=(zlo-d)*(u-zlo)>0.0?min(abs(zlo-d),abs(u-zlo)):0.0;
  // One stencil texel plus the adjacent temporal sample covers the local plane.
  // A footprint entirely in front of the object always rejects history.
  reject=reject || !owned || zhi<owner || zlo+2.0*(gx+gy)<owner;
  bool useHistory=!reject && historyWeight>0.25;
  if(Moving){
   // Current support bounds ringing and prevents old edges from trailing a
   // moving or disappearing silhouette. This does not relax scene occlusion.
   vec3 maximum=now;
   for(int y=-1;y<=1;y++)for(int x=-1;x<=1;x++)maximum=max(maximum,contribution(p+ivec2(x,y)));
   history.rgb=min(history.rgb,maximum);
  }
  Color=vec4(mix(now,history.rgb,useHistory?0.9:0.0),encodedSpan(zlo,zhi,reject));
  Depth=useHistory?zlo:-zlo;
 }
 """;
 private static final String COMBINE="""
 #version 330 core
 uniform sampler2D Before,After,Filtered,DepthResult;
 out vec4 Color;
 void main(){ivec2 p=ivec2(gl_FragCoord.xy);vec4 raw=texelFetch(After,p,0);if((floatBitsToUint(texelFetch(DepthResult,p,0).r)&0x80000000u)!=0u){Color=raw;return;}Color=vec4(texelFetch(Before,p,0).rgb+texelFetch(Filtered,p,0).rgb,raw.a);}
 """;
 public static void beginFrame(){
  valid &= seen;motion.begin();depthCache.begin();geometry.clear();nearestDepth.clear();
  seen=copied=enabled=originalCaptured=false;
  if(jitterLifetime>0)jitterLifetime--;
  var mc=Minecraft.getInstance();var target=mc==null?null:mc.getMainRenderTarget();
  if(target==null || !cn.spectra.gallium.glowoutline.IrisCompat.isShaderActive()
    || !supportedMode()
    || width!=0 && (width!=target.width || height!=target.height))releaseHistory();
 }
 public static long reservedBytes(){return reserved
   ;}
 private static boolean supportedMode(){
  return !cn.spectra.gallium.glowoutline.IrisCompat.isActiveSrRuntime();
 }
 private static boolean frameReady(RenderTarget target){
  return GlowCaptureManager.ownsSequentialSharedMaskFrame();
 }
 public static final class Scope implements AutoCloseable {
  private final boolean active;private Scope(boolean active){this.active=active;}
  @Override public void close(){if(active)finish();}
 }
 private static final Scope INACTIVE=new Scope(false),ACTIVE=new Scope(true);
 private static RenderTarget destination;
 private static int destinationTexture;
 public static Scope begin(Minecraft mc,RenderTarget target){
  if(copied){valid=false;enabled=false;return INACTIVE;}
  try{before(target);return copied?ACTIVE:INACTIVE;}
  catch(RuntimeException | LinkageError failure){unavailable(failure);return INACTIVE;}
 }
 private static void finish(){
  try{after(destination);}
  catch(RuntimeException | LinkageError failure){
   if(originalCaptured && destination!=null && destination.width==width && destination.height==height
     && destination.getColorTextureId()==destinationTexture)copy(after,destinationTexture);
   unavailable(failure);
  }finally{copied=enabled=originalCaptured=false;destination=null;}
 }
 private static void unavailable(Throwable failure){
  disabled=true;disposeObjects();cn.spectra.gallium.Gallium.LOGGER.warn("Outline history unavailable; retaining unfiltered rendering",failure);
 }
 private static void releaseHistory(){
  if(fbo!=0){GL45.glNamedFramebufferTexture(fbo,GL30.GL_COLOR_ATTACHMENT0,0,0);GL45.glNamedFramebufferTexture(fbo,GL30.GL_COLOR_ATTACHMENT1,0,0);}
  for(int t:new int[]{before,after,colors[0],colors[1],depths[0],depths[1]})if(t!=0)GL11.glDeleteTextures(t);
  before=after=width=height=index=0;Arrays.fill(colors,0);Arrays.fill(depths,0);reserved=0;valid=false;motion.clear();depthCache.clear();previous.clear();geometry.clear();nearestDepth.clear();
 }
 private static void disposeObjects(){
  releaseHistory();if(resolveCpu!=0)GL20.glDeleteProgram(resolveCpu);if(resolveGpu!=0)GL20.glDeleteProgram(resolveGpu);if(combine!=0)GL20.glDeleteProgram(combine);
  if(vao!=0)GL30.glDeleteVertexArrays(vao);if(fbo!=0)GL30.glDeleteFramebuffers(fbo);
  resolve=resolveCpu=resolveGpu=combine=vao=fbo=0;copied=enabled=seen=false;depthPrograms.clear();
 }
 public static void dispose(){disposeObjects();disabled=false;jitterSeen=false;jitterLifetime=0;}

 public static void mesh(GlowCaptureState state,RenderedBuffer mesh,Matrix4fc modelView,Matrix4fc projection,int program){
  if(!enabled || state==null || state.firstPerson)return;
  var d=mesh.drawState();var f=d.format();int position=positionOffset(f);if(position<0)return;
  var b=mesh.vertexBuffer();int stride=f.getVertexSize(),offset=position;geometry.add(state);
  var cameraPosition=Minecraft.getInstance().gameRenderer.getMainCamera()
    .getPosition();
  if(state.capturedModelViewMatrix!=null)motion.observe(state,b,stride,offset,d.vertexCount(),modelView,state.capturedModelViewMatrix,cameraPosition.x,cameraPosition.y,cameraPosition.z);
  if(depthPrograms.size()>=128 && !depthPrograms.containsKey(program))depthPrograms.clear();
  float near=GL11.glIsEnabled(GL11.GL_POLYGON_OFFSET_FILL) || !depthPrograms.computeIfAbsent(program,OutlineTemporalStabilizer::nativeDepth)
      ? Float.NaN : depthCache.minimum(state,b,stride,d.vertexCount(),offset,modelView,projection);
  nearestDepth.merge(state,near,Math::min);
 }
 private static int positionOffset(com.mojang.blaze3d.vertex.VertexFormat f){
  int offset=0;for(var element:f.getElements()){if(element.getUsage()==VertexFormatElement.Usage.POSITION)return offset;offset+=element.getByteSize();}return -1;
 }
 private static boolean nativeDepth(int program){
  try(var stack=MemoryStack.stackPush()){
   var shaders=stack.mallocInt(2);var count=stack.mallocInt(1);GL20.glGetAttachedShaders(program,count,shaders);
   if(count.get(0)!=2)return false;
   for(int i=0;i<2;i++)if(GL20.glGetShaderi(shaders.get(i),GL20.GL_SHADER_TYPE)==GL20.GL_FRAGMENT_SHADER){
    return !GL20.glGetShaderSource(shaders.get(i)).replaceAll("(?s)/\\*.*?\\*/|//[^\\r\\n]*", " ").contains("gl_FragDepth");
   }
   return false;
  }
 }
 private static int shader(int type,String source){
  int shader=GL20.glCreateShader(type);
  try{GL20.glShaderSource(shader,source);GL20.glCompileShader(shader);
   if(GL20.glGetShaderi(shader,GL20.GL_COMPILE_STATUS)==0)throw new IllegalStateException(GL20.glGetShaderInfoLog(shader));
   return shader;
  }catch(RuntimeException | Error failure){GL20.glDeleteShader(shader);throw failure;}
 }
 private static int program(String fragment){
  int vertex=0,pixel=0,program=0;
  try{vertex=shader(GL20.GL_VERTEX_SHADER,VERTEX);pixel=shader(GL20.GL_FRAGMENT_SHADER,fragment);program=GL20.glCreateProgram();
   GL20.glAttachShader(program,vertex);GL20.glAttachShader(program,pixel);GL20.glLinkProgram(program);
   if(GL20.glGetProgrami(program,GL20.GL_LINK_STATUS)==0)throw new IllegalStateException(GL20.glGetProgramInfoLog(program));
   return program;
  }catch(RuntimeException | Error failure){if(program!=0)GL20.glDeleteProgram(program);throw failure;}
  finally{if(vertex!=0)GL20.glDeleteShader(vertex);if(pixel!=0)GL20.glDeleteShader(pixel);}
 }
 private static int texture(int format){int t=GL45.glCreateTextures(GL11.GL_TEXTURE_2D);GL45.glTextureStorage2D(t,1,format,width,height);GL45.glTextureParameteri(t,GL11.GL_TEXTURE_MIN_FILTER,GL11.GL_NEAREST);GL45.glTextureParameteri(t,GL11.GL_TEXTURE_MAG_FILTER,GL11.GL_NEAREST);GL45.glTextureParameteri(t,GL11.GL_TEXTURE_WRAP_S,GL12.GL_CLAMP_TO_EDGE);GL45.glTextureParameteri(t,GL11.GL_TEXTURE_WRAP_T,GL12.GL_CLAMP_TO_EDGE);return t;}
 private static void ensure(int w,int h){
  if(resolveCpu==0){
   resolve=resolveCpu=program(RESOLVE);combine=program(COMBINE);vao=GL45.glCreateVertexArrays();fbo=GL45.glCreateFramebuffers();
  }
  if(width==w && height==h)return;
  releaseHistory();
  width=w;height=h;before=texture(GL11.GL_RGBA8);after=texture(GL11.GL_RGBA8);
  for(int i=0;i<2;i++){colors[i]=texture(GL30.GL_RGBA16F);depths[i]=texture(GL30.GL_R32F);}
  reserved=OutlineHistoryPolicy.storageBytes(w,h);valid=false;previous.clear();
 }
 private static void copy(int from,int to){GL43.glCopyImageSubData(from,GL11.GL_TEXTURE_2D,0,0,0,0,to,GL11.GL_TEXTURE_2D,0,0,0,0,width,height,1);}
 private static void before(RenderTarget target){
  copied=false;if(disabled)return;var mc=Minecraft.getInstance();
  if(target==null || target!=mc.getMainRenderTarget() || mc.level==null || !frameReady(target)
    || !cn.spectra.gallium.glowoutline.IrisCompat.isShaderActive()
    || !supportedMode()
    || cn.spectra.gallium.glowoutline.SuperResolutionCompat.isHackConfigured()){releaseHistory();return;}
  boolean world=false;GlowCaptureState cameraState=null;
  for(var state:GlowCaptureManager.getActiveStates()){
   if(!state.capturedThisFrame || state.compositedThisFrame || state.guiEntity!=null
      || !OriginalGlowParameters.supportsDeferredSceneOcclusion(state.config)
      || !OutlineHistoryPolicy.additiveParameters(state.config)){releaseHistory();return;}
   if(!state.firstPerson){world=true;if(cameraState==null)cameraState=state;}
  }
  if(!world){valid=false;return;}
  var caps=GL.getCapabilities();
  if(
    (!caps.OpenGL43 && (!caps.GL_ARB_compute_shader || !caps.GL_ARB_shader_storage_buffer_object))
    || GL11.glGetInteger(GL20.GL_MAX_FRAGMENT_UNIFORM_COMPONENTS)<1280
    || (!caps.OpenGL45 && !caps.GL_ARB_direct_state_access) || (!caps.OpenGL43 && !caps.GL_ARB_copy_image)
    || (!caps.OpenGL41 && !caps.GL_ARB_viewport_array) || (!caps.OpenGL42 && !caps.GL_ARB_shading_language_packing)
    || OpenGlMaskOrdering.observe()==null){valid=false;return;}
  var value=ReflectiveSrRuntimeAccess.getInstance().shaderValue(SourceKind.UNIFORM,"fsrJitter");
  if(value.isEmpty())value=ReflectiveSrRuntimeAccess.getInstance().shaderValue(SourceKind.UNIFORM,"taaJitter");
  if(value.isEmpty()){releaseHistory();return;}
  jitterX=(float)(value.get().component(0)*.5);jitterY=(float)(value.get().component(1)*.5);
  if(!Float.isFinite(jitterX) || !Float.isFinite(jitterY) || Math.abs(jitterX*target.width)>2 || Math.abs(jitterY*target.height)>2){releaseHistory();return;}
  if(jitterSeen && (jitterX!=previousJitterX || jitterY!=previousJitterY))jitterLifetime=16;
  previousJitterX=jitterX;previousJitterY=jitterY;jitterSeen=true;
  if(jitterLifetime==0){releaseHistory();return;}
  long bytes=OutlineHistoryPolicy.storageBytes(target.width,target.height);
  if(!GlowCaptureManager.temporalHistoryFitsBudget(target.width,target.height,bytes)){releaseHistory();return;}
  if(target.getColorTextureId()<=0
    || GL45.glGetTextureLevelParameteri(target.getColorTextureId(),0,GL11.GL_TEXTURE_INTERNAL_FORMAT)!=GL11.GL_RGBA8){valid=false;return;}
  ensure(target.width,target.height);destination=target;destinationTexture=target.getColorTextureId();
  var cameraPosition=mc.gameRenderer.getMainCamera()
      .getPosition();
  depthCache.setView(cameraState.capturedModelViewMatrix,
      cameraState.capturedProjectionMatrix4fValid?cameraState.capturedProjectionMatrix4f:null,
      cameraPosition.x,cameraPosition.y,cameraPosition.z);
  copy(destinationTexture,before);copied=enabled=true;
 }
 private static long add(long h,long v){return (h^v)*0x100000001b3L;}
 private static long matrix(long h,Matrix4fc m){for(int c=0;c<4;c++)for(int r=0;r<4;r++)h=add(h,Float.floatToRawIntBits(m.get(c,r)));return h;}
 private static Rect rect(GlowCaptureState s){return ownerRect(s);}
 private static Rect ownerRect(GlowCaptureState s){
  var c=BoundedGlowContracts.get(s.config.shader());
  var observed=s.maskBounds;
  if(c==null || observed==null || !observed.valid())return null;
  var r=c.mappedRectangle(observed,width,height,s.itemWorldToUv.x,s.itemWorldToUv.y,s.lastMaskScaleX,s.lastMaskScaleY,s.lastMaskOffsetX,s.lastMaskOffsetY);
  return r==null?null:new Rect(r.x(),r.y(),r.width(),r.height());
 }
 private static void rejectPrevious(List<Rect> reject,Previous old){
  if(old==null)return;
  reject.add(old.slot>=0?new Rect(old.slot,0,-1,0):old.rect);
 }
 private static void sampler(int p,String name,int unit,int texture){GL13.glActiveTexture(GL13.GL_TEXTURE0+unit);GL11.glBindTexture(GL11.GL_TEXTURE_2D,texture);GL33.glBindSampler(unit,0);GL20.glUniform1i(GL20.glGetUniformLocation(p,name),unit);}
 private static void after(RenderTarget target){
  if(!copied || !enabled || target!=Minecraft.getInstance().getMainRenderTarget() || target.width!=width || target.height!=height
    || target.getColorTextureId()!=destinationTexture){valid=false;return;}seen=true;
  var states=GlowCaptureManager.getActiveStates();GlowCaptureState world=null;var reject=new ArrayList<Rect>();var next=new IdentityHashMap<GlowCaptureState,Previous>();var owners=new ArrayList<Owner>();
  for(var s:states){
   if(!s.compositedThisFrame || !OriginalGlowParameters.supportsDeferredSceneOcclusion(s.config) || !OutlineHistoryPolicy.additiveParameters(s.config) || ownerRect(s)==null){valid=false;return;}
   if(!s.firstPerson){var near=nearestDepth.get(s);if(near==null || !Float.isFinite(near) || near<=0 || !geometry.contains(s)){valid=false;return;}owners.add(new Owner(ownerRect(s),near));if(world==null)world=s;}
  }

  if(world==null || owners.size()>128 || !world.capturedProjectionMatrix4fValid || world.capturedModelViewMatrix==null){valid=false;return;}
  copy(destinationTexture,after);originalCaptured=true;
  long sig=matrix(matrix(1,world.capturedProjectionMatrix4f),world.capturedModelViewMatrix);var pos=Minecraft.getInstance().gameRenderer.getMainCamera()
      .getPosition();
  sig=add(add(add(sig,Double.doubleToRawLongBits(pos.x)),Double.doubleToRawLongBits(pos.y)),Double.doubleToRawLongBits(pos.z));
  sig=add(add(sig,Float.floatToRawIntBits(world.lastSceneScaleX)),Float.floatToRawIntBits(world.lastSceneScaleY));
  boolean moving=sig!=camera;
  var viewProjection=new Matrix4f(world.capturedProjectionMatrix4f).mul(world.capturedModelViewMatrix);
  var reprojection=OutlineMotion.reproject(previousViewProjection,viewProjection,pos.x-previousX,pos.y-previousY,pos.z-previousZ);
  long now=System.nanoTime();boolean reset=!valid || !reprojection.isFinite()
      || now-lastNanos>250_000_000L || Math.abs(pos.x-previousX)+Math.abs(pos.y-previousY)+Math.abs(pos.z-previousZ)>4.0;
  camera=sig;lastNanos=now;previousViewProjection.set(viewProjection);previousX=pos.x;previousY=pos.y;previousZ=pos.z;
  // Camera-relative vertex bytes and native camera uniforms change when the
  // camera moves. Compare world geometry separately from that raster movement.
  boolean gpuFootprints=false;resolve=resolveCpu;
  for(var s:states){
   if(!s.compositedThisFrame || !OriginalGlowParameters.supportsDeferredSceneOcclusion(s.config) || !OutlineHistoryPolicy.additiveParameters(s.config)){valid=false;return;}
   var r=rect(s);if(r==null){valid=false;return;}
   if(s.firstPerson){reject.add(r);rejectPrevious(reject,previous.get(s));next.put(s,new Previous(r,-1,true,s.config.hashCode()));continue;}
   if(world==null)world=s;
   var old=previous.get(s);
   if(old==null || old.hand || motion.changed(s) || old.config!=s.config.hashCode()){reject.add(r);rejectPrevious(reject,old);}
   next.put(s,new Previous(r,-1,false,s.config.hashCode()));
  }
  for(var e:previous.entrySet())if(!next.containsKey(e.getKey()))rejectPrevious(reject,e.getValue());
  if(world==null || !world.capturedProjectionMatrix4fValid || world.capturedModelViewMatrix==null){valid=false;return;}
  reset |= reject.size()>128;
  int scene=GlowCaptureManager.getSceneDepthTarget().getDepthTextureId();
  int output=target.getColorTextureId();int write=1-index;
  boolean srgb=GL11.glIsEnabled(GL30.GL_FRAMEBUFFER_SRGB),blend1=GL30.glIsEnabledi(GL11.GL_BLEND,1);
  try(var saved=new OutlineTemporalGlState();var stack=MemoryStack.stackPush()){
   var mask1=stack.mallocInt(4);GL30.glGetIntegeri_v(GL11.GL_COLOR_WRITEMASK,1,mask1);
   try{
    GL11.glDisable(GL30.GL_FRAMEBUFFER_SRGB);GL11.glDisable(GL11.GL_DEPTH_TEST);GL11.glDisable(GL11.GL_CULL_FACE);GL11.glDepthMask(false);GL30.glDisablei(GL11.GL_BLEND,0);GL30.glDisablei(GL11.GL_BLEND,1);GL30.glDisablei(GL11.GL_SCISSOR_TEST,0);GL30.glColorMaski(0,true,true,true,true);GL30.glColorMaski(1,true,true,true,true);
    GL30.glBindVertexArray(vao);GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER,fbo);GL11.glViewport(0,0,width,height);
    GL45.glNamedFramebufferTexture(fbo,GL30.GL_COLOR_ATTACHMENT0,colors[write],0);GL45.glNamedFramebufferTexture(fbo,GL30.GL_COLOR_ATTACHMENT1,depths[write],0);GL20.glDrawBuffers(stack.ints(GL30.GL_COLOR_ATTACHMENT0,GL30.GL_COLOR_ATTACHMENT1));
    if(GL30.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER)!=GL30.GL_FRAMEBUFFER_COMPLETE)throw new IllegalStateException("Temporal repair FBO incomplete");
    GL20.glUseProgram(resolve);sampler(resolve,"Before",0,before);sampler(resolve,"After",1,after);sampler(resolve,"History",2,colors[index]);sampler(resolve,"OldDepth",3,depths[index]);sampler(resolve,"Scene",4,scene);
    GL20.glUniform2i(GL20.glGetUniformLocation(resolve,"Size"),width,height);GL20.glUniform2f(GL20.glGetUniformLocation(resolve,"SceneScale"),world.lastSceneScaleX,Math.abs(world.lastSceneScaleY));GL20.glUniform2f(GL20.glGetUniformLocation(resolve,"SceneOffset"),jitterX,jitterY);GL20.glUniform1i(GL20.glGetUniformLocation(resolve,"Reset"),reset?1:0);
    GL20.glUniform1i(GL20.glGetUniformLocation(resolve,"Moving"),moving?1:0);
    GL20.glUniformMatrix4fv(GL20.glGetUniformLocation(resolve,"Reprojection"),false,reprojection.get(stack.mallocFloat(16)));
    GL20.glUniform1i(GL20.glGetUniformLocation(resolve,"ObjectCount"),owners.size());
    var objects=stack.mallocInt(owners.size()*4);var depthsNear=stack.mallocFloat(owners.size());
    for(var owner:owners){var r=owner.rect;objects.put(r.x).put(r.y).put(r.w).put(r.h);depthsNear.put(owner.near);}
    objects.flip();depthsNear.flip();GL20.glUniform4iv(GL20.glGetUniformLocation(resolve,"ObjectRects"),objects);GL20.glUniform1fv(GL20.glGetUniformLocation(resolve,"ObjectNear"),depthsNear);
    int count=Math.min(128,reject.size());GL20.glUniform1i(GL20.glGetUniformLocation(resolve,"RejectCount"),count);var rs=stack.mallocInt(Math.max(4,count*4));for(int i=0;i<count;i++){var r=reject.get(i);rs.put(i*4,r.x).put(i*4+1,r.y).put(i*4+2,r.w).put(i*4+3,r.h);}rs.limit(count*4);if(count>0)GL20.glUniform4iv(GL20.glGetUniformLocation(resolve,"RejectRects"),rs);GL11.glDrawArrays(GL11.GL_TRIANGLES,0,3);
    GL45.glNamedFramebufferTexture(fbo,GL30.GL_COLOR_ATTACHMENT0,output,0);GL45.glNamedFramebufferTexture(fbo,GL30.GL_COLOR_ATTACHMENT1,0,0);GL11.glDrawBuffer(GL30.GL_COLOR_ATTACHMENT0);
    GL20.glUseProgram(combine);sampler(combine,"Before",0,before);sampler(combine,"After",1,after);sampler(combine,"Filtered",2,colors[write]);sampler(combine,"DepthResult",3,depths[write]);GL11.glDrawArrays(GL11.GL_TRIANGLES,0,3);
   }finally{GL45.glNamedFramebufferTexture(fbo,GL30.GL_COLOR_ATTACHMENT0,0,0);GL45.glNamedFramebufferTexture(fbo,GL30.GL_COLOR_ATTACHMENT1,0,0);GL30.glColorMaski(1,mask1.get(0)!=0,mask1.get(1)!=0,mask1.get(2)!=0,mask1.get(3)!=0);if(blend1)GL30.glEnablei(GL11.GL_BLEND,1);else GL30.glDisablei(GL11.GL_BLEND,1);if(srgb)GL11.glEnable(GL30.GL_FRAMEBUFFER_SRGB);else GL11.glDisable(GL30.GL_FRAMEBUFFER_SRGB);}
  }
  index=write;valid=true;motion.finish();depthCache.finish();previous.clear();previous.putAll(next);

 }
}
