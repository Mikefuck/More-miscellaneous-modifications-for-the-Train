package com.habitrain.lottery.client.gui.config;

/** Pure geometry for the scrollable role-card editor inside Player Assets. */
public record PlayerCardScrollLayout(
        int viewportTop,
        int viewportBottom,
        int width,
        boolean stacked,
        int rowHeight,
        int labelOffset,
        int controlOffset,
        int rowCount,
        int contentHeight
) {
    private static final int STATUS_AREA_HEIGHT = 14;

    public static PlayerCardScrollLayout calculate(
            int requestedTop,
            int requestedBottom,
            int requestedWidth,
            int requestedRowCount
    ) {
        int bottom = Math.max(0, requestedBottom);
        int top = Math.min(bottom, Math.max(0, requestedTop));
        int width = Math.max(0, requestedWidth);
        int rowCount = Math.max(0, requestedRowCount);
        boolean stacked = width < 300;
        int rowHeight = stacked ? 44 : 28;
        int labelOffset = stacked ? 2 : 6;
        int controlOffset = stacked ? 14 : 0;
        int contentHeight = rowCount * rowHeight + STATUS_AREA_HEIGHT;
        return new PlayerCardScrollLayout(
                top, bottom, width, stacked, rowHeight, labelOffset,
                controlOffset, rowCount, contentHeight);
    }

    public int viewportHeight() {
        return Math.max(0, viewportBottom - viewportTop);
    }

    public int maxScroll() {
        return Math.max(0, contentHeight - viewportHeight());
    }

    public int clampScroll(int scroll) {
        return Math.max(0, Math.min(maxScroll(), scroll));
    }

    public int scrollByRows(int currentScroll, int rows) {
        long target = (long) clampScroll(currentScroll) + (long) rows * rowHeight;
        return (int) Math.max(0L, Math.min(maxScroll(), target));
    }

    public int labelY(int row, int scroll) {
        return viewportTop + Math.max(0, row) * rowHeight + labelOffset - clampScroll(scroll);
    }

    public int controlY(int row, int scroll) {
        return viewportTop + Math.max(0, row) * rowHeight + controlOffset - clampScroll(scroll);
    }

    public int statusY(int scroll) {
        return viewportTop + rowCount * rowHeight + 2 - clampScroll(scroll);
    }

    public boolean isWidgetFullyVisible(int y, int height) {
        return height > 0 && viewportHeight() >= height
                && y >= viewportTop && y + height <= viewportBottom;
    }

    public boolean isTextVisible(int y, int height) {
        return height > 0 && y < viewportBottom && y + height > viewportTop;
    }
}
