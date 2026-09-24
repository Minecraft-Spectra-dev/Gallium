package cn.spectra.gallium.glowoutline.shader;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class NativeBlendVisibilitySourceTest {
 private static String source() throws Exception {
  try(var stream=NativeBlendVisibilitySourceTest.class.getResourceAsStream("native-12111-item.fsh")){assertNotNull(stream);return new String(stream.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);}
 }
 @Test void acceptsNativeProgramAndHarmlessMetadata()throws Exception{
  String s=source();assertTrue(NativeBlendVisibilitySource.verified(s));
  assertTrue(NativeBlendVisibilitySource.verified(s.replace("#line 0 1","#line 24 81").replace("color.a < 0.1","color.a /*same threshold*/ < 0.1")));
 }
 @Test void rejectsChangedThreshold()throws Exception{assertFalse(NativeBlendVisibilitySource.verified(source().replace("color.a < 0.1","color.a < 0.001")));}
 @Test void rejectsAlphaModifiedAfterDiscard()throws Exception{assertFalse(NativeBlendVisibilitySource.verified(source().replace("fragColor = apply_fog","color.a *= 0.001;\nfragColor = apply_fog")));}
 @Test void rejectsFogAlphaChanges()throws Exception{assertFalse(NativeBlendVisibilitySource.verified(source().replace("inColor.a);","0.001);")));}
 @Test void rejectsMacrosAndAdditionalOutputs()throws Exception{
  assertFalse(NativeBlendVisibilitySource.verified("#define discard\n"+source()));
  assertFalse(NativeBlendVisibilitySource.verified(source()+"\nvoid extra(){gl_FragDepth=0.0;}"));
 }
 @Test void rejectsMissingAndMergedTokens()throws Exception{
  assertFalse(NativeBlendVisibilitySource.verified(null));
  assertFalse(NativeBlendVisibilitySource.verified(source().replace("vec4 color","vec4color")));
 }
}
