package cn.spectra.gallium.glowoutline.shader;
import org.junit.jupiter.api.Test;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import static org.junit.jupiter.api.Assertions.*;
class VisibilityGlowSourceTest {
 private static String fixture(String name)throws IOException{
  try(var stream=VisibilityGlowSourceTest.class.getResourceAsStream(name)){assertNotNull(stream);return new String(stream.readAllBytes(),StandardCharsets.UTF_8);}
 }
 @Test void adaptsCanonicalModuleAndPreservesEffectBody()throws Exception{
  String source=fixture("visibility-canonical.fsh"),actual=VisibilityGlowSource.adapt(source);assertNotNull(actual);
  assertTrue(actual.contains("imageLoad(GalliumVisibilityImage,coord)"));
  assertEquals(source.substring(source.indexOf("void main()")),actual.substring(actual.indexOf("void main()")));
  assertEquals(actual,VisibilityGlowSource.adapt(actual));
 }
 @Test void recognizesPreviouslyVerifiedVisibilityModule()throws Exception{
  String source=fixture("visibility-legacy.fsh");assertEquals(source,VisibilityGlowSource.adapt(source));
 }
 @Test void acceptsEngineAndInstanceWrappers()throws Exception{
  String source=InstancedGlowSource.adapt(WorldGlowShader.wrap(fixture("visibility-canonical.fsh"),false),false);
  assertNotNull(VisibilityGlowSource.adapt(source));
 }
 @Test void ignoresCommentsAndFormattingWithoutMergingTokens()throws Exception{
  String source=fixture("visibility-canonical.fsh").replace("bool storedMask()", "/* bool storedMask(){} MaskSampler */ bool  storedMask ( )");
  assertNotNull(VisibilityGlowSource.adapt(source));
  assertNull(VisibilityGlowSource.adapt(source.replace("return storedMask()", "returnstoredMask()")));
 }
 @Test void rejectsChangedAlphaThreshold()throws Exception{
  assertNull(VisibilityGlowSource.adapt(fixture("visibility-canonical.fsh").replace("alpha <= 0.01", "alpha <= 0.02")));
 }
 @Test void rejectsAlphaUseOutsideBinarySamplingModule()throws Exception{
  assertNull(VisibilityGlowSource.adapt(fixture("visibility-canonical.fsh")+"\nfloat alphaValue(ivec2 p){return maskColorFetch(p).a;}"));
 }
 @Test void rejectsRawSamplerAndStorageUseOutsideModule()throws Exception{
  String source=fixture("visibility-canonical.fsh");
  assertNull(VisibilityGlowSource.adapt(source+"\nvec4 rawMask(ivec2 p){return texelFetch(MaskSampler,p,0);}"));
  assertNull(VisibilityGlowSource.adapt(source+"\nfloat storageMode(){return GalliumMaskStorage.z;}"));
 }
 @Test void rejectsMacrosThatChangeSamplingMeaning()throws Exception{
  String source=fixture("visibility-canonical.fsh");
  assertNull(VisibilityGlowSource.adapt("#define clamp customClamp\n"+source));
  assertNull(VisibilityGlowSource.adapt(source+"\n#undef GALLIUM_HAS_MASK_STORAGE\n"));
 }
 @Test void rejectsDuplicateSamplingFunctions()throws Exception{
  assertNull(VisibilityGlowSource.adapt(fixture("visibility-canonical.fsh")+"\nbool storedMask(){return true;}"));
 }
 @Test void recognizesVerifiedDirectVisibilityReconstruction()throws Exception{
  String source=fixture("visibility-optimized.fsh");assertEquals(source,VisibilityGlowSource.adapt(source));
  assertNull(VisibilityGlowSource.adapt(source.replace("QaVisibilityFastEnabled=1","QaVisibilityFastEnabled=0")));
 }
}
