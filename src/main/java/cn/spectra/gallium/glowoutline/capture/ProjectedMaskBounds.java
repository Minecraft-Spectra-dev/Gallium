package cn.spectra.gallium.glowoutline.capture;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.lang.ref.Reference;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.lwjgl.system.MemoryUtil;

/** Conservative physical-pixel bounds of triangle geometry rendered with P * MV * Position. */
public final class ProjectedMaskBounds {
    private int width, height;
    private boolean failed, populated;
    private double left, bottom, right, top;
    private boolean firstInclude;
    private CachedMesh firstMesh;
    private CachedBox projectedBox;
    // Covers separate/fused matrix products, dot products and the final perspective divide.
    // Ill-conditioned transforms expand the bound or fall back, never shrink it.
    private static final double ROUNDING = 1.0 / 65536.0;

    public void begin(int width, int height) {
        this.width = width;
        this.height = height;
        failed = width <= 0 || height <= 0;
        populated = false;
        firstInclude = true;
        left = bottom = Double.POSITIVE_INFINITY;
        right = top = Double.NEGATIVE_INFINITY;
    }

    public void invalidate() { failed = true; }
    public boolean accepting() { return !failed; }
    public boolean valid() { return !failed && populated; }
    public float minX() { return valid() ? (float) left : 0; }
    public float minY() { return valid() ? (float) bottom : 0; }
    public float maxX() { return valid() ? (float) right : 0; }
    public float maxY() { return valid() ? (float) top : 0; }

    /** Unions an already projected bound on the same physical grid without retaining its owner. */
    public void include(ProjectedMaskBounds other) {
        if (failed) return;
        if (other == null || other.failed || width != other.width || height != other.height) {
            invalidate();
            return;
        }
        if (!other.populated) return;
        firstInclude = false;
        left = Math.min(left, other.left); bottom = Math.min(bottom, other.bottom);
        right = Math.max(right, other.right); top = Math.max(top, other.top);
        populated = true;
    }

    /** Bounds an ephemeral source range without retaining vertex bytes for a later cache hit. */
    public void includeTransient(ByteBuffer vertices, int stride, int count, int positionOffset,
                                 Matrix4fc modelView, Matrix4fc projection) {
        firstInclude = false;
        include(vertices, stride, count, positionOffset, modelView, projection);
    }

    public void include(ByteBuffer vertices, int stride, int count, int positionOffset,
                        Matrix4fc modelView, Matrix4fc projection) {
        if (failed || count == 0) return;
        if (vertices == null || count < 0 || stride < 12 || positionOffset < 0 || positionOffset > stride - 12
                || (long) stride * count > vertices.remaining()
                || !modelView.isFinite() || !projection.isFinite()) {
            invalidate();
            return;
        }
        boolean first = firstInclude;
        firstInclude = false;
        int length = stride * count; // The validated range fits in this ByteBuffer.
        if (first && firstMesh != null && firstMesh.matches(vertices, stride, count, positionOffset,
                width, height, modelView, projection)) {
            left = firstMesh.left; bottom = firstMesh.bottom; right = firstMesh.right; top = firstMesh.top;
            populated = true;
            return;
        }
        // The complete interleaved range is checked above. Direct native-order meshes
        // can then avoid repeating ByteBuffer bounds and byte-order checks per component.
        long address = vertices.isDirect() && vertices.order() == ByteOrder.nativeOrder()
                ? MemoryUtil.memAddress(vertices) - vertices.position() : 0;
        try {
            includeValidated(vertices, address, stride, count, positionOffset, modelView, projection);
        } finally {
            Reference.reachabilityFence(vertices);
        }
        if (first && !failed && length <= 65536) {
            if (firstMesh == null) firstMesh = new CachedMesh();
            firstMesh.remember(vertices, stride, count, positionOffset, width, height, modelView, projection,
                    left, bottom, right, top);
        }
    }

    /** Only the first mesh starts from an empty bound; later layers retain their normal union
     * and large-box refinement. Store owned bytes, never a view into a freed native mesh. */
    private static final class CachedMesh {
        private final Matrix4f modelView = new Matrix4f(), projection = new Matrix4f();
        private ByteBuffer bytes;
        private ByteOrder order;
        private int stride, count, positionOffset, width, height;
        private double left, bottom, right, top;

        boolean matches(ByteBuffer vertices, int stride, int count, int positionOffset, int width, int height,
                        Matrix4fc modelView, Matrix4fc projection) {
            if (this.stride != stride || this.count != count || this.positionOffset != positionOffset
                    || this.width != width || this.height != height || order != vertices.order()
                    || !this.modelView.equals(modelView) || !this.projection.equals(projection)) return false;
            int difference = vertices.mismatch(bytes);
            // Trailing source bytes are outside the declared vertex range and are not read.
            return difference < 0 || difference >= bytes.remaining();
        }

        void remember(ByteBuffer vertices, int stride, int count, int positionOffset, int width, int height,
                      Matrix4fc modelView, Matrix4fc projection, double left, double bottom, double right, double top) {
            int length = stride * count;
            if (bytes == null || bytes.capacity() < length) bytes = ByteBuffer.allocate(length);
            bytes.clear().limit(length);
            bytes.put(0, vertices, vertices.position(), length);
            this.stride = stride; this.count = count; this.positionOffset = positionOffset;
            this.width = width; this.height = height; order = vertices.order();
            this.modelView.set(modelView); this.projection.set(projection);
            this.left = left; this.bottom = bottom; this.right = right; this.top = top;
        }
    }

    /** Stores only the eight-corner projection, never a refined result or vertex storage. */
    private static final class CachedBox {
        private final Matrix4f modelView=new Matrix4f(),projection=new Matrix4f();
        private float minX,minY,minZ,maxX,maxY,maxZ;
        private int width,height;
        private double left,bottom,right,top;
        private boolean populated,failed;
        private static boolean same(float a,float b){return Float.floatToRawIntBits(a)==Float.floatToRawIntBits(b);}
        boolean matches(float minX,float minY,float minZ,float maxX,float maxY,float maxZ,
                        int width,int height,Matrix4fc modelView,Matrix4fc projection) {
            return populated && this.width==width && this.height==height
                    && same(this.minX,minX) && same(this.minY,minY) && same(this.minZ,minZ)
                    && same(this.maxX,maxX) && same(this.maxY,maxY) && same(this.maxZ,maxZ)
                    && this.modelView.equals(modelView) && this.projection.equals(projection);
        }
        void remember(float minX,float minY,float minZ,float maxX,float maxY,float maxZ,
                      int width,int height,Matrix4fc modelView,Matrix4fc projection,
                      boolean failed,double left,double bottom,double right,double top) {
            this.minX=minX;this.minY=minY;this.minZ=minZ;this.maxX=maxX;this.maxY=maxY;this.maxZ=maxZ;
            this.width=width;this.height=height;this.modelView.set(modelView);this.projection.set(projection);
            this.failed=failed;this.left=left;this.bottom=bottom;this.right=right;this.top=top;populated=true;
        }
    }

    private void includeValidated(ByteBuffer vertices, long address, int stride, int count, int positionOffset,
                                  Matrix4fc modelView, Matrix4fc projection) {
        float minX = Float.POSITIVE_INFINITY, minY = minX, minZ = minX;
        float maxX = Float.NEGATIVE_INFINITY, maxY = maxX, maxZ = maxX;
        int base = vertices.position() + positionOffset;
        for (int i = 0; i < count; i++, base += stride) {
            float x = component(vertices, address, base), y = component(vertices, address, base + 4),
                    z = component(vertices, address, base + 8);
            if (!Float.isFinite(x) || !Float.isFinite(y) || !Float.isFinite(z)) { invalidate(); return; }
            minX = Math.min(minX, x); maxX = Math.max(maxX, x);
            minY = Math.min(minY, y); maxY = Math.max(maxY, y);
            minZ = Math.min(minZ, z); maxZ = Math.max(maxZ, z);
        }
        double oldLeft = left, oldBottom = bottom, oldRight = right, oldTop = top;
        if (!projectBox(minX,minY,minZ,maxX,maxY,maxZ,modelView,projection)) {
            for (int i=0;i<8 && !failed;i++) {
                corner((i & 1)==0?minX:maxX,(i & 2)==0?minY:maxY,
                        (i & 4)==0?minZ:maxZ,modelView,projection);
            }
        }
        // A rotated near-camera mesh can have an enormous box or a box corner behind
        // the camera even when all its vertices are in front. Refine just these large
        // cases with the actual vertices. Positive-w triangles stay within their
        // projected vertex hull; an actual near-plane crossing still falls back.
        double clippedWidth = Math.max(0, Math.min(width, right) - Math.max(0, left));
        double clippedHeight = Math.max(0, Math.min(height, top) - Math.max(0, bottom));
        if (failed || clippedWidth * clippedHeight > (double) width * height / 8) {
            failed = false;
            left = oldLeft; bottom = oldBottom; right = oldRight; top = oldTop;
            base = vertices.position() + positionOffset;
            for (int i = 0; i < count && !failed; i++, base += stride) {
                corner(component(vertices, address, base), component(vertices, address, base + 4),
                        component(vertices, address, base + 8),
                        modelView, projection);
            }
        }
        if (!failed) populated = true;
    }

    private boolean projectBox(float minX,float minY,float minZ,float maxX,float maxY,float maxZ,
                               Matrix4fc modelView,Matrix4fc projection) {
        // Unknown matrix implementations retain their original getter/evaluation behavior.
        if ((modelView.getClass()!=Matrix4f.class && modelView.getClass()!=org.joml.Matrix4fStack.class)
                || projection.getClass()!=Matrix4f.class) return false;
        double oldLeft=left,oldBottom=bottom,oldRight=right,oldTop=top;
        if (projectedBox==null) projectedBox=new CachedBox();
        if (!projectedBox.matches(minX,minY,minZ,maxX,maxY,maxZ,width,height,modelView,projection)) {
            left=bottom=Double.POSITIVE_INFINITY;right=top=Double.NEGATIVE_INFINITY;
            for (int i=0;i<8 && !failed;i++) {
                corner((i & 1)==0?minX:maxX,(i & 2)==0?minY:maxY,
                        (i & 4)==0?minZ:maxZ,modelView,projection);
            }
            projectedBox.remember(minX,minY,minZ,maxX,maxY,maxZ,width,height,modelView,projection,
                    failed,left,bottom,right,top);
        }
        failed=projectedBox.failed;
        left=Math.min(oldLeft,projectedBox.left);bottom=Math.min(oldBottom,projectedBox.bottom);
        right=Math.max(oldRight,projectedBox.right);top=Math.max(oldTop,projectedBox.top);
        return true;
    }

    private static float component(ByteBuffer vertices, long address, int offset) {
        return address == 0 ? vertices.getFloat(offset) : MemoryUtil.memGetFloat(address + offset);
    }

    private void corner(double x, double y, double z, Matrix4fc m, Matrix4fc p) {
        double mx = m.m00()*x + m.m10()*y + m.m20()*z + m.m30();
        double my = m.m01()*x + m.m11()*y + m.m21()*z + m.m31();
        double mz = m.m02()*x + m.m12()*y + m.m22()*z + m.m32();
        double mw = m.m03()*x + m.m13()*y + m.m23()*z + m.m33();
        double ax = Math.abs(m.m00()*x) + Math.abs(m.m10()*y) + Math.abs(m.m20()*z) + Math.abs(m.m30());
        double ay = Math.abs(m.m01()*x) + Math.abs(m.m11()*y) + Math.abs(m.m21()*z) + Math.abs(m.m31());
        double az = Math.abs(m.m02()*x) + Math.abs(m.m12()*y) + Math.abs(m.m22()*z) + Math.abs(m.m32());
        double aw = Math.abs(m.m03()*x) + Math.abs(m.m13()*y) + Math.abs(m.m23()*z) + Math.abs(m.m33());
        double cx = p.m00()*mx + p.m10()*my + p.m20()*mz + p.m30()*mw;
        double cy = p.m01()*mx + p.m11()*my + p.m21()*mz + p.m31()*mw;
        double cw = p.m03()*mx + p.m13()*my + p.m23()*mz + p.m33()*mw;
        double ex = ROUNDING * (Math.abs(p.m00())*ax + Math.abs(p.m10())*ay
                + Math.abs(p.m20())*az + Math.abs(p.m30())*aw) + 1e-12;
        double ey = ROUNDING * (Math.abs(p.m01())*ax + Math.abs(p.m11())*ay
                + Math.abs(p.m21())*az + Math.abs(p.m31())*aw) + 1e-12;
        double ew = ROUNDING * (Math.abs(p.m03())*ax + Math.abs(p.m13())*ay
                + Math.abs(p.m23())*az + Math.abs(p.m33())*aw) + 1e-12;
        if (!Double.isFinite(cw + ew) || cw - ew <= 0) { invalidate(); return; }
        double x0 = (cx - ex) / (cx - ex < 0 ? cw - ew : cw + ew);
        double x1 = (cx + ex) / (cx + ex > 0 ? cw - ew : cw + ew);
        double y0 = (cy - ey) / (cy - ey < 0 ? cw - ew : cw + ew);
        double y1 = (cy + ey) / (cy + ey > 0 ? cw - ew : cw + ew);
        double l = Math.floor((x0 * .5 + .5) * width) - 1;
        double r = Math.ceil((x1 * .5 + .5) * width) + 1;
        double b = Math.floor((y0 * .5 + .5) * height) - 1;
        double t = Math.ceil((y1 * .5 + .5) * height) + 1;
        // Below 2^23 every integer edge remains exact when uploaded as a float uniform.
        if (!Double.isFinite(l + r + b + t) || Math.max(Math.max(Math.abs(l), Math.abs(r)),
                Math.max(Math.abs(b), Math.abs(t))) >= 8388608) { invalidate(); return; }
        left = Math.min(left, l); right = Math.max(right, r);
        bottom = Math.min(bottom, b); top = Math.max(top, t);
    }
}
