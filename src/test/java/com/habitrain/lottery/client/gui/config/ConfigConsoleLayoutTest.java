package com.habitrain.lottery.client.gui.config;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.*;

class ConfigConsoleLayoutTest {
    @ParameterizedTest
    @CsvSource({"1280,720", "854,480", "640,360", "427,240", "320,180"})
    void calculatedRegionsStayInsideScreen(int width, int height) {
        ConfigConsoleLayout layout = ConfigConsoleLayout.calculate(width, height);

        assertTrue(layout.content().width() >= 0);
        assertTrue(layout.content().height() >= 0);
        assertTrue(layout.footer().bottom() <= height);
        assertTrue(layout.content().right() <= width);
        assertFalse(layout.navigation().intersects(layout.content()));
        assertFalse(layout.header().intersects(layout.content()));
        assertFalse(layout.footer().intersects(layout.content()));
    }

    @ParameterizedTest
    @CsvSource({"1280,720,WIDE", "854,480,WIDE", "640,360,COMPACT", "427,240,NARROW", "320,180,NARROW"})
    void selectsExpectedResponsiveMode(int width, int height, String mode) {
        assertEquals(mode, ConfigConsoleLayout.calculate(width, height).mode().name());
    }

    @ParameterizedTest
    @CsvSource({"1280,720", "854,480", "640,360"})
    void nonNarrowPlayerColumnsDoNotOverlap(int width, int height) {
        ConfigConsoleLayout layout = ConfigConsoleLayout.calculate(width, height);

        assertTrue(layout.playerList().width() > 0);
        assertTrue(layout.playerDetail().width() > 0);
        assertFalse(layout.playerList().intersects(layout.playerDetail()));
    }
}
