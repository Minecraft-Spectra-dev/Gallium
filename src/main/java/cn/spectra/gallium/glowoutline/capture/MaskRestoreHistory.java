package cn.spectra.gallium.glowoutline.capture;

/** Tracks the only region modified since a mask was initialized from an immutable depth base. */
public final class MaskRestoreHistory {
    public record Key(Object target, int color, int depth, int width, int height,
                      long epoch, Object source, int sourceDepth, long sourceGeneration) {}
    public record Region(int x, int y, int width, int height) {}
    private Key key;
    private Region written;
    private boolean complete;

    /** Null requests a full initialization. The caller still needs its normal mask lease. */
    public Region begin(Key next) {
        Region restore = complete && same(key, next) ? written : null;
        key = next;
        complete = false;
        written = null;
        return restore;
    }

    public void finish(ProjectedMaskBounds bounds) {
        if (key == null || !bounds.valid()) { complete = false; return; }
        int x0 = (int) Math.max(0, Math.min(key.width(), bounds.minX()));
        int y0 = (int) Math.max(0, Math.min(key.height(), bounds.minY()));
        int x1 = (int) Math.max(x0, Math.min(key.width(), bounds.maxX()));
        int y1 = (int) Math.max(y0, Math.min(key.height(), bounds.maxY()));
        written = new Region(x0, y0, x1 - x0, y1 - y0);
        complete = true;
    }

    public void invalidate() { key = null; written = null; complete = false; }

    private static boolean same(Key a, Key b) {
        return a != null && b != null && a.target() == b.target() && a.source() == b.source()
                && a.color() == b.color() && a.depth() == b.depth()
                && a.width() == b.width() && a.height() == b.height() && a.epoch() == b.epoch()
                && a.sourceDepth() == b.sourceDepth() && a.sourceGeneration() == b.sourceGeneration();
    }
}
