package cn.spectra.gallium.glowoutline.shader;
import java.util.IdentityHashMap;
import org.lwjgl.opengl.*;
import org.lwjgl.system.MemoryStack;
/** A resource fingerprint alone is insufficient: inspect the actually linked world program. */
final class AutomaticGlowPrograms {
 private record Proof(int id,boolean valid){}
 private static final IdentityHashMap<Object,Proof> programs=new IdentityHashMap<>();
 static {GlowResources.register(programs::clear);}
 private AutomaticGlowPrograms(){}
 static boolean verify(Object program,int id){
  if(program==null || id<=0)return false;
  var old=programs.get(program);if(old!=null && old.id==id)return old.valid;
  if(programs.size()>=128)programs.clear();
  boolean result=GL20.glIsProgram(id) && inspect(id);programs.put(program,new Proof(id,result));return result;
 }
 private static boolean inspect(int id){
  if(GL20.glGetProgrami(id,GL20.GL_LINK_STATUS)==0 || GL20.glGetProgrami(id,GL20.GL_ATTACHED_SHADERS)!=2)return false;
  try(var stack=MemoryStack.stackPush()){
   var shaders=stack.mallocInt(2);var count=stack.mallocInt(1);GL20.glGetAttachedShaders(id,count,shaders);
   if(count.get(0)!=2)return false;boolean vertex=false,fragment=false;
   for(int i=0;i<2;i++){
    int shader=shaders.get(i),type=GL20.glGetShaderi(shader,GL20.GL_SHADER_TYPE);String source=GL20.glGetShaderSource(shader);
    if(type==GL20.GL_VERTEX_SHADER){if(vertex || !OriginalGlowSource.linkedSource(source,true))return false;vertex=true;}
    else if(type==GL20.GL_FRAGMENT_SHADER){if(fragment || !OriginalGlowSource.linkedSource(source,false))return false;fragment=true;}
    else return false;
   }
   return vertex && fragment;
  }
 }
}
