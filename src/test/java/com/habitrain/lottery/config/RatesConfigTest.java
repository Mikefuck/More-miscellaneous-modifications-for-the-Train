package com.habitrain.lottery.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RatesConfigTest {
    @Test
    void opPermissionLevelClampsToOneThroughFour() {
        RatesConfig r = new RatesConfig();
        r.opPermissionLevel = 0;
        assertEquals(1, r.opPermissionLevel());
        r.opPermissionLevel = 99;
        assertEquals(4, r.opPermissionLevel());
        r.opPermissionLevel = 2;
        assertEquals(2, r.opPermissionLevel());
    }
}
