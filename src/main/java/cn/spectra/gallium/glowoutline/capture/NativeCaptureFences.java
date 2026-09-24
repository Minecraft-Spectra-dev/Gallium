package cn.spectra.gallium.glowoutline.capture;

//#if MC>=1_21_06 && MC<1_26_02
import cn.spectra.gallium.glowoutline.GlowOutlineConfig;
import cn.spectra.gallium.glowoutline.IrisCompat;
import com.mojang.blaze3d.buffers.GpuFence;
import com.mojang.blaze3d.systems.CommandEncoder;

/** Shares completion of inspected ring-buffer reads without advancing any completion point. */
public final class NativeCaptureFences implements AutoCloseable {
    private static NativeCaptureFences current, frame;
    private final NativeCaptureFences parent, parentFrame;
    private final OpenGlMaskOrdering.Stamp ordering;
    private final boolean frameScope;
    private Group pending;
    private boolean closed;

    public NativeCaptureFences() { this(OpenGlMaskOrdering.observe(), false); }

    private NativeCaptureFences(OpenGlMaskOrdering.Stamp ordering, boolean frameScope) {
        this.ordering = ordering; this.frameScope = frameScope;
        parent = current; parentFrame = frame;
        if (frameScope) frame = this;
        else current = this;
    }

    /** Keeps every ring's completion at or after its last draw, including native frame cleanup. */
    public static NativeCaptureFences beginFrame() {
        if (!GlowOutlineConfig.isEnabled() || IrisCompat.isShaderActive() || IrisCompat.isActiveSrRuntime()) return null;
        var ordering = OpenGlMaskOrdering.observe();
        return ordering == null ? null : new NativeCaptureFences(ordering, true);
    }

    /** Used only by the inspected MappableRingBuffer, which never unwraps a native fence. */
    public static GpuFence createForRing(CommandEncoder encoder) {
        var scope = frame;
        var shared = scope == null ? null : scope.reference(encoder);
        return shared == null ? create(encoder) : shared;
    }

    /** Only the inspected MappableRingBuffer uses these references, through the GpuFence API. */
    public static GpuFence create(CommandEncoder encoder) {
        var scope = current;
        return scope == null ? null : scope.reference(encoder);
    }

    private GpuFence reference(CommandEncoder encoder) {
        if (closed || ordering == null || !ordering.current()) return null;
        if (pending == null || pending.sealed) pending = new Group(encoder);
        return new Reference(pending);
    }

    @Override public void close() {
        if (closed) return;
        closed = true;
        try {
            // Every buffer's last draw and rotate has completed before this outer scope closes.
            if (pending != null) pending.seal();
        } finally {
            if (frameScope) { if (frame == this) frame = parentFrame; }
            else if (current == this) current = parent;
        }
    }

    private static final class Group {
        final CommandEncoder encoder;
        GpuFence nativeFence;
        boolean sealed, completed;
        int references;

        Group(CommandEncoder encoder) { this.encoder = encoder; }

        void seal() {
            if (sealed) return;
            if (references != 0) nativeFence = encoder.createFence();
            sealed = true;
        }

        void release() {
            if (--references < 0) throw new IllegalStateException("Glow fence released twice");
            if (references == 0 && nativeFence != null) {
                nativeFence.close(); nativeFence = null;
            }
        }
    }

    private static final class Reference implements GpuFence {
        final Group group;
        boolean closed;

        Reference(Group group) { this.group = group; group.references++; }

        @Override public boolean awaitCompletion(long timeout) {
            if (closed) throw new IllegalStateException("Awaiting a closed glow fence");
            // A ring can wrap again inside a large sequence. Seal its current group now;
            // subsequent rotates will form a new group, so no buffer can overwrite live data.
            group.seal();
            if (!group.completed) group.completed = group.nativeFence.awaitCompletion(timeout);
            return group.completed;
        }

        @Override public void close() {
            if (!closed) { closed = true; group.release(); }
        }
    }
}
//#else
//$$ public final class NativeCaptureFences { private NativeCaptureFences() {} }
//#endif
