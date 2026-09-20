package com.habitrain.lottery.client.gui;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class WardrobeLayoutTest {
    @Test void controlsAndCardsStayInsideViewportAcrossGuiScales() {
        for (int width : new int[]{320, 427, 480, 640, 854, 1280, 1920}) {
            for (int height : new int[]{240, 270, 360, 480, 720, 1080}) {
                var l = WardrobeLayout.of(width, height);
                assertTrue(l.x() >= 0 && l.y() >= 0);
                assertTrue(l.x() + l.width() <= width && l.bottom() <= height);
                assertTrue(l.cardWidth() >= 80 && l.cardHeight() >= 70);
                assertTrue(l.detailWidth() >= 112 && l.contentHeight() >= 94);
                assertTrue(l.gridX() + l.columns() * (l.cardWidth() + 6) - 6 <= l.detailX() - 10);
                assertTrue(l.gridY() + l.rows() * (l.cardHeight() + 6) - 6 <= l.bottom() - 44);
                assertTrue(l.detailX() + l.detailWidth() <= l.x() + l.width() - 10);
            }
        }
    }
}
