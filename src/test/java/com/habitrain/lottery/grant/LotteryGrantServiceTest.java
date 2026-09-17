package com.habitrain.lottery.grant;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LotteryGrantServiceTest {
    @Test
    void computeDrawCostDefaultAtLeastOneAndCanAfford() {
        int cost = LotteryGrantService.computeDrawCost();
        assertTrue(cost >= 1);
        assertFalse(LotteryGrantService.canAfford(0));
        assertTrue(LotteryGrantService.canAfford(cost));
        assertFalse(LotteryGrantService.canAfford(cost - 1));
    }

    @Test
    void scaleChanceDeltaPassthroughAndMinusOneCost() {
        assertEquals(-1, LotteryGrantService.scaleChanceDelta(-1, false, 160));
        assertEquals(-160, LotteryGrantService.scaleChanceDelta(-1, true, 160));
        assertEquals(-5, LotteryGrantService.scaleChanceDelta(-5, true, 160));
        assertEquals(3, LotteryGrantService.scaleChanceDelta(3, true, 160));
        assertEquals(3, LotteryGrantService.scaleChanceDelta(3, false, 160));
        assertEquals(0, LotteryGrantService.scaleChanceDelta(0, true, 160));
    }
}
