package cn.spectra.gallium.glowoutline.capture;
//#if MC==1_21_08 || MC==1_26_01
import org.junit.jupiter.api.Test;
import org.joml.*;
import static org.junit.jupiter.api.Assertions.*;
class NativeTransformSlotTest {
 @Test void snapshotsMutableInputsAndRequiresEveryComponent() {
  var slot=new NativeVertexUploadBatch.TransformSlot();var model=new Matrix4f();var texture=new Matrix4f();var color=new Vector4f(1);var offset=new Vector3f();
  slot.set(model,color,offset,texture,1f);
  assertTrue(slot.matches(new Matrix4f(),new Vector4f(1),new Vector3f(),new Matrix4f(),1f));
  model.translate(1,0,0);assertFalse(slot.matches(model,color,offset,texture,1f));model.identity();
  color.x=.5f;assertFalse(slot.matches(model,color,offset,texture,1f));color.x=1;
  offset.z=2;assertFalse(slot.matches(model,color,offset,texture,1f));offset.zero();
  texture.rotateZ(.5f);assertFalse(slot.matches(model,color,offset,texture,1f));texture.identity();
  assertFalse(slot.matches(model,color,offset,texture,2f));assertTrue(slot.matches(model,color,offset,texture,1f));
 }
 @Test void retainsTheLineWidthBitPattern() {
  var slot=new NativeVertexUploadBatch.TransformSlot();var model=new Matrix4f();var color=new Vector4f(1);var offset=new Vector3f();
  slot.set(model,color,offset,model,-0.0f);assertFalse(slot.matches(model,color,offset,model,0.0f));assertTrue(slot.matches(model,color,offset,model,-0.0f));
 }
}
//#else
//$$ class NativeTransformSlotTest {}
//#endif
