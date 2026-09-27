package com.habitrain.lottery.crate;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CratePoolMigrationTest {
    @Test void legacyBonusesJoinSkinWeightsWithoutResurrectingDisabledEntries() {
        var pool = new CrateService.CratePool();
        pool.skinWeights.put("knife/event_horizon", 10);
        var apples = new CrateService.ExtraReward(); apples.chance = .5; apples.amount = 4;
        var disabled = new CrateService.ExtraReward(); disabled.chance = 0; disabled.weight = 100;
        pool.extraRewards.add(apples); pool.extraRewards.add(disabled);
        CrateService.migrateRewardPool(pool);
        assertTrue(pool.customPool);
        assertEquals("unified_pool", pool.rewardMode);
        assertEquals(1, pool.rollCount);
        assertEquals(0, pool.minimumSkinCount);
        assertEquals(10, pool.skinWeights.get("knife/event_horizon"));
        assertEquals(50, apples.weight);
        assertEquals(4, apples.amount);
        assertEquals(0, disabled.weight);
        CrateService.migrateRewardPool(pool);
        assertEquals(50, apples.weight);
    }

    @Test void legacyItemOnlyCrateDoesNotEnablePreviouslyInactiveSkins() {
        var pool = new CrateService.CratePool(); pool.skinDrawCount = 0;
        pool.skinWeights.put("knife/unused", 100);
        var item = new CrateService.ExtraReward(); item.chance = 1;
        pool.extraRewards.add(item);
        CrateService.migrateRewardPool(pool);
        assertEquals(0, pool.skinWeights.get("knife/unused"));
        assertEquals(100, item.weight);
        assertTrue(pool.enabled);
    }

    @Test void existingUnifiedWeightsAndDrawCountSurviveMigration() {
        var pool = new CrateService.CratePool(); pool.rewardMode = "unified_pool";
        pool.rollCount = 3; pool.minimumSkinCount = 1;
        var item = new CrateService.ExtraReward(); item.weight = 27; item.chance = .9;
        pool.extraRewards.add(item);
        CrateService.migrateRewardPool(pool);
        assertEquals(27, item.weight);
        assertEquals(3, pool.rollCount);
        assertEquals(0, pool.minimumSkinCount);
    }

    @Test void emptyLegacyPoolIsDisabledSoItCannotBlockSavingOtherCrates() {
        var pool = new CrateService.CratePool();
        CrateService.migrateRewardPool(pool);
        assertFalse(pool.enabled);
        assertTrue(pool.skinWeights.isEmpty());
        assertTrue(pool.extraRewards.isEmpty());
    }
}
