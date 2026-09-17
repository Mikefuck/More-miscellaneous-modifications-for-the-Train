package com.habitrain.lottery.api.player;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class HabiAssetPolicyTest {

    @Test
    void currencyAddAndSetClampToNonNegative() {
        assertEquals(150, HabiAssetPolicy.applyCurrency(100, HabiAssetOperation.ADD, 50));
        assertEquals(0, HabiAssetPolicy.applyCurrency(100, HabiAssetOperation.ADD, -500));
        assertEquals(7, HabiAssetPolicy.applyCurrency(100, HabiAssetOperation.SET, 7));
        assertEquals(0, HabiAssetPolicy.applyCurrency(100, HabiAssetOperation.SET, -7));
        assertEquals(HabiAssetPolicy.MAX_CURRENCY,
                HabiAssetPolicy.applyCurrency(Integer.MAX_VALUE - 1, HabiAssetOperation.ADD, 100));
    }

    @Test
    void currencyValidation() {
        assertFalse(HabiAssetPolicy.isValidCurrencyOperation(null, 1));
        assertFalse(HabiAssetPolicy.isValidCurrencyOperation(HabiAssetOperation.ADD, 0));
        assertTrue(HabiAssetPolicy.isValidCurrencyOperation(HabiAssetOperation.ADD, -5));
        assertFalse(HabiAssetPolicy.isValidCurrencyOperation(HabiAssetOperation.SET, -1));
        assertTrue(HabiAssetPolicy.isValidCurrencyOperation(HabiAssetOperation.SET, 0));
    }

    @Test
    void cardValidationMirrorsAdminPolicy() {
        assertFalse(HabiAssetPolicy.isValidCardOperation(HabiAssetOperation.ADD, 0));
        assertTrue(HabiAssetPolicy.isValidCardOperation(HabiAssetOperation.ADD, 1000));
        assertFalse(HabiAssetPolicy.isValidCardOperation(HabiAssetOperation.ADD, 1001));
        assertTrue(HabiAssetPolicy.isValidCardOperation(HabiAssetOperation.ADD, -1000));
        assertFalse(HabiAssetPolicy.isValidCardOperation(HabiAssetOperation.ADD, -1001));
        assertTrue(HabiAssetPolicy.isValidCardOperation(HabiAssetOperation.SET, 0));
        assertTrue(HabiAssetPolicy.isValidCardOperation(HabiAssetOperation.SET, 100_000));
        assertFalse(HabiAssetPolicy.isValidCardOperation(HabiAssetOperation.SET, 100_001));
    }

    @Test
    void cardCountClamps() {
        assertEquals(5, HabiAssetPolicy.applyCardCount(3, HabiAssetOperation.ADD, 2));
        assertEquals(0, HabiAssetPolicy.applyCardCount(3, HabiAssetOperation.ADD, -9));
        assertEquals(HabiAssetPolicy.MAX_CARD_COUNT,
                HabiAssetPolicy.applyCardCount(0, HabiAssetOperation.SET, Integer.MAX_VALUE));
    }

    @Test
    void dailyRemainingAddsBonusAndSubtractsUses() {
        assertEquals(4, HabiAssetPolicy.dailyRemaining(0, 0, 4));
        assertEquals(2, HabiAssetPolicy.dailyRemaining(2, 0, 4));
        assertEquals(3, HabiAssetPolicy.dailyRemaining(2, 1, 4));
        assertEquals(0, HabiAssetPolicy.dailyRemaining(6, 0, 4));
        // negative counters are treated as zero, so the full quota remains
        assertEquals(4, HabiAssetPolicy.dailyRemaining(-5, -5, 4));
    }
}
