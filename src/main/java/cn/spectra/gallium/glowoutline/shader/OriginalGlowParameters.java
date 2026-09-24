package cn.spectra.gallium.glowoutline.shader;
import cn.spectra.gallium.glowoutline.ShaderParam;
/** Parameters whose original effect cannot read overridden native coordinates or overflow. */
public final class OriginalGlowParameters {
 private OriginalGlowParameters() {}
 public static boolean supported(cn.spectra.gallium.glowoutline.ItemEffectConfig cfg){
  if(cfg==null || cfg.params()==null || cfg.params().size()!=5)return false;int seen=0;
  for(var p:cfg.params()){
   int bit=0;
   if(p instanceof ShaderParam.Float f){if(!safeParameter(f.value()))return false;bit=switch(f.name()){case "Intensity"->1;case "PulseSpeed"->2;case "WaveSpeed"->4;default->0;};}
   else if(p instanceof ShaderParam.Vec3 v){if(!safeParameter(v.x()) || !safeParameter(v.y()) || !safeParameter(v.z()))return false;bit=switch(v.name()){case "InnerColor"->8;case "OuterColor"->16;default->0;};}
   if(bit==0 || (seen&bit)!=0)return false;seen|=bit;
  }
  return seen==31;
 }
 /** Only the verified original shader performs both source and destination scene occlusion. */
 public static boolean supportsDeferredSceneOcclusion(cn.spectra.gallium.glowoutline.ItemEffectConfig cfg){
  return supported(cfg) && BoundedGlowContracts.get(cfg.shader()) != null
          && BoundedGlowContracts.automatic(cfg.shader());
 }
 private static boolean safeParameter(float value){
  // Keep the verified shader's time, mix and intensity products finite.
  return Float.isFinite(value) && Math.abs(value)<=1_000_000f;
 }
}
