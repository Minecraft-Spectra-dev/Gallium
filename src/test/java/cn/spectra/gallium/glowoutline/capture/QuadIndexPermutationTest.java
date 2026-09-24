package cn.spectra.gallium.glowoutline.capture;
import java.nio.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class QuadIndexPermutationTest {
 private static ByteBuffer indices(int bytes,int...quads){
  var b=ByteBuffer.allocate(8+quads.length*6*bytes).order(ByteOrder.nativeOrder());b.position(8);
  for(int q:quads)for(int i:new int[]{0,1,2,2,3,0})if(bytes==2)b.putShort((short)(q*4+i));else b.putInt(q*4+i);
  b.flip().position(8);return b;
 }
 @Test void permitsWholeQuadSortingWithoutChangingBuffer(){
  for(int bytes:new int[]{2,4}){var b=indices(bytes,2,0,1);int p=b.position(),l=b.limit();assertTrue(QuadIndexPermutation.complete(b,bytes,12));assertEquals(p,b.position());assertEquals(l,b.limit());}
 }
 @Test void rejectsMissingOrDuplicatedQuads(){assertFalse(QuadIndexPermutation.complete(indices(2,0,0),2,8));assertFalse(QuadIndexPermutation.complete(indices(2,0),2,8));}
 @Test void rejectsChangedWindingOrTriangle(){var b=indices(4,0);b.putInt(b.position()+4,2);assertFalse(QuadIndexPermutation.complete(b,4,4));}
 @Test void rejectsOutOfRangeAndSplitQuads(){var b=indices(4,0);b.putInt(b.position(),1);assertFalse(QuadIndexPermutation.complete(b,4,4));assertFalse(QuadIndexPermutation.complete(indices(4,2),4,4));}
 @Test void acceptsUnsignedShortIndices(){int count=20000;int[] order=new int[count];for(int i=0;i<count;i++)order[i]=count-i-1;assertTrue(QuadIndexPermutation.complete(indices(4,order),4,count*4));int[] shortOrder=new int[16384];for(int i=0;i<shortOrder.length;i++)shortOrder[i]=shortOrder.length-i-1;assertTrue(QuadIndexPermutation.complete(indices(2,shortOrder),2,65536));}
 @Test void rejectsInvalidShapeAndCapacity(){assertFalse(QuadIndexPermutation.complete(null,2,4));assertFalse(QuadIndexPermutation.complete(indices(2,0),1,4));assertFalse(QuadIndexPermutation.complete(indices(2,0),2,5));assertFalse(QuadIndexPermutation.complete(indices(2,0),2,Integer.MAX_VALUE-3));}
}
