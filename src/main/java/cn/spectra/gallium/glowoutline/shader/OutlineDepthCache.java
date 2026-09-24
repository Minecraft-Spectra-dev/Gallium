package cn.spectra.gallium.glowoutline.shader;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;

/** Reuses a depth bound only when every position and both raster matrices match exactly. */
final class OutlineDepthCache {
    private static final int MAX_VERTICES = 262_144, MAX_ENTRIES = 512;
    private static final class Entry {
        final int[] positions;
        final Matrix4f model = new Matrix4f(), projection = new Matrix4f();
        float minimum;
        Entry(int count) { positions = new int[count * 3]; }
        void update(ByteBuffer data, int stride, int offset, int count,
                    Matrix4fc model, Matrix4fc projection, float minimum) {
            for (int i = 0; i < count; i++) for (int c = 0; c < 3; c++)
                positions[i * 3 + c] = data.getInt(data.position() + i * stride + offset + c * 4);
            this.model.set(model); this.projection.set(projection); this.minimum = minimum;
        }
        boolean matches(ByteBuffer data, int stride, int offset, int count,
                        Matrix4fc model, Matrix4fc projection) {
            if (positions.length != count * 3 || !same(this.model, model)
                    || !same(this.projection, projection)) return false;
            for (int i = 0; i < count; i++) for (int c = 0; c < 3; c++)
                if (positions[i * 3 + c] != data.getInt(data.position() + i * stride + offset + c * 4))
                    return false;
            return true;
        }
    }
    private Map<Object, List<Entry>> previous = new IdentityHashMap<>(), current = new IdentityHashMap<>();
    private int vertices, entries;
    private boolean refused, viewKnown, allowCache = true;
    private final Matrix4f lastView = new Matrix4f(), lastProjection = new Matrix4f();
    private double cameraX, cameraY, cameraZ;

    /** A moving camera changes every raster bound; avoid rebuilding the whole cache then. */
    void setView(Matrix4fc view, Matrix4fc projection, double x, double y, double z) {
        if (view == null || projection == null || !view.isFinite() || !projection.isFinite()
                || !Double.isFinite(x + y + z)) {
            viewKnown = false; allowCache = false; previous.clear(); current.clear(); return;
        }
        allowCache = viewKnown && x == cameraX && y == cameraY && z == cameraZ
                && same(view, lastView) && same(projection, lastProjection);
        lastView.set(view); lastProjection.set(projection); cameraX=x; cameraY=y; cameraZ=z; viewKnown=true;
        if (!allowCache) { previous.clear(); current.clear(); }
    }

    void begin() { current.clear(); vertices = entries = 0; refused = false; allowCache = true; }
    void clear() { previous.clear(); viewKnown = false; begin(); }
    void finish() { var reuse = previous; previous = current; current = reuse; current.clear(); }

    float minimum(Object owner, ByteBuffer data, int stride, int count, int offset,
                  Matrix4fc model, Matrix4fc projection) {
        if (!allowCache || refused || data == null || count <= 0 || stride < 12 || offset < 0 || offset > stride - 12
                || (long) stride * count > data.remaining() || !model.isFinite() || !projection.isFinite())
            return OutlineDepthBounds.minimum(data, stride, count, offset, model, projection);
        if (count > MAX_VERTICES - vertices || entries >= MAX_ENTRIES) {
            refused = true; previous.clear(); current.clear();
            return OutlineDepthBounds.minimum(data, stride, count, offset, model, projection);
        }
        vertices += count; entries++;
        var list = current.computeIfAbsent(owner, ignored -> new ArrayList<>());
        var old = previous.get(owner);
        Entry prior = old != null && list.size() < old.size() ? old.get(list.size()) : null;
        if (prior != null && prior.matches(data, stride, offset, count, model, projection)) {
            list.add(prior);
            return prior.minimum;
        }
        float lower = OutlineDepthBounds.minimum(data, stride, count, offset, model, projection);
        if (!Float.isFinite(lower)) { list.add(null); return lower; }
        // Each ordinal is consumed once. Reuse its storage on motion rather than
        // allocating another position array and two matrices every moving frame.
        var entry = prior != null && prior.positions.length == count * 3 ? prior : new Entry(count);
        entry.update(data, stride, offset, count, model, projection, lower);
        list.add(entry);
        return lower;
    }

    private static boolean same(Matrix4fc a, Matrix4fc b) {
        for (int c = 0; c < 4; c++) for (int r = 0; r < 4; r++)
            if (Float.floatToRawIntBits(a.get(c, r)) != Float.floatToRawIntBits(b.get(c, r))) return false;
        return true;
    }
}
