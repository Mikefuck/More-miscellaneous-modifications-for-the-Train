package com.habitrain.lottery.client.gui;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class WarehouseLayoutTest {

    /** 参考视频 1920×1080 的实测坐标必须逐项命中（§1 PHASE 1）。 */
    @Test void referenceResolutionReproducesMeasuredGeometry() {
        var l = WarehouseLayout.of(1920, 1080);
        assertEquals(9, l.columns(), "参考视频是 9 列");
        assertEquals(93, l.x(), "首个瓦片左边缘 x = 93");
        assertEquals(192, l.pitchX(), "列距 192");
        assertEquals(190, l.pitchY(), "行距 190");
        assertEquals(205, l.top(), "首个图标顶 y = 205");
        assertEquals(132, l.tileWidth(), "图标井 132 宽");
        assertEquals(92, l.iconHeight(), "图标井 92 高");
        assertEquals(20, l.captionHeight(), "说明条 20");
        assertEquals(113, l.filterY(), "三级筛选行 y = 113");
        for (int row = 0; row < 5; row++) {
            assertEquals(205 + row * 190, l.tileY(row * l.columns(), 0), "第 " + row + " 行图标顶");
        }
        assertEquals(1761, l.tileX(8) + l.tileWidth(), "第 9 列的右边缘");
    }

    @Test void gridAndScrollStayInsideEveryGuiSize() {
        for (int[] size : new int[][]{{320, 240}, {427, 240}, {480, 270}, {640, 360}, {854, 480}, {1280, 720}, {1920, 1080}}) {
            var l = WarehouseLayout.of(size[0], size[1]);
            assertTrue(l.top() > l.chromeBottom(), "格栅必须在三行 chrome 之下");
            assertTrue(l.chromeBottom() > l.navY());
            assertEquals(size[1] - l.statusHeight(), l.bottom(), "底部状态条占据最后一段");
            assertTrue(l.top() < l.bottom());
            assertTrue(l.columns() >= 1 && l.columns() <= WarehouseLayout.REFERENCE_COLUMNS);
            for (int count : new int[]{0, 1, 6, 31, 100, 4096}) {
                int max = l.maxScroll(count);
                assertTrue(max >= 0);
                for (int i = 0; i < count; i++) {
                    assertTrue(l.tileX(i) >= l.x());
                    assertTrue(l.tileX(i) + l.tileWidth() <= l.x() + l.width() - 6);
                }
                if (max > 0) assertEquals(l.bottom(), l.tileY(count - 1, max) + l.tileHeight());
                assertFalse(l.contains(l.x(), l.top() - 1));
                assertFalse(l.contains(l.x(), l.bottom()));
            }
        }
    }

    /** 等比换算：窗口越窄列数越少，且永不超过参考视频的 9 列。 */
    @Test void columnCountShrinksWithNarrowWindowsAndNeverExceedsNine() {
        int previous = 0;
        for (int width : new int[]{256, 320, 427, 480, 640, 854, 1280, 1920, 2560}) {
            var l = WarehouseLayout.of(width, Math.max(160, width * 9 / 16));
            assertTrue(l.columns() >= previous, "列数不应随宽度增加而减少：" + width);
            assertTrue(l.columns() <= WarehouseLayout.REFERENCE_COLUMNS);
            previous = l.columns();
        }
        assertEquals(9, WarehouseLayout.of(1920, 1080).columns());
        assertEquals(9, WarehouseLayout.of(640, 360).columns(), "1280×720 @ GUI 2 已是参考比例，应为 9 列");
        assertEquals(9, WarehouseLayout.of(1280, 720).columns());
        assertTrue(WarehouseLayout.of(320, 240).columns() < 9, "窄窗口必须减少列数");
        assertTrue(WarehouseLayout.of(320, 240).columns() >= 3, "仍然要能放下几列");
        assertTrue(WarehouseLayout.of(320, 240).tileWidth() >= 44, "瓦片不得小于可读下限");
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
