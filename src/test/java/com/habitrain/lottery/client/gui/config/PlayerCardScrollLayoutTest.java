package com.habitrain.lottery.client.gui.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PlayerCardScrollLayoutTest {
    @Test
    void sixthCardRemainsReachableInNarrowAndWideEditors() {
        for (int width : new int[]{220, 392}) {
            PlayerCardScrollLayout layout = PlayerCardScrollLayout.calculate(152, 198, width, 6);
            assertTrue(layout.maxScroll() > 0);
            int scroll = layout.scrollByRows(0, 5);
            assertTrue(layout.isWidgetFullyVisible(layout.controlY(5, scroll), 20));
            assertTrue(layout.isTextVisible(layout.labelY(5, scroll), 9));
        }
    }

    @Test
    void lowHeightCreatesScrollableViewportAboveFooter() {
        PlayerCardScrollLayout layout = PlayerCardScrollLayout.calculate(152, 198, 220, 4);

        assertEquals(152, layout.viewportTop());
        assertEquals(198, layout.viewportBottom());
        assertTrue(layout.maxScroll() > 0);
        assertFalse(layout.isWidgetFullyVisible(layout.controlY(3, 0), 20));
        assertTrue(layout.isWidgetFullyVisible(layout.controlY(3, layout.maxScroll()), 20));
        assertTrue(layout.controlY(3, layout.maxScroll()) + 20 <= layout.viewportBottom());
    }

    @Test
    void normalHeightShowsAllRowsWithoutScrolling() {
        PlayerCardScrollLayout layout = PlayerCardScrollLayout.calculate(190, 436, 392, 4);

        assertEquals(0, layout.maxScroll());
        for (int row = 0; row < 4; row++) {
            assertTrue(layout.isWidgetFullyVisible(layout.controlY(row, 0), 20));
        }
    }

    @Test
    void emptyOrInvertedHeightNeverExtendsPastContentBottom() {
        PlayerCardScrollLayout layout = PlayerCardScrollLayout.calculate(210, 198, 220, 4);

        assertEquals(198, layout.viewportTop());
        assertEquals(198, layout.viewportBottom());
        assertFalse(layout.isWidgetFullyVisible(layout.controlY(0, 0), 20));
    }

    @Test
    void wheelNavigationMovesByWholeRowsAndClampsAtBothEnds() {
        PlayerCardScrollLayout layout = PlayerCardScrollLayout.calculate(102, 136, 298, 4);

        assertEquals(44, layout.scrollByRows(0, 1));
        assertEquals(0, layout.scrollByRows(44, -1));
        assertEquals(layout.maxScroll(), layout.scrollByRows(0, 100));
        assertEquals(0, layout.scrollByRows(0, -100));
    }
}
