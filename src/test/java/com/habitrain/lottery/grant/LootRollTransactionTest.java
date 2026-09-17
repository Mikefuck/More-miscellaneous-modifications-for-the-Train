package com.habitrain.lottery.grant;

import com.habitrain.lottery.storage.PlayerLotteryData;
import org.junit.jupiter.api.Test;

import java.util.HashMap;

import static org.junit.jupiter.api.Assertions.*;

class LootRollTransactionTest {

    @Test
    void validRollIsNonNullAndNotMinusOne() {
        assertFalse(LootRollTransaction.isValidRoll(null));
        assertFalse(LootRollTransaction.isValidRoll(-1));
        assertTrue(LootRollTransaction.isValidRoll(0));
        assertTrue(LootRollTransaction.isValidRoll(3));
    }

    @Test
    void refundWhenInvalidAndNothingFlushed() {
        PlayerLotteryData before = new PlayerLotteryData();
        before.lootChance = 5;
        before.coinNum = 10;
        PlayerLotteryData after = before.copy();
        after.lootChance = 4;
        assertFalse(LootRollTransaction.awardPersisted(before, after));
        assertTrue(LootRollTransaction.shouldRefundChance(false, false));
    }

    @Test
    void keepDebitWhenValidRoll() {
        assertFalse(LootRollTransaction.shouldRefundChance(true, false));
        assertFalse(LootRollTransaction.shouldRefundChance(true, true));
    }

    @Test
    void keepDebitWhenThrowAfterSkinFlush() {
        PlayerLotteryData before = new PlayerLotteryData();
        before.unlocked.computeIfAbsent("knife", k -> new HashMap<>()).put("gold", true);
        PlayerLotteryData after = before.copy();
        after.unlocked.get("knife").put("diamond", true);
        assertTrue(LootRollTransaction.awardPersisted(before, after));
        assertFalse(LootRollTransaction.shouldRefundChance(false, true));
    }

    @Test
    void keepDebitWhenThrowAfterCoinAward() {
        PlayerLotteryData before = new PlayerLotteryData();
        before.coinNum = 20;
        PlayerLotteryData after = before.copy();
        after.coinNum = 80;
        assertTrue(LootRollTransaction.awardPersisted(before, after));
        assertFalse(LootRollTransaction.shouldRefundChance(false, true));
    }
}
