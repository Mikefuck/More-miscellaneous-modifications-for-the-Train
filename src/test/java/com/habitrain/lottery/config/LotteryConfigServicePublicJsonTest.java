package com.habitrain.lottery.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LotteryConfigServicePublicJsonTest {
    @Test
    void publicSnapshotOmitsOpLevelAndGrants() {
        String json = LotteryConfigService.get().exportPublicJson();
        assertFalse(json.contains("opPermissionLevel"));
        assertFalse(json.contains("\"grants\""));
        assertTrue(json.contains("drawCostMultiplier"));
        assertTrue(json.contains("coinPerDraw"));
    }
}
