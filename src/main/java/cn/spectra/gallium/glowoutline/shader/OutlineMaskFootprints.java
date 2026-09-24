package cn.spectra.gallium.glowoutline.shader;

//#if MC==1_21_08 || MC==1_21_11
//$$ import cn.spectra.gallium.glowoutline.capture.GlowCaptureState;
//$$ import com.mojang.blaze3d.opengl.GlTexture;
//$$ import com.mojang.blaze3d.textures.TextureFormat;
//$$ import org.lwjgl.opengl.*;
//$$ import org.lwjgl.system.MemoryStack;
//$$ import java.util.*;
//$$ 
//$$ /** Reduces occupied mask texels without reading each item's image back to the CPU. */
//$$ final class OutlineMaskFootprints {
//$$     private static final int LIMIT = 128, STRIDE = 80;
//$$     private static final IdentityHashMap<GlowCaptureState, NativeMaskAtlas.Entry> captured = new IdentityHashMap<>();
//$$     private static final IdentityHashMap<GlowCaptureState, Saved> previous = new IdentityHashMap<>();
//$$     private static final IdentityHashMap<GlowCaptureState, Footprint> footprints = new IdentityHashMap<>();
//$$     private static int buffer, packedAlpha, atlasWidth, atlasHeight, reduceProgram, packProgram, finalizeProgram;
//$$     private static final int[] buffers=new int[2];
//$$     private static int bufferIndex,currentUniformBinding,oldUniformBinding;
//$$     private static boolean gpuMode,previousGpuMode,modeChanged,gpuDisabled;
//$$     private static Set<GlowCaptureState> forced=Collections.emptySet();
//$$     private static boolean invalid;
//$$     private static int lastStoredSlot=-1;
//$$ 
//$$     record Footprint(BoundedGlowContract.Rectangle rectangle, boolean changed, int slot) {}
//$$     private record Saved(int x, int y, int width, int height, int offsetX, int offsetY,
//$$                          float scaleX, float scaleY, float uvX, float uvY, int slot) {
//$$         boolean comparable(NativeMaskAtlas.Entry e) {
//$$             var s = e.state();
//$$             return x == e.x() && y == e.y() && width == e.width() && height == e.height()
//$$                     && scaleX == s.lastMaskScaleX && scaleY == s.lastMaskScaleY
//$$                     && uvX == s.lastMaskOffsetX && uvY == s.lastMaskOffsetY;
//$$         }
//$$     }
//$$ 
//$$     private static final String REDUCE = """
//$$             #version 430 core
//$$             layout(local_size_x=8,local_size_y=8) in;
//$$             struct Footprint { uvec4 bounds; uvec4 state; vec4 mapping; vec4 support; ivec4 prior; };
//$$             layout(std430) buffer Results { Footprint results[]; };
//$$             uniform sampler2D Mask;
//$$             uniform usampler2D PreviousAlpha;
//$$             uniform ivec4 Region;
//$$             uniform ivec2 AtlasOffset,OldOffset;
//$$             uniform int Slot;
//$$             uniform bool Compare;
//$$             shared uvec4 boxes[64];
//$$             shared uint differences[64];
//$$             void main() {
//$$                 uint lane=gl_LocalInvocationIndex;
//$$                 ivec2 p=Region.xy+ivec2(gl_GlobalInvocationID.xy);
//$$                 bool inside=all(lessThan(p,Region.xy+Region.zw));
//$$                 bool occupied=false;
//$$                 uint changed=0u;
//$$                 if(inside) {
//$$                     occupied=texelFetch(Mask,p+AtlasOffset,0).a>0.01;
//$$                     if(Compare) {
//$$                         ivec2 old=p+OldOffset;
//$$                         uint bits=texelFetch(PreviousAlpha,ivec2(old.x>>5,old.y),0).r;
//$$                         bool wasOccupied=(bits&(1u<<uint(old.x&31)))!=0u;
//$$                         changed=occupied!=wasOccupied?1u:0u;
//$$                     }
//$$                 }
//$$                 boxes[lane]=occupied?uvec4(p,p+1):uvec4(0xffffffffu,0xffffffffu,0u,0u);
//$$                 differences[lane]=changed;
//$$                 barrier();
//$$                 for(uint step=32u;step>0u;step>>=1u) {
//$$                     if(lane<step) {
//$$                         boxes[lane].xy=min(boxes[lane].xy,boxes[lane+step].xy);
//$$                         boxes[lane].zw=max(boxes[lane].zw,boxes[lane+step].zw);
//$$                         differences[lane]|=differences[lane+step];
//$$                     }
//$$                     barrier();
//$$                 }
//$$                 if(lane==0u) {
//$$                     if(boxes[0].x!=0xffffffffu) {
//$$                         atomicMin(results[Slot].bounds.x,boxes[0].x);
//$$                         atomicMin(results[Slot].bounds.y,boxes[0].y);
//$$                         atomicMax(results[Slot].bounds.z,boxes[0].z);
//$$                         atomicMax(results[Slot].bounds.w,boxes[0].w);
//$$                     }
//$$                     if(differences[0]!=0u)atomicOr(results[Slot].state.x,1u);
//$$                 }
//$$             }
//$$             """;
//$$     private static final String FINALIZE = """
//$$             #version 430 core
//$$             #extension GL_ARB_gpu_shader_fp64 : require
//$$             layout(local_size_x=64) in;
//$$             struct Footprint { uvec4 bounds; uvec4 state; vec4 mapping; vec4 support; ivec4 prior; };
//$$             layout(std430) buffer Results { Footprint results[]; };
//$$             uniform ivec2 Size;
//$$             uniform int Count;
//$$             void main() {
//$$                 uint i=gl_GlobalInvocationID.x;
//$$                 if(i>=uint(Count))return;
//$$                 if(results[i].state.x!=0u || results[i].prior.y!=0)atomicAdd(results[0].state.z,results[i].prior.x>=0?2u:1u);
//$$                 uvec4 b=results[i].bounds;
//$$                 if(b.x==0xffffffffu){results[i].bounds=uvec4(0u);return;}
//$$                 vec4 mapping=results[i].mapping;
//$$                 // Match the CPU fallback's float product, followed by double mapping.
//$$                 precise vec2 scaled=vec2(Size)*mapping.xy;
//$$                 precise dvec2 a=dvec2(max(vec2(1.0),floor(scaled)));
//$$                 precise dvec2 c=dvec2(max(vec2(1.0),ceil(scaled)));
//$$                 precise dvec2 size=dvec2(Size);
//$$                 precise dvec2 origin=dvec2(mapping.zw)*size;
//$$                 precise dvec2 lo=dvec2(b.xy)-dvec2(0.5);
//$$                 precise dvec2 hi=dvec2(b.zw)+dvec2(0.5);
//$$                 lo=lo-origin;hi=hi-origin;lo=lo*size;hi=hi*size;
//$$                 precise dvec2 radius=dvec2(results[i].support.xy);
//$$                 precise dvec2 first=floor(min(lo/a,lo/c)-radius)-dvec2(1.0);
//$$                 precise dvec2 end=ceil(max(hi/a,hi/c)+radius)+dvec2(1.0);
//$$                 uvec2 lower=uvec2(clamp(first,dvec2(0.0),size));
//$$                 uvec2 upper=uvec2(clamp(end,dvec2(0.0),size));
//$$                 results[i].bounds=uvec4(lower,upper-lower);
//$$             }
//$$             """;
//$$     private static final String PACK = """
//$$             #version 430 core
//$$             layout(local_size_x=8,local_size_y=8) in;
//$$             layout(r32ui,binding=0) writeonly uniform uimage2D Packed;
//$$             uniform sampler2D Mask;
//$$             uniform ivec2 Extent;
//$$             void main() {
//$$                 ivec2 word=ivec2(gl_GlobalInvocationID.xy);
//$$                 if(any(greaterThanEqual(word,imageSize(Packed))))return;
//$$                 uint bits=0u;
//$$                 for(int bit=0;bit<32;bit++) {
//$$                     int x=word.x*32+bit;
//$$                     if(x<Extent.x && texelFetch(Mask,ivec2(x,word.y),0).a>0.01)bits|=1u<<uint(bit);
//$$                 }
//$$                 imageStore(Packed,word,uvec4(bits,0u,0u,0u));
//$$             }
//$$             """;
//$$ 
//$$     static void beginFrame() { captured.clear(); footprints.clear(); invalid=false;lastStoredSlot=-1;forced=Collections.emptySet(); }
//$$     static void observe(NativeMaskAtlas.Entry entry) {
//$$         // A flush can reuse the same texture and overwrite earlier slots. Include
//$$         // hand entries in this check even though their footprints are not reduced.
//$$         if(entry.slot()<=lastStoredSlot)invalid=true;
//$$         lastStoredSlot=entry.slot();
//$$         if(entry.state().firstPerson)return;
//$$         if(captured.put(entry.state(),entry)!=null)invalid=true;
//$$     }
//$$     static Footprint get(GlowCaptureState state) { return footprints.get(state); }
//$$     static void force(Set<GlowCaptureState> states) { forced=states; }
//$$     static boolean gpuMode() { return gpuMode; }
//$$     static boolean modeChanged() { return modeChanged; }
//$$     static boolean gpuCapable() { return !gpuDisabled && GL.getCapabilities().GL_ARB_gpu_shader_fp64
//$$             && GL11.glGetInteger(GL31.GL_MAX_UNIFORM_BLOCK_SIZE)>=LIMIT*STRIDE; }
//$$     static void disableGpu() { gpuDisabled=true; }
//$$     static Binding bind(int program) { return new Binding(program); }
//$$     static final class Binding implements AutoCloseable {
//$$         private final boolean active=gpuMode;
//$$         private final int generic=active?GL11.glGetInteger(GL31.GL_UNIFORM_BUFFER_BINDING):0;
//$$         Binding(int program) {
//$$             if(!active)return;
//$$             GL31.glUniformBlockBinding(program,GL31.glGetUniformBlockIndex(program,"CurrentFootprints"),currentUniformBinding);
//$$             GL31.glUniformBlockBinding(program,GL31.glGetUniformBlockIndex(program,"OldFootprints"),oldUniformBinding);
//$$             GL30.glBindBufferBase(GL31.GL_UNIFORM_BUFFER,currentUniformBinding,buffers[bufferIndex]);
//$$             GL30.glBindBufferBase(GL31.GL_UNIFORM_BUFFER,oldUniformBinding,buffers[1-bufferIndex]);
//$$         }
//$$         public void close() {
//$$             if(!active)return;
//$$             GL30.glBindBufferBase(GL31.GL_UNIFORM_BUFFER,currentUniformBinding,0);
//$$             GL30.glBindBufferBase(GL31.GL_UNIFORM_BUFFER,oldUniformBinding,0);
//$$             GL15.glBindBuffer(GL31.GL_UNIFORM_BUFFER,generic);
//$$         }
//$$     }
//$$     private static boolean selectGpu() {
//$$         if(!gpuCapable())return false;
//$$         int found=0;
//$$         for(int i=GL11.glGetInteger(GL31.GL_MAX_UNIFORM_BUFFER_BINDINGS)-1;i>=0;i--) {
//$$             if(GL30.glGetIntegeri(GL31.GL_UNIFORM_BUFFER_BINDING,i)!=0)continue;
//$$             if(found++==0)currentUniformBinding=i;
//$$             else { oldUniformBinding=i;return true; }
//$$         }
//$$         return false;
//$$     }
//$$ 
//$$     static boolean prepare(Collection<GlowCaptureState> states, int width, int height) {
//$$         var entries=new ArrayList<NativeMaskAtlas.Entry>();
//$$         int texture=0,w=0,h=0;
//$$         for(var state:states) {
//$$             if(state.firstPerson)continue;
//$$             var entry=captured.get(state);
//$$             if(entry==null || entry.visibility()!=null || entry.output().width!=width || entry.output().height!=height
//$$                     || !(entry.atlas().getColorTexture() instanceof GlTexture color)
//$$                     || color.getFormat()!=TextureFormat.RGBA8)return refuse();
//$$             if(entries.isEmpty()) { texture=color.glId();w=entry.atlas().width;h=entry.atlas().height; }
//$$             if(texture!=color.glId() || w!=entry.atlas().width || h!=entry.atlas().height)return refuse();
//$$             entries.add(entry);
//$$         }
//$$         if(invalid || entries.isEmpty() || entries.size()>LIMIT
//$$                 || w!=powerOfTwo(width) || h!=powerOfTwo(height))return refuse();
//$$         // Use an unoccupied binding so restoring it cannot turn another program's
//$$         // whole-buffer binding into a fixed range (or the reverse).
//$$         int binding=GL11.glGetInteger(GL43.GL_MAX_SHADER_STORAGE_BUFFER_BINDINGS)-1;
//$$         while(binding>=0 && GL30.glGetIntegeri(GL43.GL_SHADER_STORAGE_BUFFER_BINDING,binding)!=0)binding--;
//$$         if(binding<0)return refuse();
//$$         ensure(w,h);
//$$         gpuMode=selectGpu();modeChanged=gpuMode!=previousGpuMode;
//$$         if(modeChanged)previous.clear();
//$$         int write=1-bufferIndex;buffer=buffers[write];
//$$         try(var saved=new OutlineTemporalGlState();var stack=MemoryStack.stackPush()) {
//$$             int generic=GL11.glGetInteger(GL43.GL_SHADER_STORAGE_BUFFER_BINDING);
//$$             int imageName=GL30.glGetIntegeri(GL42.GL_IMAGE_BINDING_NAME,0);
//$$             int imageLevel=GL30.glGetIntegeri(GL42.GL_IMAGE_BINDING_LEVEL,0);
//$$             int imageLayered=GL30.glGetIntegeri(GL42.GL_IMAGE_BINDING_LAYERED,0);
//$$             int imageLayer=GL30.glGetIntegeri(GL42.GL_IMAGE_BINDING_LAYER,0);
//$$             int imageAccess=GL30.glGetIntegeri(GL42.GL_IMAGE_BINDING_ACCESS,0);
//$$             int imageFormat=GL30.glGetIntegeri(GL42.GL_IMAGE_BINDING_FORMAT,0);
//$$             try {
//$$                 var initial=stack.callocInt(LIMIT*STRIDE/4);
//$$                 for(int i=0;i<entries.size();i++) {
//$$                     var entry=entries.get(i);var state=entry.state();var old=previous.get(state);
//$$                     int at=i*STRIDE/4;
//$$                     initial.put(at,-1).put(at+1,-1);
//$$                     initial.put(at+4,old!=null && old.comparable(entry)?0:1);
//$$                     initial.put(at+8,Float.floatToRawIntBits(state.lastMaskScaleX));
//$$                     initial.put(at+9,Float.floatToRawIntBits(state.lastMaskScaleY));
//$$                     initial.put(at+10,Float.floatToRawIntBits(state.lastMaskOffsetX));
//$$                     initial.put(at+11,Float.floatToRawIntBits(state.lastMaskOffsetY));
//$$                     initial.put(at+12,Float.floatToRawIntBits(state.itemWorldToUv.x>0?state.itemWorldToUv.x*width/64f:6));
//$$                     initial.put(at+13,Float.floatToRawIntBits(state.itemWorldToUv.y>0?state.itemWorldToUv.y*height/64f:6));
//$$                     initial.put(at+16,old==null?-1:old.slot);
//$$                     initial.put(at+17,forced.contains(state)?1:0);
//$$                 }
//$$                 // Orphan storage still referenced by an earlier frame instead of waiting on it.
//$$                 GL45.glNamedBufferData(buffer,initial,GL15.GL_DYNAMIC_COPY);
//$$                 GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER,binding,buffer);
//$$                 GL43.glShaderStorageBlockBinding(reduceProgram,
//$$                         GL43.glGetProgramResourceIndex(reduceProgram,GL43.GL_SHADER_STORAGE_BLOCK,"Results"),binding);
//$$                 GL20.glUseProgram(reduceProgram);
//$$                 sampler(reduceProgram,"Mask",0,texture);sampler(reduceProgram,"PreviousAlpha",1,packedAlpha);
//$$                 for(int i=0;i<entries.size();i++) {
//$$                     var e=entries.get(i);var old=previous.get(e.state());
//$$                     boolean compare=old!=null && old.comparable(e);
//$$                     GL20.glUniform1i(GL20.glGetUniformLocation(reduceProgram,"Slot"),i);
//$$                     GL20.glUniform1i(GL20.glGetUniformLocation(reduceProgram,"Compare"),compare?1:0);
//$$                     GL20.glUniform4i(GL20.glGetUniformLocation(reduceProgram,"Region"),e.x(),e.y(),e.width(),e.height());
//$$                     GL20.glUniform2i(GL20.glGetUniformLocation(reduceProgram,"AtlasOffset"),e.offsetX(),e.offsetY());
//$$                     GL20.glUniform2i(GL20.glGetUniformLocation(reduceProgram,"OldOffset"),compare?old.offsetX:0,compare?old.offsetY:0);
//$$                     if(e.width()>0 && e.height()>0)GL43.glDispatchCompute((e.width()+7)/8,(e.height()+7)/8,1);
//$$                 }
//$$                 GL42.glMemoryBarrier(GL43.GL_SHADER_STORAGE_BARRIER_BIT|GL42.GL_BUFFER_UPDATE_BARRIER_BIT);
//$$                 if(gpuMode) {
//$$                     GL43.glShaderStorageBlockBinding(finalizeProgram,
//$$                             GL43.glGetProgramResourceIndex(finalizeProgram,GL43.GL_SHADER_STORAGE_BLOCK,"Results"),binding);
//$$                     GL20.glUseProgram(finalizeProgram);
//$$                     GL20.glUniform2i(GL20.glGetUniformLocation(finalizeProgram,"Size"),width,height);
//$$                     GL20.glUniform1i(GL20.glGetUniformLocation(finalizeProgram,"Count"),entries.size());
//$$                     GL43.glDispatchCompute((entries.size()+63)/64,1,1);
//$$                     GL42.glMemoryBarrier(GL42.GL_UNIFORM_BARRIER_BIT|GL43.GL_SHADER_STORAGE_BARRIER_BIT);
//$$                     for(int i=0;i<entries.size();i++)footprints.put(entries.get(i).state(),new Footprint(null,false,i));
//$$                 } else {
//$$                     var result=stack.mallocInt(entries.size()*STRIDE/4);
//$$                     GL45.glGetNamedBufferSubData(buffer,0,result);
//$$                     for(int i=0;i<entries.size();i++) {
//$$                         var e=entries.get(i);int at=i*STRIDE/4;
//$$                         int x0=result.get(at),y0=result.get(at+1),x1=result.get(at+2),y1=result.get(at+3);
//$$                         var rectangle=x0==-1?new BoundedGlowContract.Rectangle(0,0,0,0)
//$$                                 : rectangle(e.state(),x0,y0,x1,y1,width,height);
//$$                         if(rectangle==null)throw new IllegalStateException("Invalid outline footprint reduction");
//$$                         footprints.put(e.state(),new Footprint(rectangle,result.get(at+4)!=0,i));
//$$                     }
//$$                 }
//$$                 GL20.glUseProgram(packProgram);sampler(packProgram,"Mask",0,texture);
//$$                 GL20.glUniform2i(GL20.glGetUniformLocation(packProgram,"Extent"),w,h);
//$$                 GL20.glUniform1i(GL20.glGetUniformLocation(packProgram,"Packed"),0);
//$$                 GL42.glBindImageTexture(0,packedAlpha,0,false,0,GL15.GL_WRITE_ONLY,GL30.GL_R32UI);
//$$                 GL43.glDispatchCompute(((w+31)/32+7)/8,(h+7)/8,1);
//$$                 GL42.glMemoryBarrier(GL42.GL_TEXTURE_FETCH_BARRIER_BIT|GL42.GL_SHADER_IMAGE_ACCESS_BARRIER_BIT);
//$$                 previous.clear();
//$$                 for(int i=0;i<entries.size();i++) {
//$$                     var e=entries.get(i);var s=e.state();
//$$                     previous.put(s,new Saved(e.x(),e.y(),e.width(),e.height(),e.offsetX(),e.offsetY(),
//$$                             s.lastMaskScaleX,s.lastMaskScaleY,s.lastMaskOffsetX,s.lastMaskOffsetY,i));
//$$                 }
//$$                 bufferIndex=write;previousGpuMode=gpuMode;return true;
//$$             } finally {
//$$                 GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER,binding,0);
//$$                 GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER,generic);
//$$                 GL42.glBindImageTexture(0,imageName,imageLevel,imageLayered!=0,imageLayer,imageAccess,imageFormat);
//$$             }
//$$         }
//$$     }
//$$ 
//$$     private static boolean refuse() { previous.clear();footprints.clear();return false; }
//$$     private static int powerOfTwo(int value) { return value<=1?1:Integer.highestOneBit(value-1)<<1; }
//$$     private static void sampler(int program,String name,int unit,int texture) {
//$$         GL13.glActiveTexture(GL13.GL_TEXTURE0+unit);GL11.glBindTexture(GL11.GL_TEXTURE_2D,texture);
//$$         GL33.glBindSampler(unit,0);GL20.glUniform1i(GL20.glGetUniformLocation(program,name),unit);
//$$     }
//$$     private static BoundedGlowContract.Rectangle rectangle(GlowCaptureState s,int x0,int y0,int x1,int y1,int w,int h) {
//$$         if(x0<0 || y0<0 || x1<=x0 || y1<=y0 || x1>w || y1>h)return null;
//$$         double rx=s.itemWorldToUv.x>0?s.itemWorldToUv.x*w/64.0:6;
//$$         double ry=s.itemWorldToUv.y>0?s.itemWorldToUv.y*h/64.0:6;
//$$         int[] x=axis(x0,x1,w,s.lastMaskScaleX,s.lastMaskOffsetX,rx);
//$$         int[] y=axis(y0,y1,h,s.lastMaskScaleY,s.lastMaskOffsetY,ry);
//$$         return new BoundedGlowContract.Rectangle(x[0],y[0],x[1]-x[0],y[1]-y[0]);
//$$     }
//$$     private static int[] axis(int low,int high,int size,float scale,float offset,double radius) {
//$$         double a=Math.max(1,Math.floor(size*scale)),b=Math.max(1,Math.ceil(size*scale));
//$$         // A covered texel contributes through the original bilinear reconstruction
//$$         // for half a texel beyond its cell. Then add the certified outline radius.
//$$         double lo=(low-.5-(double)offset*size)*size,hi=(high+.5-(double)offset*size)*size;
//$$         int first=(int)Math.max(0,Math.min(size,Math.floor(Math.min(lo/a,lo/b)-radius)-1));
//$$         int end=(int)Math.max(0,Math.min(size,Math.ceil(Math.max(hi/a,hi/b)+radius)+1));
//$$         return new int[]{first,end};
//$$     }
//$$     private static void ensure(int w,int h) {
//$$         if(reduceProgram==0)reduceProgram=program(REDUCE);
//$$         if(packProgram==0)packProgram=program(PACK);
//$$         if(gpuCapable() && finalizeProgram==0) {
//$$             try { finalizeProgram=program(FINALIZE); }
//$$             catch(RuntimeException|LinkageError unavailable) {
//$$                 gpuDisabled=true;cn.spectra.gallium.Gallium.LOGGER.warn("GPU footprint mapping unavailable; keeping the readback path",unavailable);
//$$             }
//$$         }
//$$         for(int i=0;i<2;i++)if(buffers[i]==0) {
//$$             buffers[i]=GL45.glCreateBuffers();GL45.glNamedBufferData(buffers[i],(long)LIMIT*STRIDE,GL15.GL_DYNAMIC_COPY);
//$$         }
//$$         if(packedAlpha!=0 && atlasWidth==w && atlasHeight==h)return;
//$$         if(packedAlpha!=0)GL11.glDeleteTextures(packedAlpha);
//$$         packedAlpha=0;previous.clear();atlasWidth=w;atlasHeight=h;
//$$         packedAlpha=GL45.glCreateTextures(GL11.GL_TEXTURE_2D);
//$$         GL45.glTextureStorage2D(packedAlpha,1,GL30.GL_R32UI,(w+31)/32,h);
//$$         GL45.glTextureParameteri(packedAlpha,GL11.GL_TEXTURE_MIN_FILTER,GL11.GL_NEAREST);
//$$         GL45.glTextureParameteri(packedAlpha,GL11.GL_TEXTURE_MAG_FILTER,GL11.GL_NEAREST);
//$$     }
//$$     private static int program(String source) {
//$$         int shader=GL20.glCreateShader(GL43.GL_COMPUTE_SHADER),program=0;
//$$         try {
//$$             GL20.glShaderSource(shader,source);GL20.glCompileShader(shader);
//$$             if(GL20.glGetShaderi(shader,GL20.GL_COMPILE_STATUS)==0)throw new IllegalStateException(GL20.glGetShaderInfoLog(shader));
//$$             program=GL20.glCreateProgram();GL20.glAttachShader(program,shader);GL20.glLinkProgram(program);
//$$             if(GL20.glGetProgrami(program,GL20.GL_LINK_STATUS)==0)throw new IllegalStateException(GL20.glGetProgramInfoLog(program));
//$$             return program;
//$$         } catch(RuntimeException|Error failure) {
//$$             if(program!=0)GL20.glDeleteProgram(program);throw failure;
//$$         } finally { GL20.glDeleteShader(shader); }
//$$     }
//$$     static void release() {
//$$         if(packedAlpha!=0)GL11.glDeleteTextures(packedAlpha);
//$$         for(int b:buffers)if(b!=0)GL15.glDeleteBuffers(b);Arrays.fill(buffers,0);bufferIndex=0;
//$$         packedAlpha=buffer=atlasWidth=atlasHeight=0;previous.clear();captured.clear();footprints.clear();
//$$     }
//$$     static void dispose() {
//$$         release();if(reduceProgram!=0)GL20.glDeleteProgram(reduceProgram);if(packProgram!=0)GL20.glDeleteProgram(packProgram);
//$$         if(finalizeProgram!=0)GL20.glDeleteProgram(finalizeProgram);finalizeProgram=0;gpuDisabled=false;
//$$         reduceProgram=packProgram=0;
//$$     }
//$$ }
//#else
final class OutlineMaskFootprints { private OutlineMaskFootprints() {} }
//#endif
