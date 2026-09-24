package cn.spectra.gallium.glowoutline.capture;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.BitSet;
/** Proves that sorting changed only the order of whole native quads. */
public final class QuadIndexPermutation {
 private static final int[] TRIANGLES={0,1,2,2,3,0};
 private QuadIndexPermutation(){}
 public static boolean complete(ByteBuffer source,int bytes,int vertices){
  if(source==null || bytes!=2 && bytes!=4 || vertices<=0 || vertices%4!=0 || vertices>4*1024*1024/36
      || source.remaining()!=(long)(vertices/4)*6*bytes)return false;
  var data=source.duplicate().order(ByteOrder.nativeOrder());var seen=new BitSet(vertices/4);int offset=data.position();
  for(int q=0;q<vertices/4;q++){
   int first=index(data,offset,bytes);
   if(first<0 || first%4!=0 || first>=vertices || seen.get(first/4))return false;
   seen.set(first/4);
   for(int i=1;i<6;i++)if(index(data,offset+i*bytes,bytes)!=first+TRIANGLES[i])return false;
   offset+=6*bytes;
  }
  return true;
 }
 private static int index(ByteBuffer source,int offset,int bytes){return bytes==2?Short.toUnsignedInt(source.getShort(offset)):source.getInt(offset);}
}
