package cn.spectra.gallium.glowoutline.shader;
//#if MC<1_21_05
//$$ import cn.spectra.gallium.Gallium;
//$$ import cn.spectra.gallium.glowoutline.ShaderParam;
//$$ import cn.spectra.gallium.glowoutline.capture.*;
//$$ import com.mojang.blaze3d.pipeline.RenderTarget;
//$$ import org.lwjgl.opengl.*;
//$$ import org.lwjgl.system.MemoryStack;
//$$ import java.nio.ByteBuffer;
//$$ import java.util.List;
//$$ /** One verified original effect, ordered instances and exact native-size viewports. */
//$$ final class LegacyGlowInstances implements AutoCloseable {
//$$  private static final int LIMIT=16,STRIDE=160,CAPACITY=128*1024;
//$$  private static int program,vao,buffer,sampler;
//$$  private static boolean unavailable;
//$$  private static OpenGlMaskOrdering.Stamp owner;
//$$  private int cursor;private boolean orphaned;
//$$  static {GlowResources.register(LegacyGlowInstances::dispose);}
//$$  static boolean supported(){
//$$   var caps=GL.getCapabilities();
//$$   return OpenGlMaskOrdering.observe()!=null && LegacyCompositeState.indexedBlendSupported() && (caps.OpenGL45 || caps.GL_ARB_direct_state_access)
//$$    && (caps.OpenGL44 || caps.GL_ARB_clear_texture) && (caps.OpenGL43 || caps.GL_ARB_copy_image)
//$$    && (caps.OpenGL41 || caps.GL_ARB_viewport_array) && caps.GL_ARB_shader_viewport_layer_array
//$$    && GL11.glGetInteger(GL41.GL_MAX_VIEWPORTS)>=LIMIT;
//$$  }
//$$  static boolean parameters(cn.spectra.gallium.glowoutline.ItemEffectConfig cfg){
//$$   return OriginalGlowParameters.supported(cfg);
//$$  }
//$$  private static int shader(int type,String source){
//$$   int id=GL20.glCreateShader(type);
//$$   try{GL20.glShaderSource(id,source);GL20.glCompileShader(id);if(GL20.glGetShaderi(id,GL20.GL_COMPILE_STATUS)==0)throw new IllegalStateException(GL20.glGetShaderInfoLog(id));return id;}
//$$   catch(RuntimeException failure){GL20.glDeleteShader(id);throw failure;}
//$$  }
//$$  private static boolean ensure(){
//$$   if(program!=0)return owner!=null && owner.current();
//$$   if(unavailable || !supported())return false;unavailable=true;
//$$   int v=0,f=0,p=0;
//$$   try{
//$$    v=shader(GL20.GL_VERTEX_SHADER,OriginalGlowSource.storedStage(true));f=shader(GL20.GL_FRAGMENT_SHADER,OriginalGlowSource.storedStage(false));
//$$    p=GL20.glCreateProgram();GL20.glAttachShader(p,v);GL20.glAttachShader(p,f);GL20.glLinkProgram(p);
//$$    if(GL20.glGetProgrami(p,GL20.GL_LINK_STATUS)==0)throw new IllegalStateException(GL20.glGetProgramInfoLog(p));
//$$    int block=GL31.glGetUniformBlockIndex(p,"GlowUniforms");
//$$    if(block<0 || GL31.glGetActiveUniformBlocki(p,block,GL31.GL_UNIFORM_BLOCK_DATA_SIZE)!=STRIDE*LIMIT)throw new IllegalStateException("Unexpected original-effect UBO size");
//$$    String[] names={"FrameTimeCounter","ScreenSize","Intensity","WaveSpeed","InnerColor","OuterColor","ShaderAlign","ShaderOffset","GalliumWorldToUv","GalliumMaskRegion","GalliumMaskStorage"};
//$$    int[] offsets={0,8,16,24,32,48,64,80,104,128,144};
//$$    for(int i=0;i<names.length;i++){
//$$     int index=GL31.glGetUniformIndices(p,"gallium_InternalValues[0]."+names[i]);
//$$     if(index<0 || GL31.glGetActiveUniformsi(p,index,GL31.GL_UNIFORM_OFFSET)!=offsets[i])throw new IllegalStateException("Unexpected original-effect field "+names[i]);
//$$    }
//$$    GL31.glUniformBlockBinding(p,block,0);
//$$    String[] textures={"DiffuseSampler","MaskSampler","MaskDepthSampler","SceneDepthSampler","GalliumMaskBaseDepthSampler",WorldGlowShader.FOREGROUND_SAMPLER};
//$$    for(int i=0;i<textures.length;i++){int location=GL20.glGetUniformLocation(p,textures[i]);if(location>=0)GL41.glProgramUniform1i(p,location,i);}
//$$    vao=GL30.glGenVertexArrays();buffer=GL45.glCreateBuffers();sampler=GL33.glGenSamplers();
//$$    GL33.glSamplerParameteri(sampler,GL11.GL_TEXTURE_MIN_FILTER,GL11.GL_NEAREST);GL33.glSamplerParameteri(sampler,GL11.GL_TEXTURE_MAG_FILTER,GL11.GL_NEAREST);
//$$    GL33.glSamplerParameteri(sampler,GL11.GL_TEXTURE_WRAP_S,GL12.GL_CLAMP_TO_EDGE);GL33.glSamplerParameteri(sampler,GL11.GL_TEXTURE_WRAP_T,GL12.GL_CLAMP_TO_EDGE);
//$$    program=p;p=0;owner=OpenGlMaskOrdering.observe();unavailable=false;return true;
//$$   }catch(RuntimeException failure){Gallium.LOGGER.warn("Legacy ordered glow instances unavailable: {}",failure.toString());return false;}
//$$   finally{if(p!=0)GL20.glDeleteProgram(p);if(v!=0)GL20.glDeleteShader(v);if(f!=0)GL20.glDeleteShader(f);}
//$$  }
//$$  private static void values(ByteBuffer out,int base,LegacyMaskAtlas.Entry entry){
//$$   var s=entry.state();out.putFloat(base,GlowTime.worldSecondsFloat());out.putFloat(base+8,entry.output().width);out.putFloat(base+12,entry.output().height);
//$$   for(var p:s.config.params()){
//$$    if(p instanceof ShaderParam.Float f)out.putFloat(base+switch(f.name()){case "Intensity"->16;case "PulseSpeed"->20;default->24;},f.value());
//$$    else if(p instanceof ShaderParam.Vec3 v){int at=base+(v.name().equals("InnerColor")?32:48);out.putFloat(at,v.x()).putFloat(at+4,v.y()).putFloat(at+8,v.z());}
//$$   }
//$$   out.putFloat(base+64,s.lastMaskScaleX).putFloat(base+68,s.lastSceneScaleX).putFloat(base+72,s.lastMaskScaleY).putFloat(base+76,s.exactDepthAlignment?s.lastSceneScaleY:-s.lastSceneScaleY);
//$$   out.putFloat(base+80,s.lastMaskOffsetX).putFloat(base+84,s.lastSceneOffsetX).putFloat(base+88,s.lastMaskOffsetY).putFloat(base+92,s.lastSceneOffsetY);
//$$   out.putFloat(base+96,s.itemDistance).putFloat(base+104,s.itemWorldToUv.x).putFloat(base+108,s.itemWorldToUv.y);
//$$   out.putFloat(base+112,s.maskBounds.minX()).putFloat(base+116,s.maskBounds.minY()).putFloat(base+120,s.maskBounds.maxX()).putFloat(base+124,s.maskBounds.maxY());
//$$   out.putFloat(base+128,entry.x()).putFloat(base+132,entry.y()).putFloat(base+136,entry.width()).putFloat(base+140,entry.height());
//$$   out.putFloat(base+144,entry.offsetX()).putFloat(base+148,entry.offsetY()).putFloat(base+152,1).putFloat(base+156,s.firstPerson?1:0);
//$$  }
//$$  int draw(List<LegacyMaskAtlas.Entry> entries,int start,RenderTarget output,int diffuse,int scene,int foreground){
//$$   if(output.viewWidth!=output.width || output.viewHeight!=output.height || !ensure() || GL11.glIsEnabled(GL11.GL_STENCIL_TEST))return 0;
//$$   var first=entries.get(start);int count=1;
//$$   while(count<LIMIT && start+count<entries.size()){
//$$    var e=entries.get(start+count);
//$$    if(e.atlas()!=first.atlas() || e.baseDepth()!=first.baseDepth() || e.state().firstPerson!=first.state().firstPerson || !e.state().config.shader().equals(first.state().config.shader()))break;
//$$    count++;
//$$   }
//$$   var contract=BoundedGlowContracts.get(first.state().config.shader());
//$$   if(contract==null || !BoundedGlowContracts.automatic(first.state().config.shader()))return 0;
//$$   var rectangles=new BoundedGlowContract.Rectangle[count];
//$$   for(int i=0;i<count;i++){
//$$    var e=entries.get(start+i);var state=e.state();
//$$    if(!state.hasOrdinaryMaskStored(e.epoch(),e) || state.compositedThisFrame || !state.maskPreparedThisFrame || !parameters(state.config))return 0;
//$$    rectangles[i]=contract.rectangle(state.maskBounds,output.width,output.height,state.itemWorldToUv.x,state.itemWorldToUv.y);
//$$    if(rectangles[i]==null)return 0;
//$$   }
//$$   int alignment=GL11.glGetInteger(GL31.GL_UNIFORM_BUFFER_OFFSET_ALIGNMENT);if(alignment<=0)return 0;
//$$   int offset=(cursor+alignment-1)/alignment*alignment,size=STRIDE*LIMIT;if((long)offset+size>CAPACITY)return 0;
//$$   if(!LegacySparseGlow.prepareStored(output,foreground,contract))return 0;
//$$   try(var saved=new LegacyCompositeState();var stack=MemoryStack.stackPush()){
//$$    if(!saved.compatible())return 0;
//$$    if(!orphaned){GL45.glNamedBufferData(buffer,CAPACITY,GL15.GL_STREAM_DRAW);orphaned=true;}
//$$    var data=stack.calloc(size);for(int i=0;i<count;i++)values(data,i*STRIDE,entries.get(start+i));
//$$    GL45.glNamedBufferSubData(buffer,offset,data);GL30.glBindBufferRange(GL31.GL_UNIFORM_BUFFER,0,buffer,offset,size);cursor=offset+size;
//$$    GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER,output.frameBufferId);
//$$    GL20.glUseProgram(program);GL30.glBindVertexArray(vao);GL11.glDisable(GL11.GL_DEPTH_TEST);GL11.glDepthMask(false);GL11.glDisable(GL11.GL_CULL_FACE);
//$$    GL30.glEnablei(GL11.GL_BLEND,0);LegacyCompositeState.blend(GL11.GL_ONE,GL11.GL_ONE,GL11.GL_ZERO,GL11.GL_ONE,GL14.GL_FUNC_ADD,GL14.GL_FUNC_ADD);
//$$    GL30.glColorMaski(0,true,true,true,false);
//$$    int[] textures={diffuse,first.atlas().getColorTextureId(),first.atlas().getDepthTextureId(),scene,first.baseDepth()==null?foreground:first.baseDepth().getDepthTextureId(),foreground};
//$$    for(int i=0;i<textures.length;i++){GL13.glActiveTexture(GL13.GL_TEXTURE0+i);GL11.glBindTexture(GL11.GL_TEXTURE_2D,textures[i]);GL33.glBindSampler(i,sampler);}
//$$    var viewports=stack.mallocFloat(count*4);var scissors=stack.mallocInt(count*4);
//$$    for(var r:rectangles){viewports.put(0).put(0).put(output.width).put(output.height);scissors.put(r.x()).put(r.y()).put(r.width()).put(r.height());}
//$$    viewports.flip();scissors.flip();GL41.glViewportArrayv(0,viewports);GL41.glScissorArrayv(0,scissors);for(int i=0;i<count;i++)GL30.glEnablei(GL11.GL_SCISSOR_TEST,i);
//$$    // Preserve the original legacy quad index sequence and gl_VertexID values.
//$$    var indices=com.mojang.blaze3d.systems.RenderSystem.getSequentialBuffer(com.mojang.blaze3d.vertex.VertexFormat.Mode.QUADS);
//$$    indices.bind(6);
//$$    GL31.glDrawElementsInstanced(GL11.GL_TRIANGLES,6,indices.type().asGLType,0L,count);
//$$   }
//$$   LegacySparseGlow.storedCompleted(output);
//$$   for(int i=0;i<count;i++)entries.get(start+i).state().compositedThisFrame=true;
//$$   return count;
//$$  }
//$$  public void close(){}
//$$  private static void dispose(){
//$$   if(owner!=null && !owner.current())return;
//$$   if(program!=0)GL20.glDeleteProgram(program);if(vao!=0)GL30.glDeleteVertexArrays(vao);if(buffer!=0)GL15.glDeleteBuffers(buffer);if(sampler!=0)GL33.glDeleteSamplers(sampler);
//$$   program=vao=buffer=sampler=0;unavailable=false;owner=null;
//$$  }
//$$ }
//#else
final class LegacyGlowInstances { private LegacyGlowInstances() {} }
//#endif
