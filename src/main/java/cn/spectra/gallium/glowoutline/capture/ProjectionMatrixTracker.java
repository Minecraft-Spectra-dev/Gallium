package cn.spectra.gallium.glowoutline.capture;

//#if MC>=1_21_06
import cn.spectra.gallium.glowoutline.shader.GlowResources;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.systems.RenderSystem;
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
 * (level, hud3d, ...) don't overwrite one another's associations. Resource-pack reloads can
 * recreate Iris/vanilla projection buffers, so the map is cleared via {@link GlowResources}
 * to release stale slice references rather than letting them outlive their owners.
 * <p>
 * All entry points assert render-thread because the backing {@link IdentityHashMap} is
 * unsynchronized — a stray worker-thread call would silently corrupt the map.
 */
public final class ProjectionMatrixTracker {

    private static final class Association {
        private final Matrix4f matrix;
        private boolean valid;

        private Association(Matrix4f matrix) {
            this.matrix = new Matrix4f(matrix);
            valid = true;
        }
    }

    private static final Map<GpuBufferSlice, Association> ASSOCIATIONS = new IdentityHashMap<>();

    static {
        GlowResources.register(ProjectionMatrixTracker::clear);
    }

    private ProjectionMatrixTracker() {}

    public static void remember(GpuBufferSlice slice, Matrix4f matrix) {
        RenderSystem.assertOnRenderThread();
        // Defensive copy: vanilla often reuses a stack-allocated Matrix4f across calls. Without
        // copying, a later upload would mutate the matrix already mapped to a different slice.
        Association stored = ASSOCIATIONS.get(slice);
        if (stored == null) {
            ASSOCIATIONS.put(slice, new Association(matrix));
        } else {
            stored.matrix.set(matrix);
            stored.valid = true;
        }
    }

    /** Revoke proof before a fallible upload, retaining CPU storage for the next successful write. */
    public static void forget(GpuBufferSlice slice) {
        RenderSystem.assertOnRenderThread();
        Association stored = ASSOCIATIONS.get(slice);
        if (stored != null) stored.valid = false;
    }

    /**
     * Returns a fresh copy of the matrix associated with {@code slice}, or {@code null} if no
     * valid upload proof exists for that slice. Callers should tolerate {@code null} by
     * skipping the downscale-aware path and falling back to the unmodified slice.
     */
    public static @Nullable Matrix4f lookup(GpuBufferSlice slice) {
        RenderSystem.assertOnRenderThread();
        if (slice == null) return null;
        Association stored = ASSOCIATIONS.get(slice);
        return stored != null && stored.valid ? new Matrix4f(stored.matrix) : null;
    }

    /**
     * Variant that writes the matrix into the caller-provided {@code dest} and returns it.
     * Saves the per-call {@code Matrix4f} allocation when the caller already owns scratch
     * space. Returns {@code null} (and leaves {@code dest} untouched) when no valid association
     * has been recorded for {@code slice}.
     */
    public static @Nullable Matrix4f lookupInto(GpuBufferSlice slice, Matrix4f dest) {
        RenderSystem.assertOnRenderThread();
        if (slice == null) return null;
        Association stored = ASSOCIATIONS.get(slice);
        if (stored == null || !stored.valid) return null;
        return dest.set(stored.matrix);
    }

    /** Drops every cached association. Invoked by {@link GlowResources} on resource reload. */
    public static void clear() {
        RenderSystem.assertOnRenderThread();
        ASSOCIATIONS.clear();
    }
}
//#else
//$$ public final class ProjectionMatrixTracker {
//$$     private ProjectionMatrixTracker() {}
//$$ }
//#endif
