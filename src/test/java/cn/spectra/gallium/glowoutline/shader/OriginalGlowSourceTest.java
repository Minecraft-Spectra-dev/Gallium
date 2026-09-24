package cn.spectra.gallium.glowoutline.shader;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class OriginalGlowSourceTest {
 private static String resource(String name)throws Exception{try(var in=OriginalGlowSourceTest.class.getResourceAsStream("/cn/spectra/gallium/verified-shaders/"+name)){assertNotNull(in);return new String(in.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);}}
 @Test void acceptsTheCompleteOriginalBundle()throws Exception{assertTrue(OriginalGlowSource.bundle(resource("fragment.fsh"),resource("vertex.vsh"),resource("common.glsl")));}
 @Test void acceptsCommentsWithoutChangingTokens()throws Exception{assertTrue(OriginalGlowSource.bundle(resource("fragment.fsh").replace("WORLD_RADIUS =","WORLD_RADIUS /* explanation */ ="),resource("vertex.vsh"),resource("common.glsl")));}
 @Test void rejectsChangedRadiusAndThreshold()throws Exception{
  for(String changed:new String[]{resource("fragment.fsh").replace("1.0 / 64.0","1.0 / 32.0"),resource("fragment.fsh").replace("alpha <= 0.01","alpha <= 0.1")})
   assertFalse(OriginalGlowSource.bundle(changed,resource("vertex.vsh"),resource("common.glsl")));
 }
 @Test void rejectsDifferentVertexMapping()throws Exception{assertFalse(OriginalGlowSource.bundle(resource("fragment.fsh"),resource("vertex.vsh").replace("* 0.5","* 0.25"),resource("common.glsl")));}
 @Test void rejectsChangedCommonAlphaOrColor()throws Exception{
  for(String changed:new String[]{resource("common.glsl").replace(", 0.5)",", 1.0)"),resource("common.glsl").replace("innerColor * finalIntensity","innerColor + finalIntensity")})
   assertFalse(OriginalGlowSource.bundle(resource("fragment.fsh"),resource("vertex.vsh"),changed));
 }
 @Test void rejectsMacroAndImportChanges()throws Exception{
  String f=resource("fragment.fsh");
  assertFalse(OriginalGlowSource.bundle("#define finalizeGlow modified\n"+f,resource("vertex.vsh"),resource("common.glsl")));
  assertFalse(OriginalGlowSource.bundle(f.replace("gallium:glow_common.glsl","other:glow_common.glsl"),resource("vertex.vsh"),resource("common.glsl")));
 }
 @Test void doesNotMergeIncrementTokens()throws Exception{assertFalse(OriginalGlowSource.bundle(resource("fragment.fsh").replace("i++","i + +"),resource("vertex.vsh"),resource("common.glsl")));}
 @Test void preservesTheOriginalMainBody()throws Exception{
  String f=resource("fragment.fsh"),adapted=OriginalGlowSource.adapt(f,false);
  assertEquals(f.substring(f.indexOf("void main()")),adapted.substring(adapted.indexOf("void main()")));
  //#if MC>=1_21_06 && MC<1_26_02
  assertTrue(adapted.contains("GalliumMaskStorage"));
  //#else
  //$$ assertSame(f,adapted);
  //#endif
 }
 @Test void leavesOtherProgramsAndVerticesUntouched()throws Exception{
  String f=resource("fragment.fsh").replace("0.01","0.02"),v=resource("vertex.vsh");
  assertSame(f,OriginalGlowSource.adapt(f,false));assertSame(v,OriginalGlowSource.adapt(v,true));
 }
 @Test void verifiesWrappedProgramAndRejectsOutputChanges()throws Exception{
  for(boolean vertex:new boolean[]{false,true}){
   String expected=OriginalGlowSource.expected(vertex);assertTrue(OriginalGlowSource.linkedSource(expected,vertex));
   assertTrue(OriginalGlowSource.linkedSource("#line 90 4\n"+expected,vertex));
   assertFalse(OriginalGlowSource.linkedSource("#define vec4 vec3\n"+expected,vertex));
  }
  assertFalse(OriginalGlowSource.linkedSource(OriginalGlowSource.expected(false).replace("fragColor.rgb *= subpixelCoverage","fragColor.rgb += subpixelCoverage"),false));
 }
 @Test void rejectsMissingOversizedAndEscapedSources()throws Exception{
  assertFalse(OriginalGlowSource.bundle(null,resource("vertex.vsh"),resource("common.glsl")));
  assertFalse(OriginalGlowSource.linkedSource(null,false));
  assertNull(OriginalGlowSource.fingerprint("x".repeat(1_048_577),false));
  assertNull(OriginalGlowSource.fingerprint("#define x \\\ny",false));
 }
 @Test void limitsPrivateSourceNames(){
  assertEquals("outline",WorldGlowShader.sourceName("core/outline"));
  assertEquals("folder/outline",WorldGlowShader.sourceName("shaders/core/folder/outline.fsh"));
  assertNull(WorldGlowShader.sourceName("internal/other"));
  assertNull(WorldGlowShader.sourceName("core/"));
 }
 @Test void storedStagesPreserveOriginalGeometryAndEffectBody()throws Exception{
  String vertex=OriginalGlowSource.storedStage(true),fragment=OriginalGlowSource.storedStage(false);
  assertNotNull(vertex);assertNotNull(fragment);
  String v=resource("vertex.vsh"),a=resource("adapted.fsh"),f=resource("fragment.fsh");
  assertTrue(vertex.contains(v.substring(v.indexOf("void main()"))));
  assertTrue(fragment.contains(f.substring(f.indexOf("void main()"))));
  assertTrue(fragment.contains(a.substring(a.indexOf("struct GalliumGlowValues"),a.indexOf("in vec2 texCoord;"))));
  assertTrue(fragment.contains("#define GALLIUM_HAS_MASK_INSTANCES 1"));
  assertTrue(fragment.contains("#define GALLIUM_HAS_MASK_STORAGE 1"));
  assertTrue(fragment.indexOf("#define GALLIUM_HAS_MASK_INSTANCES 1")<fragment.indexOf("struct GalliumGlowValues"));
  assertTrue(fragment.indexOf("#define GALLIUM_HAS_MASK_STORAGE 1")<fragment.indexOf("struct GalliumGlowValues"));
  assertTrue(vertex.endsWith("    gl_ViewportIndex = gl_InstanceID;\n}\n"));
 }
 @Test void nativeOcclusionPreservesTheOriginalEffectAndSamplingBody()throws Exception{
  String original=resource("fragment.fsh"),adapted=resource("adapted.fsh"),nativeSource=OriginalGlowSource.nativeOcclusionStage();
  assertNotNull(nativeSource);
  assertEquals(original.substring(original.indexOf("void main()")),nativeSource.substring(nativeSource.indexOf("void main()")));
  assertTrue(nativeSource.contains(adapted.substring(adapted.indexOf("float visibleMaskTap("),adapted.indexOf("float sceneDepthAt(vec2 uv, bool exactReplay, out bool valid) {"))));
 }
}
