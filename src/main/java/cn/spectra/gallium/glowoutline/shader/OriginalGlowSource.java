package cn.spectra.gallium.glowoutline.shader;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.regex.Pattern;
/** A closed set of source programs whose sampling and finite support have been verified. */
public final class OriginalGlowSource {
 private static final Pattern COMMENTS=Pattern.compile("(?s)/\\*.*?\\*/|//[^\\r\\n]*");
 private static final Pattern METADATA=Pattern.compile("(?m)^\\h*#\\h*(?:line\\h+\\d+(?:\\h+\\d+)?|version\\h+\\d+(?:\\h+(?:core|compatibility))?)\\h*$");
 private static final Pattern TOKENS=Pattern.compile("[A-Za-z_]\\w*|(?:\\d+(?:\\.\\d*)?|\\.\\d+)(?:[eE][+-]?\\d+)?[fFuU]?|<<=|>>=|\\+\\+|--|==|!=|<=|>=|&&|\\|\\||<<|>>|\\+=|-=|\\*=|/=|%=|&=|\\|=|\\^=|\\S");
 private static final String FRAGMENT=load("fragment.fsh"),VERTEX=load("vertex.vsh"),COMMON=load("common.glsl"),ADAPTED=load("adapted.fsh");
 private static final String EXPANDED=FRAGMENT.replace("#moj_import <gallium:glow_common.glsl>",COMMON);
 private static final String FRAGMENT_HASH=fingerprint(FRAGMENT,false),VERTEX_HASH=fingerprint(VERTEX,false),COMMON_HASH=fingerprint(COMMON,false);
 private static final String EXPANDED_HASH=fingerprint(EXPANDED,true);
 private static final boolean TRUSTED=
  "6e0fa610322034318248d99ffaa813408a84c4a57f4f1cfc5517e225f875fe43".equals(FRAGMENT_HASH)
  && "e63c1e7588878068e2376762a2aa2472b43d629f51e79295effe465ca02648d1".equals(VERTEX_HASH)
  && "17ea47b333708614d927106c9bd59674c52f9d554c0a20b903d3e1ebba7e9820".equals(COMMON_HASH)
  && "9f20bc6ea3f05e87688e741845f29fe9242182f34e90e621430ee711a74bcf97".equals(fingerprint(ADAPTED,false));
 private OriginalGlowSource(){}
 private static String load(String name){
  try(var stream=OriginalGlowSource.class.getResourceAsStream("/cn/spectra/gallium/verified-shaders/"+name)){
   if(stream==null)return "";
   return new String(stream.readAllBytes(),StandardCharsets.UTF_8);
  }catch(java.io.IOException failure){return "";}
 }
 public static boolean bundle(String fragment,String vertex,String common){
  return TRUSTED && FRAGMENT_HASH.equals(fingerprint(fragment,false)) && VERTEX_HASH.equals(fingerprint(vertex,false)) && COMMON_HASH.equals(fingerprint(common,false));
 }
 /** Only the private world alias is adapted; GUI and resource files retain their original code. */
 public static String adapt(String source,boolean vertex){
  //#if MC>=1_21_06 && MC<1_26_02
  if(TRUSTED && !vertex && (EXPANDED_HASH.equals(fingerprint(source,true)) || FRAGMENT_HASH.equals(fingerprint(source,false))))return ADAPTED;
  //#endif
  return source;
 }
 static String expected(boolean vertex){
  String source=vertex?VERTEX:EXPANDED;
  //#if MC>=1_21_06 && MC<1_26_02
  if(!vertex)source=ADAPTED;
  //#elseif MC<1_21_06
  //$$ source=UboRewriter.rewrite(source);
  //#endif
  return WorldGlowShader.wrap(source,vertex);
 }
 static String storedStage(boolean vertex){
  if(!TRUSTED)return null;
  String source=WorldGlowShader.wrap(vertex?VERTEX:ADAPTED,vertex);
  if(!vertex){int end=source.indexOf('\n')+1;source=source.substring(0,end)+"#define GALLIUM_HAS_MASK_STORAGE 1\n"+source.substring(end);}
  return InstancedGlowSource.adapt(source,vertex);
 }
 /** Same verified effect with an additional display-grid inter-item depth test. */
 static String nativeOcclusionStage(){
  if(!TRUSTED)return null;
  int start=ADAPTED.indexOf("#ifdef GALLIUM_HAS_MASK_INSTANCES"),end=ADAPTED.indexOf("in vec2 texCoord;");
  if(start<0 || end<start)return null;
  String uniforms="""
   #define GALLIUM_HAS_MASK_STORAGE 1
   uniform float FrameTimeCounter,Intensity,PulseSpeed,WaveSpeed,GalliumItemDistance;
   uniform vec2 ScreenSize,GalliumWorldToUv;
   uniform vec3 InnerColor,OuterColor;
   uniform vec4 ShaderAlign,ShaderOffset,GalliumMaskBounds,GalliumMaskRegion,GalliumMaskStorage;
   uniform sampler2D NativeScene;
   """;
  return (ADAPTED.substring(0,start)+uniforms+ADAPTED.substring(end)).replace("return sceneDepth;",
   "return storedHand() ? sceneDepth : min(sceneDepth,texelFetch(NativeScene,clamp(ivec2(uv*vec2(textureSize(NativeScene,0))),ivec2(0),textureSize(NativeScene,0)-1),0).r);");
 }
 public static boolean linkedSource(String source,boolean vertex){String expected=fingerprint(expected(vertex),true);return TRUSTED && expected!=null && expected.equals(fingerprint(source,true));}
 static String fingerprint(String source,boolean metadata){
  if(source==null || source.length()>1_048_576 || source.contains("\\") || source.contains("##"))return null;
  if(source.startsWith("\uFEFF"))source=source.substring(1);
  var visible=new StringBuilder(source);var comments=COMMENTS.matcher(source);
  while(comments.find())for(int i=comments.start();i<comments.end();i++)if(source.charAt(i)!='\r' && source.charAt(i)!='\n')visible.setCharAt(i,' ');
  source=visible.toString();if(metadata)source=METADATA.matcher(source).replaceAll("");
  var tokens=new ArrayList<String>();
  for(String line:source.split("\\R")){
   var matcher=TOKENS.matcher(line);boolean any=false;
   while(matcher.find()){tokens.add(matcher.group());any=true;}
   if(any && line.stripLeading().startsWith("#"))tokens.add("@newline");
  }
  try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(String.join(" ",tokens).getBytes(StandardCharsets.UTF_8)));}
  catch(NoSuchAlgorithmException impossible){throw new AssertionError(impossible);}
 }
}
