package cn.spectra.gallium.glowoutline.shader;

import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import java.nio.ByteBuffer;
import java.util.*;

/** Camera-relative meshes compared in world space; no GPU readback is needed. */
final class OutlineMotion {
    private record Mesh(float[] positions, Matrix4f local, double x, double y, double z) {}
    private Map<Object,List<Mesh>> previous = new IdentityHashMap<>();
    private Map<Object,List<Mesh>> current = new IdentityHashMap<>();
    private final Set<Object> changed = Collections.newSetFromMap(new IdentityHashMap<>());
    private int vertices;

    void begin() { current.clear(); changed.clear(); vertices=0; }
    void clear() { previous.clear(); begin(); }
    void observe(Object owner, ByteBuffer data, int stride, int offset, int count,
                 Matrix4fc model, Matrix4fc camera, double x, double y, double z) {
        if (count<0 || count>1_000_000-vertices) { changed.add(owner); return; }
        vertices+=count;
        var list=current.computeIfAbsent(owner, ignored->new ArrayList<>());
        var old=previous.get(owner);
        Mesh prior=old!=null && list.size()<old.size()?old.get(list.size()):null;
        var local=new Matrix4f(camera).invert().mul(model);
        float[] positions=new float[count*3];
        boolean same=prior!=null && prior.positions.length==positions.length
                && local.isFinite() && local.equals(prior.local, 0.00001f);
        for(int i=0;i<count;i++)for(int c=0;c<3;c++) {
            float value=data.getFloat(data.position()+i*stride+offset+c*4);
            positions[i*3+c]=value;
            if(same) {
                double delta=c==0?x-prior.x:c==1?y-prior.y:z-prior.z;
                float before=prior.positions[i*3+c];
                double tolerance=Math.max(0.00001,8.0*Math.max(Math.ulp(value),Math.ulp(before)));
                if(!Float.isFinite(value) || Math.abs(value-before+delta)>tolerance)same=false;
            }
        }
        list.add(new Mesh(positions,local,x,y,z));
        if(!same)changed.add(owner);
    }
    boolean changed(Object owner) {
        var a=current.get(owner);var b=previous.get(owner);
        return changed.contains(owner) || a==null || b==null || a.size()!=b.size();
    }
    void finish() { var reuse=previous;previous=current;current=reuse;current.clear(); }

    static Matrix4f reproject(Matrix4fc previous, Matrix4fc current,
                              double dx,double dy,double dz) {
        return new Matrix4f(previous).translate((float)dx,(float)dy,(float)dz)
                .mul(new Matrix4f(current).invert());
    }
}
