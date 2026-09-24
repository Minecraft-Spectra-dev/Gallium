package cn.spectra.gallium.glowoutline.capture;

/** Incremental placement; a full page is consumed before its storage can be reused. */
public final class MaskAtlasLayout {
    public record Tile(int x, int y, int width, int height) {}
    private final int width, height;
    private int x, y, rowHeight;

    public MaskAtlasLayout(int width, int height) {
        if (width <= 0 || height <= 0) throw new IllegalArgumentException("Invalid atlas dimensions");
        this.width = width; this.height = height;
    }
    public void reset() { x = y = rowHeight = 0; }
    public Tile place(int w, int h) {
        if (w < 0 || h < 0 || w > width || h > height) return null;
        if (w == 0 || h == 0) return new Tile(0, 0, 0, 0);
        int nextX = x, nextY = y, nextRow = rowHeight;
        if ((long) nextX + w > width) { nextX = 0; nextY += nextRow; nextRow = 0; }
        if ((long) nextY + h > height) return null;
        var tile = new Tile(nextX, nextY, w, h);
        x = nextX + w; y = nextY; rowHeight = Math.max(nextRow, h);
        return tile;
    }
}
