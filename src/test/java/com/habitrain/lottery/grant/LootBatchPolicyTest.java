package com.habitrain.lottery.grant;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LootBatchPolicyTest {
    @Test
    void supportsTenAndFiftyWithoutOldFiveRollCap() {
        assertEquals(10, LootBatchPolicy.affordableRolls(10, 100, 1));
        assertEquals(50, LootBatchPolicy.affordableRolls(50, 100, 1));
        assertEquals(5, LootBatchPolicy.affordableRolls(5, 100, 1));
    }

    @Test
    void respectsConfiguredCostAndPartialBalance() {
        assertEquals(50, LootBatchPolicy.affordableRolls(50, 100, 2));
        assertEquals(49, LootBatchPolicy.affordableRolls(50, 99, 2));
        assertEquals(7, LootBatchPolicy.affordableRolls(10, 15, 2));
        assertEquals(0, LootBatchPolicy.affordableRolls(50, 1, 2));
    }

    @Test
    void boundsUntrustedRequestsAndAvoidsOverflow() {
        assertEquals(50, LootBatchPolicy.affordableRolls(Integer.MAX_VALUE, Integer.MAX_VALUE, 1));
        assertEquals(1, LootBatchPolicy.affordableRolls(50, Integer.MAX_VALUE, Integer.MAX_VALUE));
        assertEquals(0, LootBatchPolicy.affordableRolls(-1, 100, 1));
        assertEquals(0, LootBatchPolicy.affordableRolls(0, 100, 1));
        assertEquals(0, LootBatchPolicy.affordableRolls(50, -1, 1));
        assertEquals(0, LootBatchPolicy.affordableRolls(50, 100, 0));
    }
}
