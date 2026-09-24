package cn.spectra.gallium.glowoutline.shader;

import java.nio.ByteBuffer;
import org.joml.Matrix4fc;

/** A lower window-depth bound for the certified native P * MV * Position transform. */
public final class OutlineDepthBounds {
    private static final double ROUNDING = 1.0 / 65536.0;

    private OutlineDepthBounds() {}

    public static float minimum(ByteBuffer vertices, int stride, int count, int offset,
                                Matrix4fc modelView, Matrix4fc projection) {
        if (vertices == null || count <= 0 || stride < 12 || offset < 0 || offset > stride - 12
                || (long) stride * count > vertices.remaining()
                || !modelView.isFinite() || !projection.isFinite()) return Float.NaN;
        double result = 1.0;
        double[] position = new double[4], view = new double[4], magnitude = new double[4];
        position[3] = 1.0;
        for (int i = 0; i < count; i++) {
            int start = vertices.position() + i * stride + offset;
            double x = vertices.getFloat(start), y = vertices.getFloat(start + 4), z = vertices.getFloat(start + 8);
            position[0] = x; position[1] = y; position[2] = z;
            for (int row = 0; row < 4; row++) {
                view[row] = magnitude[row] = 0;
                for (int column = 0; column < 4; column++) {
                    double term = modelView.get(column, row) * position[column];
                    view[row] += term;
                    magnitude[row] += Math.abs(term);
                }
            }
            double clipZ = 0, clipW = 0, errorZ = 1e-12, errorW = 1e-12;
            for (int column = 0; column < 4; column++) {
                clipZ += projection.get(column, 2) * view[column];
                clipW += projection.get(column, 3) * view[column];
                errorZ += ROUNDING * Math.abs(projection.get(column, 2)) * magnitude[column];
                errorW += ROUNDING * Math.abs(projection.get(column, 3)) * magnitude[column];
            }
            // The same arithmetic envelope as ProjectedMaskBounds covers separate or fused
            // matrix products. A primitive crossing the eye plane cannot establish this bound.
            if (!Double.isFinite(clipZ + clipW + errorZ + errorW) || clipW - errorW <= 0) return Float.NaN;
            double lower = clipZ - errorZ;
            double ndc = lower / (lower < 0 ? clipW - errorW : clipW + errorW);
            result = Math.min(result, .5 + .5 * ndc);
        }
        // Keep conversion to float conservative as well.
        return Math.nextDown((float) result);
    }
}
