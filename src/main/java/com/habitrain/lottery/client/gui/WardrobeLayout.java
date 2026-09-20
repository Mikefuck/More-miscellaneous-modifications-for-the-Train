package com.habitrain.lottery.client.gui;

/** GUI-scaled coordinates; the smallest standard Minecraft viewport is 320 x 240. */
record WardrobeLayout(int x, int y, int width, int height, int gridX, int gridY,
                      int gridWidth, int contentHeight, int detailX, int detailWidth,
                      int columns, int rows, int cardWidth, int cardHeight) {
    static WardrobeLayout of(int width, int height) {
        int w = Math.min(900, width - 16), h = Math.min(550, height - 16);
        int x = (width - w) / 2, y = (height - h) / 2;
        int inner = w - 20, detail = Math.max(112, Math.min(250, inner / 3));
        int grid = inner - detail - 10, content = h - 130;
        int cols = Math.max(1, grid / 90), rows = Math.max(1, content / 78);
        return new WardrobeLayout(x, y, w, h, x + 10, y + 86, grid, content,
                x + 20 + grid, detail, cols, rows,
                (grid - (cols - 1) * 6) / cols, (content - (rows - 1) * 6) / rows);
    }
    int pageSize() { return columns * rows; }
    int bottom() { return y + height; }
}
