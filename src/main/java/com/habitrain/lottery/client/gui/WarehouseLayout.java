package com.habitrain.lottery.client.gui;

/** Shared geometry for drawing, clipping and hit testing, in scaled GUI pixels. */
public record WarehouseLayout(int x, int width, int top, int bottom, int columns, int tileWidth, int tileHeight) {
    public static final int GAP = 6;
    public static WarehouseLayout of(int width, int height) {
        int w = Math.max(80, width < 560 ? width - 24 : Math.min(1000, Math.round(width * .8f)));
        int cols = Math.max(1, Math.min(8, (w + GAP) / (width >= 560 ? 70 : 94)));
        int tw = (w - (cols - 1) * GAP - 6) / cols;
        int bottom = Math.max(130, height - 28);
        return new WarehouseLayout((width - w) / 2, w, 94, bottom, cols, tw,
                Math.min(bottom - 94, Math.max(108, Math.min(178, Math.round(tw * 1.48f)))));
    }
    public int viewportHeight() { return bottom - top; }
    public int rowHeight() { return tileHeight + GAP; }
    public int maxScroll(int count) {
        int rows = (count + columns - 1) / columns;
        return Math.max(0, rows * rowHeight() - GAP - viewportHeight());
    }
    public int tileX(int index) { return x + index % columns * (tileWidth + GAP); }
    public int tileY(int index, double scroll) { return top + index / columns * rowHeight() - (int) scroll; }
    public boolean contains(double mx, double my) {
        return mx >= x && mx < x + width && my >= top && my < bottom;
    }
}
