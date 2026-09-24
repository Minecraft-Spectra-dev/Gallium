package cn.spectra.gallium.glowoutline.shader;
//#if MC<1_21_05
//$$ import org.lwjgl.opengl.*;
//$$ import org.lwjgl.system.MemoryStack;
//$$ /** Raw work has no native callbacks; restore every touched binding and indexed raster state. */
//$$ final class LegacyCompositeState implements AutoCloseable {
//$$  private final int program=GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM),vao=GL11.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING);
//$$  private final int read=GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING),draw=GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
//$$  private final int active=GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE),uniform=GL11.glGetInteger(GL31.GL_UNIFORM_BUFFER_BINDING);
//$$  private final int indexed=GL30.glGetIntegeri(GL31.GL_UNIFORM_BUFFER_BINDING,0);
//$$  private final long start=GL32.glGetInteger64i(GL31.GL_UNIFORM_BUFFER_START,0),size=GL32.glGetInteger64i(GL31.GL_UNIFORM_BUFFER_SIZE,0);
//$$  private final boolean depth=GL11.glIsEnabled(GL11.GL_DEPTH_TEST),cull=GL11.glIsEnabled(GL11.GL_CULL_FACE),blend=GL30.glIsEnabledi(GL11.GL_BLEND,0),depthWrite=GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK);
//$$  private final int srcRgb=GL30.glGetIntegeri(GL14.GL_BLEND_SRC_RGB,0),dstRgb=GL30.glGetIntegeri(GL14.GL_BLEND_DST_RGB,0),srcAlpha=GL30.glGetIntegeri(GL14.GL_BLEND_SRC_ALPHA,0),dstAlpha=GL30.glGetIntegeri(GL14.GL_BLEND_DST_ALPHA,0);
//$$  private final int eqRgb=GL30.glGetIntegeri(GL20.GL_BLEND_EQUATION_RGB,0),eqAlpha=GL30.glGetIntegeri(GL20.GL_BLEND_EQUATION_ALPHA,0);
//$$  private final int[] textures=new int[6],samplers=new int[6],scissors=new int[64],color=new int[4];
//$$  private final boolean[] enabled=new boolean[16];
//$$  private final float[] viewports=new float[64];
//$$  LegacyCompositeState(){
//$$   try(var stack=MemoryStack.stackPush()){
//$$    var ints=stack.mallocInt(4);var floats=stack.mallocFloat(4);
//$$    GL30.glGetIntegeri_v(GL11.GL_COLOR_WRITEMASK,0,ints);for(int j=0;j<4;j++)color[j]=ints.get(j);
//$$    for(int i=0;i<16;i++){
//$$     GL30.glGetIntegeri_v(GL11.GL_SCISSOR_BOX,i,ints);GL41.glGetFloati_v(GL11.GL_VIEWPORT,i,floats);enabled[i]=GL30.glIsEnabledi(GL11.GL_SCISSOR_TEST,i);
//$$     for(int j=0;j<4;j++){scissors[i*4+j]=ints.get(j);viewports[i*4+j]=floats.get(j);}
//$$    }
//$$   }
//$$   for(int i=0;i<6;i++){GL13.glActiveTexture(GL13.GL_TEXTURE0+i);textures[i]=GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);samplers[i]=GL30.glGetIntegeri(GL33.GL_SAMPLER_BINDING,i);}
//$$   GL13.glActiveTexture(active);
//$$  }
//$$  boolean compatible(){
//$$   return !enabled[0] && eqRgb==GL14.GL_FUNC_ADD && eqAlpha==GL14.GL_FUNC_ADD
//$$    && color[0]!=0 && color[1]!=0 && color[2]!=0 && color[3]!=0;
//$$  }
//$$  static boolean indexedBlendSupported(){
//$$   var caps=GL.getCapabilities();
//$$   return caps.glBlendFuncSeparatei!=0 && caps.glBlendEquationSeparatei!=0
//$$    || caps.glBlendFuncSeparateiARB!=0 && caps.glBlendEquationSeparateiARB!=0;
//$$  }
//$$  static void blend(int srcRgb,int dstRgb,int srcAlpha,int dstAlpha,int eqRgb,int eqAlpha){
//$$   // Legacy contexts can expose only the ARB entry points, even on newer hardware.
//$$   if(GL.getCapabilities().glBlendFuncSeparatei!=0 && GL.getCapabilities().glBlendEquationSeparatei!=0){
//$$    GL40.glBlendFuncSeparatei(0,srcRgb,dstRgb,srcAlpha,dstAlpha);GL40.glBlendEquationSeparatei(0,eqRgb,eqAlpha);
//$$   }else{
//$$    ARBDrawBuffersBlend.glBlendFuncSeparateiARB(0,srcRgb,dstRgb,srcAlpha,dstAlpha);ARBDrawBuffersBlend.glBlendEquationSeparateiARB(0,eqRgb,eqAlpha);
//$$   }
//$$  }
//$$  private static void enable(int cap,boolean value){if(value)GL11.glEnable(cap);else GL11.glDisable(cap);}
//$$  public void close(){
//$$   GL20.glUseProgram(program);GL30.glBindVertexArray(vao);
//$$   for(int i=0;i<6;i++){GL13.glActiveTexture(GL13.GL_TEXTURE0+i);GL11.glBindTexture(GL11.GL_TEXTURE_2D,textures[i]);GL33.glBindSampler(i,samplers[i]);}
//$$   GL13.glActiveTexture(active);
//$$   if(indexed!=0 && size>0)GL30.glBindBufferRange(GL31.GL_UNIFORM_BUFFER,0,indexed,start,size);else GL30.glBindBufferBase(GL31.GL_UNIFORM_BUFFER,0,indexed);
//$$   GL15.glBindBuffer(GL31.GL_UNIFORM_BUFFER,uniform);
//$$   GL41.glViewportArrayv(0,viewports);GL41.glScissorArrayv(0,scissors);
//$$   for(int i=0;i<16;i++)if(enabled[i])GL30.glEnablei(GL11.GL_SCISSOR_TEST,i);else GL30.glDisablei(GL11.GL_SCISSOR_TEST,i);
//$$   GL30.glColorMaski(0,color[0]!=0,color[1]!=0,color[2]!=0,color[3]!=0);GL11.glDepthMask(depthWrite);
//$$   enable(GL11.GL_DEPTH_TEST,depth);enable(GL11.GL_CULL_FACE,cull);if(blend)GL30.glEnablei(GL11.GL_BLEND,0);else GL30.glDisablei(GL11.GL_BLEND,0);
//$$   blend(srcRgb,dstRgb,srcAlpha,dstAlpha,eqRgb,eqAlpha);
//$$   GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER,read);GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER,draw);
//$$  }
//$$ }
//#else
final class LegacyCompositeState { private LegacyCompositeState() {} }
//#endif
