package com.habitrain.lottery.client.gui;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class WarehouseLayoutTest {

    private static final int[][] SIZES = {{160, 120}, {320, 240}, {427, 240}, {480, 270}, {640, 360},
            {854, 480}, {1280, 720}, {1920, 1080}, {3440, 1440}};

    /** 纵向顺序与开箱终端一致：标题 → 标签 → 展柜（分类行 → 格栅）→ 底部操作栏。 */
    @Test void sectionsStackTopToBottomWithoutOverlap() {
        for (int[] size : SIZES) for (float titleScale : new float[]{1F, 4F / 3, 2F, 4F}) {
            var l = WarehouseLayout.of(size[0], size[1], titleScale);
            String at = size[0] + "×" + size[1] + " @" + titleScale;
            if (!l.compact()) assertTrue(l.eyebrowY() >= 0 && l.eyebrowY() + 8 <= l.titleY(), "眉题在标题之上：" + at);
            assertTrue(l.titleY() + 9 * l.titleScale() <= l.chipY(), "标签行在标题之下：" + at);
            assertTrue(l.chipY() + l.chipHeight() <= l.panelY0(), "展柜在标签行之下：" + at);
            assertTrue(l.tabY() >= l.panelY0() && l.tabY() + l.tabHeight() < l.top(), "分类行在格栅之上：" + at);
            assertTrue(l.bottom() <= l.panelY1(), "格栅不越出展柜：" + at);
            assertTrue(l.panelY1() <= l.barTop(), "展柜不压底部操作栏：" + at);
            assertEquals(size[1], l.barTop() + l.barHeight(), "操作栏贴底：" + at);
            assertTrue(l.panelX0() >= 0 && l.panelX1() <= size[0], "展柜不越出屏幕：" + at);
        }
    }

    @Test void gridAndScrollStayInsideEveryGuiSize() {
        for (int[] size : SIZES) {
            var l = WarehouseLayout.of(size[0], size[1]);
            assertTrue(l.columns() >= 1 && l.columns() <= WarehouseLayout.MAX_COLUMNS);
            assertTrue(l.tileHeight() <= l.viewportHeight(), "至少能完整放下一行卡片");
            assertTrue(l.x() >= l.panelX0() + l.pad() - 1 && l.x() + l.width() <= l.panelX1() - l.pad() + 1,
                    "格栅在展柜内边距之内");
            for (int count : new int[]{0, 1, 6, 31, 100, 4096}) {
                int max = l.maxScroll(count);
                assertTrue(max >= 0);
                for (int i = 0; i < Math.min(count, 64); i++) {
                    assertTrue(l.tileX(i) >= l.x());
                    assertTrue(l.tileX(i) + l.tileWidth() <= l.x() + l.width());
                }
                if (max > 0) assertEquals(l.bottom(), l.tileY(count - 1, max) + l.tileHeight(), "滚到底时最后一行贴住视口下沿");
            }
            assertFalse(l.contains(l.x(), l.top() - 1));
            assertFalse(l.contains(l.x(), l.bottom()));
            assertTrue(l.contains(l.x(), l.top()));
        }
    }

    /** 格栅在展柜里水平居中（左右留白相差不超过 1px）。 */
    @Test void gridIsCenteredInThePanel() {
        for (int[] size : SIZES) {
            var l = WarehouseLayout.of(size[0], size[1]);
            int left = l.x() - l.panelX0(), right = l.panelX1() - (l.x() + l.width());
            assertTrue(Math.abs(left - right) <= 1, size[0] + "×" + size[1] + " left=" + left + " right=" + right);
        }
    }

    @Test void columnCountGrowsWithWidthAndTilesStayReadable() {
        int previous = 0;
        for (int width : new int[]{256, 320, 427, 480, 640, 854, 1280, 1920, 2560}) {
            var l = WarehouseLayout.of(width, Math.max(160, width * 9 / 16));
            assertTrue(l.columns() >= previous, "列数不应随宽度增加而减少：" + width);
            assertTrue(l.tileWidth() >= WarehouseLayout.MIN_TILE, "卡片不得小于可读下限：" + width);
            assertTrue(l.tileWidth() <= WarehouseLayout.MAX_TILE);
            previous = l.columns();
        }
        assertTrue(WarehouseLayout.of(640, 360).columns() >= 7, "1280×720 @ GUI 2 至少 7 列");
        assertTrue(WarehouseLayout.of(320, 240).columns() >= 4, "小窗口仍然要能放下几列");
        assertEquals(WarehouseLayout.MAX_COLUMNS, WarehouseLayout.of(1920, 1080).columns());
    }

    /** 超宽屏：展柜封顶并居中，卡片不会被拉到上限以上。 */
    @Test void ultraWidePanelIsCappedAndCentered() {
        var l = WarehouseLayout.of(3440, 1440);
        assertTrue(l.panelX1() - l.panelX0() < 3440 / 2);
        assertEquals(3440 - l.panelX1(), l.panelX0(), 1);
    }

    @Test void motionFinishesIndependentOfFrameRateAndNeverOvershoots() {
        for (int step : new int[]{7, 16, 33, 100}) {
            float previous = 0;
            for (long time = 0; time < 1000; time += step) {
                float v = WarehouseMotion.reveal(time, 100, 12);
                assertTrue(v >= previous && v >= 0 && v <= 1); previous = v;
            }
            assertEquals(1, WarehouseMotion.reveal(1000, 100, 12));
        }
    }
}
