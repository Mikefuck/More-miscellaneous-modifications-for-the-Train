package com.habitrain.lottery.client.gui;

/**
 * 仓库界面的几何，与开箱终端共用同一套构图：
 *
 * <pre>
 *   眉题 + 大标题 + 标签行（居中）        ← 开箱终端的 header
 *   玻璃展柜：分类标签 / 搜索 + 品质卡片格栅  ← 开箱终端的物品条
 *   底部操作栏：状态 · 按钮 · 账户          ← 开箱终端的操作栏
 * </pre>
 *
 * <p>卡片先按可读的目标宽度求列数，再把余量平均分给每张卡，格栅在展柜内居中；
 * 宽屏时展柜宽度封顶，避免十列卡片被拉成一条细线。矮窗口（高度 &lt; 280）进入紧凑模式：
 * 省掉眉题、缩小标题与间距，保证至少能放下一整行卡片。</p>
 *
 * <p>纯数学、无 Minecraft 依赖，{@code WarehouseLayoutTest} 直接校验不变量。</p>
 */
public record WarehouseLayout(int screenWidth, int screenHeight, boolean compact,
                              int eyebrowY, int titleY, float titleScale, int chipY, int chipHeight,
                              int panelX0, int panelY0, int panelX1, int panelY1, int tabY, int tabHeight, int pad,
                              int x, int width, int top, int bottom, int columns,
                              int tileWidth, int tileHeight, int gap, int barTop, int barHeight) {

    public static final int MAX_COLUMNS = 10;
    /** 卡片宽度的可读下限：再窄就减少列数。 */
    public static final int MIN_TILE = 52;
    /** 卡片宽度上限：宽屏时展柜封顶，不再继续放大。 */
    public static final int MAX_TILE = 96;
    /** 卡片高宽比，与开箱物品条的卡片一致（略高于正方形，底部留给名称）。 */
    public static final float TILE_ASPECT = 1.14F;

    public static WarehouseLayout of(int screenWidth, int screenHeight) {
        return of(screenWidth, screenHeight, 2F);
    }

    /**
     * @param titleScale 大标题的字号倍率。像素字体只有在「每个字形像素 = 整数个屏幕像素」时才清晰，
     *                   调用方按 GUI 缩放换算（{@code 4 / guiScale}），这里只负责给它留出高度。
     */
    public static WarehouseLayout of(int screenWidth, int screenHeight, float titleScale) {
        int sw = Math.max(160, screenWidth), sh = Math.max(120, screenHeight);
        boolean compact = sh < 280;

        int margin = clamp(Math.round(sw * .045F), 6, 64);
        int barHeight = clamp(Math.round(sh * .08F), 22, 36);
        int barTop = sh - barHeight;

        int eyebrowY = compact ? -1 : Math.max(4, Math.round(sh * .02F));
        titleScale = Math.max(1F, Math.min(compact ? 1.5F : 4F, titleScale));
        int titleY = compact ? 5 : eyebrowY + 10;
        int titleHeight = Math.round(9 * titleScale);
        int chipHeight = compact ? 13 : 14;
        int chipY = titleY + titleHeight + (compact ? 3 : 5);

        int pad = compact ? 5 : 8;
        int gap = compact ? 4 : 6;
        int tabHeight = compact ? 14 : 16;

        // 展柜宽度封顶：十列上限卡片 + 内边距；更宽的屏幕把展柜居中。
        int maxPanel = MAX_COLUMNS * MAX_TILE + (MAX_COLUMNS - 1) * gap + pad * 2;
        int panelW = Math.min(sw - margin * 2, maxPanel);
        int panelX0 = (sw - panelW) / 2, panelX1 = panelX0 + panelW;
        int panelY0 = chipY + chipHeight + (compact ? 4 : 8);
        int panelY1 = barTop - (compact ? 4 : 8);

        int tabY = panelY0 + (compact ? 3 : 5);
        int top = tabY + tabHeight + (compact ? 5 : 8);
        int bottom = Math.max(top + 1, panelY1 - 3);

        int inner = Math.max(MIN_TILE, panelW - pad * 2);
        int preferred = clamp(Math.round(sw * .1F), MIN_TILE, MAX_TILE);
        int columns = clamp((inner + gap) / (preferred + gap), 1, MAX_COLUMNS);
        int tileWidth = Math.min(MAX_TILE, (inner - gap * (columns - 1)) / columns);
        int tileHeight = Math.round(tileWidth * TILE_ASPECT);
        // 视口放不下一整张卡时按高度反推，保证至少一整行可见。
        int viewport = bottom - top;
        if (tileHeight > viewport && viewport > 20) {
            tileHeight = viewport;
            tileWidth = Math.max(20, Math.round(tileHeight / TILE_ASPECT));
        }
        int gridWidth = columns * tileWidth + (columns - 1) * gap;
        int x = panelX0 + (panelW - gridWidth) / 2;

        return new WarehouseLayout(sw, sh, compact, eyebrowY, titleY, titleScale, chipY, chipHeight,
                panelX0, panelY0, panelX1, panelY1, tabY, tabHeight, pad,
                x, gridWidth, top, bottom, columns, tileWidth, tileHeight, gap, barTop, barHeight);
    }

    public int pitchX() {
        return tileWidth + gap;
    }

    public int pitchY() {
        return tileHeight + gap;
    }

    public int rowHeight() {
        return pitchY();
    }

    public int viewportHeight() {
        return bottom - top;
    }

    public int maxScroll(int count) {
        int rows = (count + columns - 1) / columns;
        return Math.max(0, rows * pitchY() - gap - viewportHeight());
    }

    public int tileX(int index) {
        return x + Math.floorMod(index, columns) * pitchX();
    }

    public int tileY(int index, double scroll) {
        return top + index / columns * pitchY() - (int) scroll;
    }

    /** 格栅视口（滚动与点击命中的范围）。 */
    public boolean contains(double mx, double my) {
        return mx >= panelX0 && mx < panelX1 && my >= top && my < bottom;
    }

    private static int clamp(int value, int min, int max) {
        return value < min ? min : Math.min(value, max);
    }
}
