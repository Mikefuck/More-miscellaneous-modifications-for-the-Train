package com.habitrain.lottery.client.gui;

/**
 * 仓库格栅与顶部 chrome 的几何。全部尺寸以参考视频 1920×1080 的实测坐标为基准，
 * 按 {@code scale = 窗口宽度 / 1920} 等比换算到当前 GUI 空间：
 *
 * <pre>
 *   列距 192 · 行距 190 · 首个瓦片左边缘 x=93 · 图标顶 y=205
 *   图标井 132×92 · 说明条 20 · 物品名 22（行距 - 井 - 说明条 = 参考视频的大留白）
 *   顶部 chrome：主导航 y 20（高 30）→ 次级标签 y 66（高 20）→ 三级筛选 y 113（高 27）
 *   排序下拉框 210×40（field #1A1F1E / 1px #3A4442 / r=4）
 * </pre>
 *
 * <p>类型尺寸有绝对可读下限：窗口过窄时先触底（图标井 / 说明条 / 行距不再缩小），
 * 由可用宽度反推列数，因此窄窗口会自然减少列数而不是把瓦片压成不可读的一团。
 * 列数上限固定为参考视频的 9 列。</p>
 *
 * <p>纯数学、无 Minecraft 依赖，{@code WarehouseLayoutTest} 直接校验不变量。</p>
 */
public record WarehouseLayout(int x, int width, int top, int bottom, int columns, int tileWidth, int tileHeight,
                              int pitchX, int pitchY, int iconHeight, int captionHeight, int nameHeight,
                              int chromeBottom, int statusHeight, int navY, int navHeight,
                              int tabY, int tabHeight, int filterY, int filterHeight,
                              int sortFieldX, int sortFieldY, int sortFieldWidth, int sortFieldHeight,
                              int margin, float scale) {

    /** 参考视频的基准宽度。 */
    public static final float REFERENCE_WIDTH = 1920F;
    /** 参考视频基准下的列数（9 列 × 4.5 行，最后一行被下边缘裁掉）。 */
    public static final int REFERENCE_COLUMNS = 9;

    private static final float REF_MARGIN = 93F, REF_PITCH_X = 192F, REF_GRID_TOP = 205F;
    private static final float REF_TILE_W = 132F, REF_WELL_H = 92F, REF_CAPTION_H = 20F, REF_NAME_H = 22F;
    private static final float REF_PITCH_Y = 190F;
    private static final float REF_NAV_Y = 20F, REF_NAV_H = 30F, REF_TAB_Y = 66F, REF_TAB_H = 20F;
    private static final float REF_FILTER_Y = 113F, REF_FILTER_H = 27F, REF_SORT_W = 210F, REF_SORT_H = 36F;
    private static final float REF_SORT_RIGHT = 70F;
    private static final float REF_STATUS_H = 60F;

    /**
     * 图标井的绝对下限：1280×720 @ GUI 2 时的参考比例值（132 / 3）。
     * 再窄就靠减少列数维持可读，而不是把瓦片压成不可读的一团。
     */
    private static final int MIN_TILE_WIDTH = 44;

    public static WarehouseLayout of(int screenWidth, int screenHeight) {
        int sw = Math.max(64, screenWidth), sh = Math.max(48, screenHeight);
        float s = clamp(sw / REFERENCE_WIDTH, .10F, 3F);

        int statusHeight = Math.max(12, Math.round(REF_STATUS_H * s));
        int bottom = Math.max(24, sh - statusHeight);

        int navY = Math.max(2, Math.round(REF_NAV_Y * s));
        int navHeight = Math.max(14, Math.round(REF_NAV_H * s));
        int tabY = navY + navHeight + Math.max(2, Math.round(16 * s));
        int tabHeight = Math.max(12, Math.round(REF_TAB_H * s));
        int filterY = tabY + tabHeight + Math.max(2, Math.round(27 * s));
        int filterHeight = Math.max(13, Math.round(REF_FILTER_H * s));
        int chromeBottom = filterY + filterHeight + Math.max(2, Math.round(15 * s));

        int tileWidth = Math.max(MIN_TILE_WIDTH, Math.round(REF_TILE_W * s));
        int iconHeight = Math.max(22, Math.round(REF_WELL_H * s));
        int captionHeight = Math.max(10, Math.round(REF_CAPTION_H * s));
        int nameHeight = Math.max(11, Math.round(REF_NAME_H * s));
        int tileHeight = iconHeight + captionHeight + nameHeight;

        // 列距优先取参考比例（192 ≈ 132 瓦片 + 60 间距）；瓦片触底时用最小间距兜住。
        int pitchX = Math.max(tileWidth + 6, Math.round(REF_PITCH_X * s));
        int pitchY = Math.max(tileHeight + 6, Math.round(REF_PITCH_Y * s));

        int margin = Math.max(4, Math.round(REF_MARGIN * s));
        int available = Math.max(tileWidth, sw - margin * 2);
        int columns = clamp(available / pitchX, 1, REFERENCE_COLUMNS);
        int gridWidth = (columns - 1) * pitchX + tileWidth + 6;
        if (margin + gridWidth > sw - 2) columns = Math.max(1, (sw - 2 - margin - tileWidth - 6) / pitchX + 1);
        gridWidth = Math.min(gridWidth, Math.max(tileWidth + 6, sw - margin - 2 - margin));

        int top = Math.max(chromeBottom + 3, Math.round(REF_GRID_TOP * s));
        top = Math.min(top, bottom - Math.max(16, tileHeight));
        top = Math.max(1, top);

        int sortFieldWidth = Math.max(56, Math.round(REF_SORT_W * s));
        int sortFieldHeight = Math.max(14, Math.round(REF_SORT_H * s));
        int sortFieldY = filterY + filterHeight / 2 - sortFieldHeight / 2;
        // 参考视频里下拉框比三级筛选行更高、右边缘更靠外（x 1640–1850 / y 108–148）
        int sortFieldX = Math.max(margin, sw - Math.max(4, Math.round(REF_SORT_RIGHT * s)) - sortFieldWidth);

        return new WarehouseLayout(margin, gridWidth, top, bottom, columns, tileWidth, tileHeight,
                pitchX, pitchY, iconHeight, captionHeight, nameHeight, Math.min(chromeBottom, top - 1), statusHeight,
                navY, navHeight, tabY, tabHeight, filterY, filterHeight,
                sortFieldX, Math.max(0, sortFieldY), sortFieldWidth, sortFieldHeight, margin, s);
    }

    /** 行距与瓦片高度之差：参考视频里的行间大留白（190 - 134 = 56）。 */
    public int rowGap() {
        return pitchY - tileHeight;
    }

    public int rowHeight() {
        return pitchY;
    }

    public int viewportHeight() {
        return bottom - top;
    }

    public int maxScroll(int count) {
        int rows = (count + columns - 1) / columns;
        return Math.max(0, rows * pitchY - rowGap() - viewportHeight());
    }

    public int tileX(int index) {
        return x + Math.floorMod(index, columns) * pitchX;
    }

    public int tileY(int index, double scroll) {
        return top + index / columns * pitchY - (int) scroll;
    }

    public boolean contains(double mx, double my) {
        return mx >= x && mx < x + width && my >= top && my < bottom;
    }

    private static int clamp(int value, int min, int max) {
        return value < min ? min : Math.min(value, max);
    }

    private static float clamp(float value, float min, float max) {
        return value < min ? min : Math.min(value, max);
    }
}
