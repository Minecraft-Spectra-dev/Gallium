package cn.spectra.gallium.glowoutline.capture;

import com.mojang.blaze3d.buffers.GpuBufferSlice;
import java.util.IdentityHashMap;
import java.util.Map;
import org.joml.Matrix4f;
import org.jspecify.annotations.Nullable;

/**
 * Maps each projection-matrix UBO {@link GpuBufferSlice} to the last {@link Matrix4f} uploaded
 * into it. Vanilla hands {@code RenderSystem.setProjectionMatrix} a slice (already serialized
 * into a UBO), losing the original matrix we need to apply {@code VertexDownscaling} on the
 * mask render.
 * <p>
 * Per-version mixins call {@link #remember} from inside the projection buffer's writeBuffer/
 * getBuffer paths. Capture sites then call {@link #lookup} on the slice obtained from
 * {@code RenderSystem.getProjectionMatrixBuffer()} to recover the matrix.
 * <p>
 * Indexed by {@code IdentityHashMap} (slice identity) so independent projection buffers
 * (level, hud3d, ...) don't overwrite one another's associations. The Minecraft client only
 * holds a handful of projection buffers; the map never grows past that fixed set.
 */
public final class ProjectionMatrixTracker {

    private static final Map<GpuBufferSlice, Matrix4f> ASSOCIATIONS = new IdentityHashMap<>();

    private ProjectionMatrixTracker() {}

    public static void remember(GpuBufferSlice slice, Matrix4f matrix) {
        // Defensive copy: vanilla often reuses a stack-allocated Matrix4f across calls. Without
        // copying, a later upload would mutate the matrix already mapped to a different slice.
        ASSOCIATIONS.put(slice, new Matrix4f(matrix));
    }

    /**
     * Returns a fresh copy of the matrix associated with {@code slice}, or {@code null} if no
     * mixin has yet observed an upload to that slice. Callers should tolerate {@code null} by
     * skipping the downscale-aware path and falling back to the unmodified slice.
     */
    public static @Nullable Matrix4f lookup(GpuBufferSlice slice) {
        if (slice == null) return null;
        Matrix4f stored = ASSOCIATIONS.get(slice);
        return stored != null ? new Matrix4f(stored) : null;
    }
}
