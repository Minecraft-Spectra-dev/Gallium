package cn.spectra.gallium.glowoutline.shader;
import cn.spectra.gallium.glowoutline.ItemEffectConfig;
import cn.spectra.gallium.glowoutline.ShaderParam;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class OriginalGlowParametersTest {
 private static ArrayList<ShaderParam> valid(){return new ArrayList<>(List.of(new ShaderParam.Float("Intensity",1),new ShaderParam.Float("PulseSpeed",2),new ShaderParam.Float("WaveSpeed",3),new ShaderParam.Vec3("InnerColor",1,.5f,0),new ShaderParam.Vec3("OuterColor",0,.5f,1)));}
 private static boolean accepted(List<ShaderParam> params){return OriginalGlowParameters.supported(new ItemEffectConfig("glow_outline",params));}
 @Test void acceptsEveryOrderingWithoutDependingOnParameterOffsets(){var params=valid();permute(params,0);}
 private static void permute(ArrayList<ShaderParam> params,int at){if(at==params.size()){assertTrue(accepted(params));return;}for(int i=at;i<params.size();i++){Collections.swap(params,at,i);permute(params,at+1);Collections.swap(params,at,i);}}
 @Test void rejectsMissingExtraDuplicateAndUnknownParameters(){
  assertFalse(OriginalGlowParameters.supported(null));
  for(int i=0;i<5;i++){var params=valid();params.remove(i);assertFalse(accepted(params));}
  var params=valid();params.add(new ShaderParam.Float("Extra",1));assertFalse(accepted(params));
  params=valid();params.set(0,params.get(1));assertFalse(accepted(params));
  params=valid();params.set(0,new ShaderParam.Float("Unknown",1));assertFalse(accepted(params));
 }
 @Test void rejectsParametersThatCanMakeZeroSupportPixelsNonFinite(){
  for(int i=0;i<3;i++){var params=valid();params.set(i,new ShaderParam.Float(params.get(i).name(),Float.MAX_VALUE));assertFalse(accepted(params));}
  var params=valid();params.set(3,new ShaderParam.Vec3("InnerColor",Float.MAX_VALUE,0,0));assertFalse(accepted(params));
 }
 @Test void rejectsNonFiniteValuesAndWrongTypes(){
  for(float bad:new float[]{Float.NaN,Float.POSITIVE_INFINITY,Float.NEGATIVE_INFINITY}){
   for(int i=0;i<3;i++){var params=valid();params.set(i,new ShaderParam.Float(params.get(i).name(),bad));assertFalse(accepted(params));}
   for(int i=3;i<5;i++)for(int axis=0;axis<3;axis++){var params=valid();params.set(i,new ShaderParam.Vec3(params.get(i).name(),axis==0?bad:1,axis==1?bad:1,axis==2?bad:1));assertFalse(accepted(params));}
  }
  for(int i=0;i<5;i++){var params=valid();params.set(i,new ShaderParam.Vec2(params.get(i).name(),1,1));assertFalse(accepted(params));}
 }
}
