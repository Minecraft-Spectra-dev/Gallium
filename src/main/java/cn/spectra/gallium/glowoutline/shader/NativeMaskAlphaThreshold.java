package cn.spectra.gallium.glowoutline.shader;

//#if MC==1_21_11 || MC==1_26_01
import com.mojang.blaze3d.buffers.*;
import com.mojang.blaze3d.opengl.GlTexture;
import com.mojang.blaze3d.pipeline.*;
import com.mojang.blaze3d.shaders.*;
import com.mojang.blaze3d.systems.*;
import com.mojang.blaze3d.vertex.*;
import net.minecraft.resources.Identifier;
import org.lwjgl.opengl.*;
import org.lwjgl.system.*;
import java.nio.*;
import java.util.*;
public final class NativeMaskAlphaThreshold {
 private static Float minimum;private static boolean tried;private static cn.spectra.gallium.glowoutline.capture.OpenGlMaskOrdering.Stamp owner;
 static {cn.spectra.gallium.glowoutline.shader.GlowResources.register(()->{minimum=null;tried=false;owner=null;});}
 public static Float minimum(){if(owner==null || !owner.current()){owner=cn.spectra.gallium.glowoutline.capture.OpenGlMaskOrdering.observe();minimum=null;tried=false;if(owner==null)return null;}if(tried)return minimum;tried=true;try{return minimum=find();}catch(RuntimeException unsupported){return null;}}
 private static float find(){
  var device=RenderSystem.getDevice();var id=Identifier.fromNamespaceAndPath("gallium","internal/visibility_alpha_probe");
  var pipeline=RenderPipeline.builder().withLocation(id).withVertexShader(id).withFragmentShader(id)
   .withUniform("AlphaProbe",UniformType.UNIFORM_BUFFER)
   //#if MC>=1_26_00
   .withColorTargetState(new ColorTargetState(Optional.empty(),15)).withDepthStencilState(Optional.empty())
   //#else
   //$$ .withColorWrite(true,true).withDepthWrite(false).withDepthTestFunction(com.mojang.blaze3d.platform.DepthTestFunction.NO_DEPTH_TEST)
   //#endif
   .withCull(false).withVertexFormat(DefaultVertexFormat.EMPTY,VertexFormat.Mode.TRIANGLES).build();
  String vs="#version 450\nvoid main(){vec2 p=vec2((gl_VertexID<<1)&2,gl_VertexID&2);gl_Position=vec4(p*2.0-1.0,0.0,1.0);}";
  String fs="#version 450\nlayout(std140) uniform AlphaProbe {vec4 Value;};out vec4 fragColor;void main(){fragColor=Value;}";
  if(!device.precompilePipeline(pipeline,(shader,type)->!id.equals(shader)?null:type==ShaderType.VERTEX?vs:fs).isValid())throw new IllegalStateException("Alpha calibration shader failed");
  var target=new TextureTarget("Alpha conversion probe",8,8,false);var pixels=MemoryUtil.memAlloc(8*8*4);
  try(
   //#if MC<1_26_00
   //$$ var vertices=device.createBuffer(()->"Alpha probe vertices",GpuBuffer.USAGE_VERTEX,16);
   //#endif
   var values=device.createBuffer(()->"Alpha probe values",GpuBuffer.USAGE_UNIFORM|GpuBuffer.USAGE_COPY_DST,16);var pack=new cn.spectra.gallium.glowoutline.capture.NativePixelPackScope()){
   int low=Float.floatToRawIntBits(2f/255),high=Float.floatToRawIntBits(3f/255);
   if(visible(pipeline,target,values,
   //#if MC<1_26_00
   //$$ vertices,
   //#endif
   pixels,Float.intBitsToFloat(low)) || !visible(pipeline,target,values,
   //#if MC<1_26_00
   //$$ vertices,
   //#endif
   pixels,Float.intBitsToFloat(high)))throw new IllegalStateException("Alpha calibration interval unsupported");
   while(high-low>1){int mid=low+(high-low)/2;if(visible(pipeline,target,values,
   //#if MC<1_26_00
   //$$ vertices,
   //#endif
   pixels,Float.intBitsToFloat(mid)))high=mid;else low=mid;}
   float result=Float.intBitsToFloat(high);return result;
  }finally{MemoryUtil.memFree(pixels);target.destroyBuffers();}
 }
 private static boolean visible(RenderPipeline pipeline,TextureTarget target,GpuBuffer values,
  //#if MC<1_26_00
  //$$ GpuBuffer vertices,
  //#endif
  ByteBuffer pixels,float alpha){
  var encoder=RenderSystem.getDevice().createCommandEncoder();try(var stack=MemoryStack.stackPush()){var data=stack.malloc(16);data.putFloat(0,1).putFloat(4,1).putFloat(8,1).putFloat(12,alpha);encoder.writeToBuffer(values.slice(),data);}
  var indices=RenderSystem.getSequentialBuffer(VertexFormat.Mode.TRIANGLES);var index=indices.getBuffer(3);
  try(var pass=encoder.createRenderPass(()->"Alpha calibration",target.getColorTextureView(),OptionalInt.of(0))){pass.setPipeline(pipeline);
   //#if MC<1_26_00
   //$$ pass.setVertexBuffer(0,vertices);
   //#endif
   pass.setUniform("AlphaProbe",values);pass.setIndexBuffer(index,indices.type());pass.drawIndexed(0,0,3,1);}
  GL45.glGetTextureImage(((GlTexture)target.getColorTexture()).glId(),0,GL11.GL_RGBA,GL11.GL_UNSIGNED_BYTE,pixels);
  boolean result=Byte.toUnsignedInt(pixels.get(3))>=3;for(int i=1;i<64;i++)if((Byte.toUnsignedInt(pixels.get(i*4+3))>=3)!=result)throw new IllegalStateException("Pixel-dependent alpha conversion");return result;
 }
}

//#else
//$$ public final class NativeMaskAlphaThreshold {}
//#endif
