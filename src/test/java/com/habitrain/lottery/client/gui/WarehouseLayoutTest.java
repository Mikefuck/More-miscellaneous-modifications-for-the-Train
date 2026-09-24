package com.habitrain.lottery.client.gui;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class WarehouseLayoutTest {
    @Test void gridAndScrollStayInsideEveryGuiSize() {
        for (int[] size : new int[][]{{320,240},{427,240},{480,270},{640,360},{854,480},{1280,720},{1920,1080}}) {
            var l = WarehouseLayout.of(size[0], size[1]);
            assertTrue(l.top() >= 88);
            assertTrue(l.bottom() < size[1] - 24);
            for (int count : new int[]{0,1,6,31,100,4096}) {
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
    @Test void motionFinishesIndependentOfFrameRateAndNeverOvershoots() {
        for (int step : new int[]{7,16,33,100}) {
            float previous = 0;
            for (long time = 0; time < 1000; time += step) {
                float v = WarehouseMotion.reveal(time, 100, 12);
                assertTrue(v >= previous && v >= 0 && v <= 1); previous = v;
            }
            assertEquals(1, WarehouseMotion.reveal(1000, 100, 12));
        }
    }
}
